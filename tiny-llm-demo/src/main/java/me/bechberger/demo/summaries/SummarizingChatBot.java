package me.bechberger.demo.summaries;

import me.bechberger.demo.FileTools;
import me.bechberger.demo.util.ModelSize;
import me.bechberger.demo.summaries.LLMClient.ChatResult;
import me.bechberger.demo.summaries.LLMClient.TokenUsage;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.femtoschema.Schemas;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Token-aware chatbot with tool support and automatic conversation summarization.
 * <p>
 * Implements the <b>Hybrid Memory</b> strategy (agentailor taxonomy):
 * when prompt tokens exceed a threshold (80% of context window), older messages
 * are summarized via an LLM call while pinning the system prompt and keeping
 * the most recent messages verbatim.
 * <p>
 * Message structure after summarization:
 * {@code [system_prompt, system(summary), recent_msg_1, ..., recent_msg_N]}
 * <p>
 * Also supports tool calling (file tools) so it can demonstrate the most
 * realistic scenario where token budgets matter — tool call messages inflate
 * history fast.
 */
@Command(name = "summarizing-chatbot",
        description = "Token-tracked chatbot with auto-summarization and file tools",
        version = "1.0.0")
public class SummarizingChatBot implements Callable<Integer> {

    @Option(names = {"-m", "--model"},
            description = "Model size: fast (1.7B), medium (9B), slow (27B) (default: ${DEFAULT-VALUE})",
            defaultValue = "fast")
    ModelSize modelSize;

    @Option(names = {"-u", "--base-url"},
            description = "LLM API base URL (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"-r", "--root"},
            description = "Sandbox root directory for file tools (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    String root;

    @Option(names = {"--max-tokens"},
            description = "Override token threshold for summarization (default: auto = 80%% of context window)",
            defaultValue = "0")
    int maxTokensOverride;

    /** Fraction of context window that triggers summarization. */
    private static final double THRESHOLD_PERCENT = 0.8;

    /** Minimum number of recent messages to keep verbatim (2 user/assistant pairs). */
    private static final int MIN_RECENT_MESSAGES = 4;

    @Override
    public Integer call() {
        String model = modelSize.getModelId();
        var client = new LLMClient(baseUrl, model, System.out::print);

        // Detect context window
        int contextWindow = client.getContextWindowSize(modelSize.getDefaultContextWindow());
        int threshold = maxTokensOverride > 0
                ? maxTokensOverride
                : (int) (contextWindow * THRESHOLD_PERCENT);

        System.out.println("Connecting to " + baseUrl + "...");
        client.listModels();
        System.out.println("\nSummarizing Chatbot ready!");
        System.out.println("  Model:          " + modelSize.name() + " (" + modelSize.getDescription() + ")");
        System.out.println("  Context window:  " + contextWindow + " tokens");
        System.out.println("  Summarize at:    " + threshold + " prompt tokens (" + (int)(THRESHOLD_PERCENT * 100) + "%)");
        System.out.println("  Sandbox root:    " + Path.of(root).toAbsolutePath().normalize());

        // Register tools
        var toolSupport = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));
        registerTools(toolSupport, fileTools);

        // Initial messages
        var messages = new ArrayList<Map<String, Object>>();
        var systemPrompt = "You are a helpful assistant with access to filesystem tools. " +
                "Use the tools to answer questions about files and directories. " +
                "Always use the tools rather than guessing about file contents.";
        messages.add(LLMClient.system(systemPrompt));

        // REPL
        runREPL(client, toolSupport, messages, threshold);
        return 0;
    }

    private void runREPL(LLMClient client, ToolSupport toolSupport,
                         ArrayList<Map<String, Object>> messages, int threshold) {
        var scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\nYou: ");
            if (!scanner.hasNextLine()) return;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;
            if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) break;

            messages.add(LLMClient.user(input));

            System.out.print("\nAssistant: ");
            ChatResult result = toolSupport.handleToolLoop(client, messages);
            String response = result.content();
            messages.add(LLMClient.assistant(response));

            // Print the response text
            System.out.println(response);

            // Print token usage
            TokenUsage usage = result.usage();
            if (usage != null) {
                System.out.println("[tokens: prompt=" + usage.promptTokens()
                        + ", completion=" + usage.completionTokens()
                        + ", total=" + usage.totalTokens() + "]");

                // Check if summarization is needed
                if (usage.promptTokens() > threshold) {
                    summarizeOlderMessages(client, messages, usage.promptTokens());
                }
            }
        }
    }

    /**
     * Hybrid Memory summarization: pin system prompt, summarize middle, keep recent.
     * <p>
     * 3-tier split:
     * <ol>
     *   <li><b>Pinned</b>: system prompt (index 0) — never summarized</li>
     *   <li><b>Middle</b>: everything between pinned and recent — summarized via LLM</li>
     *   <li><b>Recent</b>: last {@value #MIN_RECENT_MESSAGES} messages — kept verbatim</li>
     * </ol>
     * <p>
     * Tool call messages (role "tool") and their corresponding assistant tool_calls
     * messages are included in the summary text, then dropped.
     */
    private void summarizeOlderMessages(LLMClient client,
                                        ArrayList<Map<String, Object>> messages,
                                        int previousPromptTokens) {
        // Need at least: system + something to summarize + MIN_RECENT_MESSAGES
        if (messages.size() <= 1 + MIN_RECENT_MESSAGES) {
            System.out.println("[Cannot summarize — too few messages]");
            return;
        }

        // Determine the split points
        int pinnedEnd = 1; // system prompt at index 0
        // If index 1 is already a prior summary, it will be re-summarized into the new one
        int recentStart = Math.max(pinnedEnd, messages.size() - MIN_RECENT_MESSAGES);

        // Nothing to summarize?
        if (recentStart <= pinnedEnd) {
            System.out.println("[Cannot summarize — not enough middle messages]");
            return;
        }

        // Build the conversation text to summarize
        List<Map<String, Object>> middleMessages = messages.subList(pinnedEnd, recentStart);
        var conversationText = new StringBuilder();
        for (var msg : middleMessages) {
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            if (content != null) {
                conversationText.append(role).append(": ").append(content).append("\n\n");
            } else if ("assistant".equals(role) && msg.containsKey("tool_calls")) {
                // Include tool call info in the summary input
                conversationText.append("assistant: [called tools]\n\n");
            }
        }

        System.out.println("[Summarizing " + middleMessages.size() + " older messages...]");

        // Ask the LLM to summarize
        var summaryMessages = new ArrayList<Map<String, Object>>();
        summaryMessages.add(LLMClient.system(
                "Summarize the following conversation concisely, preserving key facts, " +
                "decisions, tool results, and user preferences. " +
                "Write in third person as a summary of what was discussed."));
        summaryMessages.add(LLMClient.user(conversationText.toString()));

        ChatResult summaryResult = client.chat(summaryMessages);
        String summary = summaryResult.content();

        // Replace middle messages with a single summary system message
        var recentMessages = new ArrayList<>(messages.subList(recentStart, messages.size()));
        var pinnedMessages = new ArrayList<>(messages.subList(0, pinnedEnd));

        messages.clear();
        messages.addAll(pinnedMessages);
        messages.add(LLMClient.system("[Conversation summary] " + summary));
        messages.addAll(recentMessages);

        System.out.println("[Summarized — history reduced from " + (pinnedEnd + middleMessages.size() + recentMessages.size())
                + " to " + messages.size() + " messages, prompt tokens were " + previousPromptTokens + "]");
    }

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

        var runCmdSchema = Schemas.object()
                .required("command", Schemas.string().withDescription("Bash command to run (user must confirm via prompt)"))
                .toJsonSchema();
        toolSupport.registerTool("run-command", "Run arbitrary bash command (requires user confirmation)", runCmdSchema,
                args -> fileTools.runCommand((String) args.get("command")));
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new SummarizingChatBot(), args));
    }
}
