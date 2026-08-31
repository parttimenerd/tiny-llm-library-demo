package me.bechberger.demo.solutions;

import me.bechberger.demo.AgentState;
import me.bechberger.demo.FileTools;
import me.bechberger.util.femtoschema.Schemas;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public final class CodingTools {

    private CodingTools() {}

    public static void register(ToolSupport ts, String name, String description,
                                Function<Map<String, Object>, String> handler,
                                String... nameThenDescription) {
        var schema = Schemas.object();
        for (int i = 0; i < nameThenDescription.length; i += 2) {
            schema = schema.required(nameThenDescription[i],
                    Schemas.string().withDescription(nameThenDescription[i + 1]));
        }
        ts.registerTool(name, description, schema.toJsonSchema(), handler);
    }

    public static void registerReadOnlyFileTools(ToolSupport ts, FileTools fileTools) {
        register(ts, "ls", "List directory contents (one level). Use tree for a recursive overview.",
                args -> fileTools.ls(str(args, "path")),
                "path", "Directory path relative to project root");

        ts.registerTool("read-file",
                "Read a file's contents. Large files are capped at 20 000 chars — use start_line/end_line to paginate.",
                Schemas.object()
                        .required("path", Schemas.string().withDescription("File path relative to project root"))
                        .optional("start_line", Schemas.number().withDescription("First line to read (1-based, inclusive)"))
                        .optional("end_line",   Schemas.number().withDescription("Last line to read (1-based, inclusive)"))
                        .toJsonSchema(),
                args -> {
                    int start = args.containsKey("start_line") ? ((Number) args.get("start_line")).intValue() : -1;
                    int end   = args.containsKey("end_line")   ? ((Number) args.get("end_line")).intValue()   : -1;
                    return fileTools.readFile(str(args, "path"), start, end);
                });

        ts.registerTool("grep",
                "Search project files for text (case-insensitive), like grep -rn. Omit path to search the whole project.",
                Schemas.object()
                        .required("query", Schemas.string().withDescription("Text to search for (case-insensitive)"))
                        .optional("path", Schemas.string().withDescription("File or directory relative to project root (default: '.' = whole project)"))
                        .toJsonSchema(),
                args -> fileTools.grep(str(args, "query"), args.containsKey("path") ? str(args, "path") : "."));

        ts.registerTool("tree",
                "Recursive directory tree (default depth 3, max 5). Use to get a project overview before reading individual files.",
                Schemas.object()
                        .optional("path",  Schemas.string().withDescription("Directory relative to project root (default: '.')"))
                        .optional("depth", Schemas.number().withDescription("Tree depth 1–5 (default 3)"))
                        .toJsonSchema(),
                args -> fileTools.tree(
                        args.containsKey("path")  ? str(args, "path")  : ".",
                        args.containsKey("depth") ? ((Number) args.get("depth")).intValue() : 3));

        ts.registerTool("find-file",
                "Find files whose path or name contains the given text. Faster than grep when you know the filename.",
                Schemas.object()
                        .required("query", Schemas.string().withDescription("Text to match against file paths (case-insensitive)"))
                        .toJsonSchema(),
                args -> fileTools.findFiles(str(args, "query")));
    }

    public static void registerFileTools(ToolSupport ts, FileTools fileTools, Predicate<String> approve) {
        registerReadOnlyFileTools(ts, fileTools);

        register(ts, "create-file", "Create a new file with content (fails if it already exists - use write-file to overwrite)",
                args -> guarded(approve, action("create-file", args, "content"),
                        () -> fileTools.createFile(str(args, "path"), str(args, "content"))),
                "path", "File path relative to project root", "content", "File content");

        register(ts, "write-file", "Write (create or overwrite) a file",
                args -> guarded(approve, action("write-file", args, "content"),
                        () -> fileTools.writeFile(str(args, "path"), str(args, "content"))),
                "path", "File path relative to project root", "content", "Full file content to write");

        register(ts, "edit", "Replace exact text in an existing file - 'old' must occur exactly once; " +
                        "use for surgical changes instead of rewriting the whole file",
                args -> guarded(approve, action("edit", args, "old", "new"),
                        () -> fileTools.editFile(str(args, "path"), str(args, "old"), str(args, "new"))),
                "path", "File relative to project root",
                "old", "Exact current text to replace (must occur exactly once - include surrounding lines if ambiguous)",
                "new", "Replacement text");

        register(ts, "create-folder", "Create a folder (and any missing parents)",
                args -> guarded(approve, action("create-folder", args),
                        () -> fileTools.createFolder(str(args, "path"))),
                "path", "Folder path relative to project root");

        register(ts, "delete", "Delete a file or folder (folders are deleted recursively). Requires user confirmation.",
                args -> guarded(approve, action("delete", args),
                        () -> fileTools.delete(str(args, "path")),
                        "User declined this deletion."),
                "path", "Path relative to project root");

        register(ts, "run", "Run a bash command in the project root and return its output - use it to build and to " +
                        "verify, e.g. \"mvn -q package\", \"java -jar target/app.jar '2+3*4'\" " +
                        "(60s timeout, output truncated at 16KB). Requires user confirmation.",
                args -> guarded(approve, action("run", args),
                        () -> fileTools.run(str(args, "command")),
                        "User declined to run this command."),
                "command", "Bash command to execute in the project root");
    }

    private static final String DECLINED = "User declined — ask why or suggest /yolo.";

    private static String guarded(Predicate<String> approve, String actionStr,
                                  java.util.function.Supplier<String> work) {
        return guarded(approve, actionStr, work, DECLINED);
    }

    private static String guarded(Predicate<String> approve, String actionStr,
                                  java.util.function.Supplier<String> work, String declineMsg) {
        return approve.test(actionStr) ? work.get() : declineMsg;
    }

    public static void registerStateTools(ToolSupport ts, AgentState state, Predicate<String> approve) {
        register(ts, "todo-add", "Add a new TODO item",
                args -> "Added TODO #" + state.addTodo(str(args, "description")),
                "description", "What needs to be done");

        register(ts, "todo-update", "Update the status of a TODO item",
                args -> {
                    int id = number(args, "id");
                    String status = (str(args, "status")).toLowerCase().replace('-', '_');
                    var s = switch (status) {
                        case "in_progress" -> AgentState.Status.IN_PROGRESS;
                        case "completed"   -> AgentState.Status.COMPLETED;
                        default            -> AgentState.Status.PENDING;
                    };
                    return state.updateTodo(id, s) ? "Updated TODO #" + id + " -> " + status : "Unknown TODO #" + id;
                },
                "id", "TODO id", "status", "pending, in_progress, or completed");

        ts.registerTool("todo-edit", "Change the description (and optionally the status) of an existing TODO item",
                Schemas.object()
                        .required("id",          Schemas.number().withDescription("TODO id"))
                        .required("description", Schemas.string().withDescription("New description text"))
                        .optional("status",      Schemas.string().withDescription("pending, in_progress, or completed — omit to keep current"))
                        .toJsonSchema(),
                args -> {
                    int id = number(args, "id");
                    String desc = str(args, "description");
                    Object statusArg = args.get("status");
                    AgentState.Status s = null;
                    if (statusArg != null) {
                        s = switch (statusArg.toString().toLowerCase().replace('-', '_')) {
                            case "in_progress" -> AgentState.Status.IN_PROGRESS;
                            case "completed"   -> AgentState.Status.COMPLETED;
                            default            -> AgentState.Status.PENDING;
                        };
                    }
                    return state.editTodo(id, desc, s) ? "Edited TODO #" + id : "Unknown TODO #" + id;
                });

        register(ts, "todo-remove", "Remove a TODO item",
                args -> {
                    int id = number(args, "id");
                    return state.removeTodo(id) ? "Removed TODO #" + id : "Unknown TODO #" + id;
                },
                "id", "TODO id to remove");

        register(ts, "update-plan", "Record or update the current plan — user must approve before execution begins",
                args -> {
                    String plan = str(args, "plan");
                    state.setPlan(plan);
                    if (!approve.test(action("plan", args))) {
                        state.setPlan(null);
                        return "Plan rejected by user. Ask the user what to change, then call update-plan again with the revised plan.";
                    }
                    return "Plan approved.";
                },
                "plan", "Concise description of the plan");
    }

    public static String str(Map<String, Object> args, String name) {
        var value = args.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing required argument '" + name + "' - check the tool's parameter list");
        }
        return value.toString();
    }

    private static int number(Map<String, Object> args, String name) {
        var value = args.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing required argument '" + name + "' - check the tool's parameter list");
        }
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString().trim());
    }

    /**
     * Build an action string for the approve predicate.
     * Format: "toolName: primaryValue | {\"key\":\"val\",...}"
     * Keys in {@code skipKeys} are excluded from the JSON suffix (use for large content fields).
     * The primary value is the first non-skipped arg value, used for backward-compatible glob matching.
     */
    static String action(String toolName, Map<String, Object> args, String... skipKeys) {
        var skip = java.util.Set.of(skipKeys);
        // primary value: first arg value not in skipKeys, for the "toolName: X" prefix
        String primary = args.entrySet().stream()
                .filter(e -> !skip.contains(e.getKey()))
                .map(e -> e.getValue() == null ? "" : e.getValue().toString())
                .findFirst().orElse("");
        // compact JSON of all non-skipped args
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : args.entrySet()) {
            if (skip.contains(e.getKey())) continue;
            if (!first) sb.append(",");
            first = false;
            String v = e.getValue() == null ? "" : e.getValue().toString();
            sb.append("\"").append(e.getKey()).append("\":\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return toolName + ": " + primary + " | " + sb;
    }
}
