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
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Mixin;
import me.bechberger.femtocli.annotations.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * A coding chatbot with file/exec tools, a dynamic TODO list, and /plan mode.
 * <p>
 * The boring plumbing lives in helpers — {@link Repl} (prompt loop + Commands DSL) and
 * {@link CodingTools} (tool registrations); this class keeps the interesting parts:
 * pinned context, plan mode, the confirmation policy, and compaction of old history.
 * <p>
 * Reading guide — the file is laid out in the order a session unfolds:
 * options → runtime state → the main loop as a composition of hooks → pinned context →
 * /plan mode → user interactions. Subclasses extend it via those hooks (see
 * {@link SkillCodingAgent}, which only overrides a few of them).
 * <p>
 * REPL commands ({@code /help} lists them):
 *   /todo        — show TODO list; /todo add|done|undone|del to manage entries
 *   /plan &lt;goal&gt;  — planning mode (read-only tools, focused planning prompt)
 *   /run &lt;cmd&gt;    — execute a shell command in the project, output shared with the agent
 *   /mode        — cycle approval policy (NORMAL → AUTO-EDIT → YOLO); /yolo toggles YOLO directly
 *   /clear       — clear the conversation, keeping system prompt and pinned state
 *   /compact     — fold old history into a summary now; /tokens shows usage + threshold
 *   exit / quit  — exit (a trailing "\" continues input on the next line)
 */
@Command(name = "coding-agent", description = "A coding chatbot with file/exec tools and plan/todo support", version = "1.0.0")
public class CodingAgent implements Callable<Integer> {

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
    private final Scanner scanner = new Scanner(System.in);

    /**
     * Approval policy for risky actions - like Claude Code's Shift-Tab modes:
     * NORMAL asks for run/delete, AUTO_EDIT auto-approves run (but not plans), YOLO approves run/delete (but not plans).
     */
    protected ApprovalMode approval = ApprovalMode.NORMAL;

    /** The approval policies; the badge shows in the prompt when not NORMAL. */
    protected enum ApprovalMode {
        NORMAL(""), AUTO_EDIT("⏵ "), YOLO("⚡ ");
        final String badge;
        ApprovalMode(String badge) { this.badge = badge; }
        String badge() { return badge; }
        ApprovalMode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private final AgentState state = new AgentState();

    /** Set once the REPL is built in {@link #call()} — used by confirmPlan for prompting. */
    private Repl repl;

    /** Index into messages[] where the pinned state message lives, or -1 if not yet inserted. */
    private int stateMessageIndex = -1;

    /** Compaction policy - folds old history once the prompt exceeds the threshold (built in {@link #createCompactor}). */
    protected Compactor compactor;

    // ── main loop: a small composition of overridable hooks ──────────────────

    @Override
    public Integer call() throws IOException, InterruptedException {
        onStart();
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(buildSystemPrompt()));

        var builder = new Repl.Builder("\n" + Ansi.bold(Ansi.blue("You: ")), scanner, messages)
                .prompt(() -> "\n" + approval.badge() + Ansi.bold(Ansi.blue("You: ")));
        var client = createClient(builder);
        compactor = createCompactor(client);
        var fileTools = new FileTools(Path.of(root));
        var toolSupport = createToolSupport(fileTools);

        registerCommands(builder, client, fileTools, messages);
        var paneRepl = builder.showPane(() -> state.renderPane());
        toolSupport.setOnToolCall((toolName, result) -> {
            if (toolName.startsWith("todo-") || toolName.equals("update-plan")) paneRepl.pane.run();
            paneRepl.redrawSidebar();
        });
        this.repl = paneRepl.build();
        startSessionLog();

        // Ctrl+C during an LLM call: interrupt the current thread so the HTTP call unblocks
        // and Repl.run catches InterruptedException, prints [interrupted], and loops back
        var mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {})); // suppress default SIGINT exit
        try {
            var sig = new sun.misc.Signal("INT");
            sun.misc.Signal.handle(sig, s -> mainThread.interrupt());
        } catch (IllegalArgumentException ignored) {} // not available on all JVMs

        repl.greet(greeting());
        repl.run(input -> chat(client, toolSupport, messages, input));
        return 0;
    }

    // ── extension hooks ──────────────────────────────────────────────────────
    // Override any of these in subclasses. SkillCodingAgent overrides:
    // onStart, greeting, createToolSupport, registerCommands, buildSystemPrompt.

    /** Hook for subclasses: runs once before the client, tools and commands are created. */
    protected void onStart() {}

    protected LLMClient createClient(Repl.Builder builder) {
        var client = options.createClient(builder);
        options.model = client.detectServerModelId();
        return client;
    }

    /** --model, else the endpoint's default model from the config file, else server-detected, else {@link ModelSize#FAST}. */
    protected String resolveModel() {
        return options.resolveModel();
    }

    protected String greeting() {
        return "Coding agent ready. Model: " + resolveModel()
                + " (compacting above " + compactor.threshold() + " prompt tokens)"
                + " - project root: " + Path.of(root).toAbsolutePath().normalize();
    }

    protected Compactor createCompactor(LLMClient client) {
        int contextWindow = client.getContextWindowSize(ModelSize.defaultContextWindowFor(resolveModel()));
        int threshold = maxTokens > 0 ? maxTokens : (int) (contextWindow * 0.8);
        return new Compactor(threshold, 6);
    }

    /** All agent tools: file/exec tools (confirmation-gated), TODO/plan tools. */
    protected ToolSupport createToolSupport(FileTools fileTools) {
        var toolSupport = new ToolSupport();
        CodingTools.registerFileTools(toolSupport, fileTools, action -> confirm(action, false));
        CodingTools.registerStateTools(toolSupport, state, action -> confirmPlan(action));
        return toolSupport;
    }

    /**
     * The main system prompt. Re-synced into {@code messages[0]} before every LLM call
     * (via {@link #syncConversation}), so overrides that append dynamic sections —
     * e.g. active skills — take effect immediately.
     */
    protected String buildSystemPrompt() {
        return "Coding assistant. Use tools only, keep replies brief.\n" +
               "- Exact param names: ls→path, todo-add→description, run→command, edit→path/old/new.\n" +
               "- All paths are relative to the sandbox root. Never search from '/' — use 'find . -name foo' instead.\n" +
               "- For well-known tasks (calculator, hello-world, etc): skip exploration, go straight to creating files.\n" +
               "- For non-trivial or unfamiliar tasks: update-plan first, then todo-add each step, todo-update in_progress/completed as you go.\n" +
               "- Always verify: run mvn package then java -jar target/*.jar with realistic inputs.\n" +
               "- End with one line: what was done and how verified.";
    }

    /** REPL commands beyond the built-in exit/quit and /help. */
    protected void registerCommands(Repl.Builder builder, LLMClient client, FileTools fileTools,
                                    List<Map<String, Object>> messages) {
        builder
                .on("state", "show the full conversation (messages truncated to 120 chars)",
                        args -> printState(messages))
                .sub("todo", "manage TODOs (no args → show list)")
                    .on("add",    "<desc> — add a TODO",   (String desc) -> { int id = state.addTodo(desc); System.out.println(Ansi.green("Added #" + id + ": " + desc)); })
                    .on("done",   "<id> — mark completed", (int id) -> state.updateTodo(id, AgentState.Status.COMPLETED))
                    .on("undone", "<id> — mark pending",   (int id) -> state.updateTodo(id, AgentState.Status.PENDING))
                    .on("del",    "<id> — remove",         (int id) -> state.removeTodo(id))
                .end(this::printTodos)
                .on("plan", "enter planning mode for a goal: /plan <goal>",
                        args -> handlePlanCommand(args, client, messages))
                .on("run", "execute a shell command in the project, output shared with the agent: /run <command>",
                        args -> runForUser(args, fileTools, messages))
                .on("yolo", "toggle YOLO mode — everything auto-approved except plans",
                        args -> {
                            approval = approval == ApprovalMode.YOLO ? ApprovalMode.NORMAL : ApprovalMode.YOLO;
                            printMode();
                        })
                .on("mode", "cycle approval mode: NORMAL → AUTO-EDIT → YOLO (like Shift-Tab in Claude Code)",
                        args -> { approval = approval.next(); printMode(); })
                .on("clear", "clear the conversation, keeping system prompt and pinned state",
                        args -> clearConversation(messages))
                .on("compact", "fold old history into a summary now (usually automatic near the context limit)",
                        args -> compactNow(client, messages))
                .on("tokens", "show token usage of the last call and the compaction threshold",
                        args -> printTokens(client, messages));
    }

    // ── chat round ───────────────────────────────────────────────────────────

    /**
     * One chat round: append user input, sync context, run tool loop, record reply, compact if needed.
     * <p>
     * TODO: live code
     */
    protected void chat(LLMClient client, ToolSupport toolSupport,
                        List<Map<String, Object>> messages, String input) throws IOException {
        // @stub
        messages.add(LLMClient.user(input));
        syncConversation(messages);
        System.out.print(Ansi.bold(Ansi.green("\nAssistant: ")));
        String response = toolSupport.handleToolLoop(client, messages);
        System.out.println(response);
        messages.add(LLMClient.assistant(response));
        syncStateMessage(messages);
        var compaction = compactor.maybeCompact(client, messages, 1);
        if (compaction.compacted()) {
            stateMessageIndex = -1;
            System.out.println(Ansi.dim("[compact] " + compaction.messagesBefore() + " → " + compaction.messagesAfter() + " messages"));
        }
        // @end
    }

    // ── pinned context ───────────────────────────────────────────────────────

    /**
     * Refresh volatile context before every LLM call: current system prompt + pinned agent state.
     * <p>
     * TODO: live code
     */
    protected void syncConversation(List<Map<String, Object>> messages) {
        // @stub
        messages.set(0, LLMClient.system(buildSystemPrompt()));
        syncStateMessage(messages);
        // @end
    }

    /**
     * Keep ONE pinned state message (goal, plan, TODOs) right after system[0],
     * updating it in-place so the model always sees a single current snapshot.
     * <p>
     * TODO: live code
     */
    private void syncStateMessage(List<Map<String, Object>> messages) {
        // @stub
        if (state.isEmpty()) return;
        var msg = LLMClient.assistant(state.render());
        if (stateMessageIndex < 0) { messages.add(1, msg); stateMessageIndex = 1; }
        else messages.set(stateMessageIndex, msg);
        // @end
    }

    protected void printTodos() {
        var pane = state.renderPane();
        System.out.println(pane != null ? pane : Ansi.dim("(no plan or TODOs yet)"));
    }

    private void printState(List<Map<String, Object>> messages) {
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

    // ── /plan mode ───────────────────────────────────────────────────────────

    /**
     * /plan &lt;goal&gt; — side conversation (read-only tools) → plan + TODOs → confirm → pin into main chat.
     * <p>
     * TODO: live code
     */
    protected void handlePlanCommand(String goal, LLMClient client,
                                     List<Map<String, Object>> messages) {
        // @stub
        if (goal.isBlank()) { System.out.println("Usage: /plan <goal>"); return; }
        var planTools = new ToolSupport();
        CodingTools.registerReadOnlyFileTools(planTools, new FileTools(Path.of(root)));
        CodingTools.registerStateTools(planTools, state, action -> true);
        System.out.print(Ansi.bold(Ansi.yellow("\nPlanning: ")));
        String response = Repl.io(() -> planTools.handleToolLoop(client,
                LLMClient.conversation(planningPrompt(), "Goal: " + goal + "\n\nExplore and produce a plan with TODOs.")));
        System.out.println(Ansi.bold("\n─── Plan ready ──────────────────────────────────────────"));
        printTodos();
        if (!confirm("Accept this plan?", true)) { state.clear(); System.out.println("Plan discarded."); return; }
        state.setGoal(goal);
        messages.add(LLMClient.user("/plan " + goal));
        messages.add(LLMClient.assistant(response));
        syncStateMessage(messages);
        // @end
    }

    private String planningPrompt() {
        return "You are in planning mode: explore and plan, do not execute. " +
               "Explore with ls and read-file, then call update-plan ONCE with a concise approach " +
               "naming the concrete files to create and the exact run command that will verify it. " +
               "Add each implementation step exactly once via todo-add — never duplicate a step. " +
               "Do NOT write files or run builds. Stop after plan and TODOs are recorded.";
    }

    // ── user interactions ────────────────────────────────────────────────────

    /** Start the session transcript - everything you see also lands in the log file. */
    private void startSessionLog() {
        if (noLog) return;
        try {
            System.out.println("Session log: " + SessionLog.start(getClass().getSimpleName()));
        } catch (IOException e) {
            System.err.println("Could not start session log: " + e.getMessage());
        }
    }

    private void printMode() {
        System.out.println(switch (approval) {
            case YOLO      -> Ansi.yellow("⚡ YOLO mode — everything is auto-approved (except plans)");
            case AUTO_EDIT -> Ansi.blue("⏵ AUTO-EDIT mode — run auto-approved, delete/plans still ask");
            case NORMAL    -> Ansi.dim("🔒 NORMAL mode — risky actions need confirmation");
        });
    }

    private void printTokens(LLMClient client, List<Map<String, Object>> messages) {
        var u = client.lastUsage();
        System.out.println(u == null ? "(no usage data yet)"
                : "last call: prompt " + u.promptTokens() + " + completion " + u.completionTokens() + " tokens"
                + " - history: " + messages.size() + " messages - compacting above " + compactor.threshold());
    }

    private void compactNow(LLMClient client, List<Map<String, Object>> messages) {
        var outcome = compactor.compactNow(client, messages);
        if (outcome.compacted()) stateMessageIndex = -1;
        System.out.println(outcome.compacted()
                ? "[compact] " + outcome.messagesBefore() + " -> " + outcome.messagesAfter() + " messages"
                : "Nothing to compact yet (" + messages.size() + " messages).");
    }

    private void clearConversation(List<Map<String, Object>> messages) {
        int dropped = Math.max(0, messages.size() - 1);
        if (dropped > 0) messages.subList(1, messages.size()).clear();
        stateMessageIndex = -1;
        System.out.println("Conversation cleared (" + dropped + " messages dropped; goal/plan/TODOs stay pinned).");
    }

    /**
     * Ask the user to approve a risky agent action — auto-approved in YOLO mode (/yolo).
     *
     * @param action description of the action, e.g. "run: mvn package"
     * @param defaultYes if true, pressing Enter approves (used for plan acceptance);
     *                   if false, Enter declines (used for run/delete)
     */
    private boolean confirm(String action, boolean defaultYes) {
        if (approval == ApprovalMode.YOLO || (approval == ApprovalMode.AUTO_EDIT && defaultYes)) {
            System.out.println("  " + approval.badge() + Ansi.dim("auto-approved (" + approval.name().toLowerCase().replace('_', '-') + "): " + action));
            return true;
        }
        System.out.print("\n" + Ansi.yellow("⚠  " + action) + "\n    Allow? " + (defaultYes ? "[Y/n] " : "[y/N] "));
        if (!scanner.hasNextLine()) return defaultYes;
        String answer = scanner.nextLine().trim().toLowerCase();
        if (System.console() == null) System.out.println(answer); // piped: keep logs readable
        return answer.isEmpty() ? defaultYes : answer.startsWith("y");
    }

    /**
     * Always pauses to show the plan and ask for approval — even in YOLO mode.
     * The action string is "plan: <plan text>"; strips the prefix before rendering.
     */
    private boolean confirmPlan(String action) {
        String plan = action.startsWith("plan: ") ? action.substring(6) : action;
        System.out.println("\n" + Ansi.bold("─── Plan ────────────────────────────────────────────────"));
        for (String line : plan.split("\n", -1)) System.out.println("  " + line);
        System.out.println(Ansi.bold("─────────────────────────────────────────────────────────"));
        if (approvePlans) {
            System.out.println(Ansi.dim("  auto-approved (--approve-plans)"));
            return true;
        }
        String answer = repl != null ? repl.prompt("  Proceed? [Y/n/feedback] ", "") : "";
        if (answer.isEmpty() || answer.equalsIgnoreCase("y")) return true;
        if (answer.equalsIgnoreCase("n")) return false;
        state.setPlan("REJECTED — user feedback: " + answer);
        return false;
    }

    /** Execute a shell command just like the agent's own "run" tool,
     * print the output, and add it to the conversation so the agent can react to it.
     */
    private void runForUser(String command, FileTools fileTools, List<Map<String, Object>> messages) {
        if (command.isBlank()) { System.out.println("Usage: /run <command>"); return; }
        System.out.println(Ansi.dim("  ⚙ " + command));
        String output = fileTools.run(command);
        if (output.length() > 2000) output = output.substring(0, 2000) + "\n… (truncated)";
        System.out.println(output);
        messages.add(LLMClient.user("I ran `" + command + "` in the project root:\n" + output));
        syncStateMessage(messages);
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new CodingAgent(), args));
    }
}
