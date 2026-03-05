package me.bechberger.demo.summaries;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;

import java.util.*;
import java.util.function.Function;

/**
 * Tool support with token usage tracking.
 * <p>
 * Same tool registration and execution as the base ToolSupport, but
 * {@link #handleToolLoop} returns {@link LLMClient.ChatResult} so callers
 * get token usage from the final LLM response.
 */
public class ToolSupport {

    record ToolDef(String name, String description, Map<String, Object> parameterSchema,
                   Function<Map<String, Object>, String> handler) {}

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    public void registerTool(String name, String description,
                             Map<String, Object> parameterSchema,
                             Function<Map<String, Object>, String> handler) {
        tools.put(name, new ToolDef(name, description, parameterSchema, handler));
    }

    public List<Map<String, Object>> buildToolsJson() {
        var result = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            var fn = new LinkedHashMap<String, Object>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.put("parameters", tool.parameterSchema());

            var toolObj = new LinkedHashMap<String, Object>();
            toolObj.put("type", "function");
            toolObj.put("function", fn);
            result.add(toolObj);
        }
        return result;
    }

    /**
     * Handle tool-calling loop, returning the final response with token usage.
     * <p>
     * Optional summarization callback: if provided and prompt tokens exceed threshold,
     * the callback is invoked during the loop to allow the caller to summarize the
     * conversation history before the next API call.
     *
     * @param client              LLM client (summaries version with token tracking)
     * @param messages            Conversation history (mutated: tool calls and results are added)
     * @param tokenThreshold      If > 0, trigger summarization when prompt tokens exceed this value
     * @param summarizationAction Callback invoked with (messages, currentPromptTokens) when threshold is exceeded
     * @return Final assistant response text paired with token usage from the last API call
     */
    public LLMClient.ChatResult handleToolLoop(LLMClient client, List<Map<String, Object>> messages,
                                                int tokenThreshold,
                                                java.util.function.BiConsumer<List<Map<String, Object>>, Integer> summarizationAction) {
        var toolsJson = buildToolsJson();
        int maxIterations = 100;

        for (int i = 0; i < maxIterations; i++) {
            var rawResult = client.chatRaw(messages, toolsJson);
            var choice = rawResult.choice();
            var finishReason = (String) choice.get("finish_reason");

            // Check if we need to summarize during the tool loop
            if (tokenThreshold > 0 && summarizationAction != null && rawResult.usage() != null) {
                int promptTokens = rawResult.usage().promptTokens();
                if (promptTokens > tokenThreshold) {
                    System.out.println("  [tool loop] Prompt tokens (" + promptTokens + ") exceeded threshold (" + tokenThreshold + "), triggering summarization");
                    summarizationAction.accept(messages, promptTokens);
                }
            }

            if (!"tool_calls".equals(finishReason)) {
                var message = Util.asMap(choice.get("message"));
                return new LLMClient.ChatResult((String) message.get("content"), rawResult.usage());
            }

            // Process tool calls: add assistant message with tool_calls, execute, add results
            var assistantMessage = Util.asMap(choice.get("message"));
            messages.add(assistantMessage);

            var toolCalls = Util.asList(assistantMessage.get("tool_calls"));
            for (var toolCall : toolCalls) {
                var result = executeToolCall(Util.asMap(toolCall));
                messages.add(result);
            }
        }

        return new LLMClient.ChatResult("[Tool loop exceeded maximum iterations]", null);
    }

    /**
     * Convenience overload: handle tool loop without summarization callback.
     */
    public LLMClient.ChatResult handleToolLoop(LLMClient client, List<Map<String, Object>> messages) {
        return handleToolLoop(client, messages, 0, null);
    }

    private Map<String, Object> executeToolCall(Map<String, Object> toolCall) {
        var toolCallId = (String) toolCall.get("id");
        var function = Util.asMap(toolCall.get("function"));
        var toolName = (String) function.get("name");
        var argumentsJson = (String) function.get("arguments");

        String result;
        try {
            var arguments = Util.asMap(JSONParser.parse(argumentsJson));
            var toolDef = tools.get(toolName);
            if (toolDef == null) {
                result = "Error: unknown tool '" + toolName + "'";
            } else {
                result = toolDef.handler().apply(arguments);
            }
        } catch (Exception e) {
            result = "Error executing tool '" + toolName + "': " + e.getMessage()
                    + "\nPlease try again with valid arguments.";
        }

        System.out.println("  [tool] " + toolName + "(" + argumentsJson + ") → "
                + (result.length() > 120 ? result.substring(0, 120) + "..." : result));

        var toolResultMessage = new LinkedHashMap<String, Object>();
        toolResultMessage.put("role", "tool");
        toolResultMessage.put("tool_call_id", toolCallId);
        toolResultMessage.put("content", result);
        return toolResultMessage;
    }
}