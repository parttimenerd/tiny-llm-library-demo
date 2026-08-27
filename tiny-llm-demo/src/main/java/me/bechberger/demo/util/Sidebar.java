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
 *   q / Esc      detach sidebar
 * </pre>
 */
public final class Sidebar {

    private static final int MIN_SIDEBAR_WIDTH = 30;
    private static final int DEFAULT_TERM_WIDTH = 120;

    // ── layout ────────────────────────────────────────────────────────────────

    private final int termWidth;
    private final int leftWidth;   // left column + 1 separator column
    private final int sideWidth;   // usable content width inside the box

    // ── state ─────────────────────────────────────────────────────────────────

    private final List<Map<String, Object>> messages;

    private volatile boolean paused = false;
    private final Object pauseLock = new Object();
    private volatile boolean editRequested = false;

    private int selectedRow = 0;
    private int scrollOffset = 0;  // first visible row index in the flattened row list
    private int expandedMsg = -1;  // index into messages[], -1 = none
    private boolean expandAll = false;

    private int lastDrawnRows = 0;  // how many terminal lines we painted last redraw

    // last known usage / context window for the footer
    private int promptTokens = 0;
    private int completionTokens = 0;
    private int contextWindow = 0;

    // ── construction ──────────────────────────────────────────────────────────

    public Sidebar(List<Map<String, Object>> messages) {
        this.messages = messages;
        this.termWidth = detectTermWidth();
        this.leftWidth = termWidth / 2 + 1;        // +1 for the separator column
        this.sideWidth = termWidth - leftWidth - 2; // 2 for '║' borders
    }

    /** True when the terminal is wide enough and stdout is a real TTY. */
    public boolean isUsable() {
        return Ansi.isTerminal() && sideWidth >= MIN_SIDEBAR_WIDTH;
    }

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

    /** Called by the key thread when 'e' is pressed; the REPL loop checks this before each prompt. */
    public void requestEdit() { editRequested = true; }

    public boolean isEditRequested() { return editRequested; }

    /** Run the inline editor using the REPL's scanner; clears the flag regardless of outcome. */
    public void runEdit(java.util.Scanner scanner) {
        editRequested = false;
        editSelected(scanner);
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

    // ── edit / drop ───────────────────────────────────────────────────────────

    /** Drop the selected message.  Returns true if a message was removed. */
    public boolean dropSelected() {
        if (messages.isEmpty()) return false;
        int idx = clamp(selectedRow, 0, messages.size() - 1);
        messages.remove(idx);
        if (selectedRow >= messages.size()) selectedRow = Math.max(0, messages.size() - 1);
        if (expandedMsg == idx) expandedMsg = -1;
        return true;
    }

    /**
     * Open a simple inline editor for the selected message.
     * Reads a replacement string from {@code scanner}.
     * Returns true if the content was changed.
     */
    public boolean editSelected(Scanner scanner) {
        if (messages.isEmpty()) return false;
        int idx = clamp(selectedRow, 0, messages.size() - 1);
        var msg = messages.get(idx);
        Object current = msg.get("content");
        if (current == null) return false;

        System.out.println();
        System.out.println(Ansi.dim("Current: ") + current);
        System.out.print(Ansi.bold("New content (blank = cancel): "));
        if (!scanner.hasNextLine()) return false;
        String input = scanner.nextLine().trim();
        if (input.isEmpty()) return false;

        var updated = new java.util.LinkedHashMap<>(msg);
        updated.put("content", input);
        messages.set(idx, updated);
        return true;
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
     * restores cursor.  Safe to call from the main thread at any point.
     */
    public void redraw() {
        if (!isUsable()) return;

        var rows = buildRows();
        var sb = new StringBuilder();
        sb.append(Ansi.CURSOR_SAVE);

        int totalRows = Math.max(rows.size(), lastDrawnRows);
        if (totalRows > 0) sb.append(Ansi.cursorUp(totalRows));

        for (int i = 0; i < totalRows; i++) {
            sb.append(Ansi.cursorCol(leftWidth));
            if (i < rows.size()) {
                sb.append(rows.get(i));
            }
            sb.append(Ansi.ERASE_EOL);
            if (i < totalRows - 1) sb.append("\n");
        }

        sb.append(Ansi.CURSOR_RESTORE);
        System.out.print(sb);
        System.out.flush();
        lastDrawnRows = rows.size();
    }

    // ── row builder ───────────────────────────────────────────────────────────

    private List<String> buildRows() {
        var rows = new ArrayList<String>();

        // header
        String countLabel = "context (" + messages.size() + ")";
        rows.add(box("╔══ " + countLabel + " " + "═".repeat(Math.max(0, sideWidth - countLabel.length() - 4)) + "╗"));

        // message rows
        for (int i = 0; i < messages.size(); i++) {
            boolean selected = (i == selectedRow);
            boolean expanded = expandAll || (i == expandedMsg);
            renderMessage(rows, i, messages.get(i), selected, expanded);
        }

        // token footer
        rows.add(box("╠══ tokens " + "═".repeat(Math.max(0, sideWidth - 10)) + "╣"));
        if (promptTokens > 0 || completionTokens > 0) {
            String tok = "prompt " + promptTokens + "  completion " + completionTokens;
            rows.add(box("║ " + pad(tok, sideWidth - 2) + "║"));
            if (contextWindow > 0) {
                int pct = Math.min(100, (promptTokens + completionTokens) * 100 / contextWindow);
                String bar = progressBar(pct, 10);
                String usageLine = (promptTokens + completionTokens) + " / " + contextWindow + " " + bar + " " + pct + "%";
                rows.add(box("║ " + pad(usageLine, sideWidth - 2) + "║"));
            }
        } else {
            rows.add(box("║ " + pad("—", sideWidth - 2) + "║"));
        }

        // pause banner
        if (paused) {
            rows.add(box("╠══ " + Ansi.boldYellow("PAUSED — press Space to resume") + " " + "═".repeat(Math.max(0, sideWidth - 34)) + "╣"));
        }

        // footer
        rows.add(box("╚" + "═".repeat(sideWidth) + "╝"));

        return rows;
    }

    private void renderMessage(List<String> rows, int idx, Map<String, Object> msg, boolean selected, boolean expanded) {
        String role = String.valueOf(msg.get("role"));
        String roleTag = roleTag(role);
        String prefix = selected ? Ansi.bold(">") : " ";

        if (expanded) {
            // full content, word-wrapped
            String content = messageContent(msg);
            var lines = wordWrap(content, sideWidth - roleTag.length() - 4);
            for (int li = 0; li < lines.size(); li++) {
                String linePrefix = (li == 0) ? prefix + " " + roleTag + " " : "  " + " ".repeat(visibleLen(roleTag) + 1);
                rows.add(box("║" + truncate(linePrefix + Ansi.dim(lines.get(li)), sideWidth) + "║"));
            }
        } else {
            // collapsed: one line
            String content = summarise(msg, sideWidth - visibleLen(roleTag) - 4);
            rows.add(box("║" + truncate(prefix + " " + roleTag + " " + content, sideWidth) + "║"));
        }
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
        return truncate(text, maxLen);
    }

    private static String messageContent(Map<String, Object> msg) {
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
        return content != null ? String.valueOf(content) : "—";
    }

    private static String progressBar(int pct, int width) {
        int filled = pct * width / 100;
        return "█".repeat(filled) + "░".repeat(width - filled);
    }

    private static String pad(String s, int width) {
        int vl = visibleLen(s);
        return vl >= width ? s : s + " ".repeat(width - vl);
    }

    private static String truncate(String s, int maxVisible) {
        if (visibleLen(s) <= maxVisible) return s;
        // strip trailing ANSI and trim
        String stripped = s.replaceAll("\033\\[[^m]*m", "");
        if (stripped.length() <= maxVisible - 1) return s;
        return stripped.substring(0, Math.max(0, maxVisible - 1)) + "…";
    }

    private static int visibleLen(String s) {
        return s.replaceAll("\033\\[[^m]*m", "").length();
    }

    private static List<String> wordWrap(String text, int width) {
        var result = new ArrayList<String>();
        for (String rawLine : text.split("\n", -1)) {
            if (rawLine.isEmpty()) { result.add(""); continue; }
            while (rawLine.length() > width) {
                int cut = rawLine.lastIndexOf(' ', width);
                if (cut <= 0) cut = width;
                result.add(rawLine.substring(0, cut));
                rawLine = rawLine.substring(cut).stripLeading();
            }
            result.add(rawLine);
        }
        return result;
    }

    private String box(String content) {
        return Ansi.dim(content);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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
