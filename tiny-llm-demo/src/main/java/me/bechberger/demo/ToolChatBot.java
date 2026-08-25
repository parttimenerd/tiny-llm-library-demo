package me.bechberger.demo;

import me.bechberger.demo.http.Config;
import me.bechberger.demo.util.ModelSize;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.femtoschema.Schemas;

import java.io.IOException;
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

    @Option(names = {"-m", "--model"}, description = "Model size: fast (1.7B), medium (9B), slow (27B), gpt4o_mini, gpt4o, kimi_k3 (default: the endpoint's model from the config file, else fast)")
    ModelSize modelSize;

    @Option(names = {"-u", "--base-url"}, description = "LLM API base URL (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"-r", "--root"}, description = "Sandbox root directory (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    String root;

    /** Pre-written system prompt - the prompt text itself is not what this demo is about. */
    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant with access to sandboxed file tools. " +
            "Use ls, cat-paged, grep and find-file to explore the sandbox, " +
            "answer from what you find, and keep replies short.";

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
    public Integer call() throws IOException, InterruptedException {
        String model = modelSize != null ? modelSize.getModelId()
                : Config.load().modelFor(baseUrl, ModelSize.FAST.getModelId());
        var client = new LLMClient(baseUrl, model, System.out::print);
        var toolSupport = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));
        registerTools(toolSupport, fileTools);

        System.out.println("Connecting to " + baseUrl + "...");
        client.listModels();
        System.out.println("\nTool Chatbot ready! Model: " + model);
        System.out.println("Sandbox root: " + Path.of(root).toAbsolutePath().normalize());

        // TODO live on stage: the conversation - one system message, then the loop
        //   var messages = new ArrayList<Map<String, Object>>();
        //   messages.add(LLMClient.system(SYSTEM_PROMPT));
        //   runREPL(client, toolSupport, messages);
        return 0;
    }

    /**
     * Register all tools with the tool support.
     * <p>
     * Implementation: Build JSON Schema for each tool → register with handler function
     */
    private void registerTools(ToolSupport toolSupport, FileTools fileTools) {
        // the first one verbose, so the schema plumbing is visible...
        var lsSchema = Schemas.object()
                .required("path", Schemas.string().withDescription("Directory path relative to sandbox"))
                .toJsonSchema();
        toolSupport.registerTool("ls", "List directory contents, just their names", lsSchema,
                args -> fileTools.ls((String) args.get("path")));

        // ...then the same pattern, one line per tool (boring parts extracted into CodingTools.register)
        CodingTools.register(toolSupport, "cat-paged", "Read file contents, paged (4KB per page, 0-based)",
                args -> fileTools.catPaged((String) args.get("path"), ((Number) args.get("page")).intValue()),
                "path", "File path relative to sandbox", "page", "Page number, 0-based");
        CodingTools.register(toolSupport, "grep", "Search for text in a file or directory",
                args -> fileTools.grep((String) args.get("query"), (String) args.get("path")),
                "query", "Search query (case-insensitive)", "path", "File or directory relative to sandbox");
        CodingTools.register(toolSupport, "find-file", "Find all files containing the given text",
                args -> fileTools.findFiles((String) args.get("query")),
                "query", "Text to search for (literal, case-insensitive)");

        // TODO live on stage: the run-command tool - shell access with the human in the loop
        //   CodingTools.register(toolSupport, "run-command", "Run a bash command (asks the user first)",
        //           args -> fileTools.runCommand((String) args.get("command")),
        //           "command", "Bash command to run after user confirmation");
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
            // TODO live on stage: the tool loop + record the reply
            //   String response = toolSupport.handleToolLoop(client, messages);
            //   System.out.println(response);
            //   messages.add(LLMClient.assistant(response));
        }
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new ToolChatBot(), args));
    }
}