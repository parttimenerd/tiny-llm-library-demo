package me.bechberger.demo.solutions;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;
import me.bechberger.demo.LLMClient;
import me.bechberger.demo.util.Ansi;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Solution: Complete tool support with registration, JSON building, and tool loop.
 * <p>
 * Tools are registered directly via {@link #registerTool(String, String, Map, Function)}
 * with name, description, JSON Schema for parameters, and a handler function.
 */
public class ToolSupport implements me.bechberger.demo.util.ToolCallListener {

    record ToolDef(String name, String description, Map<String, Object> parameterSchema,
                   Function<Map<String, Object>, String> handler) {}

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    /** Called after each tool execution with (toolName, result) — e.g. to re-render agent state. */
    private BiConsumer<String, String> onToolCall = null;

    public void setOnToolCall(BiConsumer<String, String> onToolCall) {
        this.onToolCall = onToolCall;
    }

    public void registerTool(String name, String description,
                             Map<String, Object> parameterSchema,
                             Function<Map<String, Object>, String> handler) {
        tools.put(name, new ToolDef(name, description, parameterSchema, handler));
    }

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

    public String handleToolLoop(LLMClient client, List<Map<String, Object>> messages) {
        var toolsJson = buildToolsJson();
        for (int i = 0; i < 100; i++) {
            var choice = client.chatRaw(messages, toolsJson);
            if (!"tool_calls".equals(choice.get("finish_reason"))) {
                String content = (String) Util.asMap(choice.get("message")).get("content");
                if (content != null) messages.add(Map.of("role", "assistant", "content", content));
                return content;
            }
            processToolCalls(choice, messages);
        }
        return null;
    }

    private void processToolCalls(Map<String, Object> choice, List<Map<String, Object>> messages) {
        var assistantMessage = Util.asMap(choice.get("message"));
        messages.add(assistantMessage);
        var narration = (String) assistantMessage.get("content");
        if (narration != null && !narration.isBlank()) System.out.print("  " + Ansi.renderMarkdown(narration.strip()));
        for (var toolCall : Util.asList(assistantMessage.get("tool_calls"))) {
            messages.add(executeToolCall(Util.asMap(toolCall)));
        }
    }

    private Map<String, Object> executeToolCall(Map<String, Object> toolCall) {
        var toolCallId = (String) toolCall.get("id");
        var function = Util.asMap(toolCall.get("function"));
        var toolName = (String) function.get("name");
        var argumentsJson = (String) function.get("arguments");

        String result = callTool(toolName, argumentsJson);

        System.out.println(Ansi.dim("\n  ⚙ ") + Ansi.bold(toolName)
                + Ansi.dim("(" + truncateArgs(argumentsJson, 80) + ")"));
        printResult(result);

        if (onToolCall != null) onToolCall.accept(toolName, result);

        return Map.of("role", "tool", "tool_call_id", toolCallId, "content", result);
    }

    private String callTool(String toolName, String argumentsJson) {
        try {
            var arguments = Util.asMap(JSONParser.parse(argumentsJson));
            var toolDef = tools.get(toolName);
            if (toolDef == null) return "Error: unknown tool '" + toolName + "'";
            return toolDef.handler().apply(arguments);
        } catch (Exception e) {
            return "Error executing tool '" + toolName + "': " + e.getMessage()
                    + "\nPlease try again with valid arguments.";
        }
    }

    private static String truncateArgs(String json, int max) {
        if (json == null) return "";
        // strip outer braces and newlines for inline display
        String s = json.strip();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1).strip();
        s = s.replace("\n", " ").replaceAll("\\s+", " ");
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Print the tool result: up to 5 lines, then a "… N more lines" hint. */
    private static void printResult(String result) {
        if (result == null || result.isBlank()) return;
        String[] lines = result.split("\n", -1);
        int show = Math.min(lines.length, 5);
        for (int i = 0; i < show; i++) {
            String line = lines[i].length() > 120 ? lines[i].substring(0, 120) + "…" : lines[i];
            System.out.println(Ansi.gray("    → ") + Ansi.dim(line));
        }
        if (lines.length > show) {
            System.out.println(Ansi.gray("    … (" + (lines.length - show) + " more lines)"));
        }
    }
}
