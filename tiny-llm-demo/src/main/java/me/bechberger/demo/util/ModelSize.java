package me.bechberger.demo.util;

/**
 * Model size options for the chatbot.
 * <p>
 * Each size maps to a specific model identifier:
 * - FAST: 1.7B model, suitable for quick responses
 * - MEDIUM: 9B model, balanced quality and speed
 * - SLOW: 27B model, highest quality but slower
 */
public enum ModelSize {
    FAST      ("Qwen/Qwen3-1.7B-GGUF:Q8_0",      "1.7B local (fastest)",        40960),
    MEDIUM    ("AaryanK/Qwen3.5-9B-GGUF:Q8_0",    "9B local (balanced)",         40960),
    SLOW      ("bartowski/Qwen_Qwen3.5-27B-GGUF",  "27B local (highest quality)", 40960),
    GPT4O_MINI("gpt-4o-mini",                      "OpenAI gpt-4o-mini",         128000),
    GPT4O     ("gpt-4o",                           "OpenAI gpt-4o",              128000),
    KIMI_K3   ("kimi-k3",                          "Kimi K3 (SAP answering-machine endpoint)", 262144);

    private final String modelId;
    private final String description;
    private final int defaultContextWindow;

    ModelSize(String modelId, String description, int defaultContextWindow) {
        this.modelId = modelId;
        this.description = description;
        this.defaultContextWindow = defaultContextWindow;
    }

    public String getModelId() { return modelId; }
    public String getDescription() { return description; }
    public int getDefaultContextWindow() { return defaultContextWindow; }
}
