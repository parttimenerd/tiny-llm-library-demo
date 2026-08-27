#!/usr/bin/env bash
# Restore the live-coding gaps for the talk.
#
#  - LLMClient.java   → chat(), chatStream(), processSSELine() stubbed (Part 3)
#  - ToolSupport.java → method bodies blanked to TODO stubs (Part 5)
#  - ChatBot, ToolChatBot, CodingAgent → regenerated from solutions via sync-demo.py
#
# Undo: git checkout -- src/main/java/me/bechberger/demo/
set -euo pipefail
cd "$(dirname "$0")/.."

# Regenerate demo stubs from solutions (ChatBot, ToolChatBot, CodingAgent, LLMClient, Options)
python3 scripts/sync-demo.py generate

# Regenerate the print cheat sheet
python3 scripts/sync-demo.py cheatsheet

# ToolSupport is not managed by sync-demo (too different from solutions) — restore manually
DEST=src/main/java/me/bechberger/demo/ToolSupport.java
cat > "$DEST" <<'JAVA'
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
     *
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
        // TODO: for each tool: Map.of("type","function", "function", Map.of("name",…,"description",…,"parameters",…))
        throw new UnsupportedOperationException("TODO: live code");
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
        // TODO: loop up to 100 times: chatRaw → if finish_reason=="stop" return extractContent, else processToolCalls and continue
        throw new UnsupportedOperationException("TODO: live code");
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
        // TODO: live code
        throw new UnsupportedOperationException("TODO: live code");
    }

    /**
     * Process tool calls from LLM response and add results to messages.
     * <p>
     * Flow: Extract tool_calls → execute each → add result messages
     * <p>
     * Implementation: Get assistant message → extract tool_calls list → execute each → add results
     */
    private void processToolCalls(Map<String, Object> choice, List<Map<String, Object>> messages) {
        // TODO: add assistant message first, then for each tool_call: executeToolCall and add result
        throw new UnsupportedOperationException("TODO: live code");
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
        // TODO: live code
        throw new UnsupportedOperationException("TODO: live code");
    }

    /**
     * Call a registered tool with JSON arguments.
     * <p>
     * Implementation: Parse arguments JSON → lookup tool → call handler (or return error)
     */
    private String callTool(String toolName, String argumentsJson) {
        // TODO: live code
        throw new UnsupportedOperationException("TODO: live code");
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
JAVA

echo "Restored ToolSupport skeleton (TODO stubs for buildToolsJson, handleToolLoop, extractContent, processToolCalls, executeToolCall, callTool)."
echo "Undo: git checkout -- src/main/java/me/bechberger/demo/ToolSupport.java"
