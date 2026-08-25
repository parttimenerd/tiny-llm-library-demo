package me.bechberger.demo.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper around {@link java.net.http.HttpClient} for OpenAI-compatible endpoints.
 * <p>
 * API keys are loaded automatically from {@link Config} based on the base URL —
 * no key needs to be passed by callers.
 * <p>
 * Returns raw strings/streams so the caller handles parsing — keeps the helper boring
 * and the interesting logic in the live-coded code.
 */
public class HttpHelper {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient client;

    /** Pairs the request body (what we sent) with the response body stream (what we get back). */
    public record StreamExchange(String requestBody, InputStream responseStream) {}

    /**
     * Accepts URLs of the form {@code http://host/path} or {@code http://host/path#token}.
     * The fragment is stripped from the URL and used as the Bearer token.
     * <p>
     * Without a fragment, the API key is looked up in the {@link Config} file
     * ({@code ~/.config/tiny-llm-library/config.config}) by base URL. If a key is
     * configured, it is sent as {@code Authorization: Bearer <key>} on every request;
     * if there is no key for the URL, requests go out unauthenticated — fine for
     * local servers like llama.cpp.
     */
    public HttpHelper(String baseUrl) {
        this(baseUrl, Config.load());
    }

    /** Visible for testing — injects the config instead of loading it from disk. */
    HttpHelper(String baseUrl, Config config) {
        String key = null;
        int hash = baseUrl.indexOf('#');
        if (hash >= 0) {
            key = baseUrl.substring(hash + 1);
            baseUrl = baseUrl.substring(0, hash);
        }
        if (key == null || key.isBlank()) {
            key = config.apiKeyFor(baseUrl);
        }
        this.apiKey = key;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newHttpClient();
    }

    private HttpRequest.Builder baseRequest(String path) {
        var builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    /**
     * GET request — for listing models.
     * <p>
     * Used by: {@code GET /v1/models}
     *
     * @param path e.g. "/v1/models"
     * @return response body as a string
     * @throws IOException if status != 200 or network error
     */
    public String get(String path) throws IOException, InterruptedException {
        var request = baseRequest(path).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("GET " + path + " returned " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /**
     * POST JSON — for non-streaming chat completions.
     * <p>
     * Used by: {@code POST /v1/chat/completions} without streaming
     *
     * @param path e.g. "/v1/chat/completions"
     * @param jsonBody the request body as a JSON string
     * @return response body as a string
     * @throws IOException if status != 200 or network error
     */
    public String postJson(String path, String jsonBody) throws IOException, InterruptedException {
        var request = baseRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("POST " + path + " returned " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /**
     * POST with SSE streaming — returns raw InputStream for the caller to consume.
     * <p>
     * Used by: {@code POST /v1/chat/completions} with {@code "stream": true}
     * <p>
     * Response format (Server-Sent Events):
     * {@code data: {"choices": [{"delta": {"content": "token"}}]}}
     * {@code data: [DONE]}
     *
     * @param path e.g. "/v1/chat/completions"
     * @param jsonBody the request body as a JSON string (should include "stream": true)
     * @return raw InputStream of SSE data lines (caller must close)
     * @throws IOException if status != 200 or network error
     */
    public InputStream postJsonStream(String path, String jsonBody) throws IOException, InterruptedException {
        var request = baseRequest(path)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("POST " + path + " returned " + response.statusCode() + ": " + errorBody);
        }
        return response.body();
    }

    /**
     * POST with SSE streaming — returns both the sent request body and the response stream.
     *
     * @param path e.g. "/v1/chat/completions"
     * @param jsonBody the request body as a JSON string (should include "stream": true)
     * @return a {@link StreamExchange} with the request body and response InputStream
     */
    public StreamExchange postJsonStreamExchange(String path, String jsonBody)
            throws IOException, InterruptedException {
        return new StreamExchange(jsonBody, postJsonStream(path, jsonBody));
    }
}
