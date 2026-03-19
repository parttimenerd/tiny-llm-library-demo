package me.bechberger.demo;

import me.bechberger.demo.util.ModelSize;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.femtoschema.Schemas;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Tool-enabled chatbot with file system access.
 * <p>
 * Features:
 * - LLM can call tools (ls, cat-paged, grep, find-file) to explore files
 * - Tool loop handles: LLM requests tool → execute → send result back → LLM processes
 * - Sandboxed file access (configurable root directory)
 * <p>
 * Workflow:
 * 1. Register tools with ToolSupport (name + description + JSON schema + handler)
 * 2. REPL loop: user input → handleToolLoop → assistant response
 * 3. handleToolLoop manages all tool calls automatically
 * <p>
 * Tool registration uses Schemas.object() to build JSON Schema for parameters.
 * <p>
 * Uses femtocli for CLI argument parsing.
 */
@Command(name = "tool-chatbot", description = "A chatbot with file system tools", version = "1.0.0")
public class ToolChatBot implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, description = "Model size: fast (1.7B), medium (9B), slow (27B) (default: ${DEFAULT-VALUE})",
            defaultValue = "fast")
    ModelSize modelSize;

    @Option(names = {"-u", "--base-url"}, description = "LLM API base URL (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"-r", "--root"}, description = "Sandbox root directory (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    String root;

    /**
     * Main application flow.
     * <p>
     * Setup phase:
     * 1. Create LLMClient, ToolSupport, FileTools
     * 2. Register tools (extracted to helper methods)
     * 3. Add system message to guide LLM behavior
     * <p>
     * REPL phase:
     * 1. Read user input
     * 2. Add user message to conversation
     * 3. Call toolSupport.handleToolLoop() - manages all tool interactions
     * 4. Add assistant response to conversation
     * <p>
     * Implementation: Setup tools → add system message → loop: user input → handleToolLoop → display response
     */
    @Override
    public Integer call() {
        String model = modelSize.getModelId();

        var client = new LLMClient(baseUrl, model, System.out::print);
        var toolSupport = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));
        registerTools(toolSupport, fileTools);

        System.out.println("Connecting to " + baseUrl + "...");
        client.listModels();
        System.out.println("\nTool Chatbot ready! Model: " + modelSize.name() + " (" + modelSize.getDescription() + ")");
        System.out.println("Sandbox root: " + Path.of(root).toAbsolutePath().normalize());

        // TODO add system message and call repl
        return 0;
    }

    /**
     * Register all tools with the tool support.
     * <p>
     * Implementation: Build JSON Schema for each tool → register with handler function
     */
    private void registerTools(ToolSupport toolSupport, FileTools fileTools) {
        // ls tool
        var lsSchema = Schemas.object()
                .required("path", Schemas.string().withDescription("Directory path relative to sandbox"))
                .toJsonSchema();
        toolSupport.registerTool("ls", "List directory contents, just their names", lsSchema,
                args -> fileTools.ls((String) args.get("path")));

        // cat-paged tool
        var catSchema = Schemas.object()
                .required("path", Schemas.string().withDescription("File path relative to sandbox"))
                .required("page", Schemas.number().withDescription("Page number, 0-based"))
                .toJsonSchema();
        toolSupport.registerTool("cat-paged", "Read file contents, paged", catSchema,
                args -> fileTools.catPaged((String) args.get("path"), ((Number) args.get("page")).intValue()));

        // grep tool
        var grepSchema = Schemas.object()
                .required("query", Schemas.string().withDescription("Search query (case-insensitive)"))
                .required("path", Schemas.string().withDescription("File path relative to sandbox"))
                .toJsonSchema();
        toolSupport.registerTool("grep", "Search for text in a file", grepSchema,
                args -> fileTools.grep((String) args.get("query"), (String) args.get("path")));

        // find-file tool
        var findFileSchema = Schemas.object()
                .required("query", Schemas.string().withDescription("Search text or regex pattern"))
                .optional("useRegex", Schemas.bool().withDescription("If true, treat query as regex; if false, literal text search (default: false)"))
                .toJsonSchema();
        toolSupport.registerTool("find-file", "Find all files containing text or matching a regex pattern", findFileSchema,
                args -> {
                    String query = (String) args.get("query");
                    boolean useRegex = args.containsKey("useRegex") && (Boolean) args.get("useRegex");
                    return fileTools.findFiles(query, useRegex);
                });
        // TODO: run-command tool
    }

    /**
     * Run the REPL loop.
     * <p>
     * Implementation: Loop → read input → add user message → call handleToolLoop → add assistant message
     */
    private void runREPL(LLMClient client, ToolSupport toolSupport, ArrayList<Map<String, Object>> messages) {
        var scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\nYou: ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            messages.add(LLMClient.user(input));

            System.out.print("\nAssistant: ");
            // TODO
        }
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new ToolChatBot(), args));
    }
}