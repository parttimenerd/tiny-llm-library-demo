package me.bechberger.demo.solutions;

import me.bechberger.demo.http.HttpHelper;
import me.bechberger.demo.util.Ansi;
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
public class LLMClient implements me.bechberger.demo.util.LLMClientInterface {
    private final HttpHelper http;
    private final String model;
    private final Consumer<String> onToken;

    private me.bechberger.demo.util.LLMClientInterface.TokenUsage lastUsage;

    /** Usage of the most recent API call, or null if the server sent none (drives compaction). */
    @Override public me.bechberger.demo.util.LLMClientInterface.TokenUsage lastUsage() {
        return lastUsage;
    }

    private boolean thinking = true;
    private int thinkingBudget = -1;

    public LLMClient(String baseUrl, String model, Consumer<String> onToken) {
        this.http = new HttpHelper(baseUrl);
        this.model = model;
        this.onToken = onToken;
    }

    public LLMClient withThinking(boolean thinking) {
        this.thinking = thinking;
        return this;
    }

    public LLMClient withThinkingBudget(int tokens) {
        this.thinkingBudget = tokens;
        return this;
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

    /** Convenience factory: create a two-message conversation list [system, user]. */
    public static List<Map<String, Object>> conversation(String systemPrompt, String userMessage) {
        var msgs = new java.util.ArrayList<Map<String, Object>>();
        msgs.add(system(systemPrompt));
        msgs.add(user(userMessage));
        return msgs;
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
    @Override public String chatSimple(List<Map<String, Object>> messages) {
        boolean prev = thinking;
        thinking = false;
        try { return chat(messages); } finally { thinking = prev; }
    }

    public String chat(List<Map<String, Object>> messages) {
        try {
            // @stub: POST /v1/chat/completions → parse JSON → return choices[0].message.content
            var response = Util.asMap(JSONParser.parse(
                    http.postJson("/v1/chat/completions", buildRequest(messages, false, null))));
            lastUsage = parseTokenUsage(response);
            return (String) Util.asMap(Util.asMap(
                    Util.asList(response.get("choices")).getFirst()).get("message")).get("content");
            // @end
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
     * Response: Server-Sent Events (SSE) stream:
     * {@code data: {"choices": [{ "delta": { "content": "token" } }]}}
     * {@code data: [DONE]}
     */
    public String chatStream(List<Map<String, Object>> messages) {
        try (var reader = new BufferedReader(new InputStreamReader(
                http.postJsonStream("/v1/chat/completions", buildRequest(messages, true, null)),
                StandardCharsets.UTF_8))) {
            var result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                    // @stub: processSSELine → skip empty/null([DONE]) → call onToken, append to result
                    var token = processSSELine(line);
                    if (token == null) break;
                    if (!token.isEmpty()) {
                        onToken.accept(token);
                        result.append(token);
                    }
                    // @end
            }
            return result.toString();
        } catch (Exception e) {
            throw new RuntimeException("Streaming failed", e);
        }
    }

    /**
     * Process one SSE line.
     * <p>
     * Return: token string, "" if no content/non-data line, null if [DONE]
     */
    private String processSSELine(String line) throws Exception {
        // @stub: skip non-"data: " lines; strip prefix; return null for "[DONE]"; parse JSON → delta.content (also print dim delta.reasoning_content/thinking if present)
        if (!line.startsWith("data: ")) return "";
        String data = line.substring(6).trim();
        if (data.equals("[DONE]")) return null;
        var delta = Util.asMap(Util.asMap(
                Util.asList(Util.asMap(JSONParser.parse(data)).get("choices")).getFirst()).get("delta"));
        var thinking = (String) delta.get("reasoning_content");
        if (thinking == null) thinking = (String) delta.get("thinking");
        if (thinking != null && System.console() != null) System.err.print(Ansi.dim(thinking));
        var content = (String) delta.get("content");
        return content != null ? content : "";
        // @end
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
            lastUsage = parseTokenUsage(response);
            return Util.asMap(Util.asList(response.get("choices")).getFirst());
        } catch (Exception e) {
            throw new RuntimeException("chatRaw failed [" + http.getBaseUrl() + "/v1/chat/completions]: " + e.getMessage(), e);
        }
    }

    /** Parse the usage object of a response, tolerating missing usage entirely. */
    private static me.bechberger.demo.util.LLMClientInterface.TokenUsage parseTokenUsage(Map<String, Object> response) {
        var usage = response.get("usage");
        if (!(usage instanceof Map)) return null;
        var u = Util.asMap(usage);
        return new me.bechberger.demo.util.LLMClientInterface.TokenUsage(
                ((Number) u.get("completion_tokens")).intValue(),
                ((Number) u.get("prompt_tokens")).intValue(),
                ((Number) u.get("total_tokens")).intValue());
    }

    /**
     * Ask the server for the context window of the current model
     * (llama-server exposes meta.n_ctx_train via GET /v1/models);
     * falls back to defaultValue when the server reports nothing.
     */
    public int getContextWindowSize(int defaultValue) {
        try {
            for (var m : Util.asList(Util.asMap(JSONParser.parse(http.get("/v1/models"))).get("data"))) {
                var modelMap = Util.asMap(m);
                if (model.equals(modelMap.get("id"))) {
                    // llama-server: meta.n_ctx_train
                    if (modelMap.containsKey("meta")) {
                        var nCtx = Util.asMap(modelMap.get("meta")).get("n_ctx_train");
                        if (nCtx instanceof Number n) return n.intValue();
                    }
                    // OpenAI-compatible servers: max_input_tokens
                    if (modelMap.get("max_input_tokens") instanceof Number n) return n.intValue();
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: could not detect context window size: " + e.getMessage());
        }
        return defaultValue;
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
        addThinkingParams(req);
        if (stream) req.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            req.put("tools", tools);
            req.put("tool_choice", "auto");
        }
        return CompactPrinter.compactPrint(req);
    }

    private void addThinkingParams(LinkedHashMap<String, Object> req) {
        if (!thinking) return;
        var tkw = new LinkedHashMap<String, Object>();
        tkw.put("enable_thinking", true);
        if (thinkingBudget > 0) tkw.put("thinking_budget", thinkingBudget);
        req.put("chat_template_kwargs", tkw);
    }
}