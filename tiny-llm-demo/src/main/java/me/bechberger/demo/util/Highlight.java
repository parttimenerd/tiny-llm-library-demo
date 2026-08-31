package me.bechberger.demo.util;

import java.util.regex.Pattern;

/**
 * Minimal regex-based syntax highlighter for terminal output.
 * Supports Java, JSON, XML, and unified-diff format.
 * Returns plain text when not running on a real TTY.
 */
public final class Highlight {

    private Highlight() {}

    /** Highlight a source file by guessing the language from the filename extension. */
    public static String file(String content, String filename) {
        if (!Ansi.isTerminal() || content == null) return content;
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".java"))                        return java(content);
        if (lower.endsWith(".json"))                        return json(content);
        if (lower.endsWith(".xml") || lower.endsWith(".pom") || lower.endsWith(".html")) return xml(content);
        return content;
    }

    /** Highlight a unified diff (lines starting with +/-/@@). */
    public static String diff(String content) {
        if (!Ansi.isTerminal() || content == null) return content;
        var sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("+ ") || line.startsWith("+\t"))  sb.append(Ansi.green(line));
            else if (line.startsWith("- ") || line.startsWith("-\t")) sb.append(Ansi.yellow(line));
            else if (line.startsWith("@@"))                           sb.append(Ansi.cyan(line));
            else                                                       sb.append(Ansi.dim(line));
            sb.append('\n');
        }
        // trim trailing newline added above
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    // ── language highlighters ─────────────────────────────────────────────────

    public static String java(String src) {
        if (!Ansi.isTerminal()) return src;
        return applyRules(src, JAVA_RULES);
    }

    public static String json(String src) {
        if (!Ansi.isTerminal()) return src;
        return applyRules(src, JSON_RULES);
    }

    public static String xml(String src) {
        if (!Ansi.isTerminal()) return src;
        return applyRules(src, XML_RULES);
    }

    /** Highlight a shell command string (no newlines). */
    public static String shell(String src) {
        if (!Ansi.isTerminal() || src == null) return src;
        return applyRules(src, SHELL_RULES);
    }

    // package-private: used by tests
    static String applyShell(String src) { return applyRules(src, SHELL_RULES); }

    private record Rule(Pattern pattern, String open, String close) {}

    // package-private: used by tests to exercise highlighting without a real TTY
    static String applyJava(String src) { return applyRules(src, JAVA_RULES); }
    static String applyJson(String src) { return applyRules(src, JSON_RULES); }
    static String applyXml(String src)  { return applyRules(src, XML_RULES); }
    static String applyDiff(String src) { return diff(src); }

    private static Rule rule(String regex, String open, String close) {
        return new Rule(Pattern.compile(regex, Pattern.DOTALL), open, close);
    }

    private static final String RESET = Ansi.RESET;
    // ANSI codes used inline — bypassing Ansi.isTerminal() guards since we
    // check isTerminal() at the top of each public method.
    private static final String C_COMMENT  = "\033[2m";    // dim
    private static final String C_STRING   = "\033[33m";   // yellow
    private static final String C_KEYWORD  = "\033[34m";   // blue
    private static final String C_NUMBER   = "\033[36m";   // cyan
    private static final String C_ANNOT    = "\033[35m";   // magenta
    private static final String C_TAG      = "\033[34m";   // blue
    private static final String C_ATTR     = "\033[33m";   // yellow
    private static final String C_KEY      = "\033[36m";   // cyan

    // Rules are applied in order; later rules don't re-highlight already-replaced ranges.
    // We use a simple scan: find the earliest match among all rules, replace it, advance.

    private static final Rule[] JAVA_RULES = {
        // multi-line comments
        rule("/\\*.*?\\*/",                                C_COMMENT, RESET),
        // single-line comments
        rule("//[^\n]*",                                   C_COMMENT, RESET),
        // string literals (including escape sequences)
        rule("\"(?:[^\"\\\\]|\\\\.)*\"",                   C_STRING,  RESET),
        // char literals
        rule("'(?:[^'\\\\]|\\\\.)*'",                      C_STRING,  RESET),
        // annotations
        rule("@[A-Za-z_][A-Za-z0-9_]*",                   C_ANNOT,   RESET),
        // keywords
        rule("\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue" +
             "|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements" +
             "|import|instanceof|int|interface|long|native|new|null|package|private|protected" +
             "|public|record|return|sealed|short|static|strictfp|super|switch|synchronized" +
             "|this|throw|throws|transient|try|var|void|volatile|while|true|false)\\b",
             C_KEYWORD, RESET),
        // numbers (int, long, float, hex)
        rule("\\b(0x[0-9A-Fa-f]+[Ll]?|[0-9]+\\.?[0-9]*[fFdDlL]?)\\b", C_NUMBER, RESET),
    };

    private static final Rule[] JSON_RULES = {
        // string keys (before the colon)
        rule("\"([^\"\\\\]|\\\\.)*\"(?=\\s*:)",            C_KEY,     RESET),
        // string values
        rule("\"([^\"\\\\]|\\\\.)*\"",                     C_STRING,  RESET),
        // numbers
        rule("-?\\b[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?\\b", C_NUMBER, RESET),
        // true/false/null
        rule("\\b(true|false|null)\\b",                    C_KEYWORD, RESET),
    };

    private static final Rule[] XML_RULES = {
        // comments
        rule("<!--.*?-->",                                  C_COMMENT, RESET),
        // string attribute values
        rule("\"[^\"]*\"",                                  C_STRING,  RESET),
        // attribute names (word= before the quote)
        rule("[A-Za-z_:][A-Za-z0-9_:.-]*(?=\\s*=)",        C_ATTR,    RESET),
        // tag names (opening/closing)
        rule("</?[A-Za-z_:][A-Za-z0-9_:.-]*",              C_TAG,     RESET),
    };

    private static final Rule[] SHELL_RULES = {
        // single-quoted strings
        rule("'[^']*'",                                    C_STRING,  RESET),
        // double-quoted strings
        rule("\"(?:[^\"\\\\]|\\\\.)*\"",                   C_STRING,  RESET),
        // shell keywords / builtins
        rule("\\b(if|then|else|elif|fi|for|while|do|done|case|esac|in" +
             "|echo|export|unset|source|return|exit|cd|pwd|set|shift" +
             "|true|false|test|local|function)\\b",        C_KEYWORD, RESET),
        // flags (-x, --long-flag)
        rule("(?<![\\w-])--?[A-Za-z][A-Za-z0-9_-]*",      C_ANNOT,   RESET),
        // numbers
        rule("\\b[0-9]+\\b",                               C_NUMBER,  RESET),
        // pipe, redirect, semicolon, ampersand operators
        rule("[|&;><]+",                                   C_COMMENT, RESET),
    };

    // ── scan-and-replace ──────────────────────────────────────────────────────

    private static String applyRules(String src, Rule[] rules) {
        // We build a result by scanning src left to right.
        // At each position, find the rule whose match starts earliest.
        var out = new StringBuilder(src.length() + 512);
        int pos = 0;
        while (pos < src.length()) {
            int bestStart = Integer.MAX_VALUE;
            int bestEnd   = pos;
            Rule bestRule = null;
            java.util.regex.Matcher bestMatcher = null;

            for (Rule rule : rules) {
                var m = rule.pattern().matcher(src);
                if (m.find(pos) && m.start() < bestStart) {
                    bestStart   = m.start();
                    bestEnd     = m.end();
                    bestRule    = rule;
                    bestMatcher = m;
                }
            }

            if (bestRule == null) {
                out.append(src, pos, src.length());
                break;
            }
            // append plain text before the match
            out.append(src, pos, bestStart);
            // append highlighted match
            out.append(bestRule.open())
               .append(src, bestStart, bestEnd)
               .append(bestRule.close());
            pos = bestEnd;
        }
        return out.toString();
    }
}
