package me.bechberger.demo.summaries;

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
 * LLM client with token usage tracking.
 * <p>
 * Extends the basic LLM client pattern with:
 * - {@link TokenUsage} record for tracking prompt/completion/total tokens
 * - {@link ChatResult} that pairs response text with usage stats
 * - {@link RawChatResult} for tool-calling flows (choice map + usage)
 * - {@code stream_options.include_usage} for streaming token counts
 * - {@link #getContextWindowSize(int)} to auto-detect context window from model metadata
 */
public class LLMClient {
    private final HttpHelper http;
    private final String model;
    private final Consumer<String> onToken;

    /** Token usage statistics from a chat completion response. */
    public record TokenUsage(int completionTokens, int promptTokens, int totalTokens) {}

    /** Chat response paired with token usage. */
    public record ChatResult(String content, TokenUsage usage) {}

    /** Raw chat response (choice map + usage) for tool-calling flows. */
    public record RawChatResult(Map<String, Object> choice, TokenUsage usage) {}

    public LLMClient(String baseUrl, String model, Consumer<String> onToken) {
        this.http = new HttpHelper(baseUrl);
        this.model = model;
        this.onToken = onToken;
    }

    // --- Message helpers ---

    public static Map<String, Object> user(String content) {
        return Map.of("role", "user", "content", content);
    }

    public static Map<String, Object> assistant(String content) {
        return Map.of("role", "assistant", "content", content);
    }

    public static Map<String, Object> system(String content) {
        return Map.of("role", "system", "content", content);
    }

    // --- Model info ---

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
     * Query the context window size from the model metadata.
     * <p>
     * Uses {@code GET /v1/models} to find {@code meta.n_ctx_train} for the current model
     * (llama-server extension). Falls back to {@code defaultValue} if not available.
     *
     * @param defaultValue fallback context window size (e.g. from ModelSize.getDefaultContextWindow())
     * @return context window size in tokens
     */
    public int getContextWindowSize(int defaultValue) {
        try {
            var json = Util.asMap(JSONParser.parse(http.get("/v1/models")));
            var models = Util.asList(json.get("data"));
            for (var m : models) {
                var modelMap = Util.asMap(m);
                if (model.equals(modelMap.get("id"))) {
                    if (modelMap.containsKey("meta")) {
                        var meta = Util.asMap(modelMap.get("meta"));
                        if (meta.containsKey("n_ctx_train")) {
                            return ((Number) meta.get("n_ctx_train")).intValue();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: could not detect context window size: " + e.getMessage());
        }
        return defaultValue;
    }

    // --- Chat methods (with token usage tracking) ---

    /**
     * Send a message and get a complete response (blocking) with token usage.
     */
    public ChatResult chat(List<Map<String, Object>> messages) {
        try {
            var response = Util.asMap(JSONParser.parse(
                    http.postJson("/v1/chat/completions", buildRequest(messages, false, null))));
            var choice = Util.asMap(Util.asList(response.get("choices")).getFirst());
            var message = Util.asMap(choice.get("message"));
            TokenUsage usage = parseTokenUsage(response);
            return new ChatResult((String) message.get("content"), usage);
        } catch (Exception e) {
            throw new RuntimeException("Chat failed", e);
        }
    }

    /**
     * Send a message and stream the response token-by-token, with token usage.
     * <p>
     * Sets {@code stream_options.include_usage: true} so the server sends a final
     * SSE chunk with usage data.
     */
    public ChatResult chatStream(List<Map<String, Object>> messages) {
        try {
            var stream = http.postJsonStream("/v1/chat/completions",
                    buildRequest(messages, true, null));
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            try (reader) {
                var result = new StringBuilder();
                TokenUsage usage = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) break;

                    var chunk = Util.asMap(JSONParser.parse(data));

                    // Capture usage from the final chunk
                    if (chunk.containsKey("usage") && chunk.get("usage") != null) {
                        usage = parseTokenUsage(chunk);
                    }

                    var choices = Util.asList(chunk.get("choices"));
                    if (choices.isEmpty()) continue;

                    var delta = Util.asMap(Util.asMap(choices.getFirst()).get("delta"));
                    if (delta.containsKey("thinking")) {
                        System.out.print(delta.get("thinking"));
                    }
                    var content = (String) delta.get("content");
                    if (content != null && !content.isEmpty()) {
                        onToken.accept(content);
                        result.append(content);
                    }
                }
                return new ChatResult(result.toString(), usage);
            }
        } catch (Exception e) {
            throw new RuntimeException("Streaming failed", e);
        }
    }

    /**
     * Send a message with tools and get the raw response (for tool-calling), with token usage.
     */
    public RawChatResult chatRaw(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        try {
            var response = Util.asMap(JSONParser.parse(
                    http.postJson("/v1/chat/completions", buildRequest(messages, false, tools))));
            var choice = Util.asMap(Util.asList(response.get("choices")).getFirst());
            TokenUsage usage = parseTokenUsage(response);
            return new RawChatResult(choice, usage);
        } catch (Exception e) {
            throw new RuntimeException("Chat with tools failed", e);
        }
    }

    // --- Internal helpers ---

    private String buildRequest(List<Map<String, Object>> messages, boolean stream,
                                List<Map<String, Object>> tools) {
        var req = new LinkedHashMap<String, Object>();
        req.put("model", model);
        req.put("messages", messages);
        if (stream) {
            req.put("stream", true);
            req.put("stream_options", Map.of("include_usage", true));
        }
        if (tools != null && !tools.isEmpty()) {
            req.put("tools", tools);
            req.put("tool_choice", "auto");
        }
        return CompactPrinter.compactPrint(req);
    }

    /** Parse token usage from a response map containing a "usage" object. */
    static TokenUsage parseTokenUsage(Map<String, Object> response) {
        if (!response.containsKey("usage") || response.get("usage") == null) {
            return null;
        }
        var usageMap = Util.asMap(response.get("usage"));
        return new TokenUsage(
                ((Number) usageMap.get("completion_tokens")).intValue(),
                ((Number) usageMap.get("prompt_tokens")).intValue(),
                ((Number) usageMap.get("total_tokens")).intValue()
        );
    }
}
