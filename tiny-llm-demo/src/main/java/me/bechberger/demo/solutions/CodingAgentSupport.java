package me.bechberger.demo.solutions;

import me.bechberger.demo.AgentState;
import me.bechberger.demo.CodingTools;
import me.bechberger.demo.FileTools;
import me.bechberger.demo.LLMClient;
import me.bechberger.demo.ToolSupport;
import me.bechberger.demo.util.Ansi;
import me.bechberger.demo.util.Compactor;
import me.bechberger.demo.util.ModelSize;
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

    protected enum ApprovalMode {
        NORMAL(""), AUTO_EDIT("⏵ "), YOLO("⚡ ");
        final String badge;
        ApprovalMode(String badge) { this.badge = badge; }
        String badge() { return badge; }
        ApprovalMode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    final AgentState state = new AgentState();

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
        int contextWindow = client.getContextWindowSize(ModelSize.defaultContextWindowFor(resolveModel()));
        int threshold = maxTokens > 0 ? maxTokens : (int) (contextWindow * 0.8);
        return new Compactor(threshold, 6);
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
        System.out.println(Ansi.dim("[compact] summarizing " + messages.size() + " messages…"));
        var outcome = compactor.compactNow(client, messages);
        if (outcome.compacted()) {
            stateMessageIndex = -1;
            System.out.println(Ansi.dim("[compact] done — " + outcome.messagesBefore()
                    + " → " + outcome.messagesAfter() + " messages"));
        } else {
            System.out.println(Ansi.dim("[compact] nothing to compact (" + messages.size() + " messages)."));
        }
    }

    void clearConversation(List<Map<String, Object>> messages) {
        int dropped = Math.max(0, messages.size() - 1);
        if (dropped > 0) messages.subList(1, messages.size()).clear();
        stateMessageIndex = -1;
        systemPromptEdited = false;
        messages.set(0, LLMClient.system(buildSystemPrompt()));
        System.out.println("Conversation cleared (" + dropped + " messages dropped; goal/plan/TODOs stay pinned).");
    }

    /** Ask the user to approve a risky agent action — auto-approved in YOLO/AUTO_EDIT mode. */
    boolean confirm(String action, boolean defaultYes) {
        boolean autoApprove = approval == ApprovalMode.YOLO
                || (approval == ApprovalMode.AUTO_EDIT && action.startsWith("run:"));
        if (autoApprove) {
            System.out.println("  " + approval.badge() + Ansi.dim("auto-approved (" + approval.name().toLowerCase().replace('_', '-') + "): " + action));
            return true;
        }
        System.out.print("\n" + Ansi.yellow("⚠  " + action) + "\n    Allow? " + (defaultYes ? "[Y/n] " : "[y/N] "));
        if (!scanner.hasNextLine()) return defaultYes;
        String answer = scanner.nextLine().trim().toLowerCase();
        if (System.console() == null) System.out.println(answer);
        return answer.isEmpty() ? defaultYes : answer.startsWith("y");
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
