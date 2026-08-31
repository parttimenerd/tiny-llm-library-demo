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
            "You have been working on a coding task but have not yet completed it. " +
            "Write a continuation summary that will allow you (or another instance of yourself) " +
            "to resume work efficiently in a future context window where the conversation history " +
            "will be replaced with this summary. Do NOT call any tools.\n\n" +
            "Your summary should be structured, concise, and actionable. Include:\n\n" +
            "1. **Task Overview**\n" +
            "   - The user's core request and success criteria\n" +
            "   - Any clarifications or constraints they specified\n\n" +
            "2. **Current State**\n" +
            "   - What has been completed so far\n" +
            "   - Files created, modified, or analyzed (with paths)\n" +
            "   - Key outputs or artifacts produced\n\n" +
            "3. **Important Discoveries**\n" +
            "   - Technical constraints or requirements uncovered\n" +
            "   - Decisions made and their rationale\n" +
            "   - Errors encountered and how they were resolved (quote error messages verbatim)\n" +
            "   - What approaches were tried that didn't work (and why)\n\n" +
            "4. **Next Steps**\n" +
            "   - Specific actions needed to complete the task\n" +
            "   - Any blockers or open questions to resolve\n" +
            "   - Priority order if multiple steps remain\n\n" +
            "5. **Context to Preserve**\n" +
            "   - User preferences or style requirements\n" +
            "   - Domain-specific details that aren't obvious\n" +
            "   - Any promises made to the user\n\n" +
            "Be concise but complete — err on the side of including information that would " +
            "prevent duplicate work or repeated mistakes. Write in a way that enables immediate " +
            "resumption of the task. Wrap your summary in <summary></summary> tags.";

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
            if ("assistant".equals(role) && msg.containsKey("tool_calls")) {
                @SuppressWarnings("unchecked")
                var toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (content != null && !content.toString().isBlank())
                    text.append("assistant: ").append(content).append("\n");
                for (var tc : toolCalls) {
                    @SuppressWarnings("unchecked")
                    var fn = (Map<String, Object>) tc.get("function");
                    String name = fn != null ? String.valueOf(fn.get("name")) : "?";
                    String args = fn != null ? String.valueOf(fn.get("arguments")) : "";
                    if (args.length() > 200) args = args.substring(0, 200) + "…";
                    text.append("tool-call: ").append(name).append("(").append(args).append(")\n");
                }
                text.append("\n");
            } else if ("tool".equals(role)) {
                String result = content != null ? content.toString() : "";
                if (result.length() > 500) result = result.substring(0, 500) + "…";
                text.append("tool-result: ").append(result).append("\n\n");
            } else if (content != null) {
                text.append(role).append(": ").append(content).append("\n\n");
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
        messages.add(LLMClientInterface.system("[Conversation summary]\n" + extractSummary(summary)));
        messages.addAll(tail);
        int after = messages.size();
        System.out.println(Ansi.dim("[compact] done — " + before + " → " + after + " messages, was " + promptTokens + " tokens"));
        return new Outcome(true, before, after, promptTokens);
    }

    private static String extractSummary(String response) {
        int start = response.indexOf("<summary>");
        int end   = response.indexOf("</summary>");
        if (start >= 0 && end > start) return response.substring(start + 9, end).strip();
        return response.strip();
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
