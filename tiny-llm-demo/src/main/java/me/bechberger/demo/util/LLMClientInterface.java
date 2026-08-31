package me.bechberger.demo.util;

import java.util.List;
import java.util.Map;

/** Minimal interface for LLM clients used by Compactor and Repl. */
public interface LLMClientInterface {
    record TokenUsage(int completionTokens, int promptTokens, int totalTokens) {}

    String chat(List<Map<String, Object>> messages);
    TokenUsage lastUsage();

    /** Chat without extended thinking — used for summarization where thinking wastes tokens. */
    default String chatSimple(List<Map<String, Object>> messages) { return chat(messages); }

    static Map<String, Object> system(String content) { return Map.of("role", "system", "content", content); }
    static Map<String, Object> user(String content)   { return Map.of("role", "user",   "content", content); }
    static Map<String, Object> assistant(String content) { return Map.of("role", "assistant", "content", content); }
}
