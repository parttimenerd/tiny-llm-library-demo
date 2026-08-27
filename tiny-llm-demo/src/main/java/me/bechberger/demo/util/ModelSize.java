package me.bechberger.demo.util;

/**
 * Model size options for the chatbot.
 * <p>
 * Each size maps to a specific model identifier:
 * - FAST: 2B model, suitable for quick responses on underpowered hardware
 * - MEDIUM: 9B model, balanced quality and speed (default)
 * - SLOW: 27B model, highest quality but slower
 */
public enum ModelSize {
    FAST      ("bartowski/Qwen3.5-2B-Instruct-GGUF:Q8_0", "2B local (fastest)",        40960),
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

    /**
     * Resolves a user-supplied model string to a model ID.
     * Accepts enum names (fast, medium, slow, kimi_k3 …) case-insensitively,
     * or passes any other string through as-is (raw model ID like "kimi-k3").
     */
    public static String resolveModelId(String nameOrId) {
        if (nameOrId == null) return null;
        for (var size : values()) {
            if (size.name().equalsIgnoreCase(nameOrId)) return size.modelId;
        }
        return nameOrId; // treat as a raw model ID
    }

    /** The default context window for a model id, or 32768 when the id is unknown. */
    public static int defaultContextWindowFor(String modelId) {
        for (var size : values()) {
            if (size.modelId.equals(modelId)) return size.defaultContextWindow;
        }
        return 32768;
    }

    public String getModelId() { return modelId; }
    public String getDescription() { return description; }
    public int getDefaultContextWindow() { return defaultContextWindow; }
}
