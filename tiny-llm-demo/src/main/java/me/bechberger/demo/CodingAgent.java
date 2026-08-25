package me.bechberger.demo;

import me.bechberger.demo.http.Config;
import me.bechberger.demo.util.Compactor;
import me.bechberger.demo.util.ModelSize;
import me.bechberger.demo.util.Repl;
import me.bechberger.demo.util.SessionLog;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *   /plan &lt;goal&gt;  — planning mode (read-only tools, focused planning prompt)
 *   /run &lt;cmd&gt;    — execute a shell command in the project, output shared with the agent
 *   /todos       — print current TODO list
 *   /mode        — cycle approval policy (NORMAL → AUTO-EDIT → YOLO); /yolo toggles YOLO directly
 *   /clear       — clear the conversation, keeping system prompt and pinned state
 *   /compact     — fold old history into a summary now; /tokens shows usage + threshold
 *   exit / quit  — exit (a trailing "\" continues input on the next line)
 */
@Command(name = "coding-agent", description = "A coding chatbot with file/exec tools and plan/todo support", version = "1.0.0")
public class CodingAgent implements Callable<Integer> {

    // ── femtocli options ─────────────────────────────────────────────────────

    @Option(names = {"-m", "--model"}, description = "Model size: fast, medium, slow, gpt4o_mini, gpt4o, kimi_k3 (default: the endpoint's model from the config file, else fast)")
    ModelSize modelSize;

    @Option(names = {"-u", "--base-url"}, description = "LLM endpoint: a name from the config file (e.g. 'gardener'), a URL, or url#token (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"--max-tokens"}, description = "Compact the conversation above this many prompt tokens (default: auto = 80%% of the model's context window)",
            defaultValue = "0")
    int maxTokens;

    @Option(names = {"--no-log"}, description = "Do not write a session transcript to ~/.tiny-llm-library/sessions/")
    boolean noLog;

    @Option(names = {"-r", "--root"}, description = "Project root directory (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    String root;

    // ── runtime state ────────────────────────────────────────────────────────

    /** Single shared Scanner for System.in — two Scanners on one stream swallow each other's input. */
    private final Scanner scanner = new Scanner(System.in);

    /**
     * Approval policy for risky actions - like Claude Code's Shift-Tab modes:
     * NORMAL asks for run/delete, AUTO_EDIT additionally auto-approves plans, YOLO approves everything.
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

    /** Index into messages[] where the pinned state message lives, or -1 if not yet inserted. */
    private int stateMessageIndex = -1;

    /** Compaction policy - folds old history once the prompt exceeds the threshold (built in {@link #createCompactor}). */
    protected Compactor compactor;

    // ── main loop: a small composition of overridable hooks ──────────────────

    @Override
    public Integer call() throws IOException, InterruptedException {
        onStart();
        var client = createClient();
        compactor = createCompactor(client);
        var fileTools = new FileTools(Path.of(root));
        var toolSupport = createToolSupport(fileTools);

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(buildSystemPrompt()));

        var repl = new Repl("\nYou: ", scanner);
        repl.setPrompt(() -> "\n" + approval.badge() + "You: ");
        registerCommands(repl, client, fileTools, messages);
        startSessionLog();

        repl.greet(greeting());
        repl.run(input -> chat(client, toolSupport, messages, input));
        return 0;
    }

    /** Hook for subclasses: runs once before the client, tools and commands are created. */
    protected void onStart() {}

    protected LLMClient createClient() {
        return new LLMClient(baseUrl, resolveModel(), System.out::print);
    }

    /** --model, else the endpoint's default model from the config file, else {@link ModelSize#FAST}. */
    protected String resolveModel() {
        return modelSize != null ? modelSize.getModelId()
                : Config.load().modelFor(baseUrl, ModelSize.FAST.getModelId());
    }

    protected String greeting() {
        return "Coding agent ready. Model: " + resolveModel()
                + " (compacting above " + compactor.threshold() + " prompt tokens)"
                + " - project root: " + Path.of(root).toAbsolutePath().normalize();
    }

    /**
     * Compaction trigger: above 80% of the model's context window (auto-detected via
     * GET /v1/models, falling back to ModelSize defaults), or the --max-tokens override.
     * Real token-usage data drives it - no character estimates.
     */
    protected Compactor createCompactor(LLMClient client) {
        String model = resolveModel();
        int contextWindow = client.getContextWindowSize(ModelSize.defaultContextWindowFor(model));
        int threshold = maxTokens > 0 ? maxTokens : (int) (contextWindow * 0.8);
        return new Compactor(threshold, 6);
    }

    /** All agent tools: file/exec tools (confirmation-gated) plus TODO/plan tools. */
    protected ToolSupport createToolSupport(FileTools fileTools) {
        var toolSupport = new ToolSupport();
        CodingTools.registerFileTools(toolSupport, fileTools, action -> confirm(action, false));
        CodingTools.registerStateTools(toolSupport, state);
        // Re-render the state box whenever a tool call changes it
        toolSupport.setOnToolCall((toolName, result) -> {
            if (toolName.startsWith("todo-") || toolName.equals("update-plan")) {
                printTodos();
            }
        });
        return toolSupport;
    }

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
            case YOLO      -> "⚡ YOLO mode - everything is auto-approved";
            case AUTO_EDIT -> "⏵ AUTO-EDIT mode - plans auto-approved, run/delete still ask";
            case NORMAL    -> "🔒 NORMAL mode - risky actions need confirmation";
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
        if (dropped > 0) messages.subList(1, messages.size()).clear(); // keep messages[0] (system prompt)
        stateMessageIndex = -1; // pinned state is re-inserted on next sync
        System.out.println("Conversation cleared (" + dropped + " messages dropped; goal/plan/TODOs stay pinned).");
    }

    /**
     * The main system prompt. Re-synced into {@code messages[0]} before every LLM call
     * (via {@link #syncConversation}), so overrides that append dynamic sections —
     * e.g. active skills — take effect immediately.
     */
    protected String buildSystemPrompt() {
        return
                "You are a pragmatic coding assistant working inside the project directory via tools. " +
                "How to work: " +
                "1. Use the EXACT parameter names from each tool's schema (ls: \"path\"; todo-add: \"description\"; " +
                "run: \"command\"). A \"missing required argument\" error means you used a wrong name — " +
                "retry immediately with the correct one. " +
                "2. Explore with ls/cat-paged before changing anything. " +
                "3. For non-trivial tasks: update-plan once, then one todo-add per concrete step — each step " +
                "exactly once. When a plan was just accepted, its TODOs already exist in your context: " +
                "start by marking the first one in_progress, never re-add them. " +
                "4. Work the list: todo-update to in_progress when you start a step and to completed as soon " +
                "as it is done, without being asked. " +
                "5. Implement with create-file (new files), write-file (full rewrite) or edit (surgical " +
                "change in an existing file), then ALWAYS verify with run: build the project and " +
                "execute the produced artifact itself - for a jar that means java -jar target/*.jar " +
                "(java -cp target/classes does NOT count), with realistic AND invalid inputs; " +
                "fix and re-run until it works. " +
                "When everything is completed, finish with a brief summary: what you built and how you " +
                "verified it. Keep chat replies short; let the tools do the work.";
    }

    /** REPL commands beyond the built-in exit/quit and /help. */
    protected void registerCommands(Repl repl, LLMClient client, FileTools fileTools,
                                    List<Map<String, Object>> messages) {
        repl.commands()
                .on("todos", "show the current plan and TODO list", args -> printTodos())
                .on("plan", "enter planning mode for a goal: /plan <goal>",
                        args -> handlePlanCommand(args, client, messages))
                .on("run", "execute a shell command in the project, output shared with the agent: /run <command>",
                        args -> runForUser(args, fileTools, messages))
                .on("yolo", "toggle YOLO mode — auto-approve all actions (plan acceptance, run, delete)",
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

    /** One chat round: append the user input, run the tool loop, print and record the reply. */
    protected void chat(LLMClient client, ToolSupport toolSupport,
                        List<Map<String, Object>> messages, String input) throws IOException {
        messages.add(LLMClient.user(input));
        syncConversation(messages);

        System.out.print("\nAssistant: ");
        String response = toolSupport.handleToolLoop(client, messages);
        System.out.println(response);
        messages.add(LLMClient.assistant(response));
        syncStateMessage(messages);
        // no banner re-print here: state changes already trigger printTodos via onToolCall

        // fold old history when the prompt got too big (the pinned state survives -
        // index 1 is never summarized, and shifts trigger a re-insert next turn)
        var compaction = compactor.maybeCompact(client, messages, 1);
        if (compaction.compacted()) {
            stateMessageIndex = -1; // indexes shifted - pinned state is re-inserted on next sync
            System.out.println("[compact] " + compaction.messagesBefore() + " -> "
                    + compaction.messagesAfter() + " messages (prompt was "
                    + compaction.promptTokens() + " tokens)");
        }
    }

    /** Refresh volatile context before every LLM call: current system prompt + pinned agent state. */
    protected void syncConversation(List<Map<String, Object>> messages) {
        messages.set(0, LLMClient.system(buildSystemPrompt()));
        syncStateMessage(messages);
    }

    // ── pinned context: one state message, always current ────────────────────

    /**
     * The agent state (goal, plan, TODOs) lives in ONE message pinned right after the
     * system prompt and is replaced in place before every LLM call — the model always
     * sees a single current snapshot instead of an accumulating history of versions.
     */
    private void syncStateMessage(List<Map<String, Object>> messages) {
        if (state.isEmpty()) return;
        var msg = stateMessage();
        if (stateMessageIndex < 0) {
            messages.add(1, msg);
            stateMessageIndex = 1;
        } else {
            messages.set(stateMessageIndex, msg);
        }
    }

    /** The pinned message (assistant role keeps it stable across providers). */
    private Map<String, Object> stateMessage() {
        var m = new LinkedHashMap<String, Object>();
        m.put("role", "assistant");
        m.put("content", state.render());
        return m;
    }

    protected void printTodos() {
        if (state.isEmpty()) {
            System.out.println("(no plan or TODOs yet)");
            return;
        }
        System.out.println("\n─── Agent State ──────────────────────────────────────────");
        System.out.println(state.render());
        System.out.println("──────────────────────────────────────────────────────────");
    }

    // ── /plan mode: read-only tools, fresh conversation, user confirmation ────

    /**
     * /plan &lt;goal&gt; — a side conversation with a planning prompt and read-only tools.
     * The model explores the project, records a plan and TODOs into the shared state,
     * and the user then accepts or discards it. An accepted plan becomes visible to the
     * main conversation (recorded as a message pair; the goal is pinned with the state).
     */
    protected void handlePlanCommand(String goal, LLMClient client,
                                     List<Map<String, Object>> messages) throws IOException {
        if (goal.isBlank()) {
            System.out.println("Usage: /plan <goal>");
            return;
        }

        var planTools = new ToolSupport();
        CodingTools.registerReadOnlyFileTools(planTools, new FileTools(Path.of(root)));
        CodingTools.registerStateTools(planTools, state);

        var planMessages = new ArrayList<Map<String, Object>>();
        planMessages.add(LLMClient.system(
                "You are in planning mode: explore and plan, do not execute. " +
                "Explore the project with ls and cat-paged (exact parameter names: ls takes \"path\", " +
                "cat-paged takes \"path\" and \"page\"), then call update-plan ONCE with a concise approach " +
                "that names the concrete files to create and the exact run command that will verify the " +
                "result, and add each implementation step exactly once via todo-add with its \"description\" " +
                "parameter — never duplicate a step. " +
                "Do NOT write files or run builds. Stop after the plan and TODOs are recorded."));
        planMessages.add(LLMClient.user("Goal: " + goal + "\n\nExplore the project and produce a plan with TODOs."));

        System.out.print("\nPlanning: ");
        String response = planTools.handleToolLoop(client, planMessages);

        System.out.println("\n\n--- Plan ready ---");
        printTodos();

        if (!confirm("Accept this plan?", true)) {
            state.clear();
            System.out.println("Plan discarded.");
            return;
        }

        state.setGoal(goal);
        messages.add(LLMClient.user("/plan " + goal));
        messages.add(LLMClient.assistant(response));
        syncStateMessage(messages);
    }

    // ── user interactions: confirmations and user-initiated runs ─────────────

    /**
     * Ask the user to approve a risky agent action — auto-approved in YOLO mode (/yolo).
     *
     * @param action description of the action, e.g. "run: mvn package"
     * @param defaultYes if true, pressing Enter approves (used for plan acceptance);
     *                   if false, Enter declines (used for run/delete)
     */
    private boolean confirm(String action, boolean defaultYes) {
        if (approval == ApprovalMode.YOLO || (approval == ApprovalMode.AUTO_EDIT && defaultYes)) {
            System.out.println("  " + approval.badge() + "auto-approved (" + approval.name().toLowerCase().replace('_', '-') + "): " + action);
            return true;
        }
        System.out.print("\n⚠️  " + action + "\n    Allow? " + (defaultYes ? "[Y/n] " : "[y/N] "));
        if (!scanner.hasNextLine()) return defaultYes; // EOF: stick with the default
        String answer = scanner.nextLine().trim().toLowerCase();
        return answer.isEmpty() ? defaultYes : answer.startsWith("y");
    }

    /**
     * /run &lt;command&gt; — execute a shell command just like the agent's own "run" tool,
     * print the output, and add it to the conversation so the agent can react to it.
     */
    private void runForUser(String command, FileTools fileTools, List<Map<String, Object>> messages) {
        if (command.isBlank()) {
            System.out.println("Usage: /run <command>");
            return;
        }
        System.out.println("  ⚙ " + command);
        String output = fileTools.run(command);
        System.out.println(output);
        messages.add(LLMClient.user("I ran `" + command + "` in the project root:\n" + output));
        syncStateMessage(messages);
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new CodingAgent(), args));
    }
}
