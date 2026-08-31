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
        this.interactive = isStdinScanner(scanner) && new java.io.File("/dev/tty").exists();
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
    void resetLivePaneCount() { lastPaneLines = 0; }

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
        System.out.print(promptText);
        if (!scanner.hasNextLine()) return defaultValue;
        String line = scanner.nextLine();
        if (!interactive) System.out.println(line); // echo for piped input
        return line.trim();
    }

    /** Print a one-line greeting with a slim hint to discover commands. */
    public void greet(String line) {
        System.out.println(Ansi.bold(line));
        System.out.println(Ansi.dim("Type /help for commands, exit to quit."));
    }

    /** Run prompt - dispatch commands - chat, until exit/quit or EOF. */
    public void run(Chat chat) {
        while (!stopped) {
            if (interactive) {
                printLivePane(); // pane above prompt; readLogicalLine/lineEditor prints the prompt itself
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
            try {
                chat.chat(input);
            } catch (java.io.UncheckedIOException e) {
                if (e.getCause() instanceof java.io.InterruptedIOException) {
                    Thread.interrupted();
                    System.out.println("\n[interrupted]");
                    resetLivePaneCount();
                    continue;
                }
                throw e;
            } catch (Exception e) {
                if (e instanceof java.io.InterruptedIOException) {
                    Thread.interrupted();
                    System.out.println("\n[interrupted]");
                    resetLivePaneCount();
                    continue;
                }
                throw new RuntimeException(e);
            }
            if (Thread.interrupted()) { System.out.println("\n[interrupted]"); resetLivePaneCount(); continue; }
            if (onResponse != null) onResponse.run();
            resetLivePaneCount(); // response text scrolled the terminal; next cycle repaints fresh
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

    /** Raw-mode line editor: ↑/↓ history, Ctrl-R search, basic editing. */
    private final LineEditor lineEditor = new LineEditor();

    final class LineEditor {
        private InputStream tty;
        private OutputStream out;
        private String currentPrompt = "";
        /** Visible (non-ANSI) length of currentPrompt, for cursor math. */
        private int promptVisibleLen = 0;

        LineEditor() {}

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
                if (b == '\n' || b == '\r') { out.write(new byte[]{'\r', '\n'}); out.flush(); return buf.toString(); }
                if (b == 4 && buf.length() == 0) return null; // Ctrl-D
                if (b >= 32) buf.append((char) b);
            }
            return null;
        }

        private String readRaw() throws Exception {
            var buf = new StringBuilder();
            int cursor = 0;          // insertion point in buf
            int histIdx = -1;        // -1 = not browsing history
            String savedLine = "";   // line typed before ↑ pressed
            boolean searchMode = false;
            var searchBuf = new StringBuilder();

            while (true) {
                int b = tty.read();
                if (b == -1) return null;  // EOF

                if (searchMode) {
                    if (b == 18) { // Ctrl-R again → search backwards further
                        String found = history.searchBackward(searchBuf.toString(),
                                histIdx < 0 ? history.size() : histIdx);
                        if (found != null) {
                            histIdx = history.entries().indexOf(found);
                            buf = new StringBuilder(found);
                            cursor = buf.length();
                        }
                        printSearchLine(searchBuf, buf);
                        continue;
                    } else if (b == 27 || b == 7) { // Esc or Ctrl-G: cancel search
                        searchMode = false;
                        buf = new StringBuilder(savedLine); cursor = buf.length(); histIdx = -1;
                        redrawLine(buf, cursor);
                        continue;
                    } else if (b == 13 || b == 10) { // Enter: accept
                        searchMode = false;
                        out.write(new byte[]{'\r', '\n'});
                        out.flush();
                        return buf.toString();
                    } else if (b == 127 || b == 8) { // Backspace in search
                        if (searchBuf.length() > 0) searchBuf.deleteCharAt(searchBuf.length() - 1);
                        String found = history.searchBackward(searchBuf.toString(), history.size());
                        if (found != null) { histIdx = history.entries().indexOf(found); buf = new StringBuilder(found); cursor = buf.length(); }
                        printSearchLine(searchBuf, buf);
                        continue;
                    } else if (b >= 32 && b < 127) { // printable: extend search query
                        searchBuf.append((char) b);
                        String found = history.searchBackward(searchBuf.toString(), history.size());
                        if (found != null) { histIdx = history.entries().indexOf(found); buf = new StringBuilder(found); cursor = buf.length(); }
                        printSearchLine(searchBuf, buf);
                        continue;
                    } else { // any other control key: exit search, fall through
                        searchMode = false;
                        redrawLine(buf, cursor);
                        // fall through to handle the key normally
                    }
                }

                if (b == 13 || b == 10) {         // Enter — \r\n needed in raw mode
                    out.write(new byte[]{'\r', '\n'});
                    out.flush();
                    return buf.toString();
                } else if (b == 3) {               // Ctrl-C
                    out.write(new byte[]{'\r', '\n'});
                    out.flush();
                    return "";
                } else if (b == 4 && buf.length() == 0) {  // Ctrl-D on empty line = EOF
                    return null;
                } else if (b == 18) {              // Ctrl-R: start reverse search
                    searchMode = true;
                    searchBuf = new StringBuilder();
                    savedLine = buf.toString();
                    histIdx = -1;
                    printSearchLine(searchBuf, buf);
                } else if (b == 1) {               // Ctrl-A: start of line
                    cursor = 0; redrawLine(buf, cursor);
                } else if (b == 5) {               // Ctrl-E: end of line
                    cursor = buf.length(); redrawLine(buf, cursor);
                } else if (b == 11) {              // Ctrl-K: kill to end
                    buf.delete(cursor, buf.length()); redrawLine(buf, cursor);
                } else if (b == 21) {              // Ctrl-U: kill to start
                    buf.delete(0, cursor); cursor = 0; redrawLine(buf, cursor);
                } else if (b == 127 || b == 8) {   // Backspace / Ctrl-H
                    if (cursor > 0) { buf.deleteCharAt(--cursor); redrawLine(buf, cursor); }
                } else if (b == 27) {              // Escape sequence
                    long deadline = System.currentTimeMillis() + 50;
                    while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(1);
                    if (tty.available() == 0) continue; // bare Esc — ignore
                    int b2 = tty.read();
                    if (b2 == '[') {
                        deadline = System.currentTimeMillis() + 50;
                        while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(1);
                        if (tty.available() == 0) continue;
                        int b3 = tty.read();
                        if (b3 == 'A') {           // ↑ — older history
                            if (histIdx == -1) { savedLine = buf.toString(); histIdx = history.size(); }
                            if (histIdx > 0) {
                                histIdx--;
                                buf = new StringBuilder(history.entries().get(histIdx));
                                cursor = buf.length();
                                redrawLine(buf, cursor);
                            }
                        } else if (b3 == 'B') {    // ↓ — newer history / restore
                            if (histIdx >= 0 && histIdx < history.size() - 1) {
                                histIdx++;
                                buf = new StringBuilder(history.entries().get(histIdx));
                                cursor = buf.length();
                                redrawLine(buf, cursor);
                            } else if (histIdx == history.size() - 1 || histIdx == history.size()) {
                                histIdx = -1;
                                buf = new StringBuilder(savedLine);
                                cursor = buf.length();
                                redrawLine(buf, cursor);
                            }
                        } else if (b3 == 'C') {    // → (right arrow)
                            if (cursor < buf.length()) { cursor++; redrawLine(buf, cursor); }
                        } else if (b3 == 'D') {    // ← (left arrow)
                            if (cursor > 0) { cursor--; redrawLine(buf, cursor); }
                        } else if (b3 == 'H') {    // Home (ESC [ H)
                            cursor = 0; redrawLine(buf, cursor);
                        } else if (b3 == 'F') {    // End (ESC [ F)
                            cursor = buf.length(); redrawLine(buf, cursor);
                        } else if (b3 == '3') {    // Delete key (ESC [ 3 ~)
                            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                            if (tty.available() > 0) tty.read(); // consume '~'
                            if (cursor < buf.length()) { buf.deleteCharAt(cursor); redrawLine(buf, cursor); }
                        } else if (b3 == '1') {    // ESC [ 1 ... — Home or Ctrl+Arrow
                            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                            if (tty.available() > 0) {
                                int b4 = tty.read();
                                if (b4 == '~') {   // ESC [ 1 ~ — Home (alternate)
                                    cursor = 0; redrawLine(buf, cursor);
                                } else if (b4 == ';') { // ESC [ 1 ; ... — modifier sequences
                                    while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                                    if (tty.available() > 0) tty.read(); // consume modifier digit (5=Ctrl)
                                    while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                                    if (tty.available() > 0) {
                                        int b6 = tty.read();
                                        if (b6 == 'C') {       // Ctrl+→: skip word forward
                                            while (cursor < buf.length() && buf.charAt(cursor) == ' ') cursor++;
                                            while (cursor < buf.length() && buf.charAt(cursor) != ' ') cursor++;
                                            redrawLine(buf, cursor);
                                        } else if (b6 == 'D') { // Ctrl+←: skip word backward
                                            while (cursor > 0 && buf.charAt(cursor - 1) == ' ') cursor--;
                                            while (cursor > 0 && buf.charAt(cursor - 1) != ' ') cursor--;
                                            redrawLine(buf, cursor);
                                        }
                                    }
                                }
                            }
                        } else if (b3 == '4') {    // ESC [ 4 ~ — End (alternate)
                            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                            if (tty.available() > 0) tty.read(); // consume '~'
                            cursor = buf.length(); redrawLine(buf, cursor);
                        }
                    } else if (b2 == 'b') {        // Alt+← (word backward, xterm)
                        while (cursor > 0 && buf.charAt(cursor - 1) == ' ') cursor--;
                        while (cursor > 0 && buf.charAt(cursor - 1) != ' ') cursor--;
                        redrawLine(buf, cursor);
                    } else if (b2 == 'f') {        // Alt+→ (word forward, xterm)
                        while (cursor < buf.length() && buf.charAt(cursor) == ' ') cursor++;
                        while (cursor < buf.length() && buf.charAt(cursor) != ' ') cursor++;
                        redrawLine(buf, cursor);
                    }
                } else if (b >= 32) {              // printable character
                    buf.insert(cursor++, (char) b);
                    redrawLine(buf, cursor);
                }
            }
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

    private static boolean isStdinScanner(Scanner scanner) {
        try {
            var f = Scanner.class.getDeclaredField("source");
            f.setAccessible(true);
            return f.get(scanner) == System.in;
        } catch (Exception ignored) {
            return false;
        }
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
