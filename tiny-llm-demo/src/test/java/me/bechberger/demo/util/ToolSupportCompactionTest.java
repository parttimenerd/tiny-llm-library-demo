package me.bechberger.demo.util;

import me.bechberger.demo.solutions.LLMClient;
import me.bechberger.demo.solutions.ToolSupport;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for proactive compaction wired into solutions/ToolSupport.
 * All tests use stub implementations – no real HTTP calls are made.
 */
class ToolSupportCompactionTest {

    private ByteArrayOutputStream out;
    private PrintStream originalOut;

    @BeforeEach
    void captureOut() {
        originalOut = System.out;
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    // ── stub LLMClient (for compactor's summarization client) ─────────────────

    /**
     * Stub that implements LLMClientInterface directly (used by Compactor for
     * summarization – does NOT need chatRaw).
     */
    private static LLMClientInterface stubCompactorClient(int promptTokens) {
        return new LLMClientInterface() {
            @Override
            public TokenUsage lastUsage() {
                return new TokenUsage(0, promptTokens, promptTokens);
            }

            @Override
            public String chat(List<Map<String, Object>> messages) {
                return "[summary]";
            }
        };
    }

    // ── stub LLMClient (subclass of solutions.LLMClient for handleToolLoop) ───

    /**
     * Subclass of solutions.LLMClient that intercepts chatRaw and lastUsage
     * without making real HTTP connections.
     *
     * <p>The response queue drives chatRaw: each call pops the next entry.
     * lastUsage() returns the configured token count.
     */
    static class StubLLMClient extends LLMClient {

        private final Queue<Map<String, Object>> responses;
        private final int promptTokens;

        StubLLMClient(int promptTokens, Map<String, Object>... responses) {
            // "http://localhost:1" is a valid URL so HttpHelper doesn't reject it
            // in the constructor; it won't be contacted because chatRaw is overridden.
            super("http://localhost:1", "stub", null);
            this.promptTokens = promptTokens;
            this.responses = new ArrayDeque<>(Arrays.asList(responses));
        }

        @Override
        public Map<String, Object> chatRaw(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools) {
            if (responses.isEmpty()) {
                // default: stop response
                return stopResponse("done");
            }
            return responses.poll();
        }

        @Override
        public TokenUsage lastUsage() {
            return new TokenUsage(0, promptTokens, promptTokens);
        }

        @Override
        public String chat(List<Map<String, Object>> messages) {
            return "[summary]";
        }
    }

    // ── response builders ─────────────────────────────────────────────────────

    /** A response with finish_reason=tool_calls invoking the "echo" tool. */
    private static Map<String, Object> toolCallResponse() {
        var toolCall = new LinkedHashMap<String, Object>();
        toolCall.put("id", "tc1");
        var function = new LinkedHashMap<String, Object>();
        function.put("name", "echo");
        function.put("arguments", "{\"text\":\"hi\"}");
        toolCall.put("function", function);

        var message = new LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("tool_calls", List.of(toolCall));

        var choice = new LinkedHashMap<String, Object>();
        choice.put("finish_reason", "tool_calls");
        choice.put("message", message);
        return choice;
    }

    /** A response with finish_reason=stop and the given content. */
    private static Map<String, Object> stopResponse(String content) {
        var message = new LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", content);

        var choice = new LinkedHashMap<String, Object>();
        choice.put("finish_reason", "stop");
        choice.put("message", message);
        return choice;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Build a ToolSupport with a simple "echo" tool registered. */
    private static ToolSupport echoToolSupport() {
        var ts = new ToolSupport();
        ts.registerTool("echo", "Echoes text",
                Map.of("type", "object",
                        "properties", Map.of(
                                "text", Map.of("type", "string")),
                        "required", List.of("text")),
                args -> (String) args.get("text"));
        return ts;
    }

    /**
     * A messages list with enough entries to trigger compaction.
     * Compactor needs recentStart > pinned (1), so we need more than keepRecent+1
     * messages (keepRecent=2 in most tests → need >3 messages total).
     */
    private static List<Map<String, Object>> baseMessages() {
        var msgs = new ArrayList<Map<String, Object>>();
        msgs.add(Map.of("role", "system", "content", "You are a test assistant."));
        msgs.add(Map.of("role", "user", "content", "msg1"));
        msgs.add(Map.of("role", "assistant", "content", "reply1"));
        msgs.add(Map.of("role", "user", "content", "msg2"));
        msgs.add(Map.of("role", "assistant", "content", "reply2"));
        msgs.add(Map.of("role", "user", "content", "Do something."));
        return msgs;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * 1. When tokens are above threshold, compaction fires before the tool result
     *    is appended; the message list shrinks and onCompact is called.
     */
    @Test
    @SuppressWarnings("unchecked")
    void compactionFiresBeforeToolResultAppended() {
        // Threshold is 9500; stub reports 10000 tokens – over threshold.
        var compactor = new Compactor(9500, 8500, 2);
        var compactorClient = stubCompactorClient(10000);

        // Loop client: tool_calls once, then stop.
        var loopClient = new StubLLMClient(10000, toolCallResponse(), stopResponse("done"));

        var ts = echoToolSupport();
        var onCompactCalls = new AtomicInteger(0);
        ts.setCompactor(compactor, loopClient, onCompactCalls::incrementAndGet);

        var msgs = baseMessages();
        int initialSize = msgs.size();
        ts.handleToolLoop(loopClient, msgs);

        // Compaction must have been called (onCompact fires at least once).
        assertTrue(onCompactCalls.get() > 0, "onCompact should have been called");

        // After compaction the messages list is rebuilt with a summary – it
        // should NOT simply equal the initial size + tool additions uncompacted.
        // The key check: a [Conversation summary] system message is present.
        boolean hasSummary = msgs.stream()
                .anyMatch(m -> "system".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("[summary]"));
        assertTrue(hasSummary, "messages should contain a compaction summary");
    }

    /**
     * 2. Verify compaction fires at the start of the loop iteration (maybeCompact
     *    is called before chatRaw in handleToolLoop).
     * Strategy: give the loop client high usage AND make the compactorClient also
     * report high tokens. The first call to maybeCompact at loop start must compact.
     */
    @Test
    void compactionFiresAtLoopStart() {
        var compactor = new Compactor(9500, 8500, 2);

        // loopClient has high usage so maybeCompact fires at loop start.
        var loopClient = new StubLLMClient(10000, stopResponse("done"));

        var ts = echoToolSupport();
        var onCompactCalls = new AtomicInteger(0);
        ts.setCompactor(compactor, loopClient, onCompactCalls::incrementAndGet);

        var msgs = baseMessages();
        ts.handleToolLoop(loopClient, msgs);

        // Compaction at loop start (before chatRaw) means it fires even with a
        // stop response (no tool calls at all).
        assertTrue(onCompactCalls.get() > 0,
                "compaction should fire at loop start when tokens exceed threshold");
    }

    /**
     * 3. onCompact is called exactly once per compaction event, and NOT called
     *    when compaction doesn't happen.
     */
    @Test
    void onCompactCallbackInvokedOnCompaction() {
        // Scenario A: tokens above threshold → fires exactly once.
        var compactorA = new Compactor(9500, 8500, 2);
        var loopClientAbove = new StubLLMClient(10000, stopResponse("done"));
        var tsA = echoToolSupport();
        var callsAbove = new AtomicInteger(0);
        tsA.setCompactor(compactorA, loopClientAbove, callsAbove::incrementAndGet);
        tsA.handleToolLoop(loopClientAbove, baseMessages());
        assertEquals(1, callsAbove.get(), "onCompact must be called exactly once when over threshold");

        // Scenario B: tokens below threshold → never fires.
        var compactorB = new Compactor(9500, 8500, 2);
        var loopClientBelow = new StubLLMClient(100, stopResponse("done"));
        var tsB = echoToolSupport();
        var callsBelow = new AtomicInteger(0);
        tsB.setCompactor(compactorB, loopClientBelow, callsBelow::incrementAndGet);
        tsB.handleToolLoop(loopClientBelow, baseMessages());
        assertEquals(0, callsBelow.get(), "onCompact must NOT be called when below threshold");
    }

    /**
     * 4. When tokens are below the compaction threshold, no compaction happens
     *    and onCompact is never called.
     */
    @Test
    void noCompactionWhenBelowThreshold() {
        var compactor = new Compactor(9500, 8500, 2);
        var loopClient = new StubLLMClient(100 /* below 9500 */, stopResponse("done"));

        var ts = echoToolSupport();
        var onCompactCalls = new AtomicInteger(0);
        ts.setCompactor(compactor, loopClient, onCompactCalls::incrementAndGet);

        var msgs = baseMessages();
        int beforeSize = msgs.size();
        ts.handleToolLoop(loopClient, msgs);

        assertEquals(0, onCompactCalls.get(), "onCompact must not fire below threshold");
        // messages should have grown (assistant reply appended) but no summary injected
        boolean hasSummary = msgs.stream()
                .anyMatch(m -> "system".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("[summary]"));
        assertFalse(hasSummary, "no summary message should be injected when below threshold");
    }

    /**
     * 5. When no compactor is set, the tool loop runs without throwing NPE.
     */
    @Test
    void noCompactionWhenCompactorNotSet() {
        var loopClient = new StubLLMClient(10000, toolCallResponse(), stopResponse("done"));
        var ts = echoToolSupport();
        // intentionally NOT calling ts.setCompactor(...)

        var msgs = baseMessages();
        assertDoesNotThrow(() -> ts.handleToolLoop(loopClient, msgs),
                "handleToolLoop must not throw when no compactor is set");
    }

    /**
     * 6. After compaction, if the tail starts with a tool-role message (orphaned),
     *    Compactor advances recentStart past it. The resulting messages must not
     *    start with role=tool.
     */
    @Test
    void orphanedToolResultSkipped() {
        // Build a Compactor with keepRecent=3 so the tail may include the orphan.
        var compactor = new Compactor(9500, 8500, 3);
        var compactorClient = stubCompactorClient(10000);

        // We test Compactor.compactNow directly for this scenario.
        var msgs = new ArrayList<Map<String, Object>>();
        msgs.add(Map.of("role", "system", "content", "sys"));
        msgs.add(Map.of("role", "user", "content", "u1"));
        msgs.add(Map.of("role", "user", "content", "u2"));
        msgs.add(Map.of("role", "user", "content", "u3"));
        // tail: starts with an orphaned tool result
        var orphan = new LinkedHashMap<String, Object>();
        orphan.put("role", "tool");
        orphan.put("tool_call_id", "tc1");
        orphan.put("content", "some result");
        msgs.add(orphan);
        msgs.add(Map.of("role", "user", "content", "u4"));

        compactor.compactNow(compactorClient, msgs);

        // The messages list must not start with a tool role after the pinned system prompt.
        for (var m : msgs) {
            assertNotEquals("tool", m.get("role"),
                    "compacted messages must not contain an orphaned tool result at the start of the kept tail");
            // Once we hit non-system non-summary content we know the tail starts cleanly
            break;
        }
        // More precisely: no message immediately after the system prompt(s) should be role=tool
        int firstNonSystem = 0;
        while (firstNonSystem < msgs.size() && "system".equals(msgs.get(firstNonSystem).get("role"))) {
            firstNonSystem++;
        }
        if (firstNonSystem < msgs.size()) {
            assertNotEquals("tool", msgs.get(firstNonSystem).get("role"),
                    "first non-system message after compaction must not be role=tool");
        }
    }

    /**
     * 7. setCompactor with null onCompact must not throw NPE when compaction fires.
     */
    @Test
    void compactorWithNullOnCompact() {
        var compactor = new Compactor(9500, 8500, 2);
        var loopClient = new StubLLMClient(10000, stopResponse("done"));

        var ts = echoToolSupport();
        // pass null explicitly for onCompact
        ts.setCompactor(compactor, loopClient, null);

        var msgs = baseMessages();
        assertDoesNotThrow(() -> ts.handleToolLoop(loopClient, msgs),
                "setCompactor with null onCompact must not cause NPE during compaction");
    }
}
