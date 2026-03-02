package me.bechberger.demo;

import me.bechberger.demo.http.HttpHelper;

import java.util.List;
import java.util.Map;
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
        // TODO: implement listModels()
        //   GET /v1/models, parse JSON, print model IDs
        throw new UnsupportedOperationException("TODO: implement listModels()");
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
        // TODO: implement chat(messages)
        //   POST /v1/chat/completions with model + messages
        //   Parse response, return choices[0].message.content
        throw new UnsupportedOperationException("TODO: implement chat()");
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
        // TODO: implement chatStream(messages)
        //   POST /v1/chat/completions with stream:true
        //   Read SSE lines, extract delta.content, call onToken handler
        //   Return the full assembled response
        throw new UnsupportedOperationException("TODO: implement chatStream()");
    }
}
