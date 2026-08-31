package me.bechberger.demo.solutions;

import me.bechberger.demo.AgentState;
import me.bechberger.demo.solutions.CodingTools;
import me.bechberger.demo.FileTools;
import me.bechberger.demo.LLMClient;
import me.bechberger.demo.solutions.ToolSupport;
import me.bechberger.demo.util.Ansi;
import me.bechberger.demo.util.ApprovalRules;
import me.bechberger.demo.util.Compactor;
import me.bechberger.demo.util.Highlight;

import me.bechberger.demo.util.Repl;
import me.bechberger.demo.util.SessionLog;
import me.bechberger.femtocli.annotations.Mixin;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.json.CompactPrinter;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Operational plumbing for CodingAgent — session logging, /state edit, confirmation
 * prompts, /run, /compact, /clear, /tokens, /mode. Kept here so CodingAgent only
 * shows the concepts that matter for the talk.
 */
abstract class CodingAgentSupport implements Callable<Integer> {

    // ── femtocli options ─────────────────────────────────────────────────────

    @Mixin
    Options options;

    @Option(names = {"--max-tokens"}, description = "Compact the conversation above this many prompt tokens (default: auto = 80%% of the model's context window)",
            defaultValue = "0")
    int maxTokens;

    @Option(names = {"--no-log"}, description = "Do not write a session transcript to ~/.tiny-llm-library/sessions/")
    boolean noLog;

    @Option(names = {"--approve-plans"}, description = "Auto-approve plans without prompting (useful for scripted sessions)")
    boolean approvePlans;

    @Option(names = {"-r", "--root"}, description = "Project root directory (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    protected String root;

    // ── runtime state ────────────────────────────────────────────────────────

    /** Single shared Scanner for System.in — two Scanners on one stream swallow each other's input. */
    final Scanner scanner = new Scanner(System.in);

    protected ApprovalMode approval = ApprovalMode.NORMAL;
    final ApprovalRules approvalRules = defaultRules();

    private static ApprovalRules defaultRules() {
        var r = new ApprovalRules();
        r.allow("run: pwd");
        r.allow("run: echo *");
        r.allow("run: which *");
        r.allow("run: git status*");
        r.allow("run: git diff");
        return r;
    }

    protected enum ApprovalMode {
        NORMAL(""), AUTO_EDIT("⏵ "), YOLO("⚡ ");
        final String badge;
        ApprovalMode(String badge) { this.badge = badge; }
        String badge() { return badge; }
        ApprovalMode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    final AgentState state = new AgentState();

    /**
     * Set by the `continue` or `schedule` tool — chat() drains this after each tool loop
     * and re-invokes itself without waiting for user input.
     */
    volatile String pendingContinuation = null;

    /** Active schedule handles keyed by identity hash code, for per-ID cancellation. */
    final Map<Integer, Repl.ScheduleHandle> scheduleHandles = new HashMap<>();

    /** Set once the REPL is built in call() — used by confirmPlan for prompting. */
    Repl repl;

    int stateMessageIndex = -1;

    /** True after /state edit — suppresses system-prompt overwrite until next /clear. */
    boolean systemPromptEdited = false;

    protected Compactor compactor;

    // ── helpers used by CodingAgent ──────────────────────────────────────────

    protected String resolveModel() { return options.resolveModel(); }

    protected LLMClient createClient(Repl.Builder builder) { return options.createClient(builder); }

    protected Compactor createCompactor(LLMClient client) {
        int contextWindow = client.getContextWindowSize(32768);
        int compact = maxTokens > 0 ? maxTokens : (int) (contextWindow * 0.90);
        int alert   = (int) (contextWindow * 0.80);
        return new Compactor(compact, alert, 6);
    }

    void startSessionLog() {
        if (noLog) return;
        try {
            System.out.println("Session log: " + SessionLog.start(getClass().getSimpleName()));
        } catch (IOException e) {
            System.err.println("Could not start session log: " + e.getMessage());
        }
    }

    void printMode() {
        System.out.println(switch (approval) {
            case YOLO      -> Ansi.yellow("⚡ YOLO mode — everything is auto-approved (except plans)");
            case AUTO_EDIT -> Ansi.blue("⏵ AUTO-EDIT mode — run auto-approved, delete/plans still ask");
            case NORMAL    -> Ansi.dim("🔒 NORMAL mode — risky actions need confirmation");
        });
    }

    void printTokens(LLMClient client, List<Map<String, Object>> messages) {
        var u = client.lastUsage();
        System.out.println(u == null ? "(no usage data yet)"
                : "last call: prompt " + u.promptTokens() + " + completion " + u.completionTokens() + " tokens"
                + " - history: " + messages.size() + " messages - compacting above " + compactor.threshold());
    }

    void compactNow(LLMClient client, List<Map<String, Object>> messages) {
        var outcome = compactor.compactNow(client, messages);
        if (outcome.compacted()) {
            stateMessageIndex = -1;
        } else {
            System.out.println(Ansi.dim("[compact] nothing to compact (" + messages.size() + " messages)."));
        }
    }

    /** Add dummy messages until token usage is near the alert threshold (for testing compaction). */
    void fillContext(LLMClient client, List<Map<String, Object>> messages) {
        int contextWindow = client.getContextWindowSize(32768);
        int target = (int) (contextWindow * 0.82); // just above alert threshold
        var u = client.lastUsage();
        int current = u != null ? u.promptTokens() : 0;
        if (current >= target) {
            System.out.println(Ansi.dim("[fill] already at " + current + "/" + contextWindow + " tokens, nothing to add."));
            return;
        }
        // Each token ≈ 4 chars; pad with ~500-token messages
        String chunk = "A".repeat(2000);
        int added = 0;
        while (current + added * 500 < target) {
            messages.add(LLMClient.user("[fill-context padding] " + chunk));
            messages.add(LLMClient.assistant("Understood."));
            added++;
        }
        System.out.println(Ansi.dim("[fill] added " + (added * 2) + " dummy messages — send any message to see token count."));
    }

    void clearConversation(List<Map<String, Object>> messages) {
        int dropped = Math.max(0, messages.size() - 1);
        if (dropped > 0) messages.subList(1, messages.size()).clear();
        stateMessageIndex = -1;
        systemPromptEdited = false;
        messages.set(0, LLMClient.system(buildSystemPrompt()));
        System.out.println("Conversation cleared (" + dropped + " messages dropped; goal/plan/TODOs stay pinned).");
    }

    private static String formatAction(String action) {
        int colon = action.indexOf(": ");
        if (colon < 0) return Ansi.yellow(action);
        String prefix = action.substring(0, colon + 2);
        String rest   = action.substring(colon + 2);
        // split off the " | {json}" suffix added by CodingTools.action()
        int pipe = rest.indexOf(" | {");
        String primary = pipe >= 0 ? rest.substring(0, pipe) : rest;
        String jsonSuffix = pipe >= 0 ? rest.substring(pipe) : "";
        String highlighted = action.startsWith("run:") || action.startsWith("delete:")
                ? Highlight.shell(primary)
                : Ansi.yellow(primary);
        return Ansi.yellow(Ansi.BOLD + prefix + Ansi.RESET) + highlighted + Ansi.dim(jsonSuffix);
    }

    /** Ask the user to approve a risky agent action — auto-approved in YOLO/AUTO_EDIT mode. */
    boolean confirm(String action, boolean defaultYes) {
        var effect = approvalRules.match(action);
        if (effect == ApprovalRules.Effect.ALLOW) { System.out.println("  " + Ansi.dim("rule-allow: " + action)); return true; }
        if (effect == ApprovalRules.Effect.DENY)  { System.out.println("  " + Ansi.dim("rule-deny: "  + action)); return false; }
        boolean autoApprove = approval == ApprovalMode.YOLO
                || (approval == ApprovalMode.AUTO_EDIT && action.startsWith("run:"));
        if (autoApprove) {
            System.out.println("  " + approval.badge() + Ansi.dim("auto-approved (" + approval.name().toLowerCase().replace('_', '-') + "): " + action));
            return true;
        }
        while (true) {
            String prompt = "\n" + Ansi.yellow("⚠  ") + formatAction(action) + "\n    Allow? [y/N/a=always/Y=yolo/r=rules] ";
            String answer = repl != null
                    ? repl.prompt(prompt, defaultYes ? "y" : "n")
                    : (scanner.hasNextLine() ? scanner.nextLine().trim().toLowerCase() : (defaultYes ? "y" : ""));
            if (System.console() == null) System.out.println(answer);
            if (answer.equals("a")) {
                approvalRules.allow(action);
                System.out.println(Ansi.green("  Rule added: allow " + action));
                return true;
            }
            if (answer.equals("y!") || answer.equals("yolo")) {
                approval = ApprovalMode.YOLO;
                printMode();
                return true;
            }
            if (answer.equals("r")) { editRules(); continue; }
            return answer.isEmpty() ? defaultYes : answer.startsWith("y");
        }
    }

    /** Always pauses to show the plan — even in YOLO mode. */
    boolean confirmPlan(String action) {
        String plan = action.startsWith("plan: ") ? action.substring(6) : action;
        System.out.println("\n" + Ansi.bold("─── Plan ────────────────────────────────────────────────"));
        for (String line : plan.split("\n", -1)) System.out.println("  " + line);
        System.out.println(Ansi.bold("─────────────────────────────────────────────────────────"));
        if (approvePlans) { System.out.println(Ansi.dim("  auto-approved (--approve-plans)")); return true; }
        String answer = repl != null ? repl.prompt("  Proceed? [Y/n/feedback] ", "") : "";
        if (answer.isEmpty() || answer.equalsIgnoreCase("y")) return true;
        if (answer.equalsIgnoreCase("n")) return false;
        state.setPlan("REJECTED — user feedback: " + answer);
        return false;
    }

    /** Interactive /rules editor: list rules, delete by number, add allow/deny by pattern. */
    void editRules() {
        while (true) {
            var list = approvalRules.rules();
            System.out.println(Ansi.bold("\n─── Rules ──────────────────────────────────────────────"));
            if (list.isEmpty()) {
                System.out.println(Ansi.dim("  (no rules)"));
            } else {
                for (int i = 0; i < list.size(); i++) {
                    var r = list.get(i);
                    String label = r.effect() == ApprovalRules.Effect.ALLOW ? Ansi.green("allow") : Ansi.yellow("deny ");
                    System.out.println("  " + Ansi.dim((i + 1) + ".") + " " + label + "  " + r.pattern());
                }
            }
            System.out.println(Ansi.dim("  Pattern matches the full action string, e.g.:"));
            System.out.println(Ansi.dim("    run: mvn*        edit: src/main*    delete: tmp/*"));
            System.out.println(Ansi.dim("    edit: * | *Controller*              (match JSON args)"));
            System.out.println(Ansi.dim("  Commands: allow <pattern> | deny <pattern> | <number> to delete | empty to exit"));
            System.out.println(Ansi.bold("────────────────────────────────────────────────────────"));
            String input = repl != null ? repl.prompt("  > ", null) : null;
            if (input == null || input.isBlank()) break;
            if (input.startsWith("allow ")) {
                String pat = input.substring(6).trim();
                if (!pat.isBlank()) { approvalRules.allow(pat); System.out.println(Ansi.green("  Added: allow " + pat)); }
            } else if (input.startsWith("deny ")) {
                String pat = input.substring(5).trim();
                if (!pat.isBlank()) { approvalRules.deny(pat); System.out.println(Ansi.yellow("  Added: deny " + pat)); }
            } else {
                try {
                    int idx = Integer.parseInt(input.trim()) - 1;
                    var rules = approvalRules.rules();
                    if (idx >= 0 && idx < rules.size()) {
                        approvalRules.remove(idx);
                        System.out.println(Ansi.dim("  Removed rule " + (idx + 1)));
                    } else {
                        System.out.println(Ansi.dim("  No rule #" + (idx + 1)));
                    }
                } catch (NumberFormatException e) {
                    System.out.println(Ansi.dim("  Unknown command — use: allow <pat> | deny <pat> | <number> to delete"));
                }
            }
        }
    }

    /** Run a shell command and inject the output into the conversation. */
    void runForUser(String command, FileTools fileTools, List<Map<String, Object>> messages) {
        if (command.isBlank()) { System.out.println("Usage: /run <command>"); return; }
        System.out.println(Ansi.dim("  ⚙ " + command));
        String output = fileTools.run(command);
        if (output.length() > 2000) output = output.substring(0, 2000) + "\n… (truncated)";
        System.out.println(output);
        messages.add(LLMClient.user("I ran `" + command + "` in the project root:\n" + output));
    }

    void printState(List<Map<String, Object>> messages) {
        System.out.println(Ansi.divider(58));
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            String role    = (String) msg.get("role");
            String content = String.valueOf(msg.get("content"));
            String preview = content.length() > 120 ? content.substring(0, 120).replace('\n', '↵') + "…" : content.replace('\n', '↵');
            String label   = switch (role) {
                case "system"    -> Ansi.dim("[" + i + "] SYS");
                case "user"      -> Ansi.bold(Ansi.blue("[" + i + "] YOU"));
                case "assistant" -> Ansi.bold(Ansi.green("[" + i + "] AST"));
                default          -> "[" + i + "] " + role.toUpperCase();
            };
            System.out.println(label + "  " + Ansi.dim(preview));
        }
        System.out.println(Ansi.divider(58));
    }

    /** Open the full API JSON in nvim/vim, read it back and replace messages in-place. */
    void editState(List<Map<String, Object>> messages, ToolSupport toolSupport) {
        try {
            var obj = new java.util.LinkedHashMap<String, Object>();
            obj.put("messages", new ArrayList<>(messages));
            obj.put("tools", toolSupport.buildToolsJson());
            String json = PrettyPrinter.prettyPrint(JSONParser.parse(CompactPrinter.compactPrint(obj)));

            Path tmp = Files.createTempFile("llm-state-", ".json");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);

            String editor = "vi";
            for (String e : new String[]{"nvim", "vim", "vi"}) {
                var p = new ProcessBuilder("which", e).start(); p.waitFor();
                if (p.exitValue() == 0) { editor = e; break; }
            }
            int exit = new ProcessBuilder(editor, tmp.toString()).inheritIO().start().waitFor();
            if (exit != 0) { System.out.println(Ansi.yellow("Editor exited with " + exit + " — no changes applied.")); Files.deleteIfExists(tmp); return; }

            String edited = Files.readString(tmp, StandardCharsets.UTF_8);
            Files.deleteIfExists(tmp);
            if (edited.strip().equals(json.strip())) { System.out.println(Ansi.dim("No changes.")); return; }

            var parsed = (java.util.Map<?, ?>) JSONParser.parse(edited);
            var newMessages = (java.util.List<?>) parsed.get("messages");
            if (newMessages == null) { System.out.println(Ansi.yellow("No 'messages' key — no changes applied.")); return; }

            messages.clear();
            for (var m : newMessages) {
                @SuppressWarnings("unchecked") var map = (Map<String, Object>) m;
                messages.add(map);
            }
            stateMessageIndex = -1;
            for (int i = 1; i < messages.size(); i++) {
                var content = String.valueOf(messages.get(i).get("content"));
                if ("assistant".equals(messages.get(i).get("role"))
                        && (content.startsWith("## Goal") || content.startsWith("## Plan") || content.startsWith("## TODOs"))) {
                    stateMessageIndex = i; break;
                }
            }
            System.out.println(Ansi.green("State updated — " + messages.size() + " messages."));
            systemPromptEdited = !String.valueOf(messages.get(0).get("content")).equals(buildSystemPrompt());
        } catch (Exception e) {
            System.out.println(Ansi.yellow("State edit failed: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    /** Subclass provides this — used by clearConversation to restore the system prompt. */
    protected abstract String buildSystemPrompt();
}
