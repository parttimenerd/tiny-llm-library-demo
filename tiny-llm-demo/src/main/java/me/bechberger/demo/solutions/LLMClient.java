package me.bechberger.demo.solutions;

import me.bechberger.demo.http.HttpHelper;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.CompactPrinter;
import me.bechberger.util.json.Util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Simple LLM client for live coding.
 * Streaming is the default mode. Each token arrives via the onToken callback.
 */
public class LLMClient {
    private final HttpHelper http;
    private final String model;
    private final Consumer<String> onToken;

    public LLMClient(String baseUrl, String model, Consumer<String> onToken) {
        this.http = new HttpHelper(baseUrl);
        this.model = model;
        this.onToken = onToken;
    }

    /**
     * Build message objects for use in chat requests.
     * Messages follow the OpenAI API format: {"role": "role", "content": "text"}
     */
    public static Map<String, Object> user(String content) {
        return Map.of("role", "user", "content", content);
    }

    /**
     * Build an assistant message for conversation history.
     */
    public static Map<String, Object> assistant(String content) {
        return Map.of("role", "assistant", "content", content);
    }

    /**
     * Build a system message to set context/behavior for the LLM.
     */
    public static Map<String, Object> system(String content) {
        return Map.of("role", "system", "content", content);
    }

    /**
     * List available models.
     * <p>
     * API: {@code GET /v1/models}
     * <p>
     * Response: {@code { "data": [{ "id": "model-1" }, { "id": "model-2" }, ...] }}
     * <p>
     * Implementation: Parse JSON → extract "data" list → print each model's "id"
     */
    public void listModels() {
        try {
            var json = Util.asMap(JSONParser.parse(http.get("/v1/models")));
            var models = Util.asList(json.get("data"));
            models.forEach(m -> System.out.println("  - " + Util.asMap(m).get("id")));
        } catch (Exception e) {
            System.err.println("Error listing models: " + e.getMessage());
        }
    }

    /**
     * Send a message and get a complete response (blocking).
     * <p>
     * API: {@code POST /v1/chat/completions}
     * <p>
     * Request: {@code { "model": "...", "messages": [{"role": "user", "content": "..."}] }}
     * <p>
     * Response: {@code { "choices": [{ "message": { "content": "response text" } }] }}
     * <p>
     * Implementation: POST request → parse response → extract choices[0].message.content
     * @param messages List of message maps with "role" and "content" keys
     * @return The assistant's response text
     */
    public String chat(List<Map<String, Object>> messages) {
        try {
            var response = Util.asMap(JSONParser.parse(http.postJson("/v1/chat/completions", buildRequest(messages, false, null))));
            var choice = Util.asMap(Util.asList(response.get("choices")).getFirst());
            var message = Util.asMap(choice.get("message"));
            return (String) message.get("content");
        } catch (Exception e) {
            throw new RuntimeException("Chat failed", e);
        }
    }

    /**
     * Send a message and stream the response token-by-token.
     * <p>
     * API: {@code POST /v1/chat/completions} with {@code "stream": true}
     * <p>
     * Request: {@code { "model": "...", "messages": [...], "stream": true }}
     * <p>
     * Response: Server-Sent Events (SSE) stream with lines like:
     * {@code data: {"choices": [{ "delta": { "content": "token" } }]}}
     * {@code data: [DONE]}
     * <p>
     * Implementation: Open stream → wrap in reader → process each SSE line → extract tokens → call onToken callback
     * @param messages List of message maps
     * @return Complete response text accumulated from all tokens
     */
    public String chatStream(List<Map<String, Object>> messages) {
        try {
            var stream = http.postJsonStream("/v1/chat/completions", buildRequest(messages, true, null));
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            try (reader) {
                var result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    var token = processSSELine(line);
                    if (token == null) break; // [DONE] signal
                    if (!token.isEmpty()) {
                        onToken.accept(token);
                        result.append(token);
                    }
                }
                return result.toString();
            }
        } catch (Exception e) {
            throw new RuntimeException("Streaming failed", e);
        }
    }

    /**
     * Process one SSE line and extract token content.
     * <p>
     * Format: {@code data: {"choices": [{ "delta": { "content": "token" } }]}}
     * <p>
     * Implementation: Strip "data: " prefix → parse JSON → extract delta.content
     * @return Token string, empty string if no content, or null if [DONE]
     */
    private String processSSELine(String line) throws Exception {
        if (!line.startsWith("data: ")) return "";
        
        String data = line.substring(6).trim();
        if (data.equals("[DONE]")) return null;
        
        var chunk = Util.asMap(JSONParser.parse(data));
        var choices = Util.asList(chunk.get("choices"));
        if (choices.isEmpty()) return "";
        
        var delta = Util.asMap(Util.asMap(choices.getFirst()).get("delta"));
        var content = (String) delta.get("content");
        if (delta.containsKey("thinking")) {
            System.out.print(delta.get("thinking"));
        }
        return content != null ? content : "";
    }

    /**
     * Send a message with tools and get the raw response (for tool-calling).
     * <p>
     * API: {@code POST /v1/chat/completions} with tools parameter
     * <p>
     * Request: {@code { "model": "...", "messages": [...], "tools": [{"type": "function", "function": {...}}] }}
     * <p>
     * Response: {@code { "choices": [{ "finish_reason": "tool_calls", "message": { "tool_calls": [...] } }] }}
     * or {@code { "finish_reason": "stop", "message": { "content": "text" } }}
     * <p>
     * Implementation: POST with tools → parse response → extract and return choices[0]
     * @param messages List of message maps
     * @param tools List of tool definitions in OpenAI format
     * @return The complete first choice object (check finish_reason and message structure)
     */
    public Map<String, Object> chatRaw(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        try {
            var response = Util.asMap(JSONParser.parse(http.postJson("/v1/chat/completions", buildRequest(messages, false, tools))));
            return Util.asMap(Util.asList(response.get("choices")).getFirst());
        } catch (Exception e) {
            throw new RuntimeException("Chat with tools failed", e);
        }
    }

    /**
     * Build JSON request body for chat API.
     * <p>
     * Format: {@code { "model": "...", "messages": [...], "stream": true, "tools": [...], "tool_choice": "auto" }}
     * <p>
     * Implementation: Build map with required fields → add optional fields → serialize to JSON string
     */
    private String buildRequest(List<Map<String, Object>> messages, boolean stream, List<Map<String, Object>> tools) {
        var req = new LinkedHashMap<String, Object>();
        req.put("model", model);
        req.put("messages", messages);
        if (stream) req.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            req.put("tools", tools);
            req.put("tool_choice", "auto");
        }
        return CompactPrinter.compactPrint(req);
    }
}