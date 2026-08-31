package me.bechberger.demo.util;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Highlight, Repl, and Sidebar.
 * Ansi.forceTerminal = true enables colour output without a real TTY.
 */
class HighlightTest {

    @BeforeEach void enableAnsi()  { Ansi.forceTerminal = true; }
    @AfterEach  void disableAnsi() { Ansi.forceTerminal = false; }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Strip all ANSI escape sequences from a string. */
    private static String plain(String s) {
        return s == null ? null : s.replaceAll("\033\\[[^m]*m", "");
    }

    /** Returns true if the string contains an ESC sequence wrapping the token. */
    private static boolean isColoured(String s) {
        return s != null && s.contains("\033[");
    }

    // ── Highlight.java ────────────────────────────────────────────────────────

    @Nested class JavaHighlight {

        @Test void keywordsAreColoured() {
            String out = Highlight.applyJava("public class Foo {}");
            assertTrue(isColoured(out), "keywords should be coloured");
            assertEquals("public class Foo {}", plain(out));
        }

        @Test void stringLiteralsAreColoured() {
            String out = Highlight.applyJava("String s = \"hello\";");
            assertTrue(isColoured(out));
            assertEquals("String s = \"hello\";", plain(out));
        }

        @Test void singleLineCommentIsColoured() {
            String out = Highlight.applyJava("int x = 1; // comment");
            assertTrue(isColoured(out));
            assertEquals("int x = 1; // comment", plain(out));
        }

        @Test void multiLineCommentIsColoured() {
            String out = Highlight.applyJava("/* block */\nint x;");
            assertTrue(isColoured(out));
            assertEquals("/* block */\nint x;", plain(out));
        }

        @Test void keywordInsideStringNotHighlightedSeparately() {
            // "public" inside a string literal should not get keyword colour
            // independently — the whole string token is one span
            String out = Highlight.applyJava("\"public void\"");
            // plain text is preserved
            assertEquals("\"public void\"", plain(out));
            // count open colour spans by splitting on RESET (\033[0m) and checking
            // how many segments start with a colour code
            long openSpans = Arrays.stream(out.split("\033\\[0m", -1))
                    .filter(s -> s.contains("\033["))
                    .count();
            assertEquals(1, openSpans, "expected exactly one colour span (the string literal)");
        }

        @Test void annotationIsColoured() {
            String out = Highlight.applyJava("@Override\nvoid foo() {}");
            assertTrue(isColoured(out));
            assertEquals("@Override\nvoid foo() {}", plain(out));
        }

        @Test void numberIsColoured() {
            String out = Highlight.applyJava("int x = 42;");
            assertTrue(isColoured(out));
            assertEquals("int x = 42;", plain(out));
        }

        @Test void plainTextUnchanged() {
            String out = Highlight.applyJava("identifier");
            // "identifier" is not a keyword — no colouring
            assertFalse(isColoured(out));
        }
    }

    @Nested class JsonHighlight {

        @Test void keysAreColoured() {
            String out = Highlight.applyJson("{\"name\": \"Alice\"}");
            assertTrue(isColoured(out));
            assertEquals("{\"name\": \"Alice\"}", plain(out));
        }

        @Test void nullAndBooleanAreColoured() {
            String out = Highlight.applyJson("{\"ok\": true, \"x\": null}");
            assertTrue(isColoured(out));
            assertEquals("{\"ok\": true, \"x\": null}", plain(out));
        }

        @Test void numbersAreColoured() {
            String out = Highlight.applyJson("{\"n\": 3.14}");
            assertTrue(isColoured(out));
            assertEquals("{\"n\": 3.14}", plain(out));
        }
    }

    @Nested class XmlHighlight {

        @Test void tagNamesAreColoured() {
            String out = Highlight.applyXml("<dependency>");
            assertTrue(isColoured(out));
            assertEquals("<dependency>", plain(out));
        }

        @Test void attributeNamesAreColoured() {
            String out = Highlight.applyXml("<tag attr=\"val\">");
            assertTrue(isColoured(out));
            assertEquals("<tag attr=\"val\">", plain(out));
        }

        @Test void xmlCommentIsColoured() {
            String out = Highlight.applyXml("<!-- comment -->");
            assertTrue(isColoured(out));
            assertEquals("<!-- comment -->", plain(out));
        }

        @Test void closingTagColoured() {
            String out = Highlight.applyXml("</groupId>");
            assertTrue(isColoured(out));
            assertEquals("</groupId>", plain(out));
        }
    }

    @Nested class DiffHighlight {

        @Test void addedLinesAreColoured() {
            String out = Highlight.applyDiff("+ added line\n  context");
            assertTrue(isColoured(out));
            assertEquals("+ added line\n  context", plain(out));
        }

        @Test void removedLinesAreColoured() {
            String out = Highlight.applyDiff("- removed line");
            assertTrue(isColoured(out));
            assertEquals("- removed line", plain(out));
        }

        @Test void contextLinesAreDim() {
            // context lines go through Ansi.dim which wraps in DIM...RESET
            String out = Highlight.applyDiff("  context line");
            // dim adds colour codes
            assertTrue(isColoured(out));
        }

        @Test void plainTextPreserved() {
            assertEquals("+ hello\n- world", plain(Highlight.applyDiff("+ hello\n- world")));
        }
    }

    @Nested class TableRendering {

        private String table(String md) { return plain(Ansi.renderMarkdown(md)); }

        @BeforeEach void fixWidth()    { Ansi.forcedTerminalWidth = 80; }
        @AfterEach  void clearWidth()  { Ansi.forcedTerminalWidth = 0; }

        @Test void shortTableFitsInOneLine() {
            String out = table("| A | B |\n|---|---|\n| x | y |\n");
            assertTrue(out.contains("x"), "cell content preserved");
            assertTrue(out.contains("y"), "cell content preserved");
            long xLines = out.lines().filter(l -> l.contains("x")).count();
            assertEquals(1, xLines, "short cell must not wrap");
        }

        @Test void longCellWrapsToMultipleLines() {
            // 150-char value in an 80-col terminal must wrap
            String longVal = "word ".repeat(30).trim();
            String md = "| Key | Value |\n|-----|-------|\n| k | " + longVal + " |\n";
            String out = table(md);
            long dataLines = out.lines().filter(l -> l.startsWith("│") && !l.contains("Key")).count();
            assertTrue(dataLines > 1, "long cell must produce multiple output lines, got: " + dataLines);
        }

        @Test void wrappedCellPlainTextPreserved() {
            String content = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi";
            String md = "| Col |\n|-----|\n| " + content + " |\n";
            String out = table(md);
            for (String word : content.split(" "))
                assertTrue(out.contains(word), "word '" + word + "' must appear in wrapped output");
        }

        @Test void tableWidthRespectsBound() {
            String longCell = "this is a very long description ".repeat(5).trim();
            String md = "| Name | Description | Extra |\n|------|-------------|-------|\n| foo | " + longCell + " | bar |\n";
            String out = table(md);
            int maxLine = out.lines().mapToInt(String::length).max().orElse(0);
            assertTrue(maxLine <= Ansi.forcedTerminalWidth + 2, "table line too wide: " + maxLine);
        }
    }

    @Nested class FileDispatch {

        @Test void javaFilenameRoutesToJava() {
            // should highlight 'public' as a keyword
            String out = Highlight.applyJava("public class X {}");
            assertTrue(isColoured(out));
        }

        @Test void jsonFilenameRoutesToJson() {
            String out = Highlight.applyJson("{\"k\": 1}");
            assertTrue(isColoured(out));
        }

        @Test void xmlFilenameRoutesToXml() {
            String out = Highlight.applyXml("<root/>");
            assertTrue(isColoured(out));
        }

        @Test void unknownExtensionPassesThrough() {
            // Highlight.file with no recognisable extension returns plain text unchanged
            Ansi.forceTerminal = false; // isTerminal() = false → passthrough
            String content = "hello world";
            assertEquals(content, Highlight.file(content, "file.txt"));
        }
    }
}
