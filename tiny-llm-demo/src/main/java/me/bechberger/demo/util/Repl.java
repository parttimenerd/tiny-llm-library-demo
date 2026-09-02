package me.bechberger.demo.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import me.bechberger.demo.LLMClient;


/**
 * Minimal REPL framework for the chatbots - prompt loop, slash-commands, multi-line
 * input, and an optional after-response panel (like Claude Code's todo list).
 * <p>
 * - prompt comes from a {@link Supplier}, so a mode badge can change it live
 *   ({@code /mode}, {@code /yolo})
 * - a trailing {@code \} continues input on the next line (pasting multi-line text)
 * - piped input (no console) is echoed, so scripted sessions stay readable in logs
 * - slash-commands dispatch via {@link Commands} (auto-registers exit/quit + /help);
 *   unknown slash-commands are rejected locally instead of burning an LLM call
 * - {@link #showPane} registers a supplier whose output is printed automatically
 *   after every response (pass it to the tool loop too for mid-turn updates)
 * - ends on exit/quit or EOF (Ctrl-D)
 */
public final class Repl {

    /** Receives one non-command input (possibly multi-line). */
    @FunctionalInterface
    public interface Chat {
        void chat(String input) throws Exception;
    }

    final Scanner scanner;
    private final Commands commands = Commands.create();
    private Supplier<String> prompt;
    private Runnable onResponse;
    private boolean stopped = false;
    /** When non-null, injected as the next input line instead of reading from stdin. */
    private volatile Supplier<String> injectedInput;
    /** Active schedule handles; cancel() stops the virtual thread. */
    private final java.util.concurrent.CopyOnWriteArrayList<ScheduleHandle> schedules =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Lines typed while the assistant was responding, drained at the start of the next iteration. */
    private final java.util.concurrent.LinkedBlockingDeque<String> messageQueue =
            new java.util.concurrent.LinkedBlockingDeque<>();
    /** The QueueReader running during a turn — stopped before any mid-turn prompt so echo is restored. */
    private volatile QueueReader activeReader;

    /** Handle returned by {@link #schedule} — call {@link #cancel()} to stop it. */
    public static final class ScheduleHandle {
        private volatile boolean cancelled = false;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }
    /** True only when running interactively on a real TTY with stdin as input source. */
    final boolean interactive;
    final History history;
    private Supplier<String> livePane = null;
    private int lastPaneLines = 0; // lines printed by last printLivePane(); 0 = needs fresh print

    /**
     * @param prompt  printed before every input line, e.g. "\nYou: "
     * @param scanner shared System.in scanner — pass one in so sibling prompts
     *                (confirmations, plan acceptance) don't fight over the stream
     */
    public Repl(String prompt, Scanner scanner) {
        this.scanner = scanner;
        this.interactive = hasTty();
        this.history = new History(interactive);
        this.prompt = () -> prompt;
        commands.on("exit", "leave the chat", args -> stop(), "quit");
        commands.on("history", "show input history", args -> history.print());
    }

    /** Ordered list of non-command inputs sent to chat, most-recent last. */
    public List<String> getHistory() { return history.entries(); }

    /** Dynamic prompt - evaluated before every input line (mode badges, cwd, ...). */
    public Repl setPrompt(Supplier<String> prompt) {
        this.prompt = prompt;
        return this;
    }

    /**
     * Register a live pane shown above the prompt line on every REPL cycle (interactive mode only).
     * In non-interactive mode the pane is never rendered here — use showPane() for that.
     */
    public Repl setLivePane(Supplier<String> pane) {
        this.livePane = pane;
        return this;
    }

    /**
     * Erase the previous pane (cursor-up by lastPaneLines), print the new content, track line count.
     * No-op when not interactive or no live pane is registered.
     * Resets lastPaneLines to 0 when content is empty (nothing on screen to erase next time).
     */
    void printLivePane() {
        if (!interactive || livePane == null) return;
        String content = livePane.get();
        if (lastPaneLines > 0) {
            System.out.print(Ansi.cursorUp(lastPaneLines));
            for (int i = 0; i < lastPaneLines; i++)
                System.out.print("\r" + Ansi.ERASE_EOL + (i < lastPaneLines - 1 ? "\n" : ""));
        }
        if (content == null || content.isBlank()) { lastPaneLines = 0; System.out.flush(); return; }
        System.out.println(content);
        System.out.flush();
        lastPaneLines = content.split("\n", -1).length + 1;
    }

    /** Reset pane line counter so next printLivePane() repaints fresh below current output. */
    public void resetLivePaneCount() { lastPaneLines = 0; }

    /** Inject a line to be consumed as the next user input (used by the schedule tool). */
    public void injectInput(String line) { this.injectedInput = () -> line; }

    /**
     * Schedule {@code message} to be injected after {@code delayMs} ms, then every
     * {@code repeatMs} ms if > 0. Returns a handle whose {@link ScheduleHandle#cancel()}
     * stops the virtual thread.
     */
    public ScheduleHandle schedule(String message, long delayMs, long repeatMs) {
        var handle = new ScheduleHandle();
        schedules.add(handle);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delayMs);
                if (!handle.isCancelled()) injectInput(message);
                if (repeatMs > 0) {
                    while (!handle.isCancelled()) {
                        Thread.sleep(repeatMs);
                        if (!handle.isCancelled()) injectInput(message);
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                schedules.remove(handle);
            }
        });
        return handle;
    }

    /** Cancel all active scheduled injections. */
    public void cancelAllScheduled() {
        schedules.forEach(ScheduleHandle::cancel);
        schedules.clear();
        injectedInput = null;
    }

    /** The command table - add your own slash-commands here. */
    public Commands commands() {
        return commands;
    }

    /** Register a command directly (fluent shorthand for {@code commands().on(...)}). */
    public Repl on(String name, String description, Commands.Handler handler, String... aliases) {
        commands.on(name, description, handler, aliases);
        return this;
    }

    /**
     * Register a callback invoked after every chat response.
     * Replaces any previously registered callback — use {@link #showPane} for the
     * common "print a panel after each response" pattern.
     */
    public Repl onResponse(Runnable handler) {
        this.onResponse = handler;
        return this;
    }

    /**
     * Show a panel (e.g. TODO list) automatically after every chat response.
     * The supplier is called after each response; null or blank output is suppressed.
     * Returns the runnable so the caller can also trigger it from tool loops.
     * <pre>{@code
     * var pane = repl.showPane(() -> state.isEmpty() ? null : state.renderPane());
     * toolSupport.setOnToolCall((name, r) -> { if (name.startsWith("todo-")) pane.run(); });
     * }</pre>
     */
    public Runnable showPane(Supplier<String> pane) {
        Runnable r = () -> {
            var s = pane.get();
            if (s != null && !s.isBlank()) System.out.println(s);
        };
        Runnable prev = this.onResponse;
        Runnable chained = prev == null ? r : () -> { prev.run(); r.run(); };
        this.onResponse = chained;
        return r;
    }

    /** Wrap a checked-IO call for use in a lambda — rethrows as {@link java.io.UncheckedIOException}. */
    @FunctionalInterface
    public interface IORunnable { void run() throws IOException; }
    @FunctionalInterface
    public interface IOSupplier<T> { T get() throws IOException; }

    public static void io(IORunnable r) {
        try { r.run(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    public static <T> T io(IOSupplier<T> s) {
        try { return s.get(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    /** End the run loop after the current iteration. */
    public void stop() {
        stopped = true;
    }

    /**
     * Read one line of user input with a prompt, handling EOF and piped-input echo.
     * Returns {@code defaultValue} on EOF (Ctrl-D / end of piped input).
     */
    public String prompt(String promptText, String defaultValue) {
        // Stop QueueReader if active — it holds stty -echo, we need echo for the prompt.
        var r = activeReader;
        if (r != null) {
            r.stop();
            activeReader = null;
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        System.out.print(promptText);
        if (!scanner.hasNextLine()) return defaultValue;
        try {
            String line = scanner.nextLine();
            if (!interactive) System.out.println(line); // echo for piped input
            return line.trim();
        } catch (Exception e) {
            if (e instanceof java.util.NoSuchElementException || Thread.interrupted()) return defaultValue;
            throw e;
        }
    }

    /** Print a one-line greeting with a slim hint to discover commands. */
    public void greet(String line) {
        System.out.println(Ansi.bold(line));
        System.out.println(Ansi.dim("Type /help for commands, exit to quit."));
    }

    /** Run prompt - dispatch commands - chat, until exit/quit or EOF. */
    public void run(Chat chat) {
        while (!stopped) {
            Thread.interrupted(); // clear any pending interrupt from a previous turn's Ctrl-C

            // ── drain queued messages (typed while the last response was streaming) ──
            String queued = messageQueue.poll();
            if (queued != null) {
                System.out.println(Ansi.dim("\n[sending queued] ") + queued);
                if (!commands.handle(queued)) {
                    history.add(queued);
                    try { runTurn(chat, queued); } catch (Exception e) { handleTurnException(e); }
                }
                if (onResponse != null && !stopped) onResponse.run();
                resetLivePaneCount();
                continue;
            }

            // ── check for input injected by the schedule/continue tool ──
            var injected = injectedInput;
            if (injected != null) {
                injectedInput = null;
                String input = injected.get();
                if (input != null && !input.isBlank()) {
                    System.out.println(Ansi.dim("[scheduled] " + input));
                    history.add(input);
                    try { runTurn(chat, input); } catch (Exception e) { handleTurnException(e); }
                    if (onResponse != null) onResponse.run();
                    resetLivePaneCount();
                    continue;
                }
            }

            if (interactive) {
                printLivePane();
            } else {
                System.out.print(prompt.get());
            }
            if (!interactive && !scanner.hasNextLine()) break;
            String raw = readLogicalLine();
            if (raw == null) break; // EOF from line editor (Ctrl-D on empty line)
            String input = raw.trim();
            if (input.isEmpty()) continue;
            if (commands.handle(input)) {
                if (onResponse != null && !stopped) onResponse.run();
                resetLivePaneCount();
                continue;
            }
            history.add(input);
            if (interactive) {
                var reader = new QueueReader();
                activeReader = reader;
                var readerThread = Thread.ofVirtual().start(reader);
                try {
                    runTurn(chat, input);
                } catch (Exception e) {
                    handleTurnException(e);
                } finally {
                    reader.stop();
                    activeReader = null;
                    try { readerThread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            } else {
                try { runTurn(chat, input); } catch (Exception e) { handleTurnException(e); }
            }
            if (Thread.interrupted()) { System.out.println("\n[interrupted]"); resetLivePaneCount(); continue; }
            if (onResponse != null) onResponse.run();
            resetLivePaneCount();
        }
    }

    private void runTurn(Chat chat, String input) throws Exception {
        chat.chat(input);
    }

    private void handleTurnException(Exception e) {
        boolean isInterrupt = e instanceof InterruptedException
                || e instanceof java.io.InterruptedIOException
                || (e instanceof java.io.UncheckedIOException ue && ue.getCause() instanceof java.io.InterruptedIOException)
                || (e instanceof RuntimeException && e.getCause() instanceof InterruptedException);
        if (isInterrupt) {
            Thread.interrupted();
            System.out.println("\n[interrupted]");
            resetLivePaneCount();
            return;
        }
        throw e instanceof RuntimeException re ? re : new RuntimeException(e);
    }

    /** Raw-mode line editor: ↑/↓ history, Ctrl-R search, basic editing. */
    private final LineEditor lineEditor = new LineEditor();

    /**
     * Runs in a virtual thread while the assistant is responding.
     * Owns /dev/tty in raw mode, echoes keystrokes, and pushes completed lines onto
     * {@link #messageQueue}. Partially typed text when stopped is carried over as
     * {@link #lineEditor} prefill for the next prompt.
     */
    final class QueueReader implements Runnable {
        private volatile boolean running = true;
        private volatile Thread thread;

        void stop() {
            running = false;
            var t = thread;
            if (t != null) t.interrupt(); // wake immediately from sleep
        }

        @Override public void run() {
            thread = Thread.currentThread();
            var devTty = new java.io.File("/dev/tty");
            try (var tty = new java.io.FileInputStream(devTty)) {
                var out = System.out;
                boolean rawOk = runSttyQuiet(new String[]{"stty", "-icanon", "-echo"});
                try {
                    readLoop(tty, out);
                } finally {
                    if (rawOk) runStty(new String[]{"stty", "sane"});
                }
            } catch (Exception ignored) {}
        }

        private void readLoop(java.io.FileInputStream tty, java.io.PrintStream out) throws Exception {
            var buf = new StringBuilder();
            out.print("\n" + Ansi.dim("  [type to queue] "));
            out.flush();
            while (running) {
                if (tty.available() == 0) { Thread.sleep(20); continue; }
                int b = tty.read();
                if (b == -1 || !running) break;
                if (b == '\r' || b == '\n') {
                    out.write(new byte[]{'\r', '\n'});
                    String line = buf.toString().trim();
                    buf.setLength(0);
                    if (!line.isBlank()) {
                        messageQueue.addLast(line);
                        out.print(Ansi.dim("  [queued] ") + line + "\n");
                    }
                    out.print(Ansi.dim("  [type to queue] "));
                    out.flush();
                } else if (b == 127 || b == '\b') {     // Backspace
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        out.write(new byte[]{'\b', ' ', '\b'});
                        out.flush();
                    }
                } else if (b == 3) {                    // Ctrl-C — discard buffer
                    buf.setLength(0);
                    out.write(new byte[]{'\r', '\n'});
                    out.print(Ansi.dim("  [type to queue] "));
                    out.flush();
                } else if (b >= 32 && b < 127) {        // printable ASCII
                    buf.append((char) b);
                    out.write(b);
                    out.flush();
                }
            }
            if (buf.length() > 0) {
                out.write(new byte[]{'\r', '\n'});
                out.flush();
                lineEditor.setPrefill(buf.toString());
            } else {
                out.write(new byte[]{'\r', '\n'});
                out.flush();
            }
        }

        private boolean runSttyQuiet(String[] cmd) {
            try {
                var devTty = new java.io.File("/dev/tty");
                return new ProcessBuilder(cmd)
                        .redirectInput(ProcessBuilder.Redirect.from(devTty))
                        .redirectOutput(ProcessBuilder.Redirect.to(devTty))
                        .redirectError(ProcessBuilder.Redirect.to(devTty))
                        .start().waitFor() == 0;
            } catch (Exception e) { return false; }
        }
    }

    private String readLogicalLine() {
        if (interactive) {
            // Real TTY: use raw-mode line editor with history navigation.
            // Strip leading newlines — they're spacing, not part of the input line.
            String line = lineEditor.readLine(prompt.get().stripLeading().replace("\n", ""));
            if (line == null) return null; // EOF (Ctrl-D on empty line)
            var sb = new StringBuilder(line);
            while (sb.toString().endsWith("\\")) {
                sb.setLength(sb.length() - 1);
                System.out.print("  ... ");
                String cont = lineEditor.readLine("  ... ");
                if (cont == null) break;
                sb.append('\n').append(cont);
            }
            return sb.toString();
        }
        // Piped input: fall back to scanner
        String line = scanner.nextLine();
        System.out.println(line); // echo for logs
        var sb = new StringBuilder(line);
        while (sb.toString().endsWith("\\")) {
            sb.setLength(sb.length() - 1);
            System.out.print("  ... ");
            if (!scanner.hasNextLine()) break;
            line = scanner.nextLine();
            System.out.println(line);
            sb.append('\n').append(line);
        }
        return sb.toString();
    }

    final class LineEditor {
        private InputStream tty;
        private OutputStream out;
        private String currentPrompt = "";
        /** Visible (non-ANSI) length of currentPrompt, for cursor math. */
        private int promptVisibleLen = 0;
        /** Text to pre-populate on the next readLine() call (carry-over from QueueReader). */
        private volatile String prefill = null;

        LineEditor() {}

        /** Set text to pre-populate when the next readLine() starts. */
        void setPrefill(String text) { this.prefill = text; }

        /** Read a line using raw terminal mode. Returns null on EOF/Ctrl-D. */
        String readLine(String promptText) {
            currentPrompt = promptText;
            promptVisibleLen = stripAnsi(promptText).length();
            try {
                if (tty == null) {
                    tty = new java.io.FileInputStream("/dev/tty");
                    out = System.out;
                }
                boolean rawOk = tryStty(new String[]{"stty", "-icanon", "-echo"});
                try {
                    if (rawOk) return readRaw();
                    return readCooked();
                } finally {
                    if (rawOk) runStty(new String[]{"stty", "sane"});
                }
            } catch (Exception e) {
                return null;
            }
        }

        private static String stripAnsi(String s) {
            return s.replaceAll("\033\\[[^a-zA-Z]*[a-zA-Z]", "");
        }

        private boolean tryStty(String[] cmd) {
            try {
                var devTty = new java.io.File("/dev/tty");
                int exit = new ProcessBuilder(cmd)
                        .redirectInput(ProcessBuilder.Redirect.from(devTty))
                        .redirectOutput(ProcessBuilder.Redirect.to(devTty))
                        .redirectError(ProcessBuilder.Redirect.to(devTty))
                        .start().waitFor();
                return exit == 0;
            } catch (Exception e) { return false; }
        }

        /** Simple byte-by-byte cooked read (when stty is unavailable). */
        private String readCooked() throws Exception {
            var buf = new StringBuilder();
            int b;
            while ((b = tty.read()) != -1) {
                if (b == '\n' || b == '\r') {
                    out.write(new byte[]{'\r', '\n'});
                    out.flush();
                    return buf.toString();
                }
                if (b == 4 && buf.length() == 0) return null; // Ctrl-D
                if (b >= 32) buf.append((char) b);
            }
            return null;
        }

        private String readRaw() throws Exception {
            var buf = new StringBuilder();
            String pf = prefill;
            if (pf != null) { prefill = null; buf.append(pf); }
            int cursor = buf.length();
            int histIdx = -1;
            String savedLine = "";
            boolean searchMode = false;
            var searchBuf = new StringBuilder();

            // Enable bracketed paste mode so paste arrives as ESC[200~ ... ESC[201~
            out.write("\033[?2004h".getBytes(StandardCharsets.UTF_8));
            out.flush();

            redrawLine(buf, cursor);
            try {
            while (true) {
                // Poll so schedule-injected input can interrupt, but only on empty line.
                while (tty.available() == 0) {
                    if (buf.length() == 0 && injectedInput != null) {
                        out.write(new byte[]{'\r', '\n'});
                        out.flush();
                        return ""; // empty → outer loop picks up injectedInput
                    }
                    Thread.sleep(50);
                }
                int b = tty.read();
                if (b == -1) return null; // EOF

                // ── search mode ──────────────────────────────────────────────
                if (searchMode) {
                    if (b == 18) { // Ctrl-R: search further back
                        int from = histIdx < 0 ? history.size() : histIdx;
                        String found = history.searchBackward(searchBuf.toString(), from);
                        if (found != null) {
                            histIdx = history.entries().indexOf(found);
                            buf = new StringBuilder(found);
                            cursor = buf.length();
                        }
                        printSearchLine(searchBuf, buf);
                    } else if (b == 27 || b == 7) { // Esc / Ctrl-G: cancel
                        searchMode = false;
                        buf = new StringBuilder(savedLine);
                        cursor = buf.length();
                        histIdx = -1;
                        redrawLine(buf, cursor);
                    } else if (b == '\r' || b == '\n') { // Enter: accept
                        searchMode = false;
                        out.write(new byte[]{'\r', '\n'});
                        out.flush();
                        return buf.toString();
                    } else if (b == 127 || b == '\b') { // Backspace: shorten query
                        if (searchBuf.length() > 0) searchBuf.deleteCharAt(searchBuf.length() - 1);
                        String found = history.searchBackward(searchBuf.toString(), history.size());
                        if (found != null) {
                            histIdx = history.entries().indexOf(found);
                            buf = new StringBuilder(found);
                            cursor = buf.length();
                        }
                        printSearchLine(searchBuf, buf);
                    } else if (b >= 32 && b < 127) { // printable: extend query
                        searchBuf.append((char) b);
                        String found = history.searchBackward(searchBuf.toString(), history.size());
                        if (found != null) {
                            histIdx = history.entries().indexOf(found);
                            buf = new StringBuilder(found);
                            cursor = buf.length();
                        }
                        printSearchLine(searchBuf, buf);
                    } else { // any other key: exit search, fall through to normal handling
                        searchMode = false;
                        redrawLine(buf, cursor);
                    }
                    if (searchMode) continue; // stay in search unless we fell through
                }

                // ── normal mode ──────────────────────────────────────────────
                if (b == '\r' || b == '\n') {
                    out.write(new byte[]{'\r', '\n'});
                    out.flush();
                    return buf.toString();
                } else if (b == 3) {                    // Ctrl-C: discard line
                    Thread.interrupted();
                    out.write(new byte[]{'\r', '\n'});
                    out.flush();
                    return "";
                } else if (b == 4 && buf.length() == 0) { // Ctrl-D on empty = EOF
                    return null;
                } else if (b == 18) {                   // Ctrl-R: start search
                    searchMode = true;
                    searchBuf = new StringBuilder();
                    savedLine = buf.toString();
                    histIdx = -1;
                    printSearchLine(searchBuf, buf);
                } else if (b == 1) {                    // Ctrl-A: start of line
                    cursor = 0;
                    redrawLine(buf, cursor);
                } else if (b == 5) {                    // Ctrl-E: end of line
                    cursor = buf.length();
                    redrawLine(buf, cursor);
                } else if (b == 11) {                   // Ctrl-K: kill to end
                    buf.delete(cursor, buf.length());
                    redrawLine(buf, cursor);
                } else if (b == 21) {                   // Ctrl-U: kill to start
                    buf.delete(0, cursor);
                    cursor = 0;
                    redrawLine(buf, cursor);
                } else if (b == 127 || b == '\b') {     // Backspace / Ctrl-H
                    if (cursor > 0) {
                        buf.deleteCharAt(--cursor);
                        redrawLine(buf, cursor);
                    }
                } else if (b == 27) {                   // Escape sequence (arrows, bracketed paste, etc.)
                    // Peek for bracketed paste start: ESC [ 2 0 0 ~
                    long deadline = System.currentTimeMillis() + 50;
                    while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(1);
                    if (tty.available() == 0) { /* bare Esc — ignore */ continue; }
                    int b2 = tty.read();
                    if (b2 == '[') {
                        while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(1);
                        if (tty.available() == 0) continue;
                        int b3 = tty.read();
                        if (b3 == '2') {
                            // Could be 200~ (paste start) or 201~ (paste end) — read rest of sequence
                            var seq = new StringBuilder();
                            seq.append((char) b3);
                            long d2 = System.currentTimeMillis() + 50;
                            while (System.currentTimeMillis() < d2) {
                                while (tty.available() == 0 && System.currentTimeMillis() < d2) Thread.sleep(1);
                                if (tty.available() == 0) break;
                                int bx = tty.read();
                                seq.append((char) bx);
                                if (bx == '~') break;
                            }
                            if (seq.toString().equals("00~")) {
                                // Bracketed paste start — read until ESC[201~
                                var paste = new StringBuilder();
                                int prev = -1;
                                outer:
                                while (true) {
                                    while (tty.available() == 0) Thread.sleep(10);
                                    int pb = tty.read();
                                    if (pb == 27) {
                                        // Check for ESC[201~
                                        var end = new StringBuilder();
                                        long d3 = System.currentTimeMillis() + 100;
                                        while (end.length() < 5 && System.currentTimeMillis() < d3) {
                                            while (tty.available() == 0 && System.currentTimeMillis() < d3) Thread.sleep(1);
                                            if (tty.available() == 0) break;
                                            end.append((char) tty.read());
                                        }
                                        if (end.toString().equals("[201~")) break outer;
                                        // Not end marker — treat ESC + collected bytes as literal
                                        paste.append((char) pb).append(end);
                                    } else {
                                        paste.append((char) pb);
                                    }
                                }
                                // Insert paste text at cursor, replacing newlines with spaces
                                String pasted = paste.toString().replace('\n', ' ').replace('\r', ' ');
                                buf.insert(cursor, pasted);
                                cursor += pasted.length();
                                redrawLine(buf, cursor);
                                continue;
                            }
                            // Not a paste sequence — ignore unknown ESC[2xx~ sequences
                            continue;
                        }
                        // Delegate other ESC[ sequences to existing handler
                        // Re-assemble: we already consumed ESC [ b3, fake a re-entry
                        // by inlining the arrow/home/end logic for b3
                        if (b3 == 'A') {       // ↑
                            if (histIdx == -1) { savedLine = buf.toString(); histIdx = history.size(); }
                            if (histIdx > 0) { histIdx--; buf.replace(0, buf.length(), history.entries().get(histIdx)); cursor = buf.length(); }
                        } else if (b3 == 'B') { // ↓
                            if (histIdx >= 0 && histIdx < history.size() - 1) { histIdx++; buf.replace(0, buf.length(), history.entries().get(histIdx)); cursor = buf.length(); }
                            else if (histIdx >= history.size() - 1) { histIdx = -1; buf.replace(0, buf.length(), savedLine); cursor = buf.length(); }
                        } else if (b3 == 'C') { if (cursor < buf.length()) cursor++; }
                        else if (b3 == 'D') { if (cursor > 0) cursor--; }
                        else if (b3 == 'H') { cursor = 0; }
                        else if (b3 == 'F') { cursor = buf.length(); }
                        else if (b3 == '3') { consumeUntilTilde(deadline); if (cursor < buf.length()) buf.deleteCharAt(cursor); }
                        else if (b3 == '4') { consumeUntilTilde(deadline); cursor = buf.length(); }
                        else if (b3 == '1') {
                            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                            if (tty.available() > 0) {
                                int b4 = tty.read();
                                if (b4 == '~') { cursor = 0; }
                                else if (b4 == ';') {
                                    while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                                    if (tty.available() > 0) tty.read();
                                    while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                                    if (tty.available() > 0) { int b6 = tty.read(); if (b6 == 'C') cursor = wordForward(buf, cursor); else if (b6 == 'D') cursor = wordBackward(buf, cursor); }
                                }
                            }
                        }
                        redrawLine(buf, cursor);
                    } else if (b2 == 'b') { cursor = wordBackward(buf, cursor); redrawLine(buf, cursor); }
                    else if (b2 == 'f') { cursor = wordForward(buf, cursor); redrawLine(buf, cursor); }
                } else if (b >= 32) {                   // printable character
                    buf.insert(cursor++, (char) b);
                    redrawLine(buf, cursor);
                }
            }
            } finally {
                // Always disable bracketed paste mode on exit
                out.write("\033[?2004l".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        /**
         * Read and apply one escape sequence. Returns [newCursor, newHistIdx, savedLineUpdated].
         * Mutates buf in-place for history navigation.
         */
        private int[] handleEscape(StringBuilder buf, int cursor, int histIdx, String savedLine)
                throws Exception {
            long deadline = System.currentTimeMillis() + 50;
            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(1);
            if (tty.available() == 0) return new int[]{cursor, histIdx, 0}; // bare Esc

            int b2 = tty.read();
            if (b2 == '[') {
                while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(1);
                if (tty.available() == 0) return new int[]{cursor, histIdx, 0};
                int b3 = tty.read();

                if (b3 == 'A') {       // ↑ older history
                    if (histIdx == -1) histIdx = history.size();
                    if (histIdx > 0) {
                        histIdx--;
                        buf.replace(0, buf.length(), history.entries().get(histIdx));
                        cursor = buf.length();
                    }
                    return new int[]{cursor, histIdx, 1};
                } else if (b3 == 'B') { // ↓ newer history / restore
                    if (histIdx >= 0 && histIdx < history.size() - 1) {
                        histIdx++;
                        buf.replace(0, buf.length(), history.entries().get(histIdx));
                        cursor = buf.length();
                    } else if (histIdx >= history.size() - 1) {
                        histIdx = -1;
                        buf.replace(0, buf.length(), savedLine);
                        cursor = buf.length();
                    }
                    return new int[]{cursor, histIdx, 0};
                } else if (b3 == 'C') { // → right
                    if (cursor < buf.length()) cursor++;
                } else if (b3 == 'D') { // ← left
                    if (cursor > 0) cursor--;
                } else if (b3 == 'H') { // Home (ESC [ H)
                    cursor = 0;
                } else if (b3 == 'F') { // End (ESC [ F)
                    cursor = buf.length();
                } else if (b3 == '3') { // Delete (ESC [ 3 ~)
                    consumeUntilTilde(deadline);
                    if (cursor < buf.length()) buf.deleteCharAt(cursor);
                } else if (b3 == '4') { // End alternate (ESC [ 4 ~)
                    consumeUntilTilde(deadline);
                    cursor = buf.length();
                } else if (b3 == '1') { // ESC [ 1 ... — Home or Ctrl+Arrow
                    while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                    if (tty.available() > 0) {
                        int b4 = tty.read();
                        if (b4 == '~') {          // ESC [ 1 ~ — Home (alternate)
                            cursor = 0;
                        } else if (b4 == ';') {   // ESC [ 1 ; 5 C/D — Ctrl+Arrow
                            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                            if (tty.available() > 0) tty.read(); // consume modifier digit (5=Ctrl)
                            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                            if (tty.available() > 0) {
                                int b6 = tty.read();
                                if (b6 == 'C')      cursor = wordForward(buf, cursor);
                                else if (b6 == 'D') cursor = wordBackward(buf, cursor);
                            }
                        }
                    }
                }
            } else if (b2 == 'b') {    // Alt+← word backward
                cursor = wordBackward(buf, cursor);
            } else if (b2 == 'f') {    // Alt+→ word forward
                cursor = wordForward(buf, cursor);
            }
            return new int[]{cursor, histIdx, 0};
        }

        private void consumeUntilTilde(long deadline) throws Exception {
            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
            if (tty.available() > 0) tty.read(); // consume '~'
        }

        private int wordForward(StringBuilder buf, int cursor) {
            while (cursor < buf.length() && buf.charAt(cursor) == ' ') cursor++;
            while (cursor < buf.length() && buf.charAt(cursor) != ' ') cursor++;
            return cursor;
        }

        private int wordBackward(StringBuilder buf, int cursor) {
            while (cursor > 0 && buf.charAt(cursor - 1) == ' ') cursor--;
            while (cursor > 0 && buf.charAt(cursor - 1) != ' ') cursor--;
            return cursor;
        }

        /** Redraw prompt + buffer, placing the cursor at `cursor` offset within the buffer. */
        private void redrawLine(StringBuilder buf, int cursor) throws IOException {
            // \r to column 0, erase to EOL, reprint prompt + buffer, then reposition cursor
            out.write('\r');
            out.write(("\033[K" + currentPrompt + buf).getBytes(StandardCharsets.UTF_8));
            int charsAfterCursor = buf.length() - cursor;
            if (charsAfterCursor > 0) {
                out.write(("\033[" + charsAfterCursor + "D").getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }

        private void printSearchLine(StringBuilder search, StringBuilder match) throws IOException {
            String display = "(reverse-i-search)`" + search + "': " + match;
            out.write('\r');
            out.write(("\033[K" + display).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for wiring together a Repl with an LLM client and tools.
     * <pre>{@code
     * var builder = new Repl.Builder("\nYou: ", scanner, messages);
     * var client  = new LLMClient(baseUrl, model, builder.tokenCallback);
     * var repl    = builder.build();
     * }</pre>
     */
    public static final class Builder {
        private final Repl repl;
        private final List<Map<String, Object>> messages;

        /** Pass to {@code LLMClient} as the token callback — prints each token. */
        public final Consumer<String> tokenCallback;

        private Runnable pane = null;

        public Builder(String prompt, Scanner scanner) {
            this(prompt, scanner, null);
        }

        public Builder(String prompt, Scanner scanner, List<Map<String, Object>> messages) {
            this.repl = new Repl(prompt, scanner);
            this.messages = messages;
            this.tokenCallback = System.out::print;
        }

        public Builder prompt(Supplier<String> prompt) {
            repl.setPrompt(prompt);
            return this;
        }

        public Builder on(String name, String description, Commands.Handler handler, String... aliases) {
            repl.commands().on(name, description, handler, aliases);
            return this;
        }

        /** Start a sub-command block; each .on() registers one sub-command; .end() returns this Builder. */
        public SubBuilder<Builder> sub(String name, String description) {
            return new SubBuilder<>(name, description, h -> on(name, description, h));
        }

        /**
         * Wire a ToolSupport so the pane rerenders for todo/plan tools.
         */
        public Builder withTools(ToolCallListener toolSupport) {
            if (pane != null) {
                toolSupport.setOnToolCall((name, result) -> {
                    if (name.startsWith("todo-") || name.equals("update-plan")) pane.run();
                });
            }
            return this;
        }

        /** Register a pane supplier; stores the pane Runnable for use by {@link #withTools}. */
        public Builder showPane(Supplier<String> paneSupplier) {
            this.pane = repl.showPane(paneSupplier);
            return this;
        }

        /**
         * Register a live pane that stays above the prompt line in interactive mode.
         * In non-interactive mode falls back to printing after each response (via onResponse).
         * Stores the mid-turn Runnable for {@link #withTools} so tool-call updates still print.
         */
        public Builder setLivePane(Supplier<String> paneSupplier) {
            repl.setLivePane(paneSupplier);
            // Mid-turn print (during tool loops): print below tool output as before.
            // printLivePane() handles prompt-time display; onResponse is skipped for interactive.
            Runnable midTurn = () -> { var s = paneSupplier.get(); if (s != null && !s.isBlank()) System.out.println(s); };
            this.pane = midTurn;
            if (!repl.interactive) {
                // Non-interactive: wire as a regular onResponse pane.
                repl.onResponse = midTurn;
            }
            return this;
        }

        public Repl build() {
            return repl;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean hasTty() {
        if (System.console() == null) return false;
        try (var ignored = new java.io.FileInputStream("/dev/tty")) {
            return true;
        } catch (Exception e) { return false; }
    }

    private static void runStty(String[] cmd) {
        try {
            var devTty = new java.io.File("/dev/tty");
            new ProcessBuilder(cmd)
                    .redirectInput(ProcessBuilder.Redirect.from(devTty))
                    .redirectOutput(ProcessBuilder.Redirect.to(devTty))
                    .redirectError(ProcessBuilder.Redirect.to(devTty))
                    .start().waitFor();
        } catch (Exception ignored) {}
    }

    // ── History ───────────────────────────────────────────────────────────────

    static final Path HISTORY_FILE = Path.of(System.getProperty("user.home"), ".config", "tiny-llm", "history");

    /** Per-session input history (non-command turns only). File-backed only on a real TTY. */
    static final class History {
        private final List<String> entries = new ArrayList<>();
        private final boolean persist;

        History(boolean persist) {
            this.persist = persist;
            if (persist) load();
        }

        private void load() {
            try {
                if (Files.exists(HISTORY_FILE)) {
                    Files.readAllLines(HISTORY_FILE, StandardCharsets.UTF_8).forEach(line -> {
                        if (!line.isBlank()) entries.add(line.replace(" ↵ ", "\n"));
                    });
                }
            } catch (IOException ignored) {}
        }

        void add(String input) {
            if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(input)) return;
            entries.add(input);
            if (persist) append(input);
        }

        private void append(String input) {
            try {
                Files.createDirectories(HISTORY_FILE.getParent());
                String line = input.replace("\n", " ↵ ") + "\n";
                Files.writeString(HISTORY_FILE, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }

        List<String> entries() { return Collections.unmodifiableList(entries); }

        void print() {
            if (entries.isEmpty()) { System.out.println("(no history)"); return; }
            for (int i = 0; i < entries.size(); i++) {
                String line = entries.get(i).replace("\n", " ↵ ");
                System.out.printf("  %3d  %s%n", i + 1, line);
            }
        }

        String last() { return entries.isEmpty() ? null : entries.get(entries.size() - 1); }

        int size() { return entries.size(); }

        /**
         * Find the most recent entry containing {@code query}, starting backwards
         * from {@code beforeIndex} (exclusive). Returns null if not found.
         */
        String searchBackward(String query, int beforeIndex) {
            if (query.isEmpty()) return entries.isEmpty() ? null : entries.get(entries.size() - 1);
            int start = Math.min(beforeIndex, entries.size()) - 1;
            for (int i = start; i >= 0; i--) {
                if (entries.get(i).contains(query)) return entries.get(i);
            }
            return null;
        }
    }

    // ── SubBuilder ────────────────────────────────────────────────────────────

    /**
     * Fluent sub-command builder returned by {@link Builder#sub}.
     * <pre>{@code
     * builder
     *     .sub("todo", "manage TODOs")
     *         .on("add",  "<desc>", desc  -> addTodo(desc))
     *         .on("done", "<id>",   idStr -> markDone(idStr))
     *     .end()
     * }</pre>
     * Unknown sub-commands print their own help instead of hitting the LLM.
     */
    public static final class SubBuilder<T> {
        private final String name;
        private final Function<Commands.Handler, T> register;
        private final LinkedHashMap<String, Commands.Handler> subs = new LinkedHashMap<>();
        private final List<String> docs = new ArrayList<>();

        SubBuilder(String name, String description, Function<Commands.Handler, T> register) {
            this.name = name;
            this.register = register;
        }

        /** Sub-command whose argument is a raw string. */
        public SubBuilder<T> on(String sub, String doc, Commands.Handler handler) {
            subs.put(sub.toLowerCase(), handler);
            docs.add("/" + name + " " + sub + "  — " + doc);
            return this;
        }

        /** Sub-command whose argument is parsed as an int (bad input prints a usage hint). */
        public SubBuilder<T> on(String sub, String doc, IntConsumer handler) {
            subs.put(sub.toLowerCase(), args -> {
                try { handler.accept(Integer.parseInt(args.trim())); }
                catch (NumberFormatException e) { System.out.println("Expected a number for /" + name + " " + sub); }
            });
            docs.add("/" + name + " " + sub + "  — " + doc);
            return this;
        }

        /** Sub-command that takes no argument (args are ignored). */
        public SubBuilder<T> on(String sub, String doc, Runnable handler) {
            subs.put(sub.toLowerCase(), args -> handler.run());
            docs.add("/" + name + " " + sub + "  — " + doc);
            return this;
        }

        /** Seal the sub-command block and return the parent builder. */
        public T end() {
            return end(null);
        }

        /** Seal the sub-command block, calling {@code onEmpty} when invoked with no arguments. */
        public T end(Runnable onEmpty) {
            String help = String.join("\n", docs);
            return register.apply(args -> {
                if (args.isBlank()) {
                    if (onEmpty != null) onEmpty.run(); else System.out.println(help);
                    return;
                }
                int sp = args.indexOf(' ');
                String sub = (sp < 0 ? args : args.substring(0, sp)).toLowerCase();
                String rest = sp < 0 ? "" : args.substring(sp + 1).trim();
                var h = subs.get(sub);
                if (h == null) {
                    System.out.println("Unknown: /" + name + " " + sub + "\n" + help);
                    return;
                }
                h.handle(rest);
            });
        }
    }
}
