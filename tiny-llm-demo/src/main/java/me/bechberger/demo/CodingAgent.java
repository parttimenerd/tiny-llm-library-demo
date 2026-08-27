package me.bechberger.demo;

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
        var paneRepl = builder.showPane(() -> state.renderPane());
        paneRepl.withTools(toolSupport);
        this.repl = paneRepl.build();
        startSessionLog();

        // Ctrl+C aborts current call; double Ctrl+C within 1.5 s exits.
        var mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {}));
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
        return "Coding assistant. Use tools only, keep replies brief.\n" +
               "- Exact param names: ls→path, todo-add→description, run→command, edit→path/old/new.\n" +
               "- All paths are relative to the sandbox root. Never search from '/' — use 'find . -name foo' instead.\n" +
               "- For well-known tasks (calculator, hello-world, etc): skip exploration, go straight to creating files.\n" +
               "- For non-trivial or unfamiliar tasks: update-plan first, then todo-add each step, todo-update in_progress/completed as you go.\n" +
               "- Always verify: run mvn package then java -jar target/*.jar with realistic inputs.\n" +
               "- End with one line: what was done and how verified.";
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
        // TODO: live code
        throw new UnsupportedOperationException("TODO: live code");
    }

    // ── pinned context ───────────────────────────────────────────────────────

    /**
     * Refresh volatile context before every LLM call: current system prompt + pinned agent state.
     * <p>
     * TODO: live code
     */
    protected void syncConversation(List<Map<String, Object>> messages) {
        // TODO: live code
        throw new UnsupportedOperationException("TODO: live code");
    }

    /**
     * Keep ONE pinned state message (goal, plan, TODOs) right after system[0],
     * updating it in-place so the model always sees a single current snapshot.
     * <p>
     * TODO: live code
     */
    void syncStateMessage(List<Map<String, Object>> messages) {
        // TODO: live code
        throw new UnsupportedOperationException("TODO: live code");
    }

    // ── /plan mode ───────────────────────────────────────────────────────────

    /**
     * /plan &lt;goal&gt; — side conversation with read-only tools → plan + TODOs → confirm → pin into main chat.
     * <p>
     * TODO: live code
     */
    protected void handlePlanCommand(String goal, LLMClient client,
                                     List<Map<String, Object>> messages) {
        // TODO: live code
        throw new UnsupportedOperationException("TODO: live code");
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
