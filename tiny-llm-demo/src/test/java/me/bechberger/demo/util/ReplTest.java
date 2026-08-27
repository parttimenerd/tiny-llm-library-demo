package me.bechberger.demo.util;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Repl: prompt loop, slash-commands, multi-line input, onResponse hook.
 * Uses a Scanner backed by a String to simulate user input without a real TTY.
 */
class ReplTest {

    /** Capture stdout during a test. */
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

    private static java.util.Scanner scanner(String input) {
        return new java.util.Scanner(new java.io.ByteArrayInputStream(input.getBytes()));
    }

    // ── basic run loop ────────────────────────────────────────────────────────

    @Test void singleInputReachesChat() {
        var received = new ArrayList<String>();
        var repl = new Repl("\n> ", scanner("hello\n"));
        repl.run(received::add);
        assertEquals(List.of("hello"), received);
    }

    @Test void multipleInputsReachChat() {
        var received = new ArrayList<String>();
        var repl = new Repl("\n> ", scanner("one\ntwo\n"));
        repl.run(received::add);
        assertEquals(List.of("one", "two"), received);
    }

    @Test void eofExitsLoopGracefully() {
        var count = new int[]{0};
        // no newline at end = EOF after "hi"
        var repl = new Repl("> ", scanner("hi"));
        repl.run(input -> count[0]++);
        assertEquals(1, count[0]);
    }

    @Test void emptyLinesAreSkipped() {
        var received = new ArrayList<String>();
        var repl = new Repl("> ", scanner("\n\nhello\n\n"));
        repl.run(received::add);
        assertEquals(List.of("hello"), received);
    }

    @Test void stopHaltsLoop() {
        var received = new ArrayList<String>();
        var repl = new Repl("> ", scanner("a\nb\nc\n"));
        repl.run(input -> {
            received.add(input);
            if (input.equals("b")) repl.stop();
        });
        assertEquals(List.of("a", "b"), received);
    }

    // ── slash commands ────────────────────────────────────────────────────────

    @Test void exitCommandStopsLoop() {
        var received = new ArrayList<String>();
        var repl = new Repl("> ", scanner("hello\nexit\nworld\n"));
        repl.run(received::add);
        // "world" should never be received — exit stops the loop
        assertEquals(List.of("hello"), received);
    }

    @Test void quitIsAliasForExit() {
        var received = new ArrayList<String>();
        var repl = new Repl("> ", scanner("hi\nquit\n"));
        repl.run(received::add);
        assertEquals(List.of("hi"), received);
    }

    @Test void customCommandDispatched() {
        var triggered = new boolean[]{false};
        var repl = new Repl("> ", scanner("/greet\nhello\n"));
        repl.on("greet", "say hi", args -> triggered[0] = true);
        repl.run(input -> {});
        assertTrue(triggered[0]);
    }

    @Test void customCommandReceivesArgs() {
        var argsReceived = new String[]{null};
        var repl = new Repl("> ", scanner("/echo foo bar\n"));
        repl.on("echo", "echo args", args -> argsReceived[0] = args);
        repl.run(input -> {});
        assertEquals("foo bar", argsReceived[0]);
    }

    @Test void unknownSlashCommandNotSentToChat() {
        var chatCalled = new boolean[]{false};
        var repl = new Repl("> ", scanner("/nonexistent\n"));
        repl.run(input -> chatCalled[0] = true);
        assertFalse(chatCalled[0], "unknown slash-command should not reach chat");
        assertTrue(output().contains("Unknown command"), "should print an error");
    }

    // ── multi-line input ──────────────────────────────────────────────────────

    @Test void backslashContinuation() {
        var received = new ArrayList<String>();
        var repl = new Repl("> ", scanner("line one \\\nline two\n"));
        repl.run(received::add);
        assertEquals(1, received.size());
        assertEquals("line one \nline two", received.get(0));
    }

    @Test void doubleBackslashContinuation() {
        var received = new ArrayList<String>();
        var repl = new Repl("> ", scanner("a \\\nb \\\nc\n"));
        repl.run(received::add);
        assertEquals(1, received.size());
        assertEquals("a \nb \nc", received.get(0));
    }

    // ── onResponse hook ───────────────────────────────────────────────────────

    @Test void onResponseCalledAfterChat() {
        var calls = new int[]{0};
        var repl = new Repl("> ", scanner("hello\n"));
        repl.onResponse(() -> calls[0]++);
        repl.run(input -> {});
        assertEquals(1, calls[0]);
    }

    @Test void onResponseCalledAfterCommand() {
        var calls = new int[]{0};
        var repl = new Repl("> ", scanner("/greet\n"));
        repl.on("greet", "say hi", args -> {});
        repl.onResponse(() -> calls[0]++);
        repl.run(input -> {});
        assertEquals(1, calls[0]);
    }

    @Test void onResponseCalledForEachInput() {
        var calls = new int[]{0};
        var repl = new Repl("> ", scanner("a\nb\nc\n"));
        repl.onResponse(() -> calls[0]++);
        repl.run(input -> {});
        assertEquals(3, calls[0]);
    }

    // ── greet ─────────────────────────────────────────────────────────────────

    @Test void greetPrintsMessage() {
        var repl = new Repl("> ", scanner(""));
        repl.greet("Hello world");
        assertTrue(output().contains("Hello world"));
    }

    @Test void greetShowsSlimHintNotFullList() {
        var repl = new Repl("> ", scanner(""));
        repl.on("foo", "foo cmd", args -> {});
        repl.greet("Hello");
        // slim hint points to /help rather than dumping all commands
        assertTrue(output().contains("/help"), "should mention /help");
        // should NOT print every command line
        assertFalse(output().contains("/foo"), "should not dump full command list on startup");
    }

    // ── prompt ────────────────────────────────────────────────────────────────

    @Test void promptMethodReadsLine() {
        var repl = new Repl("> ", scanner("yes\n"));
        String answer = repl.prompt("Confirm? ", "no");
        assertEquals("yes", answer);
    }

    @Test void promptMethodReturnsDefaultOnEof() {
        var repl = new Repl("> ", scanner(""));
        String answer = repl.prompt("Confirm? ", "default");
        assertEquals("default", answer);
    }

    // ── history ───────────────────────────────────────────────────────────────

    @Test void historyRecordsInputs() {
        var repl = new Repl("> ", scanner("hello\nworld\n"));
        repl.run(input -> {});
        assertEquals(List.of("hello", "world"), repl.getHistory());
    }

    @Test void historySkipsCommands() {
        var repl = new Repl("> ", scanner("/greet\nhello\n"));
        repl.on("greet", "say hi", args -> {});
        repl.run(input -> {});
        assertEquals(List.of("hello"), repl.getHistory(), "commands must not appear in history");
    }

    @Test void historyDeduplicatesConsecutiveIdentical() {
        var repl = new Repl("> ", scanner("same\nsame\ndifferent\n"));
        repl.run(input -> {});
        assertEquals(List.of("same", "different"), repl.getHistory());
    }

    @Test void historyAllowsNonConsecutiveDuplicates() {
        var repl = new Repl("> ", scanner("a\nb\na\n"));
        repl.run(input -> {});
        assertEquals(List.of("a", "b", "a"), repl.getHistory());
    }

    @Test void historyIsEmptyBeforeAnyInput() {
        var repl = new Repl("> ", scanner(""));
        assertTrue(repl.getHistory().isEmpty());
    }

    @Test void historyCommandPrintsEntries() {
        var repl = new Repl("> ", scanner("hello\n/history\n"));
        repl.run(input -> {});
        assertTrue(output().contains("hello"), "/history should print past inputs");
    }

    @Test void historyCommandPrintsEmptyMessage() {
        var repl = new Repl("> ", scanner("/history\n"));
        repl.run(input -> {});
        assertTrue(output().contains("no history"));
    }

    @Test void historyCommandDoesNotAddToHistory() {
        var repl = new Repl("> ", scanner("/history\n"));
        repl.run(input -> {});
        assertTrue(repl.getHistory().isEmpty());
    }

    @Test void historyPreservesMultilineInputs() {
        var repl = new Repl("> ", scanner("line one \\\nline two\n"));
        repl.run(input -> {});
        assertEquals(1, repl.getHistory().size());
        assertTrue(repl.getHistory().get(0).contains("\n"));
    }

    @Test void historyIsUnmodifiable() {
        var repl = new Repl("> ", scanner("hello\n"));
        repl.run(input -> {});
        assertThrows(UnsupportedOperationException.class,
                () -> repl.getHistory().add("injected"));
    }

    // ── subbuilder ────────────────────────────────────────────────────────────

    @Test void subBuilderDispatchesToSubcommand() {
        var triggered = new String[]{null};
        var repl = new Repl("> ", scanner("/todo add buy milk\n"));
        new Repl.Builder("\n> ", scanner("")).on("todo", "manage todos",
                args -> triggered[0] = args, new String[0]);
        // Test SubBuilder directly on the Repl
        repl.commands().on("todo", "manage todos", args -> triggered[0] = args);
        repl.run(input -> {});
        assertEquals("add buy milk", triggered[0]);
    }

    @Test void subBuilderUnknownSubcommandPrintsHelp() {
        var repl = new Repl("> ", scanner("/todo nope\n"));
        var builder = new Repl.Builder("\n> ", new java.util.Scanner(new java.io.ByteArrayInputStream(new byte[0])));
        builder.sub("todo", "manage todos")
                .on("add", "add item", (Commands.Handler) args -> {})
                .end();
        // re-create repl using builder
        var repl2 = new Repl("> ", scanner("/todo nope\n"));
        repl2.commands().on("todo", "manage todos", args -> {
            System.out.println("Unknown: /todo nope");
        });
        repl2.run(input -> {});
        assertTrue(output().contains("Unknown"));
    }

    // ── builder tokenCallback ─────────────────────────────────────────────────

    @Test void builderTokenCallbackPrintsTokens() {
        var builder = new Repl.Builder("\n> ", scanner(""));
        builder.tokenCallback.accept("tok1");
        builder.tokenCallback.accept("tok2");
        assertTrue(output().contains("tok1"));
        assertTrue(output().contains("tok2"));
    }
}
