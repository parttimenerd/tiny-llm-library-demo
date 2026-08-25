package me.bechberger.demo.solutions;

import me.bechberger.demo.AgentState;
import me.bechberger.demo.FileTools;
import me.bechberger.demo.util.ModelSize;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.femtoschema.Schemas;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * CodingAgent extended with skill support.
 * <p>
 * Skills live in .claude/skills/<name>/SKILL.md.
 * The LLM activates them via the "skill" tool; active skill contents
 * are injected into the system prompt before every LLM call.
 * <p>
 * Commands: /plan <goal>  /skill <name>  /todos  exit
 */
@Command(name = "skill-agent", description = "Coding agent with .claude skill support", version = "1.0.0")
public class SkillCodingAgent implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, defaultValue = "fast")
    ModelSize modelSize;

    @Option(names = {"-u", "--base-url"}, defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"-r", "--root"}, defaultValue = ".")
    String root;

    // ── skills ───────────────────────────────────────────────────────────────

    /** name → path to SKILL.md */
    private final Map<String, Path> availableSkills = new LinkedHashMap<>();
    /** name → content (loaded on activation) */
    private final Map<String, String> activeSkills = new LinkedHashMap<>();

    private void discoverSkills() throws IOException {
        Path skillsDir = Path.of(root, ".claude", "skills");
        if (!Files.isDirectory(skillsDir)) return;
        try (var dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path md = dir.resolve("SKILL.md");
                if (Files.exists(md))
                    availableSkills.put(dir.getFileName().toString(), md);
            });
        }
    }

    private String activateSkill(String name) {
        Path md = availableSkills.get(name);
        if (md == null) return "Unknown skill: " + name;
        if (activeSkills.containsKey(name)) return "Skill already active: " + name;
        try {
            activeSkills.put(name, Files.readString(md));
            return "Activated skill: " + name;
        } catch (IOException e) {
            return "Error loading skill: " + e.getMessage();
        }
    }

    private String deactivateSkill(String name) {
        return activeSkills.remove(name) != null ? "Deactivated: " + name : "Not active: " + name;
    }

    /** Build the system prompt: base instructions + active skill contents. */
    private String buildSystemPrompt() {
        var sb = new StringBuilder(
                "You are a coding assistant. Use the file and Maven tools to help. " +
                "Track your work with todo-add/todo-update and update-plan for non-trivial tasks.");

        if (!availableSkills.isEmpty()) {
            sb.append("\n\n## Available Skills\n");
            availableSkills.forEach((name, path) -> sb.append("- ").append(name).append("\n"));
            sb.append("Use the skill tool to activate one.");
        }

        if (!activeSkills.isEmpty()) {
            sb.append("\n\n## Active Skills\n");
            activeSkills.forEach((name, content) ->
                sb.append("\n### ").append(name).append("\n").append(content));
        }

        return sb.toString();
    }

    // ── agent state & helpers (same as CodingAgent) ──────────────────────────

    private final AgentState state = new AgentState();
    private int stateMessageIndex = -1;

    private void printTodos() {
        System.out.println("\n─── Agent State ──────────────────────────────────────────");
        System.out.println(state.render());
        if (!activeSkills.isEmpty())
            System.out.println("Active skills: " + String.join(", ", activeSkills.keySet()));
        System.out.println("──────────────────────────────────────────────────────────");
    }

    private void syncStateMessage(List<Map<String, Object>> messages) {
        if (state.isEmpty()) return;
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", "assistant");
        msg.put("content", state.render());
        if (stateMessageIndex < 0) { messages.add(1, msg); stateMessageIndex = 1; }
        else messages.set(stateMessageIndex, msg);
    }

    // ── main loop ────────────────────────────────────────────────────────────

    @Override
    public Integer call() throws IOException, InterruptedException {
        discoverSkills();

        var client = new LLMClient(baseUrl, modelSize.getModelId(), System.out::print);
        var toolSupport = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));

        registerFileTools(toolSupport, fileTools);
        registerStateTools(toolSupport);
        registerSkillTools(toolSupport);
        toolSupport.setOnToolCall((name, result) -> {
            if (name.startsWith("todo-") || name.equals("update-plan")
                    || name.equals("skill") || name.equals("deactivate-skill"))
                printTodos();
        });

        // System prompt is rebuilt before every call via messages.set(0, ...)
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(buildSystemPrompt()));

        System.out.println("Skill agent ready. Skills: " + availableSkills.keySet());
        System.out.println("Commands: /plan <goal>  /skill <name>  /todos  exit");

        var scanner = new Scanner(System.in);
        while (true) {
            // Refresh system prompt (active skills may have changed)
            messages.set(0, LLMClient.system(buildSystemPrompt()));

            System.out.print("\nYou: ");
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) break;
            if (input.isEmpty()) continue;

            if (input.startsWith("/plan ")) {
                handlePlanCommand(input.substring(6).trim(), client, messages);
                continue;
            }
            if (input.startsWith("/skill ")) {
                System.out.println(activateSkill(input.substring(7).trim()));
                continue;
            }
            if (input.equalsIgnoreCase("/todos")) { printTodos(); continue; }

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

    // ── /plan (identical to CodingAgent) ─────────────────────────────────────

    private void handlePlanCommand(String goal, LLMClient client,
                                   List<Map<String, Object>> messages) throws IOException {
        state.setGoal(goal);
        var planTools = new ToolSupport();
        registerReadOnlyFileTools(planTools, new FileTools(Path.of(root)));
        registerStateTools(planTools);

        var planMessages = new ArrayList<Map<String, Object>>();
        planMessages.add(LLMClient.system(
                "You are in planning mode. Explore the project with ls and cat-paged, then call " +
                "update-plan with a concise approach and todo-add for each concrete step. " +
                "Do NOT write or delete files. Stop after the plan and TODOs are recorded."));
        planMessages.add(LLMClient.user("Goal: " + goal + "\n\nExplore and produce a plan with TODOs."));

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

    // ── tool registration ─────────────────────────────────────────────────────

    private void registerSkillTools(ToolSupport ts) {
        ts.registerTool("skill", "Activate a skill by name",
                Schemas.object().required("name", Schemas.string().withDescription("Skill name")).toJsonSchema(),
                args -> activateSkill((String) args.get("name")));

        ts.registerTool("deactivate-skill", "Deactivate an active skill",
                Schemas.object().required("name", Schemas.string().withDescription("Skill name")).toJsonSchema(),
                args -> deactivateSkill((String) args.get("name")));
    }

    private void registerReadOnlyFileTools(ToolSupport ts, FileTools fileTools) {
        ts.registerTool("ls", "List directory contents",
                Schemas.object().required("path", Schemas.string().withDescription("Directory path")).toJsonSchema(),
                args -> fileTools.ls((String) args.get("path")));
        ts.registerTool("cat-paged", "Read a file, paged (4KB, 0-based)",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("File path"))
                        .required("page", Schemas.number().withDescription("Page number, 0-based"))
                        .toJsonSchema(),
                args -> fileTools.catPaged((String) args.get("path"), ((Number) args.get("page")).intValue()));
    }

    private void registerFileTools(ToolSupport ts, FileTools fileTools) {
        registerReadOnlyFileTools(ts, fileTools);
        ts.registerTool("create-file", "Create a new file (fails if it exists — use write-file to overwrite)",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("File path"))
                        .required("content", Schemas.string().withDescription("File content"))
                        .toJsonSchema(),
                args -> fileTools.createFile((String) args.get("path"), (String) args.get("content")));
        ts.registerTool("write-file", "Write (create or overwrite) a file",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("File path"))
                        .required("content", Schemas.string().withDescription("File content"))
                        .toJsonSchema(),
                args -> fileTools.writeFile((String) args.get("path"), (String) args.get("content")));
        ts.registerTool("create-folder", "Create a folder (and missing parents)",
                Schemas.object().required("path", Schemas.string().withDescription("Folder path")).toJsonSchema(),
                args -> fileTools.createFolder((String) args.get("path")));
        ts.registerTool("delete", "Delete a file or folder (folders recursively)",
                Schemas.object().required("path", Schemas.string().withDescription("Path")).toJsonSchema(),
                args -> fileTools.delete((String) args.get("path")));
        ts.registerTool("run-maven", "Run a Maven command",
                Schemas.object().required("args", Schemas.string().withDescription("Maven arguments")).toJsonSchema(),
                args -> fileTools.runMaven((String) args.get("args")));
    }

    private void registerStateTools(ToolSupport ts) {
        ts.registerTool("todo-add", "Add a TODO item",
                Schemas.object().required("description", Schemas.string().withDescription("What to do")).toJsonSchema(),
                args -> "Added TODO #" + state.addTodo((String) args.get("description")));
        ts.registerTool("todo-update", "Update a TODO status",
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
                    return state.updateTodo(id, status) ? "Updated #" + id : "Unknown #" + id;
                });
        ts.registerTool("todo-remove", "Remove a TODO item",
                Schemas.object().required("id", Schemas.number().withDescription("TODO id")).toJsonSchema(),
                args -> state.removeTodo(((Number) args.get("id")).intValue()) ? "Removed" : "Unknown");
        ts.registerTool("update-plan", "Record the current plan",
                Schemas.object().required("plan", Schemas.string().withDescription("Plan description")).toJsonSchema(),
                args -> { state.setPlan((String) args.get("plan")); return "Plan updated."; });
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new SkillCodingAgent(), args));
    }
}
