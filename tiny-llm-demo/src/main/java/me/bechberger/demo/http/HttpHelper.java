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
 * Returns raw strings/streams so the caller handles parsing — keeps the helper boring
 * and the interesting logic in the live-coded code.
 */
public class HttpHelper {

    private final String baseUrl;
    private final HttpClient client;

    /** Pairs the request body (what we sent) with the response body stream (what we get back). */
    public record StreamExchange(String requestBody, InputStream responseStream) {}

    public HttpHelper(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newHttpClient();
    }

    /**
     * GET request — for listing models.
     * <p>
     * Used by: {@code GET /v1/models}
     * <p>
     * Implementation: Build GET request → send → check status 200 → return body string
     *
     * @param path e.g. "/v1/models"
     * @return response body as a string
     * @throws IOException if status != 200 or network error
     */
    public String get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .build();
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
     * <p>
     * Headers: {@code Content-Type: application/json}
     * <p>
     * Implementation: Build POST request with JSON body → send → check status 200 → return body string
     *
     * @param path e.g. "/v1/chat/completions"
     * @param jsonBody the request body as a JSON string
     * @return response body as a string
     * @throws IOException if status != 200 or network error
     */
    public String postJson(String path, String jsonBody) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
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
     * Headers:
     * - {@code Content-Type: application/json}
     * - {@code Accept: text/event-stream}
     * <p>
     * Response format (Server-Sent Events):
     * {@code data: {"choices": [{"delta": {"content": "token"}}]}}
     * {@code data: [DONE]}
     * <p>
     * Implementation: Build POST with SSE headers → send → return InputStream for streaming read
     *
     * @param path e.g. "/v1/chat/completions"
     * @param jsonBody the request body as a JSON string (should include "stream": true)
     * @return raw InputStream of SSE data lines (caller must close)
     * @throws IOException if status != 200 or network error
     */
    public InputStream postJsonStream(String path, String jsonBody) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            // Read the error body for diagnostics
            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("POST " + path + " returned " + response.statusCode() + ": " + errorBody);
        }
        return response.body();
    }

    /**
     * POST with SSE streaming — returns both the sent request body and the response stream.
     * <p>
     * Useful for tool-calling conversations where you need to correlate what was sent
     * with what comes back, or keep the exchange around for follow-up logic.
     *
     * @param path e.g. "/v1/chat/completions"
     * @param jsonBody the request body as a JSON string (should include "stream": true)
     * @return a {@link StreamExchange} with the request body and response InputStream
     */
    public StreamExchange postJsonStreamExchange(String path, String jsonBody)
            throws IOException, InterruptedException {
        var response = postJsonStream(path, jsonBody);
        return new StreamExchange(jsonBody, response);
    }
}
