package me.bechberger.demo;

import me.bechberger.demo.util.Ansi;
import me.bechberger.demo.util.Highlight;
import me.bechberger.util.femtoschema.Schemas;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Tool registrations for {@link CodingAgent} - the boring JSON-schema plumbing, kept out
 * of the talk-visible agent class so it can focus on the interesting parts.
 * <p>
 * Provides the read-only file tools (also used in /plan mode), the full file/exec tool
 * set with confirmation-gated risky actions, and the agent-state tools (TODOs + plan).
 */
public final class CodingTools {

    private CodingTools() {}

    /**
     * Register a tool whose parameters are all required strings,
     * given as (name, description) pairs - keeps one tool one line.
     */
    public static void register(ToolSupport ts, String name, String description,
                         Function<Map<String, Object>, String> handler, String... nameThenDescription) {
        register(ts, name, description, handler, null, nameThenDescription);
    }

    public static void register(ToolSupport ts, String name, String description,
                         Function<Map<String, Object>, String> handler,
                         java.util.function.Consumer<String> printer, String... nameThenDescription) {
        register(ts, name, description, handler, printer, null, nameThenDescription);
    }

    public static void register(ToolSupport ts, String name, String description,
                         Function<Map<String, Object>, String> handler,
                         java.util.function.Consumer<String> printer,
                         Function<Map<String, Object>, String> argSummarizer,
                         String... nameThenDescription) {
        var schema = Schemas.object();
        for (int i = 0; i < nameThenDescription.length; i += 2) {
            schema = schema.required(nameThenDescription[i],
                    Schemas.string().withDescription(nameThenDescription[i + 1]));
        }
        ts.registerTool(name, description, schema.toJsonSchema(), handler, printer, argSummarizer);
    }

    public static void register(ToolSupport ts, String name, String description,
                         Function<Map<String, Object>, String> handler,
                         BiConsumer<Map<String, Object>, String> argsPrinter,
                         Function<Map<String, Object>, String> argSummarizer,
                         String... nameThenDescription) {
        var schema = Schemas.object();
        for (int i = 0; i < nameThenDescription.length; i += 2) {
            schema = schema.required(nameThenDescription[i],
                    Schemas.string().withDescription(nameThenDescription[i + 1]));
        }
        ts.registerTool(name, description, schema.toJsonSchema(), handler, argsPrinter, argSummarizer);
    }

    private static void printFileResult(String r, String filename) {
        int nl = r.indexOf('\n');
        if (nl < 0) { System.out.println("    → " + r); return; }
        System.out.println("    → " + r.substring(0, nl));
        String body = r.substring(nl + 1);
        String highlighted = Highlight.file(body, filename);
        String[] lines = highlighted.split("\n", -1);
        int shown = Math.min(lines.length, 20);
        for (int i = 0; i < shown; i++) System.out.println("    " + lines[i]);
        if (lines.length > shown) System.out.println(Ansi.dim("    … (" + (lines.length - shown) + " more lines)"));
    }

    private static void printFileResult(String r) {
        printFileResult(r, null);
    }

    private static void printDiffResult(String r) {
        for (String line : Highlight.diff(r).split("\n", -1)) {
            System.out.println("    " + line);
        }
    }

    /** Read-only exploration tools - the only file tools available in /plan mode. */
    public static void registerReadOnlyFileTools(ToolSupport ts, FileTools fileTools) {
        register(ts, "ls", "List directory contents",
                args -> fileTools.ls(str(args, "path")),
                r -> System.out.println("    → " + Ansi.dim(r.replace("\n", "  "))),
                "path", "Directory path relative to project root");

        register(ts, "read-file", "Read a file's full contents (up to 20KB)",
                args -> fileTools.readFile(str(args, "path")),
                (args, r) -> {
                    String path = str(args, "path");
                    String[] lines = Highlight.file(r, path).split("\n", -1);
                    int shown = Math.min(lines.length, 20);
                    for (int i = 0; i < shown; i++) System.out.println("    " + lines[i]);
                    if (lines.length > shown) System.out.println(Ansi.dim("    … (" + (lines.length - shown) + " more lines)"));
                },
                null,
                "path", "File path relative to project root");

        ts.registerTool("grep",
                "Search project files for text (case-insensitive), like grep -rn. Omit path to search the whole project.",
                Schemas.object()
                        .required("query", Schemas.string().withDescription("Text to search for (case-insensitive)"))
                        .optional("path", Schemas.string().withDescription("File or directory relative to project root (default: '.' = whole project)"))
                        .toJsonSchema(),
                args -> fileTools.grep(str(args, "query"), args.containsKey("path") ? str(args, "path") : "."),
                r -> {
                    String[] lines = r.split("\n", -1);
                    int shown = Math.min(lines.length, 10);
                    for (int i = 0; i < shown; i++) System.out.println("    " + lines[i]);
                    if (lines.length > shown) System.out.println(Ansi.dim("    … (" + (lines.length - shown) + " more lines)"));
                });
    }

    /**
     * Read-only tools plus create/write/delete and the "run" exec tool.
     * Destructive actions (delete, run) consult {@code approve} first.
     */
    public static void registerFileTools(ToolSupport ts, FileTools fileTools, Predicate<String> approve) {
        registerReadOnlyFileTools(ts, fileTools);

        register(ts, "create-file", "Create a new file with content (fails if it already exists - use write-file to overwrite)",
                args -> fileTools.createFile(str(args, "path"), str(args, "content")),
                (args, r) -> printFileResult(r, str(args, "path")),
                args -> "{\"path\":\"" + args.get("path") + "\"}",
                "path", "File path relative to project root", "content", "File content");

        register(ts, "write-file", "Write (create or overwrite) a file",
                args -> fileTools.writeFile(str(args, "path"), str(args, "content")),
                (args, r) -> printFileResult(r, str(args, "path")),
                args -> "{\"path\":\"" + args.get("path") + "\"}",
                "path", "File path relative to project root", "content", "Full file content to write");

        register(ts, "edit", "Replace exact text in an existing file - 'old' must occur exactly once; " +
                        "use for surgical changes instead of rewriting the whole file",
                args -> fileTools.editFile(str(args, "path"), str(args, "old"), str(args, "new")),
                (args, r) -> printDiffResult(r),
                args -> "{\"path\":\"" + args.get("path") + "\"}",
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
                r -> {
                    String[] lines = r.split("\n", -1);
                    int shown = Math.min(lines.length, 20);
                    for (int i = 0; i < shown; i++) System.out.println("    " + lines[i]);
                    if (lines.length > shown) System.out.println(Ansi.dim("    … (" + (lines.length - shown) + " more lines)"));
                },
                args -> "{\"command\":\"" + truncateStr(str(args, "command"), 80) + "\"}",
                "command", "Bash command to execute in the project root");
    }

    /** TODO-list and plan tools that mutate the given agent state. */
    public static void registerStateTools(ToolSupport ts, AgentState state, Predicate<String> approve) {
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

        register(ts, "update-plan", "Record or update the current plan — user must approve before execution begins",
                args -> {
                    String plan = str(args, "plan");
                    state.setPlan(plan);
                    if (!approve.test("plan: " + plan)) {
                        state.setPlan(null);
                        return "Plan rejected by user. Ask the user what to change, then call update-plan again with the revised plan.";
                    }
                    return "Plan approved.";
                },
                "plan", "Concise description of the plan");
    }

    static String truncateStr(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ");
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static String str(Map<String, Object> args, String name) {
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
