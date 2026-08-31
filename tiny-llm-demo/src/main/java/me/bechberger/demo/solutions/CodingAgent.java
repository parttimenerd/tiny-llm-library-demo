package me.bechberger.demo.solutions;

import me.bechberger.demo.AgentState; // @demo: import me.bechberger.demo.AgentState;
import me.bechberger.demo.solutions.CodingTools; // @demo: import me.bechberger.demo.CodingTools;
import me.bechberger.demo.FileTools; // @demo: import me.bechberger.demo.FileTools;
import me.bechberger.demo.LLMClient; // @demo: import me.bechberger.demo.LLMClient;
import me.bechberger.demo.solutions.ToolSupport; // @demo: import me.bechberger.demo.ToolSupport;
import me.bechberger.demo.util.Ansi;
import me.bechberger.demo.util.Commands;
import me.bechberger.demo.util.Repl;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A coding chatbot with file/exec tools, a dynamic TODO list, and /plan mode.
 * <p>
 * Reading guide — the file unfolds in session order:
 * call() → chat() → syncConversation() → handlePlanCommand()
 * <p>
 * Operational plumbing (session log, /state edit, confirmations, etc.) lives in
 * {@link CodingAgentSupport} to keep this file focused on the concepts.
 */
@Command(name = "coding-agent", description = "A coding chatbot with file/exec tools and plan/todo support", version = "1.0.0")
public class CodingAgent extends CodingAgentSupport {

    // ── main loop ────────────────────────────────────────────────────────────

    @Override
    public Integer call() {
        onStart();
        var rootPath = Path.of(root).toAbsolutePath().normalize();
        try { java.nio.file.Files.createDirectories(rootPath); } catch (Exception ignored) {}
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(buildSystemPrompt()));

        var builder = new Repl.Builder("\n" + Ansi.bold(Ansi.blue("You: ")), scanner, messages)
                .prompt(() -> "\n" + approval.badge() + Ansi.bold(Ansi.blue("You: ")));
        var client = createClient(builder);
        compactor = createCompactor(client);
        var fileTools = new FileTools(rootPath);
        var toolSupport = createToolSupport(fileTools);

        registerCommands(builder, client, fileTools, toolSupport, messages);
        builder.showPane(() -> state.renderPane());
        builder.withTools(toolSupport);
        this.repl = builder.build();
        startSessionLog();

        // Ctrl+C aborts current call; double Ctrl+C within 1.5 s exits.
        var mainThread = Thread.currentThread();
        var lastInterrupt = new long[]{0};
        try {
            var sig = new sun.misc.Signal("INT");
            sun.misc.Signal.handle(sig, s -> {
                long now = System.currentTimeMillis();
                mainThread.interrupt();
                if (now - lastInterrupt[0] < 1500) { System.out.println("\n[exit]"); System.exit(0); }
                lastInterrupt[0] = now;
            });
        } catch (IllegalArgumentException ignored) {}

        repl.greet(greeting());
        repl.run(input -> chat(client, toolSupport, messages, input));
        return 0;
    }

    // ── extension hooks (override in subclasses) ─────────────────────────────

    protected void onStart() {}

    protected String greeting() {
        return "Coding agent ready. Model: " + resolveModel()
                + " — root: " + Path.of(root).toAbsolutePath().normalize();
    }

    protected ToolSupport createToolSupport(FileTools fileTools) {
        var toolSupport = new ToolSupport();
        CodingTools.registerFileTools(toolSupport, fileTools, action -> confirm(action, false));
        CodingTools.registerStateTools(toolSupport, state, action -> confirmPlan(action));
        return toolSupport;
    }

    protected String buildSystemPrompt() {
        return """
                You are a coding assistant with file and shell tools. Be concise; skip prose when tools speak for themselves.

                EXPLORATION (do this before writing anything unfamiliar):
                - tree . — project overview (depth 3 by default)
                - ls <dir> — one directory level
                - find-file <name> — locate a file by name fragment
                - grep <text> — search across all files
                - read-file <path> — read a file; add start_line/end_line to page large files

                EDITING — prefer surgical edits:
                - edit path/old/new — replace exact text (must be unique; add surrounding lines if ambiguous)
                - write-file — only for new files or complete rewrites
                - For files > 200 lines: read the relevant section (start_line/end_line) before editing

                VERIFICATION — always run after changes:
                - run "mvn -q package" — compiles and packages; check exit code
                - run "java -jar target/*.jar <args>" — test with realistic inputs
                - On failure: read the [ERROR] lines; fix, then re-run

                TOOL DISCIPLINE:
                - Exact param names: ls→path, read-file→path, grep→query/path, edit→path/old/new, run→command
                - All paths are relative to the project root — never use absolute paths
                - Never search from '/' — use 'find . -name foo' or find-file instead

                PLANNING (for non-trivial tasks):
                - update-plan once with the approach, then todo-add each step
                - todo-update in_progress when starting a step, completed when done
                - For simple well-known tasks (calculator, hello-world): skip planning, go straight to implementation

                END each turn: one line — what was done and how it was verified.""";
    }

    protected void registerCommands(Repl.Builder builder, LLMClient client, FileTools fileTools,
                                    ToolSupport toolSupport, List<Map<String, Object>> messages) {
        builder
                .sub("state", "show or edit conversation state")
                    .on("show", "print messages (truncated)", (Commands.Handler) args -> printState(messages))
                    .on("edit", "open full API JSON in vim to view/edit", (Commands.Handler) args -> editState(messages, toolSupport))
                .end(() -> printState(messages))
                .sub("todo", "manage TODOs (no args → show list)")
                    .on("add",    "<desc> — add a TODO",   (String desc) -> { int id = state.addTodo(desc); System.out.println(Ansi.green("Added #" + id + ": " + desc)); })
                    .on("done",   "<id> — mark completed", (int id) -> state.updateTodo(id, AgentState.Status.COMPLETED))
                    .on("undone", "<id> — mark pending",   (int id) -> state.updateTodo(id, AgentState.Status.PENDING))
                    .on("del",    "<id> — remove",         (int id) -> state.removeTodo(id))
                .end(() -> { if (state.isEmpty()) System.out.println(Ansi.dim("(no plan or TODOs yet)")); })
                .on("plan",    "enter planning mode: /plan <goal>",          args -> handlePlanCommand(args, client, messages))
                .on("run",     "run a shell command, output shared with the agent: /run <cmd>", args -> runForUser(args, fileTools, messages))
                .on("yolo",    "toggle YOLO mode",                           args -> { approval = approval == ApprovalMode.YOLO ? ApprovalMode.NORMAL : ApprovalMode.YOLO; printMode(); })
                .on("mode",    "cycle approval mode: NORMAL → AUTO-EDIT → YOLO", args -> { approval = approval.next(); printMode(); })
                .on("clear",   "clear conversation, keep system prompt and state", args -> clearConversation(messages))
                .on("compact", "fold old history into a summary now",        args -> compactNow(client, messages))
                .on("tokens",  "show token usage and compaction threshold",  args -> printTokens(client, messages));
    }

    // ── chat round ───────────────────────────────────────────────────────────

    /**
     * One chat round: sync context, call the tool loop, record reply, compact if needed.
     * <p>
     * TODO: live code
     */
    protected void chat(LLMClient client, ToolSupport toolSupport,
                        List<Map<String, Object>> messages, String input) throws Exception {
        // @stub
        syncConversation(messages);
        messages.add(LLMClient.user(input));
        System.out.print(Ansi.bold(Ansi.green("\nAssistant: ")));
        String response = toolSupport.handleToolLoop(client, messages);
        if (response != null && !response.isBlank()) System.out.println(response);
        else System.out.println(Ansi.dim("(no text response)"));
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
        if (!systemPromptEdited) messages.set(0, LLMClient.system(buildSystemPrompt()));
        syncStateMessage(messages);
        // @end
    }

    /**
     * Keep ONE pinned state message (goal, plan, TODOs) right after system[0],
     * updating it in-place so the model always sees a single current snapshot.
     * <p>
     * TODO: live code
     */
    void syncStateMessage(List<Map<String, Object>> messages) {
        // @stub
        if (state.isEmpty()) return;
        var msg = LLMClient.assistant(state.render());
        if (stateMessageIndex < 0) { messages.add(1, msg); stateMessageIndex = 1; }
        else messages.set(stateMessageIndex, msg);
        // @end
    }

    // ── /plan mode ───────────────────────────────────────────────────────────

    /**
     * /plan &lt;goal&gt; — side conversation with read-only tools → plan + TODOs → confirm → pin into main chat.
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
        var planMessages = LLMClient.conversation(planningPrompt(), "Goal: " + goal + "\n\nExplore and produce a plan with TODOs.");
        String response = null;
        while (true) {
            System.out.print(Ansi.bold(Ansi.yellow("\nPlanning: ")));
            response = Repl.io(() -> planTools.handleToolLoop(client, planMessages));
            System.out.println(Ansi.bold("\n─── Plan ready ──────────────────────────────────────────"));
            printTodos();
            String answer = repl != null ? repl.prompt("  Proceed? [Y/n/feedback] ", "") : "";
            if (answer.isEmpty() || answer.equalsIgnoreCase("y")) break;
            if (answer.equalsIgnoreCase("n")) { state.clear(); System.out.println("Plan discarded."); return; }
            // feedback: ask LLM to revise
            state.clear();
            planMessages.add(LLMClient.user("Please revise the plan based on this feedback: " + answer + "\n\nExplore again if needed, then call update-plan and todo-add to set the revised plan."));
        }
        state.setGoal(goal);
        messages.add(LLMClient.user("/plan " + goal));
        if (response != null) messages.add(LLMClient.assistant(response));
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

    protected void printTodos() {
        var pane = state.renderPane();
        System.out.println(pane != null ? pane : Ansi.dim("(no plan or TODOs yet)"));
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new CodingAgent(), args));
    }
}
