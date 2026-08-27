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
import me.bechberger.demo.ToolSupport;


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
 * <p>
 * Sidebar support is completely encapsulated in {@link Builder}.  Callers only need:
 * <pre>{@code
 * var builder = new Repl.Builder("\nYou: ", scanner, messages);
 * var client  = new LLMClient(baseUrl, model, builder.tokenCallback);
 * if (verbose) builder.showSidebar(client::lastUsage);
 * var repl = builder.build();
 * }</pre>
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
    private Runnable prePrompt;
    private boolean stopped = false;
    private volatile String pendingInput = null;  // injected by sidebar rerun; bypasses stdin
    /** True only when running interactively on a real TTY with stdin as input source. */
    final boolean interactive;
    final History history;
    /** Sidebar reference for raw/cooked mode toggling during run loop. */
    Sidebar sidebar = null;

    /**
     * @param prompt  printed before every input line, e.g. "\nYou: "
     * @param scanner shared System.in scanner — pass one in so sibling prompts
     *                (confirmations, plan acceptance) don't fight over the stream
     */
    public Repl(String prompt, Scanner scanner) {
        this.scanner = scanner;
        this.interactive = System.console() != null && isStdinScanner(scanner);
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
        String answer = scanner.nextLine().trim();
        if (!interactive) System.out.println(answer); // echo for piped input
        return answer;
    }

    /** Print a one-line greeting with a slim hint to discover commands. */
    public void greet(String line) {
        System.out.println(Ansi.bold(line));
        System.out.println(Ansi.dim("Type /help for commands, exit to quit."));
    }

    /** Run prompt - dispatch commands - chat, until exit/quit or EOF. */
    public void run(Chat chat) {
        while (!stopped) {
            if (prePrompt != null) prePrompt.run();
            // sidebar rerun injects input directly, bypassing stdin
            String injected = pendingInput;
            if (injected != null) {
                pendingInput = null;
                System.out.println(prompt.get() + Ansi.dim("[rerun] ") + injected.replace("\n", " ↵ "));
                history.add(injected);
                if (sidebar != null) { sidebar.setCookedMode(false); sidebar.resetAnchor(); } // raw mode during chat
                try {
                    chat.chat(injected);
                } catch (java.io.UncheckedIOException e) {
                    if (e.getCause() instanceof java.io.InterruptedIOException) {
                        Thread.interrupted(); System.out.println("\n[interrupted]"); continue;
                    }
                    throw e;
                } catch (Exception e) {
                    if (e instanceof java.io.InterruptedIOException) {
                        Thread.interrupted(); System.out.println("\n[interrupted]"); continue;
                    }
                    throw new RuntimeException(e);
                }
                if (Thread.interrupted()) { System.out.println("\n[interrupted]"); continue; }
                if (onResponse != null) onResponse.run();
                continue;
            }
            System.out.print(prompt.get());
            // On a real interactive TTY the line editor handles raw input; otherwise fall back to scanner
            if (!interactive && !scanner.hasNextLine()) break;
            String raw = readLogicalLine();
            if (raw == null) break; // EOF from line editor (Ctrl-D on empty line)
            String input = raw.trim();
            if (input.isEmpty()) continue;
            if (commands.handle(input)) { if (onResponse != null && !stopped) onResponse.run(); continue; }
            history.add(input);
            if (sidebar != null) { sidebar.setCookedMode(false); sidebar.resetAnchor(); } // raw mode during chat
            try {
                chat.chat(input);
            } catch (java.io.UncheckedIOException e) {
                if (e.getCause() instanceof java.io.InterruptedIOException) {
                    Thread.interrupted();
                    System.out.println("\n[interrupted]");
                    continue;
                }
                throw e;
            } catch (Exception e) {
                if (e instanceof java.io.InterruptedIOException) {
                    Thread.interrupted();
                    System.out.println("\n[interrupted]");
                    continue;
                }
                throw new RuntimeException(e);
            }
            if (Thread.interrupted()) { System.out.println("\n[interrupted]"); continue; }
            if (onResponse != null) onResponse.run();
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
                int exit = new ProcessBuilder(cmd).inheritIO()
                        .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/tty")))
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
                    long deadline = System.currentTimeMillis() + 30;
                    while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                    if (tty.available() == 0) continue; // bare Esc — ignore
                    int b2 = tty.read();
                    if (b2 == '[') {
                        while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
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
                        } else if (b3 == 'C') {    // →
                            if (cursor < buf.length()) { cursor++; redrawLine(buf, cursor); }
                        } else if (b3 == 'D') {    // ←
                            if (cursor > 0) { cursor--; redrawLine(buf, cursor); }
                        } else if (b3 == '3') {    // Delete key (ESC [ 3 ~)
                            while (tty.available() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
                            if (tty.available() > 0) tty.read(); // consume '~'
                            if (cursor < buf.length()) { buf.deleteCharAt(cursor); redrawLine(buf, cursor); }
                        }
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
     * Fluent builder.  Pass the live {@code messages} list to unlock the sidebar:
     * <pre>{@code
     * var builder = new Repl.Builder("\nYou: ", scanner, messages);
     * var client  = new LLMClient(baseUrl, model, builder.tokenCallback);
     * if (verbose) builder.showSidebar(client::lastUsage);
     * var repl    = builder.build();
     * }</pre>
     * {@link #tokenCallback} is always safe to pass to {@code LLMClient}; when the
     * sidebar is inactive it is simply {@code System.out::print}.
     */
    public static final class Builder {
        private final Repl repl;
        private final List<Map<String, Object>> messages;
        private Sidebar sidebar;

        /**
         * Pass to {@code LLMClient} as the token callback.  Prints each token and,
         * when the sidebar is active, pauses mid-stream on user request.
         */
        public final Consumer<String> tokenCallback;

        public Builder(String prompt, Scanner scanner) {
            this(prompt, scanner, null);
        }

        public Builder(String prompt, Scanner scanner, List<Map<String, Object>> messages) {
            this.repl = new Repl(prompt, scanner);
            this.messages = messages;
            this.tokenCallback = token -> {
                System.out.print(token);
                if (sidebar != null) sidebar.checkPause();
            };
        }

        /**
         * Enable the live message sidebar.  Hooks into {@code onResponse} for
         * automatic redraws; the {@code usageSupplier} is called after each response
         * to keep the token-usage footer current.  No-op when {@code messages} was
         * not provided or the terminal is too narrow.
         *
         * @param usageSupplier returns the latest usage, or {@code null}
         */
        public Builder showSidebar(Supplier<LLMClient.TokenUsage> usageSupplier) {
            return showSidebar(usageSupplier, () -> 0);
        }

        /**
         * Enable the live sidebar with a context-window size supplier for the
         * progress-bar footer.  The supplier is called once after each response;
         * pass {@code client::lastContextWindow} or a cached value.
         */
        public Builder showSidebar(Supplier<LLMClient.TokenUsage> usageSupplier, java.util.function.IntSupplier contextWindowSupplier) {
            if (messages == null) return this;
            sidebar = new Sidebar(messages);
            if (!sidebar.isUsable()) { sidebar = null; return this; }
            sidebar.clearScreen();
            sidebar.installColumnClamp();

            final Sidebar sb = sidebar;
            repl.sidebar = sb;  // give run() loop access for cooked/raw toggling
            var prev = repl.onResponse;
            repl.onResponse = () -> {
                if (prev != null) prev.run();
                if (usageSupplier != null) {
                    var u = usageSupplier.get();
                    if (u != null) sb.updateUsage(u.promptTokens(), u.completionTokens(),
                            contextWindowSupplier.getAsInt());
                }
                sb.redraw();
            };
            repl.prePrompt = () -> {
                // switch to cooked mode so the "You:" prompt shows typed characters
                sb.setCookedMode(true);
                if (sb.isEditRequested())   sb.runEdit(repl.scanner);
                if (sb.isInsertRequested()) sb.runInsert(repl.scanner);
                String rerun = sb.hasPendingRerun() ? sb.takePendingRerun() : null;
                if (rerun != null) repl.pendingInput = rerun;
                sb.redraw();
            };
            startKeyThread(sidebar);
            return this;
        }

        /** True when the sidebar was requested and is usable (real TTY, wide enough). */
        public boolean isSidebarActive() { return sidebar != null; }

        /**
         * Trigger a sidebar redraw — use from tool callbacks for mid-turn updates.
         * No-op when the sidebar is inactive.
         */
        public void redrawSidebar() {
            if (sidebar != null) sidebar.redraw();
        }

        public Builder prompt(Supplier<String> prompt) {
            repl.setPrompt(prompt);
            return this;
        }

        public Builder on(String name, String description, Commands.Handler handler, String... aliases) {
            repl.commands().on(name, description, handler, aliases);
            return this;
        }

        /**
         * Wire a {@link ToolSupport} so the sidebar redraws on every tool call.
         * Must be called before {@link #build()}.
         */
        public Builder withTools(ToolSupport toolSupport) {
            toolSupport.setOnToolCall((name, result) -> redrawSidebar());
            return this;
        }

        /** Start a sub-command block; each .on() registers one sub-command; .end() returns this Builder. */
        public SubBuilder<Builder> sub(String name, String description) {
            return new SubBuilder<>(name, description, h -> on(name, description, h));
        }

        /** Register a pane supplier; returns a PaneBuilder carrying the pane Runnable. */
        public PaneBuilder showPane(Supplier<String> pane) {
            return new PaneBuilder(repl, this, pane);
        }

        public Repl build() {
            return repl;
        }
    }

    /**
     * Continuation of {@link Builder} after {@link Builder#showPane} — carries the
     * pane {@link Runnable} so callers can wire it into tool callbacks.
     */
    public static final class PaneBuilder {
        private final Repl repl;
        private final Builder builder;
        public final Runnable pane;

        private PaneBuilder(Repl repl, Builder builder, Supplier<String> paneSupplier) {
            this.repl = repl;
            this.builder = builder;
            this.pane = repl.showPane(paneSupplier);
        }

        public PaneBuilder on(String name, String description, Commands.Handler handler, String... aliases) {
            repl.commands().on(name, description, handler, aliases);
            return this;
        }

        /** Start a sub-command block; .end() returns this PaneBuilder. */
        public SubBuilder<PaneBuilder> sub(String name, String description) {
            return new SubBuilder<>(name, description, h -> on(name, description, h));
        }

        public PaneBuilder prompt(Supplier<String> prompt) {
            repl.setPrompt(prompt);
            return this;
        }

        /** Enable the live sidebar — delegates to {@link Builder#showSidebar}. */
        public PaneBuilder showSidebar(Supplier<LLMClient.TokenUsage> usageSupplier) {
            builder.showSidebar(usageSupplier);
            return this;
        }

        /** Enable the live sidebar with context window — delegates to {@link Builder#showSidebar}. */
        public PaneBuilder showSidebar(Supplier<LLMClient.TokenUsage> usageSupplier, java.util.function.IntSupplier contextWindowSupplier) {
            builder.showSidebar(usageSupplier, contextWindowSupplier);
            return this;
        }

        /** Trigger a sidebar redraw — delegates to {@link Builder#redrawSidebar}. */
        public void redrawSidebar() { builder.redrawSidebar(); }

        /**
         * Wire a {@link ToolSupport} so the sidebar redraws on every tool call,
         * and the pane rerenders for todo/plan tools.
         */
        public PaneBuilder withTools(ToolSupport toolSupport) {
            toolSupport.setOnToolCall((name, result) -> {
                if (name.startsWith("todo-") || name.equals("update-plan")) pane.run();
                redrawSidebar();
            });
            return this;
        }

        public Repl build() {
            return repl;
        }
    }

    // ── sidebar key thread (internal) ─────────────────────────────────────────

    private static void startKeyThread(Sidebar sidebar) {
        var t = new Thread(() -> {
            // Use "sane" as the restore target — captures the initial good state.
            // We toggle between raw and cooked depending on whether the main thread
            // is waiting at the prompt (cookedMode=true) or processing a response.
            try {
                try (InputStream tty = new java.io.FileInputStream("/dev/tty")) {
                    boolean rawNow = false;
                    int b;
                    while (true) {
                        // Only switch to raw mode when sidebar is in charge of input
                        // (i.e. not when the main thread is waiting at the "You:" prompt).
                        boolean shouldBeRaw = !sidebar.isCookedMode() && sidebar.isUsable();
                        if (shouldBeRaw != rawNow) {
                            rawNow = shouldBeRaw;
                            runStty(rawNow ? new String[]{"stty", "-icanon", "-echo"}
                                           : new String[]{"stty", "sane"});
                        }
                        if (!shouldBeRaw) {
                            Thread.sleep(20);
                            continue;
                        }
                        if (tty.available() == 0) { Thread.sleep(5); continue; }
                        b = tty.read();
                        if (b == -1) break;
                        if (b == 27) {
                            long deadline = System.currentTimeMillis() + 20;
                            while (tty.available() == 0 && System.currentTimeMillis() < deadline) {
                                Thread.sleep(2);
                            }
                            if (tty.available() > 0) {
                                int b2 = tty.read();
                                if (b2 == '[' && tty.available() > 0) {
                                    int b3 = tty.read();
                                    if (b3 == 'A')      sidebar.scrollUp();
                                    else if (b3 == 'B') sidebar.scrollDown();
                                }
                            } else {
                                sidebar.detach(); break;
                            }
                        } else if (b == ' ')                         sidebar.togglePause();
                        else if (b == '\r' || b == '\n' || b == 'x') sidebar.toggleExpand();
                        else if (b == 'X')                           sidebar.toggleExpandAll();
                        else if (b == 'd')                           sidebar.dropSelected();
                        else if (b == 'e')                           sidebar.requestEdit();
                        else if (b == 'i')                           sidebar.requestInsert();
                        else if (b == 'r')                           sidebar.rerunSelected();
                        else if (b == 'q')                           { sidebar.detach(); break; }
                        sidebar.redraw();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                runStty(new String[]{"stty", "sane"});
            }
        });
        t.setDaemon(true);
        t.setName("sidebar-keys");
        t.start();
    }

    private static void runStty(String[] cmd) {
        try {
            new ProcessBuilder(cmd).inheritIO()
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/tty")))
                    .start().waitFor();
        } catch (Exception ignored) {}
    }

    // ── SubBuilder ────────────────────────────────────────────────────────────

    private static boolean isStdinScanner(Scanner scanner) {
        try {
            var f = Scanner.class.getDeclaredField("source");
            f.setAccessible(true);
            return f.get(scanner) == System.in;
        } catch (Exception ignored) {
            return false;
        }
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
     * Fluent sub-command builder returned by {@link Builder#sub} / {@link PaneBuilder#sub}.
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
