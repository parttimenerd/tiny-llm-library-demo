package me.bechberger.demo.util;

import java.util.function.BiConsumer;

/** Implemented by any ToolSupport to receive post-call notifications. */
public interface ToolCallListener {
    void setOnToolCall(BiConsumer<String, String> callback);
}
