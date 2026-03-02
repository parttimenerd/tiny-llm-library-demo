package me.bechberger.demo;

import java.util.List;
import java.util.Map;
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

    // TODO: implement tool registration
    //   Store in a Map<String, ToolDef> using a record:
    //     record ToolDef(String name, String description,
    //                    Map<String, Object> parameterSchema,
    //                    Function<Map<String, Object>, String> handler) {}

    /**
     * Register a tool that the LLM can call.
     * <p>
     * Implementation: Store tool definition in map keyed by name
     * @param name Tool function name (e.g. "ls", "grep")
     * @param description What the tool does (LLM uses this to decide when to use it)
     * @param parameterSchema JSON Schema defining the parameters (use Schemas.object()...toJsonSchema())
     * @param handler Function that takes parsed arguments map and returns result string
     */
    public void registerTool(String name, String description,
                             Map<String, Object> parameterSchema,
                             Function<Map<String, Object>, String> handler) {
        throw new UnsupportedOperationException("TODO: implement registerTool()");
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
        throw new UnsupportedOperationException("TODO: implement buildToolsJson()");
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
        throw new UnsupportedOperationException("TODO: implement handleToolLoop()");
    }
}
