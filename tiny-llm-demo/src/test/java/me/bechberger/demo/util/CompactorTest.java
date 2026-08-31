package me.bechberger.demo.util;

import me.bechberger.demo.util.LLMClientInterface;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Compactor: alert at 85%, compact at 95%, alert deduplication,
 * and the compactNow forced path.
 */
class CompactorTest {

    private ByteArrayOutputStream out;
    private PrintStream originalOut;

    @BeforeEach void captureOut() {
        originalOut = System.out;
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
    }

    @AfterEach void restoreOut() {
        System.setOut(originalOut);
    }

    private String output() { return out.toString(); }

    // ── stub LLMClient ────────────────────────────────────────────────────────

    /** Returns fixed usage and a canned summary so no network call is needed. */
    private static LLMClientInterface stubClient(int promptTokens) {
        return new LLMClientInterface() {
            @Override public LLMClientInterface.TokenUsage lastUsage() {
                return new LLMClientInterface.TokenUsage(0, promptTokens, promptTokens);
            }
            @Override public String chat(List<Map<String, Object>> messages) {
                return "[summary]";
            }
        };
    }

    private static List<Map<String, Object>> messages(int count) {
        var list = new ArrayList<Map<String, Object>>();
        list.add(Map.of("role", "system", "content", "sys"));
        for (int i = 1; i < count; i++)
            list.add(Map.of("role", "user", "content", "msg" + i));
        return list;
    }

    // ── alert threshold ───────────────────────────────────────────────────────

    @Test void noAlertBelowAlertThreshold() {
        var c = new Compactor(9500, 8500, 2);
        c.maybeCompact(stubClient(8000), messages(10), 1);
        assertFalse(output().contains("[context"), "no alert below threshold");
    }

    @Test void alertPrintedAtAlertThreshold() {
        var c = new Compactor(9500, 8500, 2);
        c.maybeCompact(stubClient(8600), messages(10), 1);
        assertTrue(output().contains("[context"), "alert must print at threshold");
        assertTrue(output().contains("/compact"), "alert must mention /compact");
    }

    @Test void alertPrintedOnlyOnceUntilCompaction() {
        var c = new Compactor(9500, 8500, 2);
        c.maybeCompact(stubClient(8600), messages(10), 1);
        c.maybeCompact(stubClient(8700), messages(10), 1);
        long count = output().lines().filter(l -> l.contains("[context")).count();
        assertEquals(1, count, "alert should fire only once per compaction cycle");
    }

    // ── compact threshold ─────────────────────────────────────────────────────

    @Test void noCompactionBelowCompactThreshold() {
        var c = new Compactor(9500, 8500, 2);
        var msgs = messages(10);
        var outcome = c.maybeCompact(stubClient(9000), msgs, 1);
        assertFalse(outcome.compacted());
        assertEquals(10, msgs.size());
    }

    @Test void compactionFiresAtCompactThreshold() {
        var c = new Compactor(9500, 8500, 2);
        var msgs = messages(10);
        var outcome = c.maybeCompact(stubClient(9600), msgs, 1);
        assertTrue(outcome.compacted());
        assertTrue(msgs.size() < 10, "messages should be reduced after compaction");
    }

    @Test void alertResetAfterCompaction() {
        var c = new Compactor(9500, 8500, 2);
        var msgs = messages(10);
        c.maybeCompact(stubClient(8600), msgs, 1);  // trigger alert
        c.maybeCompact(stubClient(9600), msgs, 1);  // trigger compaction, resets alerted flag
        out.reset();
        c.maybeCompact(stubClient(8600), msgs, 1);  // alert should fire again
        assertTrue(output().contains("[context"), "alert should re-fire after compaction");
    }

    // ── compactNow ────────────────────────────────────────────────────────────

    @Test void compactNowAlwaysCompacts() {
        var c = new Compactor(9500, 8500, 2);
        var outcome = c.compactNow(stubClient(100), messages(10));
        assertTrue(outcome.compacted());
    }

    @Test void compactNowInjectsSummaryMessage() {
        var c = new Compactor(9500, 8500, 2);
        var msgs = messages(10);
        c.compactNow(stubClient(100), msgs);
        boolean hasSummary = msgs.stream()
                .anyMatch(m -> "system".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("[summary]"));
        assertTrue(hasSummary, "compacted history must contain the summary message");
    }

    @Test void compactNowPreservesSystemPrompt() {
        var c = new Compactor(9500, 8500, 2);
        var msgs = messages(10);
        c.compactNow(stubClient(100), msgs);
        assertEquals("sys", msgs.get(0).get("content"), "system prompt must be pinned at index 0");
    }

    @Test void compactNowKeepsRecentMessages() {
        var c = new Compactor(9500, 8500, 2); // keepRecent = 2
        var msgs = messages(8);
        var lastMsg = msgs.get(msgs.size() - 1);
        c.compactNow(stubClient(100), msgs);
        assertTrue(msgs.contains(lastMsg), "most recent message must be kept verbatim");
    }

    // ── tool-call group integrity ─────────────────────────────────────────────

    /** Build an assistant message with one tool_call entry. */
    private static Map<String, Object> assistantToolCall(String callId, String toolName) {
        var fn = Map.of("name", toolName, "arguments", "{\"path\":\".\"}");
        var tc = new HashMap<String, Object>();
        tc.put("id", callId); tc.put("function", fn);
        var msg = new HashMap<String, Object>();
        msg.put("role", "assistant"); msg.put("tool_calls", List.of(tc));
        return msg;
    }

    private static Map<String, Object> toolResult(String callId, String content) {
        return Map.of("role", "tool", "tool_call_id", callId, "content", content);
    }

    @Test void cutDoesNotSplitAssistantAndToolResult() {
        // Layout: sys | user | assistant(tool_calls) | tool-result | user | user
        // keepRecent=2 → naive cut lands between assistant(tool_calls) and tool-result
        // The guard must pull recentStart back to include the assistant message.
        var msgs = new ArrayList<Map<String, Object>>();
        msgs.add(Map.of("role", "system",    "content", "sys"));
        msgs.add(Map.of("role", "user",      "content", "u1"));
        msgs.add(assistantToolCall("c1", "ls"));
        msgs.add(toolResult("c1", "file.txt"));
        msgs.add(Map.of("role", "user",      "content", "u2"));
        msgs.add(Map.of("role", "user",      "content", "u3"));

        var c = new Compactor(9500, 8500, 2);
        c.compactNow(stubClient(100), msgs);

        // After compaction the tail must not contain a tool result without its assistant message
        for (int i = 0; i < msgs.size(); i++) {
            if ("tool".equals(msgs.get(i).get("role"))) {
                assertTrue(i > 0 && "assistant".equals(msgs.get(i - 1).get("role"))
                        && msgs.get(i - 1).containsKey("tool_calls"),
                        "tool result at index " + i + " must be preceded by its assistant tool_calls message");
            }
        }
    }

    @Test void orphanedToolResultAtTailStartIsSkipped() {
        // Layout: sys | user | assistant(tool_calls) | tool-result | user
        // keepRecent=2 → tail starts at tool-result — guard must advance past it
        var msgs = new ArrayList<Map<String, Object>>();
        msgs.add(Map.of("role", "system",    "content", "sys"));
        msgs.add(Map.of("role", "user",      "content", "u1"));
        msgs.add(assistantToolCall("c1", "ls"));
        msgs.add(toolResult("c1", "file.txt"));
        msgs.add(Map.of("role", "user",      "content", "u2"));

        var c = new Compactor(9500, 8500, 2);
        c.compactNow(stubClient(100), msgs);

        boolean tailHasOrphanedToolResult = msgs.stream()
                .anyMatch(m -> "tool".equals(m.get("role")));
        // orphaned tool result should have been consumed into the summary
        assertFalse(tailHasOrphanedToolResult || msgs.stream().anyMatch(m ->
                "tool".equals(m.get("role")) && msgs.indexOf(m) > 0 &&
                !"assistant".equals(msgs.get(msgs.indexOf(m) - 1).get("role"))),
                "no orphaned tool results in tail");
    }
}
