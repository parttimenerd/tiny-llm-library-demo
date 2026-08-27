package me.bechberger.demo.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Live context sidebar for the Repl framework.
 *
 * <p>Renders a message-stack diagram in the right half of the terminal using
 * ANSI cursor positioning.  Redraws on events (after each response, after each
 * tool call) rather than per-token — no threading required for rendering.
 *
 * <p>Pause is handled by {@link #checkPause()}: the caller invokes it from the
 * streaming token callback so the LLM request blocks mid-stream when paused.
 *
 * <p>Key bindings (dispatched by {@link Repl}):
 * <pre>
 *   Space        pause / resume
 *   ↑ / ↓        scroll / change selection
 *   Enter / x    expand / collapse selected message
 *   X            expand / collapse all
 *   e            edit selected message inline
 *   d            drop selected message
 *   i            insert new message after selection
 *   r            truncate to selection and rerun
 *   q / Esc      detach sidebar
 * </pre>
 */
public final class Sidebar {

    private static final int MIN_SIDEBAR_WIDTH = 30;
    private static final int DEFAULT_TERM_WIDTH = 120;
    private static final int DEFAULT_TERM_HEIGHT = 40;

    // ── layout ────────────────────────────────────────────────────────────────

    private final int termWidth;
    private final int termHeight;
    private final int leftWidth;   // left column + 1 separator column
    private final int sideWidth;   // usable content width inside the box

    // ── state ─────────────────────────────────────────────────────────────────

    private final List<Map<String, Object>> messages;

    private volatile boolean paused = false;
    private final Object pauseLock = new Object();
    private volatile boolean editRequested = false;
    private volatile boolean insertRequested = false;
    private volatile String  pendingRerun = null;  // non-null → re-submit this as the next user turn

    private volatile boolean attached = true;  // false after 'q'/Esc to detach sidebar
    private volatile boolean cookedMode = true; // true when main thread is at the prompt (no raw key capture)

    private int selectedRow = 0;
    private int scrollOffset = 0;  // first visible row index in the flattened row list
    private int expandedMsg = -1;  // index into messages[], -1 = none
    private boolean expandAll = false;

    private int lastDrawnRows = 0;  // how many terminal lines we painted last redraw
    private final Object redrawLock = new Object(); // prevents key-thread / token-callback races

    // last known usage / context window for the footer
    private int promptTokens = 0;
    private int completionTokens = 0;
    private int contextWindow = 0;

    // ── construction ──────────────────────────────────────────────────────────

    public Sidebar(List<Map<String, Object>> messages) {
        this.messages = messages;
        this.termWidth = detectTermWidth();
        this.termHeight = detectTermHeight();
        this.leftWidth = termWidth / 2 + 1;        // +1 for the separator column
        this.sideWidth = termWidth - leftWidth - 2; // 2 for '║' borders
    }

    /** True when the terminal is wide enough and stdout is a real TTY. */
    public boolean isUsable() {
        return attached && Ansi.isTerminal() && sideWidth >= MIN_SIDEBAR_WIDTH;
    }

    /** Detach the sidebar (q/Esc) — after this, isUsable() returns false and redraws are no-ops. */
    public void detach() { attached = false; }

    /** Set to true when the main thread is waiting at the "You:" prompt (disables raw key capture). */
    public void setCookedMode(boolean cooked) { this.cookedMode = cooked; }
    public boolean isCookedMode() { return cookedMode; }

    // ── pause / resume ────────────────────────────────────────────────────────

    /**
     * Call from the streaming token callback.  Blocks the calling thread while
     * paused, so the LLM request freezes mid-stream.
     */
    public void checkPause() {
        if (!paused) return;
        synchronized (pauseLock) {
            while (paused) {
                try { pauseLock.wait(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    paused = false;
                }
            }
        }
    }

    public void togglePause() {
        synchronized (pauseLock) {
            paused = !paused;
            if (!paused) pauseLock.notifyAll();
        }
    }

    public boolean isPaused() { return paused; }

    int getSelectedRow() { return selectedRow; }
    int getScrollOffset() { return scrollOffset; }
    int getExpandedMsg()  { return expandedMsg; }
    boolean isExpandAll() { return expandAll; }

    /** Called by the key thread when 'e' is pressed; the REPL loop checks this before each prompt. */
    public void requestEdit()   { editRequested   = true; }
    /** Called by the key thread when 'i' is pressed; the REPL loop checks this before each prompt. */
    public void requestInsert() { insertRequested  = true; }

    public boolean isEditRequested()   { return editRequested; }
    public boolean isInsertRequested() { return insertRequested; }

    /** Run the inline editor using the REPL's scanner; clears the flag regardless of outcome. */
    public void runEdit(java.util.Scanner scanner) {
        editRequested = false;
        editSelected(scanner);
    }

    /** Run the insert dialog using the REPL's scanner; clears the flag regardless of outcome. */
    public void runInsert(java.util.Scanner scanner) {
        insertRequested = false;
        insertAfterSelected(scanner);
    }

    /** True if a rerun was queued by {@link #rerunSelected()}. */
    public boolean hasPendingRerun() { return pendingRerun != null; }

    /** Consume and return the pending rerun content, or null. */
    public String takePendingRerun() {
        String s = pendingRerun;
        pendingRerun = null;
        return s;
    }

    // ── navigation ────────────────────────────────────────────────────────────

    public void scrollUp() {
        if (selectedRow > 0) selectedRow--;
    }

    public void scrollDown() {
        if (selectedRow < messages.size() - 1) selectedRow++;
    }

    public void toggleExpand() {
        expandedMsg = (expandedMsg == selectedRow) ? -1 : selectedRow;
        expandAll = false;
    }

    public void toggleExpandAll() {
        expandAll = !expandAll;
        expandedMsg = -1;
    }

    // ── edit / drop / insert / rerun ──────────────────────────────────────────

    /** Drop the selected message. Returns true if a message was removed. */
    public boolean dropSelected() {
        if (messages.isEmpty()) return false;
        int idx = clamp(selectedRow, 0, messages.size() - 1);
        messages.remove(idx);
        if (selectedRow >= messages.size()) selectedRow = Math.max(0, messages.size() - 1);
        if (expandedMsg == idx) expandedMsg = -1;
        return true;
    }

    /**
     * Drop all messages from {@code idx} onward.
     * Returns the number of messages removed.
     */
    public int truncateFrom(int idx) {
        if (idx < 0 || idx >= messages.size()) return 0;
        int removed = messages.size() - idx;
        messages.subList(idx, messages.size()).clear();
        selectedRow = Math.max(0, messages.size() - 1);
        if (expandedMsg >= idx) expandedMsg = -1;
        return removed;
    }

    /**
     * Truncate the context to just before the selected message and queue it for
     * re-submission. Walks back to the nearest user message at or before the
     * selection, drops it and everything after, and sets pendingRerun.
     * Returns the content that will be re-submitted, or null if none found.
     */
    public String rerunSelected() {
        if (messages.isEmpty()) return null;
        int idx = clamp(selectedRow, 0, messages.size() - 1);
        for (int i = idx; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                String content = String.valueOf(messages.get(i).get("content"));
                truncateFrom(i);
                pendingRerun = content;
                return content;
            }
        }
        return null;
    }

    /**
     * Edit the selected message inline.
     * Supports {@code \} continuation for multi-line replacements.
     * Returns true if the content was changed.
     */
    public boolean editSelected(Scanner scanner) {
        if (messages.isEmpty()) return false;
        int idx = clamp(selectedRow, 0, messages.size() - 1);
        var msg = messages.get(idx);
        Object current = msg.get("content");
        if (current == null) return false;

        System.out.println();
        String currentStr = String.valueOf(current);
        System.out.println(Ansi.dim("Current: ") + currentStr.replace("\n", "\n         "));
        System.out.print(Ansi.bold("New content (\\ continues, blank = cancel): "));
        String input = readMultiLine(scanner);
        if (input.isEmpty()) return false;

        var updated = new java.util.LinkedHashMap<>(msg);
        updated.put("content", input);
        messages.set(idx, updated);
        return true;
    }

    /**
     * Insert a new message after the selected row.
     * Prompts for role (default: user) and content.
     * Returns true if a message was inserted.
     */
    public boolean insertAfterSelected(Scanner scanner) {
        System.out.println();
        System.out.print(Ansi.bold("Role (user/assistant/system, blank = user): "));
        if (!scanner.hasNextLine()) return false;
        String roleInput = scanner.nextLine().trim().toLowerCase();
        String role = roleInput.isEmpty() ? "user" : roleInput;
        if (!role.equals("user") && !role.equals("assistant") && !role.equals("system")) {
            System.out.println(Ansi.dim("Unknown role — using 'user'"));
            role = "user";
        }

        System.out.print(Ansi.bold("Content (\\ continues, blank = cancel): "));
        String content = readMultiLine(scanner);
        if (content.isEmpty()) return false;

        var newMsg = new java.util.LinkedHashMap<String, Object>();
        newMsg.put("role", role);
        newMsg.put("content", content);
        int insertAt = messages.isEmpty() ? 0
                : Math.min(clamp(selectedRow, 0, messages.size() - 1) + 1, messages.size());
        messages.add(insertAt, newMsg);
        selectedRow = insertAt;
        return true;
    }

    /** Read a (possibly multi-line) value from {@code scanner}, supporting {@code \} continuation. */
    static String readMultiLine(Scanner scanner) {
        if (!scanner.hasNextLine()) return "";
        String line = scanner.nextLine();
        var sb = new StringBuilder(line);
        while (sb.toString().endsWith("\\")) {
            sb.setLength(sb.length() - 1);
            System.out.print("  ... ");
            if (!scanner.hasNextLine()) break;
            sb.append('\n').append(scanner.nextLine());
        }
        return sb.toString().trim();
    }

    // ── token / context info (set by Repl before redraw) ─────────────────────

    public void updateUsage(int promptTokens, int completionTokens, int contextWindow) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.contextWindow = contextWindow;
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    /**
     * Redraw the sidebar.  Saves cursor → jumps to right column → paints rows →
     * restores cursor.  Synchronized so the key thread and token callback don't race.
     */
    public void redraw() {
        if (!isUsable()) return;
        synchronized (redrawLock) {
            var rows = buildRows();
            var sb = new StringBuilder();
            sb.append(Ansi.CURSOR_SAVE);

            int totalRows = Math.max(rows.size(), lastDrawnRows);
            if (totalRows > 0) sb.append(Ansi.cursorUp(totalRows));

            for (int i = 0; i < totalRows; i++) {
                sb.append(Ansi.cursorCol(leftWidth));
                if (i < rows.size()) sb.append(rows.get(i));
                sb.append(Ansi.ERASE_EOL);
                if (i < totalRows - 1) sb.append("\n");
            }

            sb.append(Ansi.CURSOR_RESTORE);
            System.out.print(sb);
            System.out.flush();
            lastDrawnRows = rows.size();
        }
    }

    // ── row builder ───────────────────────────────────────────────────────────

    List<String> buildRows() {
        // fixed chrome: header(1) + token separator(1) + token line(1) + [ctx bar(1)] + [pause(1)] + hints(1) + bottom(1) = 5-7
        int chrome = 5 + (contextWindow > 0 ? 1 : 0) + (paused ? 1 : 0);
        int maxMsgRows = Math.max(1, termHeight - chrome - 2); // 2 rows breathing room

        // build all message rows first so we can clip to viewport
        var allMsgRows = new ArrayList<String>();
        var msgRowToIdx = new ArrayList<Integer>(); // maps display row → message index
        for (int i = 0; i < messages.size(); i++) {
            boolean selected = (i == selectedRow);
            boolean expanded = expandAll || (i == expandedMsg);
            int before = allMsgRows.size();
            renderMessage(allMsgRows, i, messages.get(i), selected, expanded);
            for (int r = before; r < allMsgRows.size(); r++) msgRowToIdx.add(i);
        }

        // keep selectedRow's first display-row visible: auto-adjust scrollOffset
        if (!msgRowToIdx.isEmpty()) {
            int firstRowOfSelected = msgRowToIdx.indexOf(selectedRow);
            if (firstRowOfSelected < 0) firstRowOfSelected = 0;
            if (firstRowOfSelected < scrollOffset) scrollOffset = firstRowOfSelected;
            if (firstRowOfSelected >= scrollOffset + maxMsgRows) scrollOffset = firstRowOfSelected - maxMsgRows + 1;
        }

        var rows = new ArrayList<String>();

        // header — show ↑ if scrolled, ↓ if more below viewport
        boolean hasUp   = scrollOffset > 0;
        boolean hasDown = !allMsgRows.isEmpty() && (scrollOffset + maxMsgRows) < allMsgRows.size();
        String scrollHint = (hasUp ? "↑" : "") + (hasDown ? "↓" : "");
        String countLabel = "context (" + messages.size() + ")";
        // row must be sideWidth+2 total: "╔" (1) + "══ " (3) + label + hintPart + " " (1) + fill + "╗" (1)
        int hintExtra = scrollHint.isEmpty() ? 0 : 1 + scrollHint.length(); // " " + hint chars
        int headerFill = Math.max(0, sideWidth - 4 - countLabel.length() - hintExtra);
        String hintPart = scrollHint.isEmpty() ? "" : " " + scrollHint;
        rows.add(border("╔══ " + countLabel + hintPart + " " + "═".repeat(headerFill) + "╗"));

        // clipped message rows
        int end = Math.min(scrollOffset + maxMsgRows, allMsgRows.size());
        for (int r = scrollOffset; r < end; r++) rows.add(allMsgRows.get(r));
        // pad with empty lines so the box height stays stable
        int shown = end - scrollOffset;
        for (int r = shown; r < maxMsgRows; r++) {
            rows.add(border("║" + " ".repeat(sideWidth) + "║"));
        }

        // token footer separator
        rows.add(border("╠══ tokens " + "═".repeat(Math.max(0, sideWidth - 10)) + "╣"));
        if (promptTokens > 0 || completionTokens > 0) {
            String tok = "prompt " + promptTokens + "  completion " + completionTokens;
            rows.add(border("║" + pad(" " + tok, sideWidth) + "║"));
            if (contextWindow > 0) {
                int pct = Math.min(100, (promptTokens + completionTokens) * 100 / contextWindow);
                String bar = progressBar(pct, 10);
                String usageLine = (promptTokens + completionTokens) + " / " + contextWindow + "  " + bar + " " + pct + "%";
                rows.add(border("║" + pad(" " + usageLine, sideWidth) + "║"));
            }
        } else {
            rows.add(border("║" + pad(" —", sideWidth) + "║"));
        }

        // pause banner
        if (paused) {
            String pauseText = "PAUSED — Space to resume";
            int pauseFill = Math.max(0, sideWidth - 4 - pauseText.length());
            rows.add(border("╠══ " + Ansi.boldYellow(pauseText) + " " + "═".repeat(pauseFill) + "╣"));
        }

        // key hints row — one dim line teaching users what keys work
        rows.add(keyHintsRow());

        // footer
        rows.add(border("╚" + "═".repeat(sideWidth) + "╝"));

        return rows;
    }

    /** Build a single dim hints row that fits exactly inside the box. */
    private String keyHintsRow() {
        // Show different hints depending on context
        java.util.List<String> hints = new java.util.ArrayList<>();
        hints.add("↑↓ scroll");
        if (expandedMsg >= 0 || expandAll) {
            hints.add("x collapse");
        } else {
            hints.add("x expand");
        }
        hints.add("e edit");
        hints.add("d drop");
        hints.add("r rerun");
        hints.add("i insert");
        if (!paused) hints.add("Spc pause");
        hints.add("q close");

        // Join with  ·  separators, truncate to fit
        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < hints.size(); i++) {
            String piece = (i == 0 ? "" : " · ") + hints.get(i);
            if (sb.length() + piece.length() > sideWidth - 1) break;
            sb.append(piece);
        }
        String line = sb.toString();
        return Ansi.dim("║" + pad(line, sideWidth) + "║");
    }

    private void renderMessage(List<String> rows, int idx, Map<String, Object> msg, boolean selected, boolean expanded) {
        String role = String.valueOf(msg.get("role"));
        String roleTag;
        if (isSummary(msg)) {
            roleTag = Ansi.magenta("SUM ");
        } else {
            roleTag = roleTag(role);
        }
        int roleTagVLen = visibleLen(roleTag);
        String prefix = selected ? Ansi.bold(">") : " ";
        String filename = "tool".equals(role) ? findFilenameForTool(idx, msg) : null;

        if (expanded) {
            // full content, word-wrapped
            String content = messageContent(msg, filename);
            var lines = wordWrap(content, sideWidth - roleTagVLen - 4);
            for (int li = 0; li < lines.size(); li++) {
                String linePrefix = (li == 0) ? prefix + " " + roleTag + " " : "  " + " ".repeat(roleTagVLen + 1);
                rows.add(box("║" + fit(linePrefix + lines.get(li), sideWidth) + "║"));
            }
            if (lines.isEmpty()) {
                rows.add(box("║" + fit(prefix + " " + roleTag + " ", sideWidth) + "║"));
            }
        } else {
            // collapsed: one line
            String content = summarise(msg, sideWidth - roleTagVLen - 4);
            rows.add(box("║" + fit(prefix + " " + roleTag + " " + content, sideWidth) + "║"));
        }
    }

    /**
     * Walk backward from {@code idx} to find the assistant message containing the
     * tool_call whose id matches this tool-result message, then extract the "path"
     * argument from its JSON so we can syntax-highlight by filename.
     */
    String findFilenameForTool(int idx, Map<String, Object> toolMsg) {
        String callId = (String) toolMsg.get("tool_call_id");
        if (callId == null) return null;
        for (int i = idx - 1; i >= 0; i--) {
            var m = messages.get(i);
            var tcs = m.get("tool_calls");
            if (!(tcs instanceof List<?> list)) continue;
            for (var tc : list) {
                if (!(tc instanceof Map<?, ?> tcMap)) continue;
                if (!callId.equals(tcMap.get("id"))) continue;
                var fn = (Map<?, ?>) tcMap.get("function");
                if (fn == null) return null;
                Object argsObj = fn.get("arguments");
                String args = argsObj != null ? String.valueOf(argsObj) : "";
                // quick extraction of "path" field without a full JSON parse
                var m2 = java.util.regex.Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"").matcher(args);
                return m2.find() ? m2.group(1) : null;
            }
        }
        return null;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String roleTag(String role) {
        return switch (role) {
            case "system"    -> Ansi.dim("SYS ");
            case "user"      -> Ansi.blue("USER");
            case "assistant" -> Ansi.green("ASST");
            case "tool"      -> Ansi.yellow("TOOL");
            default          -> Ansi.gray(role.substring(0, Math.min(4, role.length())).toUpperCase());
        };
    }

    /** Label shown in the summary message inserted by Compactor. */
    private static boolean isSummary(Map<String, Object> msg) {
        var c = msg.get("content");
        return "system".equals(msg.get("role")) && c instanceof String s && s.startsWith("[Conversation summary]");
    }

    private static String summarise(Map<String, Object> msg, int maxLen) {
        var tc = msg.get("tool_calls");
        if (tc instanceof List<?> list && !list.isEmpty()) {
            var names = list.stream()
                .filter(t -> t instanceof Map)
                .map(t -> {
                    var fn = ((Map<?, ?>) ((Map<?, ?>) t).get("function"));
                    return fn != null ? String.valueOf(fn.get("name")) : "?";
                })
                .toList();
            return truncate(Ansi.cyan("⚙ " + String.join(", ", names)), maxLen);
        }
        var content = msg.get("content");
        if (content == null) return Ansi.dim("—");
        String text = String.valueOf(content).strip().replace("\n", " ↵ ");
        // strip the Compactor summary prefix for cleaner display
        if (text.startsWith("[Conversation summary]")) text = text.substring("[Conversation summary]".length()).strip();
        return truncate(text, maxLen);
    }

    private static String messageContent(Map<String, Object> msg, String filename) {
        var tc = msg.get("tool_calls");
        if (tc instanceof List<?> list && !list.isEmpty()) {
            var sb = new StringBuilder();
            for (var t : list) {
                if (t instanceof Map<?, ?> tm) {
                    var fn = (Map<?, ?>) tm.get("function");
                    if (fn != null) sb.append("⚙ ").append(fn.get("name")).append("(").append(fn.get("arguments")).append(")\n");
                }
            }
            return sb.toString().trim();
        }
        var content = msg.get("content");
        if (content == null) return "—";
        String text = String.valueOf(content);
        // apply syntax highlighting for expanded tool-result messages
        if (filename != null) {
            String highlighted = Highlight.file(text, filename);
            if (highlighted != text) return highlighted; // language matched
        }
        // diff detection: looks like a diff if majority of non-blank lines start with +/- (not counting headers)
        if (looksLikeDiff(text)) return Highlight.diff(text);
        return text;
    }

    static boolean looksLikeDiff(String text) {
        String[] lines = text.split("\n", -1);
        int diffLines = 0, total = 0;
        for (String l : lines) {
            if (l.isBlank()) continue;
            total++;
            if (l.startsWith("+ ") || l.startsWith("- ") || l.startsWith("@@")) diffLines++;
        }
        return total > 0 && diffLines * 2 >= total; // at least half are diff markers
    }

    private static String progressBar(int pct, int width) {
        int filled = pct * width / 100;
        return "█".repeat(filled) + "░".repeat(width - filled);
    }

    private static String pad(String s, int width) {
        int vl = visibleLen(s);
        return vl >= width ? s : s + " ".repeat(width - vl);
    }

    /** Truncate to at most maxVisible visible characters, appending … if cut. */
    private static String truncate(String s, int maxVisible) {
        if (visibleLen(s) <= maxVisible) return s;
        // drop ANSI codes and hard-cut the plain text
        String stripped = s.replaceAll("\033\\[[^m]*m", "");
        return stripped.substring(0, Math.max(0, maxVisible - 1)) + "…";
    }

    /** Pad or truncate to exactly maxVisible visible characters. */
    private static String fit(String s, int maxVisible) {
        return pad(truncate(s, maxVisible), maxVisible);
    }

    private static int visibleLen(String s) {
        return s.replaceAll("\033\\[[^m]*m", "").length();
    }

    /** Word-wrap plain text (no ANSI codes) to the given column width. */
    private static List<String> wordWrap(String text, int width) {
        if (width <= 0) return List.of(text);
        var result = new ArrayList<String>();
        for (String rawLine : text.split("\n", -1)) {
            if (rawLine.isEmpty()) { result.add(""); continue; }
            // strip ANSI so we measure visible chars
            String plain = rawLine.replaceAll("\033\\[[^m]*m", "");
            while (plain.length() > width) {
                int cut = plain.lastIndexOf(' ', width);
                if (cut <= 0) cut = width;
                result.add(plain.substring(0, cut));
                plain = plain.substring(cut).stripLeading();
            }
            result.add(plain);
        }
        return result;
    }

    /** Dim the entire row — used for structural chrome (borders, separators, footers). */
    private String border(String content) {
        return Ansi.dim(content);
    }

    /** For content rows: dim just the box borders, leave interior colors intact. */
    private String box(String content) {
        // content is "║" + interior + "║" — dim the outer borders, keep interior as-is
        if (content.length() < 2) return Ansi.dim(content);
        return Ansi.dim("║") + content.substring(1, content.length() - 1) + Ansi.dim("║");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int detectTermHeight() {
        String lines = System.getenv("LINES");
        if (lines != null) { try { return Integer.parseInt(lines.trim()); } catch (NumberFormatException ignored) {} }
        try {
            var pb = new ProcessBuilder("tput", "lines").redirectErrorStream(true);
            pb.environment().putIfAbsent("TERM", "xterm-256color");
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            return Integer.parseInt(out);
        } catch (Exception ignored) {}
        return DEFAULT_TERM_HEIGHT;
    }

    private static int detectTermWidth() {
        String cols = System.getenv("COLUMNS");
        if (cols != null) { try { return Integer.parseInt(cols.trim()); } catch (NumberFormatException ignored) {} }
        try {
            var pb = new ProcessBuilder("tput", "cols").redirectErrorStream(true);
            pb.environment().putIfAbsent("TERM", "xterm-256color");
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            return Integer.parseInt(out);
        } catch (Exception ignored) {}
        return DEFAULT_TERM_WIDTH;
    }
}
