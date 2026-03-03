package me.bechberger.demo;

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

    @Option(names = {"-m", "--model"}, description = "Model name (default: ${DEFAULT-VALUE})",
            defaultValue = "Qwen/Qwen3-1.7B-GGUF:Q8_0")
    String model;

    @Option(names = {"-u", "--base-url"}, description = "LLM API base URL (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"-r", "--root"}, description = "Sandbox root directory (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    String root;

    /**
     * Main application flow - PROVIDED (no need to live code this boilerplate).
     * <p>
     * Setup phase:
    * 1. Create LLMClient, ToolSupport, FileTools
     * 2. Register tools (YOU IMPLEMENT: registerTools method)
     * 3. Add system message to guide LLM behavior
     * <p>
     * REPL phase (YOU IMPLEMENT: runREPL method):
     * 1. Read user input
     * 2. Add user message to conversation
     * 3. Call toolSupport.handleToolLoop() - manages all tool interactions
     * 4. Add assistant response to conversation
     */
    @Override
    public Integer call() {
        var client = new LLMClient(baseUrl, model, System.out::print);
        var toolSupport = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));

        registerTools(toolSupport, fileTools);

        System.out.println("Connecting to " + baseUrl + "...");
        client.listModels();
        System.out.println("\nTool Chatbot ready! Model: " + model);
        System.out.println("Sandbox root: " + Path.of(root).toAbsolutePath().normalize());
        System.out.println("Available tools: ls, cat-paged, grep, find-file, run-command");
        System.out.println("Type 'quit' to exit.\n");

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(
                "You are a helpful assistant with access to filesystem tools. " +
                "Use the tools to answer questions about files and directories. " +
                "Always use the tools rather than guessing about file contents."));

        runREPL(client, toolSupport, messages);
        return 0;
    }

        /**
         * Register all tools with the tool support.
         */
        private void registerTools(ToolSupport toolSupport, FileTools fileTools) {
        var lsSchema = Schemas.object()
            .required("path", Schemas.string().withDescription("Directory path relative to sandbox"))
            .toJsonSchema();
        toolSupport.registerTool("ls", "List directory contents, just their names", lsSchema,
            args -> fileTools.ls((String) args.get("path")));

        var catSchema = Schemas.object()
            .required("path", Schemas.string().withDescription("File path relative to sandbox"))
            .required("page", Schemas.number().withDescription("Page number, 0-based"))
            .toJsonSchema();
        toolSupport.registerTool("cat-paged", "Read file contents, paged", catSchema,
            args -> fileTools.catPaged((String) args.get("path"), ((Number) args.get("page")).intValue()));

        var grepSchema = Schemas.object()
            .required("query", Schemas.string().withDescription("Search query (case-insensitive)"))
            .required("path", Schemas.string().withDescription("File path relative to sandbox"))
            .toJsonSchema();
        toolSupport.registerTool("grep", "Search for text in a file", grepSchema,
            args -> fileTools.grep((String) args.get("query"), (String) args.get("path")));

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

        // run-command tool
        var runCmdSchema = Schemas.object()
                .required("command", Schemas.string().withDescription("Bash command to run (user must confirm via prompt)"))
                .toJsonSchema();
        toolSupport.registerTool("run-command", "Run arbitrary bash command (requires user confirmation)", runCmdSchema,
                args -> fileTools.runCommand((String) args.get("command")));
    }

    /**
     * Run the REPL loop.
     */
    private void runREPL(LLMClient client, ToolSupport toolSupport, ArrayList<Map<String, Object>> messages) {
        var scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\nYou: ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;
            if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
                break;
            }

            messages.add(LLMClient.user(input));

            System.out.print("\nAssistant: ");
            String response = toolSupport.handleToolLoop(client, messages);
            messages.add(LLMClient.assistant(response));

            System.out.println(response);
        }
        scanner.close();
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new ToolChatBot(), args));
    }
}