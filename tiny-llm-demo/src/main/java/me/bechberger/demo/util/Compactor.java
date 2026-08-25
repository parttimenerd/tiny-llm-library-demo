package me.bechberger.demo.util;

import me.bechberger.demo.LLMClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Conversation compaction - the "hybrid memory" strategy from SummarizingChatBot,
 * extracted so agents don't hand-roll it:
 * <ol>
 *   <li><b>pin</b> the leading message(s) (the system prompt) - never summarized,</li>
 *   <li><b>keep</b> the most recent messages verbatim,</li>
 *   <li><b>summarize</b> everything in between via an LLM call into one
 *       "[Conversation summary]" system message.</li>
 * </ol>
 * Triggered when the last API call's prompt tokens exceed a threshold - real usage
 * data, not a character estimate (see {@link LLMClient#lastUsage()}). On the next
 * compaction the old summary is part of the middle and gets folded into the new one.
 */
public final class Compactor {

    /** One check: whether it compacted, message counts around it, and the triggering prompt size. */
    public record Outcome(boolean compacted, int messagesBefore, int messagesAfter, int promptTokens) {}

    private static final String SUMMARY_PROMPT =
            "Summarize the following conversation concisely, preserving key facts, decisions, " +
            "files created or changed, tool results, and errors that were fixed. " +
            "Write in third person as a summary of what was discussed.";

    private final int threshold;   // prompt tokens that trigger compaction
    private final int keepRecent;  // messages kept verbatim at the tail

    public Compactor(int threshold, int keepRecent) {
        this.threshold = threshold;
        this.keepRecent = keepRecent;
    }

    public int threshold() {
        return threshold;
    }

    /**
     * Compact {@code messages} in place when the prompt is over the threshold.
     *
     * @param client   used both for the usage data and the summarization call
     * @param messages conversation history (mutated on compaction)
     * @param pinned   number of leading messages never summarized (normally 1: the system prompt)
     */
    public Outcome maybeCompact(LLMClient client, List<Map<String, Object>> messages, int pinned) {
        var usage = client.lastUsage();
        if (usage == null || usage.promptTokens() <= threshold) {
            return new Outcome(false, messages.size(), messages.size(),
                    usage != null ? usage.promptTokens() : 0);
        }

        int recentStart = Math.max(pinned, messages.size() - keepRecent);
        // don't let the kept tail start with an orphaned tool result whose
        // assistant tool_calls message would have been summarized away
        while (recentStart < messages.size() && "tool".equals(messages.get(recentStart).get("role"))) {
            recentStart++;
        }
        if (recentStart <= pinned) {
            return new Outcome(false, messages.size(), messages.size(), usage.promptTokens());
        }

        var text = new StringBuilder();
        for (var msg : messages.subList(pinned, recentStart)) {
            var role = (String) msg.get("role");
            var content = msg.get("content");
            if (content != null) {
                text.append(role).append(": ").append(content).append("\n\n");
            } else if ("assistant".equals(role) && msg.containsKey("tool_calls")) {
                text.append("assistant: [called tools]\n\n");
            }
        }

        var head = new ArrayList<>(messages.subList(0, pinned));
        var tail = new ArrayList<>(messages.subList(recentStart, messages.size()));
        int before = messages.size();

        String summary;
        try {
            summary = client.chat(List.of(
                    LLMClient.system(SUMMARY_PROMPT), LLMClient.user(text.toString())));
        } catch (Exception e) {
            // compaction must never kill a session - leave history as is, retry next turn
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("[compact] summarization failed: " + e.getMessage());
            return new Outcome(false, before, messages.size(), usage.promptTokens());
        }

        messages.clear();
        messages.addAll(head);
        messages.add(LLMClient.system("[Conversation summary] " + summary));
        messages.addAll(tail);
        return new Outcome(true, before, messages.size(), usage.promptTokens());
    }
}
