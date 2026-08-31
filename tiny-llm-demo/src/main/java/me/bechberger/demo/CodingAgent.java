package me.bechberger.demo;

import me.bechberger.demo.AgentState;
import me.bechberger.demo.CodingTools;
import me.bechberger.demo.FileTools;
import me.bechberger.demo.LLMClient;
import me.bechberger.demo.ToolSupport;
import me.bechberger.demo.util.Ansi;
import me.bechberger.demo.util.ApprovalRules;
import me.bechberger.demo.util.Commands;
import me.bechberger.demo.util.Repl;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.util.femtoschema.Schemas;
import me.bechberger.util.json.Util;

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
        var toolSupport = createToolSupport(fileTools, client);

        registerCommands(builder, client, fileTools, toolSupport, messages);
        builder.setLivePane(() -> state.renderPane());
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

    protected ToolSupport createToolSupport(FileTools fileTools, LLMClient client) {
        var toolSupport = new ToolSupport();
        CodingTools.registerFileTools(toolSupport, fileTools, action -> confirm(action, false));
        CodingTools.registerStateTools(toolSupport, state, action -> confirmPlan(action));
        registerJokeTool(toolSupport, client);
        return toolSupport;
    }

    /**
     * The joke tool: a one-off side conversation asking the same LLM for a fresh
     * programming joke, optionally on a topic. Joke traffic stays out of the main
     * conversation; token usage is still tracked by the shared client.
     * Uses {@code chatRaw} so it also works while {@code chat()} is not yet live-coded;
     * API errors degrade gracefully into tool-error strings via {@code ToolSupport.callTool}.
     */
    protected void registerJokeTool(ToolSupport toolSupport, LLMClient client) {
        toolSupport.registerTool("joke",
                "Tell a short, clean, nerdy programming joke — freshly generated each time, optionally on a topic. Use when the user asks for a joke or comic relief.",
                Schemas.object()
                        .optional("topic", Schemas.string().withDescription("Optional joke topic, e.g. 'Maven'"))
                        .toJsonSchema(),
                args -> tellJoke(client, args.containsKey("topic") ? CodingTools.str(args, "topic") : ""));
    }

    private String tellJoke(LLMClient client, String topic) {
        var request = "Tell me one short, clean, nerdy programming joke"
                + (topic.isBlank() ? "" : " about " + topic)
                + ". Reply with only the joke itself.";
        var choice = client.chatRaw(LLMClient.conversation(
                "You are a joke-telling assistant. Short, clean, nerdy programming jokes; reply with only the joke.",
                request), List.of());
        return (String) Util.asMap(choice.get("message")).get("content");
    }

    protected String buildSystemPrompt() {
        return """
                You are a coding assistant with file and shell tools. Be concise; skip prose when tools speak for themselves.

                EXPLORATION (do this before writing anything unfamiliar):
                - Before each tool call, say one sentence: what you're looking for and why.
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

                CLARIFYING QUESTIONS:
                - If the request is ambiguous or could go multiple ways, ask ONE short question before acting.
                - Do not ask about things you can determine by exploring the code.

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
                .on("plan",    "enter planning mode: /plan <goal>",          args -> { try { handlePlanCommand(args, client, messages, input -> chat(client, toolSupport, messages, input)); } catch (Exception e) { throw new RuntimeException(e); } })
                .on("run",     "run a shell command, output shared with the agent: /run <cmd>", args -> runForUser(args, fileTools, messages))
                .on("yolo",    "toggle YOLO mode",                           args -> { approval = approval == ApprovalMode.YOLO ? ApprovalMode.NORMAL : ApprovalMode.YOLO; printMode(); })
                .on("mode",    "cycle approval mode: NORMAL → AUTO-EDIT → YOLO", args -> { approval = approval.next(); printMode(); })
                .on("allow",   "auto-allow matching actions: /allow <pattern>  e.g. /allow \"run: mvn *\"",
                        args -> { if (args.isBlank()) { System.out.println("Usage: /allow <pattern>"); return; }
                                  approvalRules.allow(args); System.out.println(Ansi.green("Allow: " + args)); })
                .on("deny",    "auto-deny matching actions: /deny <pattern>",
                        args -> { if (args.isBlank()) { System.out.println("Usage: /deny <pattern>"); return; }
                                  approvalRules.deny(args);  System.out.println(Ansi.yellow("Deny: " + args)); })
                .on("rules",   "list current allow/deny rules",
                        args -> { var list = approvalRules.rules();
                                  if (list.isEmpty()) { System.out.println(Ansi.dim("(no rules)")); return; }
                                  list.forEach(r -> System.out.println(
                                      (r.effect() == ApprovalRules.Effect.ALLOW ? Ansi.green("allow ") : Ansi.yellow("deny  "))
                                      + r.pattern())); })
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
                                     List<Map<String, Object>> messages, Repl.Chat chat) throws Exception {
        // TODO: live code
        throw new UnsupportedOperationException("TODO: live code");
    }

    private String planningPrompt() {
        return """
                You are in planning mode. Work in exactly three phases:

                PHASE 1 — RESEARCH
                Explore with ls, read-file, grep, find-file. Understand the codebase relevant to the goal.
                Before each tool call, say one sentence: what you're looking for and why.

                PHASE 2 — QUESTIONS (optional, max 3)
                If anything is unclear about scope, approach, or constraints, call ask-user with optional numbered choices.
                Do NOT ask about things the code already answers.

                PHASE 3 — PLAN
                Call write-plan with a complete Markdown plan. Include:
                - A short # title
                - Approach: what will be done and why
                - Numbered steps (each step maps to one TODO)
                - Files to create/modify
                - Verification: how to test

                After write-plan, call todo-add once per step (in order).
                Do NOT write files, run commands, or implement anything.""";
    }

    protected void printTodos() {
        var pane = state.renderPane();
        System.out.println(pane != null ? pane : Ansi.dim("(no plan or TODOs yet)"));
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new CodingAgent(), args));
    }
}
