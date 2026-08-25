package me.bechberger.demo;

import me.bechberger.demo.util.ModelSize;
import me.bechberger.demo.util.Repl;
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
 * The boring plumbing lives in helpers — {@link Repl} (prompt loop + Commands DSL),
 * {@link CodingTools} (JSON-schema tool registrations); this class keeps the interesting
 * parts: pinned context, plan mode, and the confirmation policy. Commands ({@code /help} lists them):
 *   /plan <goal>  — planning mode (read-only tools, focused planning prompt)
 *   /run <cmd>    — execute a shell command in the project, output shared with the agent
 *   /todos        — print current TODO list
 *   /yolo         — toggle YOLO mode: auto-approve all actions (plan, run, delete)
 *   exit / quit   — exit
 * <p>
 * The agent state (goal, plan, TODOs) is injected as a pinned message at position 1
 * in the conversation and replaced in-place before every LLM call, so the model
 * always sees one current snapshot rather than an accumulating history of state versions.
 */
@Command(name = "coding-agent", description = "A coding chatbot with file/Maven tools and plan/todo support", version = "1.0.0")
public class CodingAgent implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, description = "Model: fast, medium, slow, gpt4o_mini, gpt4o (default: ${DEFAULT-VALUE})",
            defaultValue = "fast")
    ModelSize modelSize;

    @Option(names = {"-u", "--base-url"}, description = "LLM API base URL, optionally with token: url#token (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"-r", "--root"}, description = "Project root directory (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    String root;

    protected void printTodos() {
        if (state.isEmpty()) {
            System.out.println("(no plan or TODOs yet)");
            return;
        }
        System.out.println("\n─── Agent State ──────────────────────────────────────────");
        System.out.println(state.render());
        System.out.println("──────────────────────────────────────────────────────────");
    }

    // ── agent state ──────────────────────────────────────────────────────────

    /** Single shared Scanner for System.in — two Scanners on one stream swallow each other's input. */
    private final Scanner scanner = new Scanner(System.in);

    /** YOLO mode (/yolo): skip all confirmations and let the agent act autonomously. */
    private boolean yolo = false;

    private final AgentState state = new AgentState();

    /** Index into messages[] where the pinned state message lives, or -1 if not yet inserted. */
    private int stateMessageIndex = -1;

    /** Inject or replace the pinned state message right after the system prompt. */
    private void syncStateMessage(List<Map<String, Object>> messages) {
        if (state.isEmpty()) return;
        var msg = stateMessage();
        if (stateMessageIndex < 0) {
            // Insert at index 1 (after system prompt)
            messages.add(1, msg);
            stateMessageIndex = 1;
        } else {
            messages.set(stateMessageIndex, msg);
        }
    }

    private Map<String, Object> stateMessage() {
        var m = new LinkedHashMap<String, Object>();
        m.put("role", "assistant");
        m.put("content", state.render());
        return m;
    }

    // ── main loop — small composition of overridable hooks (see SkillCodingAgent) ──

    @Override
    public Integer call() throws IOException, InterruptedException {
        onStart();
        var client = createClient();
        var fileTools = new FileTools(Path.of(root));
        var toolSupport = createToolSupport(fileTools);

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(buildSystemPrompt()));

        var repl = new Repl("\nYou: ", scanner);
        registerCommands(repl, client, fileTools, messages);

        repl.greet(greeting());
        repl.run(input -> chat(client, toolSupport, messages, input));
        return 0;
    }

    /** Hook for subclasses: runs once before the client, tools and commands are created. */
    protected void onStart() {}

    protected LLMClient createClient() {
        return new LLMClient(baseUrl, modelSize.getModelId(), System.out::print);
    }

    protected String greeting() {
        return "Coding agent ready. Project root: " + Path.of(root).toAbsolutePath().normalize();
    }

    /** All agent tools: file/exec tools (confirmation-gated) plus TODO/plan tools. */
    protected ToolSupport createToolSupport(FileTools fileTools) {
        var toolSupport = new ToolSupport();
        CodingTools.registerFileTools(toolSupport, fileTools, action -> confirm(action, false));
        CodingTools.registerStateTools(toolSupport, state);
        // Re-render todos to terminal after every state-mutating tool call
        toolSupport.setOnToolCall((toolName, result) -> {
            if (toolName.startsWith("todo-") || toolName.equals("update-plan")) {
                printTodos();
            }
        });
        return toolSupport;
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
                "5. Implement with create-file/write-file, then ALWAYS verify with run: build the project and " +
                "execute the artifact with realistic AND invalid inputs; fix and re-run until it works. " +
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
                            yolo = !yolo;
                            System.out.println(yolo ? "⚡ YOLO mode ON — everything is auto-approved"
                                                    : "🔒 YOLO mode OFF — risky actions need confirmation");
                        });
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
        if (!state.isEmpty()) printTodos();
    }

    /** Refresh volatile context before every LLM call: current system prompt + pinned agent state. */
    protected void syncConversation(List<Map<String, Object>> messages) {
        messages.set(0, LLMClient.system(buildSystemPrompt()));
        syncStateMessage(messages);
    }

    /**
     * {@code /run <command>} — execute a shell command just like the agent's own "run" tool,
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

    /**
     * Ask the user to approve a risky agent action — auto-approved in YOLO mode (/yolo).
     *
     * @param action description of the action, e.g. "run: mvn package"
     * @param defaultYes if true, pressing Enter approves (used for plan acceptance);
     *                   if false, Enter declines (used for run/delete)
     */
    private boolean confirm(String action, boolean defaultYes) {
        if (yolo) {
            System.out.println("  ⚡ auto-approved (yolo): " + action);
            return true;
        }
        System.out.print("\n⚠️  " + action + "\n    Allow? " + (defaultYes ? "[Y/n] " : "[y/N] "));
        if (!scanner.hasNextLine()) return defaultYes; // EOF: stick with the default
        String answer = scanner.nextLine().trim().toLowerCase();
        return answer.isEmpty() ? defaultYes : answer.startsWith("y");
    }

    // ── /plan mode ───────────────────────────────────────────────────────────

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

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new CodingAgent(), args));
    }
}
