package me.bechberger.demo;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Skeleton for tool support — to be live-coded during the talk.
 * <p>
 * Manages tool registration, builds the tools JSON array for the API,
 * and implements the tool-calling loop.
 * <p>
 * Tools are registered directly via {@link #registerTool(String, String, Map, Function)}
 * with name, description, JSON Schema for parameters, and a handler function.
 */
public class ToolSupport {


    /** Internal record for a registered tool */
    record ToolDef(String name, String description, Map<String, Object> parameterSchema,
                   Function<Map<String, Object>, String> handler,
                   Consumer<String> printer,
                   Function<Map<String, Object>, String> argSummarizer,
                   BiConsumer<Map<String, Object>, String> argsPrinter) {
        ToolDef(String name, String description, Map<String, Object> parameterSchema,
                Function<Map<String, Object>, String> handler) {
            this(name, description, parameterSchema, handler, null, null, null);
        }
        ToolDef(String name, String description, Map<String, Object> parameterSchema,
                Function<Map<String, Object>, String> handler, Consumer<String> printer) {
            this(name, description, parameterSchema, handler, printer, null, null);
        }
        ToolDef(String name, String description, Map<String, Object> parameterSchema,
                Function<Map<String, Object>, String> handler, Consumer<String> printer,
                Function<Map<String, Object>, String> argSummarizer) {
            this(name, description, parameterSchema, handler, printer, argSummarizer, null);
        }
    }

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    /** Called after each tool execution with (toolName, result) — e.g. to re-render agent state. */
    private BiConsumer<String, String> onToolCall = null;

    public void setOnToolCall(BiConsumer<String, String> cb) {
        this.onToolCall = cb;
    }

    /**
     * Register a tool that the LLM can call.
     * <p>
     * @param name Tool function name (e.g. "ls", "grep")
     * @param description What the tool does (LLM uses this to decide when to use it)
     * @param parameterSchema JSON Schema defining the parameters (use Schemas.object()...toJsonSchema())
     * @param handler Function that takes parsed arguments map and returns result string
     */
    public void registerTool(String name, String description,
                             Map<String, Object> parameterSchema,
                             Function<Map<String, Object>, String> handler) {
        tools.put(name, new ToolDef(name, description, parameterSchema, handler));
    }

    /** Register a tool with a custom result printer for the REPL display. */
    public void registerTool(String name, String description,
                             Map<String, Object> parameterSchema,
                             Function<Map<String, Object>, String> handler,
                             Consumer<String> printer) {
        tools.put(name, new ToolDef(name, description, parameterSchema, handler, printer));
    }

    /** Register a tool with a custom result printer and arg summarizer for the REPL display. */
    public void registerTool(String name, String description,
                             Map<String, Object> parameterSchema,
                             Function<Map<String, Object>, String> handler,
                             Consumer<String> printer,
                             Function<Map<String, Object>, String> argSummarizer) {
        tools.put(name, new ToolDef(name, description, parameterSchema, handler, printer, argSummarizer));
    }

    /** Register a tool with an args-aware printer (receives parsed args + result). */
    public void registerTool(String name, String description,
                             Map<String, Object> parameterSchema,
                             Function<Map<String, Object>, String> handler,
                             BiConsumer<Map<String, Object>, String> argsPrinter,
                             Function<Map<String, Object>, String> argSummarizer) {
        tools.put(name, new ToolDef(name, description, parameterSchema, handler, null, argSummarizer, argsPrinter));
    }

    /**
     * Build the tools array for the OpenAI API request.
     * <p>
     * Format:
     * <pre>{@code
     * [
     *   {
     *     "type": "function",
     *     "function": {
     *       "name": "tool_name",
     *       "description": "what it does",
     *       "parameters": { ...JSON Schema... }
     *     }
     *   }
     * ]
     * }</pre>
     * <p>
     * Implementation: Map each registered tool to the OpenAI tool format
     * @return List of tool definition maps ready to send in the API request
     */
    public List<Map<String, Object>> buildToolsJson() {
        return tools.values().stream().map(tool -> Map.of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.parameterSchema()
                )
        )).toList();
    }

    /**
     * Handle tool-calling loop: send message → LLM decides to call tools → execute them → repeat.
     * <p>
     * Tool-calling flow:
     * 1. Send messages + tools to API
     * 2. Check finish_reason:
     *    - "stop" → return content (normal response)
     *    - "tool_calls" → execute tools, add results to messages, loop back
     * <p>
     * Response with tools:
     * <pre>{@code
     * {
     *   "finish_reason": "tool_calls",
     *   "message": {
     *     "tool_calls": [
     *       {
     *         "id": "call_123",
     *         "function": {
     *           "name": "ls",
     *           "arguments": "{\"path\": \".\"}"
     *         }
     *       }
     *     ]
     *   }
     * }
     * }</pre>
     * <p>
     * Tool result message format:
     * <pre>{@code
     * {
     *   "role": "tool",
     *   "tool_call_id": "call_123",
     *   "content": "result text"
     * }
     * }</pre>
     * <p>
     * Implementation: Loop → call LLM → check finish_reason → process tool calls if needed
     * @param client LLM client to use
     * @param messages Conversation history (mutated: tool calls and results are added)
     * @return Final assistant response text
     */
    public String handleToolLoop(LLMClient client, List<Map<String, Object>> messages) throws IOException {
        var toolsJson = buildToolsJson();
        int maxIterations = 100;

        for (int i = 0; i < maxIterations; i++) {
            var choice = client.chatRaw(messages, toolsJson);
            var finishReason = (String) choice.get("finish_reason");

            if (!"tool_calls".equals(finishReason)) {
                return extractContent(choice);
            }

            // Print any narration text the model sent alongside tool calls (e.g. viking skill)
            var content = extractContent(choice);
            if (content != null && !content.isBlank()) System.out.println(content);

            processToolCalls(choice, messages);
        }

        return "[Tool loop exceeded maximum iterations]";
    }

    /**
     * Extract content from a normal (non-tool) response.
     * <p>
     * Format:
     * <pre>{@code
     * {
     *   "message": {
     *     "content": "text response"
     *   }
     * }
     * }</pre>
     * <p>
     * Implementation: Extract choice.message.content
     */
    private String extractContent(Map<String, Object> choice) {
        var message = Util.asMap(choice.get("message"));
        Object content = message.get("content");
        return content != null ? String.valueOf(content) : "";
    }

    /**
     * Process tool calls from LLM response and add results to messages.
     * <p>
     * Flow: Extract tool_calls → execute each → add result messages
     * <p>
     * Implementation: Get assistant message → extract tool_calls list → execute each → add results
     */
    private void processToolCalls(Map<String, Object> choice, List<Map<String, Object>> messages) {
        var assistantMessage = Util.asMap(choice.get("message"));
        messages.add(assistantMessage);

        var toolCalls = Util.asList(assistantMessage.get("tool_calls"));
        for (var toolCallObj : toolCalls) {
            var toolCall = Util.asMap(toolCallObj);
            var resultMessage = executeToolCall(toolCall);
            messages.add(resultMessage);
        }
    }

    /**
     * Execute a single tool call and build the result message.
     * <p>
     * Tool call format:
     * <pre>{@code
     * {
     *   "id": "call_123",
     *   "function": {
     *     "name": "ls",
     *     "arguments": "{\"path\": \".\"}"
     *   }
     * }
     * }</pre>
     * <p>
     * Result format:
     * <pre>{@code
     * {
     *   "role": "tool",
     *   "tool_call_id": "call_123",
     *   "content": "file1.txt\nfile2.txt"
     * }
     * }</pre>
     * <p>
     * Implementation: Extract id and function → parse arguments → call handler → build result message
     */
    private Map<String, Object> executeToolCall(Map<String, Object> toolCall) {
        var toolCallId = (String) toolCall.get("id");
        var function = Util.asMap(toolCall.get("function"));
        var toolName = (String) function.get("name");
        var argumentsJson = (String) function.get("arguments");

        String result = callTool(toolName, argumentsJson);
        var def = tools.get(toolName);

        String argDisplay = truncate(argumentsJson, 120);
        if (def != null && def.argSummarizer() != null) {
            try { argDisplay = def.argSummarizer().apply(Util.asMap(JSONParser.parse(argumentsJson))); }
            catch (Exception ignored) {}
        }
        System.out.println("\n  ⚙ " + toolName + "(" + argDisplay + ")");
        if (def != null && def.argsPrinter() != null) {
            try { def.argsPrinter().accept(Util.asMap(JSONParser.parse(argumentsJson)), result); }
            catch (Exception ignored) { printResult(result); }
        } else if (def != null && def.printer() != null) {
            def.printer().accept(result);
        } else {
            printResult(result);
        }

        if (onToolCall != null) onToolCall.accept(toolName, result);

        return Map.of("role", "tool", "tool_call_id", toolCallId, "content", result);
    }

    /**
     * Call a registered tool with JSON arguments.
     * <p>
     * Implementation: Parse arguments JSON → lookup tool → call handler (or return error)
     */
    private String callTool(String toolName, String argumentsJson) {
        try {
            var arguments = Util.asMap(JSONParser.parse(argumentsJson));
            var toolDef = tools.get(toolName);
            if (toolDef == null) {
                return "Error: unknown tool '" + toolName + "'";
            }
            return toolDef.handler().apply(arguments);
        } catch (Exception e) {
            return "Error executing tool '" + toolName + "': " + e.getMessage()
                   + "\nPlease try again with valid arguments.";
        }
    }

    private static void printResult(String result) {
        if (result == null) return;
        String[] lines = result.split("\n", -1);
        if (lines.length <= 1) {
            System.out.println("    → " + truncate(result, 200));
        } else {
            int shown = Math.min(lines.length, 30);
            for (int i = 0; i < shown; i++) System.out.println("    " + truncate(lines[i], 120));
            if (lines.length > shown) System.out.println("    … (" + (lines.length - shown) + " more lines)");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ");
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}