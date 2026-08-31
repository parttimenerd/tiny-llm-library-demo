package me.bechberger.demo.util;

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
 * Triggered when the last API call's prompt tokens exceed {@code compactThreshold} (90% of
 * context window). A warning is printed when tokens exceed {@code alertThreshold} (80%) so
 * the user knows compaction is coming and can call /compact early if they prefer.
 */
public final class Compactor {

    /** One check: whether it compacted, message counts around it, and the triggering prompt size. */
    public record Outcome(boolean compacted, int messagesBefore, int messagesAfter, int promptTokens) {}

    private static final String SUMMARY_PROMPT =
            "Summarize the following conversation concisely, preserving key facts, decisions, " +
            "files created or changed, tool results, and errors that were fixed. " +
            "Write in third person as a summary of what was discussed.";

    private final int compactThreshold; // prompt tokens that trigger compaction
    private final int alertThreshold;   // prompt tokens that trigger the "approaching limit" warning
    private final int keepRecent;       // messages kept verbatim at the tail
    private boolean alerted = false;    // print the alert only once per compaction cycle

    public Compactor(int compactThreshold, int alertThreshold, int keepRecent) {
        this.compactThreshold = compactThreshold;
        this.alertThreshold   = alertThreshold;
        this.keepRecent       = keepRecent;
    }

    public int threshold() {
        return compactThreshold;
    }

    /**
     * Compact {@code messages} in place when the prompt is over the threshold.
     *
     * @param client   used both for the usage data and the summarization call
     * @param messages conversation history (mutated on compaction)
     * @param pinned   number of leading messages never summarized (normally 1: the system prompt)
     */
    public Outcome maybeCompact(LLMClientInterface client, List<Map<String, Object>> messages, int pinned) {
        var usage = client.lastUsage();
        int tokens = usage != null ? usage.promptTokens() : 0;
        if (usage == null) return new Outcome(false, messages.size(), messages.size(), 0);
        if (tokens >= compactThreshold) {
            alerted = false; // reset for next cycle
            return doCompact(client, messages, pinned);
        }
        if (!alerted && tokens >= alertThreshold) {
            alerted = true;
            System.out.println(Ansi.yellow("[context " + tokens + "/" + compactThreshold
                    + " tokens — auto-compact soon. Use /compact to compact now.]"));
        }
        return new Outcome(false, messages.size(), messages.size(), tokens);
    }

    /** Force compaction now, regardless of the threshold (the /compact command). */
    public Outcome compactNow(LLMClientInterface client, List<Map<String, Object>> messages) {
        return doCompact(client, messages, 1);
    }

    private Outcome doCompact(LLMClientInterface client, List<Map<String, Object>> messages, int pinned) {
        var usage = client.lastUsage();
        int promptTokens = usage != null ? usage.promptTokens() : 0;

        int recentStart = Math.max(pinned, messages.size() - keepRecent);
        // don't let the kept tail start with an orphaned tool result whose
        // assistant tool_calls message would have been summarized away
        while (recentStart < messages.size() && "tool".equals(messages.get(recentStart).get("role"))) {
            recentStart++;
        }
        if (recentStart <= pinned) {
            return new Outcome(false, messages.size(), messages.size(), promptTokens);
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
            System.out.println(Ansi.dim("[compact] summarizing " + (recentStart - pinned) + " messages (" + promptTokens + " tokens)…"));
            summary = client.chatSimple(List.of(
                    LLMClientInterface.system(SUMMARY_PROMPT), LLMClientInterface.user(text.toString())));
        } catch (Exception e) {
            // compaction must never kill a session - leave history as is, retry next turn
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("[compact] summarization failed: " + rootMessage(e)
                    + " (summarizing " + text.length() + " chars)");
            return new Outcome(false, before, messages.size(), promptTokens);
        }

        messages.clear();
        messages.addAll(head);
        messages.add(LLMClientInterface.system("[Conversation summary] " + summary));
        messages.addAll(tail);
        int after = messages.size();
        System.out.println(Ansi.dim("[compact] done — " + before + " → " + after + " messages, was " + promptTokens + " tokens"));
        return new Outcome(true, before, after, promptTokens);
    }

    private static String rootMessage(Throwable e) {
        var sb = new StringBuilder();
        Throwable t = e;
        while (t != null) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            t = t.getCause();
        }
        return sb.toString();
    }
}
