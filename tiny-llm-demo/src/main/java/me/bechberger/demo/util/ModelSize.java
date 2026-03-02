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
    FAST("Qwen/Qwen3-1.7B-GGUF:Q8_0", "1.7B (fastest)"),
    MEDIUM("AaryanK/Qwen3.5-9B-GGUF:Q8_0", "9B (balanced)"),
    SLOW("bartowski/Qwen_Qwen3.5-27B-GGUF", "27B (highest quality)");

    private final String modelId;
    private final String description;

    ModelSize(String modelId, String description) {
        this.modelId = modelId;
        this.description = description;
    }

    public String getModelId() {
        return modelId;
    }

    public String getDescription() {
        return description;
    }
}
