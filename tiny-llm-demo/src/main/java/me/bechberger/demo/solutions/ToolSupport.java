package me.bechberger.demo.solutions;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;

import java.util.*;
import java.util.function.Function;

/**
 * Solution: Complete tool support with registration, JSON building, and tool loop.
 * <p>
 * Tools are registered directly via {@link #registerTool(String, String, Map, Function)}
 * with name, description, JSON Schema for parameters, and a handler function.
 * Implements a retry mechanism for malformed JSON from the model.
 */
public class ToolSupport {

    /** Internal record for a registered tool */
    record ToolDef(String name, String description, Map<String, Object> parameterSchema,
                   Function<Map<String, Object>, String> handler) {}

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

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
        var result = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            result.add(Map.of("type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.parameterSchema())));
        }
        return result;
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
    public String handleToolLoop(LLMClient client, List<Map<String, Object>> messages) {
        var toolsJson = buildToolsJson();
        int maxIterations = 100;

        for (int i = 0; i < maxIterations; i++) {
            var choice = client.chatRaw(messages, toolsJson);
            System.out.println(choice);
            var finishReason = (String) choice.get("finish_reason");

            if (!"tool_calls".equals(finishReason)) {
                return extractContent(choice);
            }

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
        return (String) message.get("content");
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
        for (var toolCall : toolCalls) {
            var result = executeToolCall(Util.asMap(toolCall));
            messages.add(result);
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

        Map<String, Object> toolResultMessage = Map.of(
                "role", "tool",
                "tool_call_id", toolCallId,
                "content", result
        );
        System.out.println("Call: " + toolName + ", Arguments: " + argumentsJson + ", Result: " + result);
        return toolResultMessage;
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
}