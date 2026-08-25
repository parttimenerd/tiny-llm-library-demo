package me.bechberger.demo.solutions;

import me.bechberger.demo.AgentState;
import me.bechberger.demo.FileTools;
import me.bechberger.demo.util.ModelSize;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.femtoschema.Schemas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

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

    private final AgentState state = new AgentState();
    private int stateMessageIndex = -1;

    private void printTodos() {
        System.out.println("\n─── Agent State ──────────────────────────────────────────");
        System.out.println(state.render());
        System.out.println("──────────────────────────────────────────────────────────");
    }

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

    private Map<String, Object> stateMessage() {
        var m = new LinkedHashMap<String, Object>();
        m.put("role", "assistant");
        m.put("content", state.render());
        return m;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var client = new LLMClient(baseUrl, modelSize.getModelId(), System.out::print);
        var toolSupport = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));

        registerFileTools(toolSupport, fileTools);
        registerStateTools(toolSupport);
        toolSupport.setOnToolCall((toolName, result) -> {
            if (toolName.startsWith("todo-") || toolName.equals("update-plan")) {
                printTodos();
            }
        });

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(
                "You are a coding assistant. Use the file and Maven tools to help. " +
                "Track your work with todo-add/todo-update and update-plan for non-trivial tasks."));

        System.out.println("Coding agent ready. Project root: " + Path.of(root).toAbsolutePath().normalize());
        System.out.println("Commands: /plan <goal>  /todos  exit");

        var scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\nYou: ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) break;
            if (input.isEmpty()) continue;

            if (input.startsWith("/plan ")) {
                handlePlanCommand(input.substring(6).trim(), client, messages);
                continue;
            }
            if (input.equalsIgnoreCase("/todos")) {
                printTodos();
                continue;
            }

            messages.add(LLMClient.user(input));
            syncStateMessage(messages);

            System.out.print("\nAssistant: ");
            String response = toolSupport.handleToolLoop(client, messages);
            messages.add(LLMClient.assistant(response));
            syncStateMessage(messages);
            System.out.println();
            if (!state.isEmpty()) printTodos();
        }
        return 0;
    }

    private void handlePlanCommand(String goal, LLMClient client, List<Map<String, Object>> messages)
            throws IOException {
        state.setGoal(goal);

        var planTools = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));
        registerReadOnlyFileTools(planTools, fileTools);
        registerStateTools(planTools);

        var planMessages = new ArrayList<Map<String, Object>>();
        planMessages.add(LLMClient.system(
                "You are in planning mode. Explore the project with ls and cat-paged, then call " +
                "update-plan with a concise approach and todo-add for each concrete step. " +
                "Do NOT write or delete files. Stop after the plan and TODOs are recorded."));
        planMessages.add(LLMClient.user("Goal: " + goal + "\n\nExplore the project and produce a plan with TODOs."));

        System.out.print("\nPlanning: ");
        String response = planTools.handleToolLoop(client, planMessages);

        System.out.println("\n\n--- Plan ready ---");
        printTodos();

        System.out.print("\nAccept? [Y/n] ");
        if (new Scanner(System.in).nextLine().trim().equalsIgnoreCase("n")) return;

        messages.add(LLMClient.user("/plan " + goal));
        messages.add(LLMClient.assistant(response));
        syncStateMessage(messages);
    }

    private void registerReadOnlyFileTools(ToolSupport ts, FileTools fileTools) {
        ts.registerTool("ls",
                "List directory contents",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("Directory path relative to project root"))
                        .toJsonSchema(),
                args -> fileTools.ls((String) args.get("path")));

        ts.registerTool("cat-paged",
                "Read a file, paged (4KB per page, 0-based)",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("File path relative to project root"))
                        .required("page", Schemas.number().withDescription("Page number, 0-based"))
                        .toJsonSchema(),
                args -> fileTools.catPaged((String) args.get("path"), ((Number) args.get("page")).intValue()));
    }

    private void registerFileTools(ToolSupport ts, FileTools fileTools) {
        registerReadOnlyFileTools(ts, fileTools);

        ts.registerTool("create-file",
                "Create a new file with content (fails if it already exists — use write-file to overwrite)",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("File path relative to project root"))
                        .required("content", Schemas.string().withDescription("File content"))
                        .toJsonSchema(),
                args -> fileTools.createFile((String) args.get("path"), (String) args.get("content")));

        ts.registerTool("write-file",
                "Write (create or overwrite) a file",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("File path relative to project root"))
                        .required("content", Schemas.string().withDescription("Full file content to write"))
                        .toJsonSchema(),
                args -> fileTools.writeFile((String) args.get("path"), (String) args.get("content")));

        ts.registerTool("create-folder",
                "Create a folder (and any missing parents)",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("Folder path relative to project root"))
                        .toJsonSchema(),
                args -> fileTools.createFolder((String) args.get("path")));

        ts.registerTool("delete",
                "Delete a file or folder (folders are deleted recursively)",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("Path relative to project root"))
                        .toJsonSchema(),
                args -> fileTools.delete((String) args.get("path")));

        ts.registerTool("run-maven",
                "Run a Maven command in the project root and return its output",
                Schemas.object()
                        .required("args", Schemas.string().withDescription("Maven arguments, e.g. \"test\", \"compile\", \"clean install -q\""))
                        .toJsonSchema(),
                args -> fileTools.runMaven((String) args.get("args")));
    }

    private void registerStateTools(ToolSupport ts) {
        ts.registerTool("todo-add",
                "Add a new TODO item",
                Schemas.object()
                        .required("description", Schemas.string().withDescription("What needs to be done"))
                        .toJsonSchema(),
                args -> {
                    int id = state.addTodo((String) args.get("description"));
                    return "Added TODO #" + id;
                });

        ts.registerTool("todo-update",
                "Update the status of a TODO item",
                Schemas.object()
                        .required("id", Schemas.number().withDescription("TODO id"))
                        .required("status", Schemas.string().withDescription("pending, in_progress, or completed"))
                        .toJsonSchema(),
                args -> {
                    int id = ((Number) args.get("id")).intValue();
                    AgentState.Status status = switch ((String) args.get("status")) {
                        case "in_progress" -> AgentState.Status.IN_PROGRESS;
                        case "completed"   -> AgentState.Status.COMPLETED;
                        default            -> AgentState.Status.PENDING;
                    };
                    return state.updateTodo(id, status)
                            ? "Updated TODO #" + id + " → " + args.get("status")
                            : "Unknown TODO #" + id;
                });

        ts.registerTool("todo-remove",
                "Remove a TODO item",
                Schemas.object()
                        .required("id", Schemas.number().withDescription("TODO id to remove"))
                        .toJsonSchema(),
                args -> {
                    int id = ((Number) args.get("id")).intValue();
                    return state.removeTodo(id) ? "Removed TODO #" + id : "Unknown TODO #" + id;
                });

        ts.registerTool("update-plan",
                "Record or update the current plan",
                Schemas.object()
                        .required("plan", Schemas.string().withDescription("Concise description of the plan"))
                        .toJsonSchema(),
                args -> {
                    state.setPlan((String) args.get("plan"));
                    return "Plan updated.";
                });
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new CodingAgent(), args));
    }
}
