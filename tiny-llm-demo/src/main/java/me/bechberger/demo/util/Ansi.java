package me.bechberger.demo.util;

/**
 * Minimal ANSI escape-code helpers for terminal colour and style.
 * All methods return plain strings when stdout is not a terminal (piped/redirected),
 * so session logs and scripted runs stay readable.
 */
public final class Ansi {

    private Ansi() {}

    // ── colours ──────────────────────────────────────────────────────────────
    public static final String RESET   = code("0");
    public static final String BOLD    = code("1");
    public static final String DIM     = code("2");

    public static final String GREEN   = code("32");
    public static final String YELLOW  = code("33");
    public static final String BLUE    = code("34");
    public static final String MAGENTA = code("35");
    public static final String CYAN    = code("36");
    public static final String WHITE   = code("37");
    public static final String GRAY    = code("90");

    public static final String BG_DARK = code("48;5;236");  // subtle dark background

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Wrap text in an ANSI code + reset, or return text unchanged if not a terminal. */
    public static String style(String ansiCode, String text) {
        return isTerminal() ? ansiCode + text + RESET : text;
    }

    public static String bold(String text)    { return style(BOLD,    text); }
    public static String dim(String text)     { return style(DIM,     text); }
    public static String green(String text)   { return style(GREEN,   text); }
    public static String yellow(String text)  { return style(YELLOW,  text); }
    public static String blue(String text)    { return style(BLUE,    text); }
    public static String magenta(String text) { return style(MAGENTA, text); }
    public static String cyan(String text)    { return style(CYAN,    text); }
    public static String gray(String text)    { return style(GRAY,    text); }

    public static String boldGreen(String text)  { return isTerminal() ? BOLD + GREEN  + text + RESET : text; }
    public static String boldBlue(String text)   { return isTerminal() ? BOLD + BLUE   + text + RESET : text; }
    public static String boldYellow(String text) { return isTerminal() ? BOLD + YELLOW + text + RESET : text; }

    /** Bold + colour combo used for section headers. */
    public static String header(String text) {
        return isTerminal() ? BOLD + CYAN + text + RESET : text;
    }

    /** Bright-green checked box — or plain [x] in piped output. */
    public static String checked()   { return isTerminal() ? GREEN  + "[x]" + RESET : "[x]"; }
    public static String inProgress(){ return isTerminal() ? YELLOW + "[>]" + RESET : "[>]"; }
    public static String pending()   { return isTerminal() ? GRAY   + "[ ]" + RESET : "[ ]"; }

    // ── cursor positioning ────────────────────────────────────────────────────
    // Raw ANSI codes — callers must check isTerminal() before using.

    public static final String CURSOR_SAVE    = "\033[s";
    public static final String CURSOR_RESTORE = "\033[u";
    public static final String ERASE_EOL      = "\033[K";  // erase to end of line

    /** Move cursor up N lines. */
    public static String cursorUp(int n)    { return "\033[" + n + "A"; }
    /** Move cursor to 1-based column. */
    public static String cursorCol(int col) { return "\033[" + col + "G"; }

    // ── dividers ──────────────────────────────────────────────────────────────

    /** Divider line — dim in terminal, plain dashes in logs. */
    public static String divider(int width) {
        String line = "─".repeat(width);
        return isTerminal() ? DIM + line + RESET : line;
    }

    // ── markdown renderer ─────────────────────────────────────────────────────

    /**
     * Render a Markdown string to ANSI-styled terminal output.
     * Handles: # headings, **bold**, `code`, fenced code blocks, - / * / 1. lists, --- rules, tables.
     * Falls back to plain text when not a terminal.
     */
    public static String renderMarkdown(String md) {
        if (md == null) return "";
        var sb = new StringBuilder();
        boolean inFence = false;
        String[] lines = md.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].stripTrailing();

            if (line.startsWith("```")) { inFence = !inFence; i++; continue; }
            if (inFence) { sb.append(style(CYAN, line)).append("\n"); i++; continue; }

            // table: collect consecutive pipe-lines
            if (line.contains("|")) {
                int j = i;
                while (j < lines.length && lines[j].stripTrailing().contains("|")) j++;
                String[] tableLines = java.util.Arrays.copyOfRange(lines, i, j);
                sb.append(renderTable(tableLines));
                i = j;
                continue;
            }

            // headings
            if (line.startsWith("### ")) { sb.append(bold(yellow(line.substring(4)))).append("\n"); i++; continue; }
            if (line.startsWith("## "))  { sb.append(bold(cyan(line.substring(3)))).append("\n"); i++; continue; }
            if (line.startsWith("# "))   { sb.append(bold(header(line.substring(2)))).append("\n"); i++; continue; }

            // horizontal rule
            if (line.matches("^[-*_]{3,}\\s*$")) { sb.append(divider(58)).append("\n"); i++; continue; }

            // bullet lists
            if (line.matches("^(\\s*)[-*+] (.*)")) {
                String indent = line.replaceFirst("^(\\s*)[-*+] .*", "$1");
                String item   = line.replaceFirst("^\\s*[-*+] ", "");
                sb.append(indent).append(gray("• ")).append(inlineMarkdown(item)).append("\n");
                i++; continue;
            }
            // numbered lists
            if (line.matches("^(\\s*)\\d+[.)] (.*)")) {
                String indent = line.replaceFirst("^(\\s*)\\d+[.)] .*", "$1");
                String num    = line.replaceFirst("^\\s*(\\d+[.)]) .*", "$1");
                String item   = line.replaceFirst("^\\s*\\d+[.)] ", "");
                sb.append(indent).append(bold(num)).append(" ").append(inlineMarkdown(item)).append("\n");
                i++; continue;
            }

            sb.append(inlineMarkdown(line)).append("\n");
            i++;
        }
        return sb.toString();
    }

    private static String renderTable(String[] lines) {
        // parse rows, skip separator lines (|---|---|)
        var rows = new java.util.ArrayList<String[]>();
        boolean[] isSeparator = new boolean[lines.length];
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].strip();
            if (l.replaceAll("[|:\\-\\s]", "").isEmpty()) { isSeparator[i] = true; continue; }
            String[] cells = splitTableRow(l);
            rows.add(cells);
        }
        if (rows.isEmpty()) return String.join("\n", lines) + "\n";

        // compute column widths (plain text, no ANSI)
        int cols = rows.stream().mapToInt(r -> r.length).max().orElse(1);
        int[] widths = new int[cols];
        for (String[] row : rows)
            for (int c = 0; c < row.length; c++)
                widths[c] = Math.max(widths[c], row[c].strip().length());

        var sb = new StringBuilder();
        String bar = gray("│");
        // top border
        sb.append(tableHRule("┌", "┬", "┐", widths)).append("\n");
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] row = rows.get(ri);
            sb.append(bar);
            for (int c = 0; c < cols; c++) {
                String cell = c < row.length ? row[c].strip() : "";
                String rendered = ri == 0 ? bold(inlineMarkdown(cell)) : inlineMarkdown(cell);
                int pad = widths[c] - cell.length();
                sb.append(" ").append(rendered).append(" ".repeat(Math.max(0, pad) + 1)).append(bar);
            }
            sb.append("\n");
            // separator after header row
            if (ri == 0 && rows.size() > 1)
                sb.append(tableHRule("├", "┼", "┤", widths)).append("\n");
        }
        sb.append(tableHRule("└", "┴", "┘", widths)).append("\n");
        return sb.toString();
    }

    private static String tableHRule(String left, String mid, String right, int[] widths) {
        var sb = new StringBuilder();
        sb.append(gray(left));
        for (int c = 0; c < widths.length; c++) {
            sb.append(gray("─".repeat(widths[c] + 2)));
            sb.append(gray(c < widths.length - 1 ? mid : right));
        }
        return sb.toString();
    }

    private static String[] splitTableRow(String line) {
        if (line.startsWith("|")) line = line.substring(1);
        if (line.endsWith("|"))   line = line.substring(0, line.length() - 1);
        return line.split("\\|", -1);
    }

    private static String inlineMarkdown(String text) {
        if (!isTerminal()) return text;
        // inline code: `...`
        text = text.replaceAll("`([^`]+)`", CYAN + "$1" + RESET);
        // bold: **...**
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", BOLD + "$1" + RESET);
        // italic: *...*  (after bold)
        text = text.replaceAll("\\*([^*]+)\\*", DIM + "$1" + RESET);
        return text;
    }

    /** True when System.out is connected to a real terminal (not redirected). */
    public static boolean isTerminal() {
        return forceTerminal || System.console() != null;
    }

    /** Package-private: set to true in tests to enable colour output without a real TTY. */
    static boolean forceTerminal = false;

    private static String code(String n) {
        return "[" + n + "m";
    }
}
