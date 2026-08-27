package me.bechberger.demo.util;

import java.io.IOException;
import java.io.InputStream;
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
    final History history = new History();
    /** Sidebar reference for raw/cooked mode toggling during run loop. */
    Sidebar sidebar = null;

    /**
     * @param prompt  printed before every input line, e.g. "\nYou: "
     * @param scanner shared System.in scanner — pass one in so sibling prompts
     *                (confirmations, plan acceptance) don't fight over the stream
     */
    public Repl(String prompt, Scanner scanner) {
        this.scanner = scanner;
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
        echoIfPiped(answer);
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
            if (!scanner.hasNextLine()) break;
            String input = readLogicalLine().trim();
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
        String line = scanner.nextLine();
        echoIfPiped(line);
        var sb = new StringBuilder(line);
        while (sb.toString().endsWith("\\")) {
            sb.setLength(sb.length() - 1);
            System.out.print("  ... ");
            if (!scanner.hasNextLine()) break;
            line = scanner.nextLine();
            echoIfPiped(line);
            sb.append('\n').append(line);
        }
        return sb.toString();
    }

    private static void echoIfPiped(String line) {
        if (System.console() == null) System.out.println(line);
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

    // ── History ───────────────────────────────────────────────────────────────

    /** Per-session input history (non-command turns only). */
    static final class History {
        private final List<String> entries = new ArrayList<>();

        void add(String input) {
            // de-duplicate consecutive identical inputs
            if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(input)) return;
            entries.add(input);
        }

        List<String> entries() { return Collections.unmodifiableList(entries); }

        /** Print numbered history to stdout. */
        void print() {
            if (entries.isEmpty()) { System.out.println("(no history)"); return; }
            for (int i = 0; i < entries.size(); i++) {
                String line = entries.get(i).replace("\n", " ↵ ");
                System.out.printf("  %3d  %s%n", i + 1, line);
            }
        }

        /** Return the most recent entry, or null if empty. */
        String last() { return entries.isEmpty() ? null : entries.get(entries.size() - 1); }

        int size() { return entries.size(); }
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
