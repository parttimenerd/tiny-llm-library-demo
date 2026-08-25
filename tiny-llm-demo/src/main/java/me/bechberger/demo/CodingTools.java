package me.bechberger.demo;

import me.bechberger.util.femtoschema.Schemas;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Tool registrations for {@link CodingAgent} - the boring JSON-schema plumbing, kept out
 * of the talk-visible agent class so it can focus on the interesting parts.
 * <p>
 * Provides the read-only file tools (also used in /plan mode), the full file/exec tool
 * set with confirmation-gated risky actions, and the agent-state tools (TODOs + plan).
 */
final class CodingTools {

    private CodingTools() {}

    /**
     * Register a tool whose parameters are all required strings,
     * given as (name, description) pairs - keeps one tool one line.
     */
    static void register(ToolSupport ts, String name, String description,
                         Function<Map<String, Object>, String> handler, String... nameThenDescription) {
        var schema = Schemas.object();
        for (int i = 0; i < nameThenDescription.length; i += 2) {
            schema.required(nameThenDescription[i],
                    Schemas.string().withDescription(nameThenDescription[i + 1]));
        }
        ts.registerTool(name, description, schema.toJsonSchema(), handler);
    }

    /** Read-only exploration tools - the only file tools available in /plan mode. */
    static void registerReadOnlyFileTools(ToolSupport ts, FileTools fileTools) {
        register(ts, "ls", "List directory contents",
                args -> fileTools.ls(str(args, "path")),
                "path", "Directory path relative to project root");

        register(ts, "cat-paged", "Read a file, paged (4KB per page, 0-based)",
                args -> fileTools.catPaged(str(args, "path"), number(args, "page")),
                "path", "File path relative to project root", "page", "Page number, 0-based");

        register(ts, "grep",
                "Search all project files for text (case-insensitive); returns 'path:line: match' lines, like grep -rn",
                args -> fileTools.grep(str(args, "query"), str(args, "path")),
                "query", "Text to search for (case-insensitive)",
                "path", "File or directory relative to project root; use '.' for the whole project");
    }

    /**
     * Read-only tools plus create/write/delete and the "run" exec tool.
     * Destructive actions (delete, run) consult {@code approve} first.
     */
    static void registerFileTools(ToolSupport ts, FileTools fileTools, Predicate<String> approve) {
        registerReadOnlyFileTools(ts, fileTools);

        register(ts, "create-file", "Create a new file with content (fails if it already exists - use write-file to overwrite)",
                args -> fileTools.createFile(str(args, "path"), str(args, "content")),
                "path", "File path relative to project root", "content", "File content");

        register(ts, "write-file", "Write (create or overwrite) a file",
                args -> fileTools.writeFile(str(args, "path"), str(args, "content")),
                "path", "File path relative to project root", "content", "Full file content to write");

        register(ts, "edit", "Replace exact text in an existing file - 'old' must occur exactly once; " +
                        "use for surgical changes instead of rewriting the whole file",
                args -> fileTools.editFile(str(args, "path"), str(args, "old"), str(args, "new")),
                "path", "File relative to project root",
                "old", "Exact current text to replace (must occur exactly once - include surrounding lines if ambiguous)",
                "new", "Replacement text");

        register(ts, "create-folder", "Create a folder (and any missing parents)",
                args -> fileTools.createFolder(str(args, "path")),
                "path", "Folder path relative to project root");

        register(ts, "delete", "Delete a file or folder (folders are deleted recursively). Requires user confirmation.",
                args -> approve.test("delete: " + args.get("path"))
                        ? fileTools.delete(str(args, "path"))
                        : "User declined this deletion - ask for reasons, or suggest they enable /yolo mode.",
                "path", "Path relative to project root");

        register(ts, "run", "Run a bash command in the project root and return its output - use it to build and to " +
                        "verify, e.g. \"mvn -q package\", \"java -jar target/app.jar '2+3*4'\" " +
                        "(60s timeout, output truncated at 16KB). Requires user confirmation.",
                args -> approve.test("run: " + args.get("command"))
                        ? fileTools.run(str(args, "command"))
                        : "User declined to run this command - ask for reasons, or suggest they enable /yolo mode. " +
                          "The user can also execute it themselves with /run.",
                "command", "Bash command to execute in the project root");
    }

    /** TODO-list and plan tools that mutate the given agent state. */
    static void registerStateTools(ToolSupport ts, AgentState state) {
        register(ts, "todo-add", "Add a new TODO item",
                args -> "Added TODO #" + state.addTodo(str(args, "description")),
                "description", "What needs to be done");

        register(ts, "todo-update", "Update the status of a TODO item",
                args -> {
                    int id = number(args, "id");
                    // models variously say "in_progress", "in-progress", "IN_PROGRESS", ... - normalize
                    String status = (str(args, "status")).toLowerCase().replace('-', '_');
                    var s = switch (status) {
                        case "in_progress" -> AgentState.Status.IN_PROGRESS;
                        case "completed"   -> AgentState.Status.COMPLETED;
                        default            -> AgentState.Status.PENDING;
                    };
                    return state.updateTodo(id, s) ? "Updated TODO #" + id + " -> " + status : "Unknown TODO #" + id;
                },
                "id", "TODO id", "status", "pending, in_progress, or completed");

        register(ts, "todo-remove", "Remove a TODO item",
                args -> {
                    int id = number(args, "id");
                    return state.removeTodo(id) ? "Removed TODO #" + id : "Unknown TODO #" + id;
                },
                "id", "TODO id to remove");

        register(ts, "update-plan", "Record or update the current plan",
                args -> {
                    state.setPlan(str(args, "plan"));
                    return "Plan updated.";
                },
                "plan", "Concise description of the plan");
    }

    /**
     * Fetch a required string arg - or fail with a clear message that the tool loop feeds back
     * to the model, so it retries with the correct name instead of us silently storing nulls
     * (models occasionally invent arg names like "task" instead of "description").
     */
    static String str(Map<String, Object> args, String name) {
        var value = args.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing required argument '" + name + "' - check the tool's parameter list");
        }
        return value.toString();
    }

    /**
     * Numeric args arrive as numbers when the model obeys the schema, as strings when it doesn't.
     * Missing args get the same feedback error as {@link #str} instead of an NPE.
     */
    private static int number(Map<String, Object> args, String name) {
        var value = args.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing required argument '" + name + "' - check the tool's parameter list");
        }
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString().trim());
    }
}
