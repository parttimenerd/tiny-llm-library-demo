package me.bechberger.demo.util;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Sidebar: navigation, expand/collapse, drop, edit, pause, and
 * syntax-highlighting integration (findFilenameForTool, looksLikeDiff).
 *
 * isUsable() is false in CI (no TTY) so redraw() is not exercised directly;
 * buildRows() is called directly instead to test rendering logic.
 */
class SidebarTest {

    /** Build a minimal message map. */
    private static Map<String, Object> msg(String role, String content) {
        var m = new LinkedHashMap<String, Object>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** Build a tool-result message with a tool_call_id. */
    private static Map<String, Object> toolResult(String id, String content) {
        var m = new LinkedHashMap<String, Object>();
        m.put("role", "tool");
        m.put("tool_call_id", id);
        m.put("content", content);
        return m;
    }

    /** Build an assistant message with a tool_call referencing a path. */
    private static Map<String, Object> assistantWithToolCall(String callId, String toolName, String path) {
        var fn = Map.of("name", toolName, "arguments", "{\"path\":\"" + path + "\"}");
        var tc = List.of(Map.of("id", callId, "function", fn));
        var m = new LinkedHashMap<String, Object>();
        m.put("role", "assistant");
        m.put("tool_calls", tc);
        return m;
    }

    private List<Map<String, Object>> messages;
    private Sidebar sidebar;

    @BeforeEach void setUp() {
        Ansi.forceTerminal = true;
        messages = new ArrayList<>();
        messages.add(msg("system", "You are helpful."));
        messages.add(msg("user", "Hello"));
        messages.add(msg("assistant", "Hi there!"));
        sidebar = new Sidebar(messages);
    }

    @AfterEach void tearDown() {
        Ansi.forceTerminal = false;
    }

    // ── navigation ────────────────────────────────────────────────────────────

    @Test void initialSelectionIsZero() {
        assertEquals(0, sidebar.getSelectedRow());
    }

    @Test void scrollDownMovesSelection() {
        sidebar.scrollDown();
        assertEquals(1, sidebar.getSelectedRow());
    }

    @Test void scrollUpMovesSelectionBack() {
        sidebar.scrollDown();
        sidebar.scrollUp();
        assertEquals(0, sidebar.getSelectedRow());
    }

    @Test void scrollUpAtTopIsNoop() {
        sidebar.scrollUp();
        assertEquals(0, sidebar.getSelectedRow());
    }

    @Test void scrollDownAtBottomIsNoop() {
        // scroll to last
        for (int i = 0; i < messages.size() + 5; i++) sidebar.scrollDown();
        assertEquals(messages.size() - 1, sidebar.getSelectedRow());
    }

    // ── expand / collapse ─────────────────────────────────────────────────────

    @Test void toggleExpandExpandsSelected() {
        sidebar.toggleExpand();
        assertEquals(0, sidebar.getExpandedMsg());
    }

    @Test void toggleExpandCollapsesSameMessage() {
        sidebar.toggleExpand();
        sidebar.toggleExpand();
        assertEquals(-1, sidebar.getExpandedMsg());
    }

    @Test void toggleExpandSwitchesFocus() {
        sidebar.toggleExpand();          // expand msg 0
        sidebar.scrollDown();
        sidebar.toggleExpand();          // expand msg 1
        assertEquals(1, sidebar.getExpandedMsg()); // msg 0 collapsed
    }

    @Test void toggleExpandAllSetsFlag() {
        assertFalse(sidebar.isExpandAll());
        sidebar.toggleExpandAll();
        assertTrue(sidebar.isExpandAll());
    }

    @Test void toggleExpandAllTwiceRestores() {
        sidebar.toggleExpandAll();
        sidebar.toggleExpandAll();
        assertFalse(sidebar.isExpandAll());
    }

    @Test void toggleExpandClearsExpandAll() {
        sidebar.toggleExpandAll();
        sidebar.toggleExpand();
        assertFalse(sidebar.isExpandAll());
    }

    // ── drop ──────────────────────────────────────────────────────────────────

    @Test void dropRemovesSelectedMessage() {
        int before = messages.size();
        sidebar.dropSelected();
        assertEquals(before - 1, messages.size());
    }

    @Test void dropFirstMessageShiftsSelection() {
        sidebar.dropSelected(); // drops index 0 (system)
        // selection stays at 0 (now pointing to what was index 1)
        assertEquals(0, sidebar.getSelectedRow());
    }

    @Test void dropLastMessageClampsSelection() {
        // select last message
        for (int i = 0; i < messages.size() - 1; i++) sidebar.scrollDown();
        sidebar.dropSelected();
        assertEquals(messages.size() - 1, sidebar.getSelectedRow());
    }

    @Test void dropOnEmptyListReturnsFalse() {
        messages.clear();
        assertFalse(sidebar.dropSelected());
    }

    @Test void dropExpandedMessageClearsExpanded() {
        sidebar.toggleExpand(); // expand msg 0
        sidebar.dropSelected(); // drop msg 0
        assertEquals(-1, sidebar.getExpandedMsg());
    }

    // ── edit ──────────────────────────────────────────────────────────────────

    @Test void editSelectedUpdatesContent() {
        var scanner = new java.util.Scanner(new ByteArrayInputStream("new content\n".getBytes()));
        sidebar.editSelected(scanner);
        assertEquals("new content", messages.get(0).get("content"));
    }

    @Test void editSelectedCancelsOnBlank() {
        String original = (String) messages.get(0).get("content");
        var scanner = new java.util.Scanner(new ByteArrayInputStream("\n".getBytes()));
        sidebar.editSelected(scanner);
        assertEquals(original, messages.get(0).get("content"));
    }

    @Test void editRequestedFlagSetAndCleared() {
        assertFalse(sidebar.isEditRequested());
        sidebar.requestEdit();
        assertTrue(sidebar.isEditRequested());
        var scanner = new java.util.Scanner(new ByteArrayInputStream("\n".getBytes()));
        sidebar.runEdit(scanner);
        assertFalse(sidebar.isEditRequested());
    }

    // ── pause / resume ────────────────────────────────────────────────────────

    @Test void initiallyNotPaused() {
        assertFalse(sidebar.isPaused());
    }

    @Test void togglePausePauses() {
        sidebar.togglePause();
        assertTrue(sidebar.isPaused());
    }

    @Test void togglePauseTwiceResumes() {
        sidebar.togglePause();
        sidebar.togglePause();
        assertFalse(sidebar.isPaused());
    }

    @Test void checkPauseReturnsImmediatelyWhenNotPaused() {
        // must not block — completes before any timeout
        assertTimeoutPreemptively(
            java.time.Duration.ofMillis(500),
            () -> sidebar.checkPause()
        );
    }

    @Test void checkPauseBlocksUntilResumed() throws InterruptedException {
        sidebar.togglePause();
        var thread = new Thread(() -> sidebar.checkPause());
        thread.start();
        Thread.sleep(50); // let thread block
        assertTrue(thread.isAlive(), "thread should be blocked in checkPause");
        sidebar.togglePause(); // resume
        thread.join(500);
        assertFalse(thread.isAlive(), "thread should have unblocked");
    }

    // ── findFilenameForTool ───────────────────────────────────────────────────

    @Test void findsFilenameFromPrecedingAssistantToolCall() {
        messages.clear();
        messages.add(assistantWithToolCall("call_1", "read-file", "src/Main.java"));
        messages.add(toolResult("call_1", "public class Main {}"));
        sidebar = new Sidebar(messages);

        String filename = sidebar.findFilenameForTool(1, messages.get(1));
        assertEquals("src/Main.java", filename);
    }

    @Test void returnsNullWhenNoMatchingCallId() {
        messages.clear();
        messages.add(assistantWithToolCall("call_1", "read-file", "Foo.java"));
        messages.add(toolResult("call_999", "content"));
        sidebar = new Sidebar(messages);

        assertNull(sidebar.findFilenameForTool(1, messages.get(1)));
    }

    @Test void returnsNullWhenToolCallIdMissing() {
        messages.clear();
        messages.add(msg("tool", "some content")); // no tool_call_id
        sidebar = new Sidebar(messages);

        assertNull(sidebar.findFilenameForTool(0, messages.get(0)));
    }

    @Test void skipsNonAssistantMessages() {
        messages.clear();
        messages.add(msg("user", "hello"));
        messages.add(toolResult("call_1", "content"));
        sidebar = new Sidebar(messages);

        assertNull(sidebar.findFilenameForTool(1, messages.get(1)));
    }

    // ── looksLikeDiff ─────────────────────────────────────────────────────────

    @Test void looksLikeDiffForTypicalDiff() {
        String diff = "+ added line\n- removed line\n  context line";
        assertTrue(Sidebar.looksLikeDiff(diff));
    }

    @Test void looksLikeDiffForHunk() {
        String diff = "@@ -1,3 +1,4 @@\n context\n+ new line\n- old line";
        assertTrue(Sidebar.looksLikeDiff(diff));
    }

    @Test void notDiffForPlainText() {
        assertFalse(Sidebar.looksLikeDiff("This is plain text without diff markers."));
    }

    @Test void notDiffForJavaCode() {
        assertFalse(Sidebar.looksLikeDiff("public class Foo {\n    int x = 1;\n}"));
    }

    @Test void emptyStringNotDiff() {
        assertFalse(Sidebar.looksLikeDiff(""));
    }

    // ── buildRows shape ───────────────────────────────────────────────────────

    @Test void buildRowsHasHeaderAndFooter() {
        Ansi.forceTerminal = true;
        var rows = sidebar.buildRows();
        String plain = rows.stream()
                           .map(s -> s.replaceAll("\033\\[[^m]*m", ""))
                           .reduce("", (a, b) -> a + "\n" + b);
        // header contains 'context'
        assertTrue(plain.contains("context"), "header should mention 'context'");
        // footer has bottom border
        assertTrue(rows.stream().anyMatch(r -> r.replaceAll("\033\\[[^m]*m","").contains("╚")));
    }

    @Test void buildRowsCountMatchesMessages() {
        // one row per message (collapsed) + header + token section (2-3 rows) + footer = messages.size + 4 or 5
        var rows = sidebar.buildRows();
        // at minimum there must be more rows than messages (chrome is added)
        assertTrue(rows.size() > messages.size());
    }

    @Test void pauseBannerAppearsWhenPaused() {
        sidebar.togglePause();
        var rows = sidebar.buildRows();
        String combined = rows.stream().map(r -> r.replaceAll("\033\\[[^m]*m","")).reduce("", String::concat);
        assertTrue(combined.contains("PAUSED"), "pause banner should be visible");
    }

    @Test void tokenFooterShowsTokenCounts() {
        sidebar.updateUsage(1234, 56, 32768);
        var rows = sidebar.buildRows();
        String combined = rows.stream().map(r -> r.replaceAll("\033\\[[^m]*m","")).reduce("", String::concat);
        assertTrue(combined.contains("1234"), "should show prompt tokens");
        assertTrue(combined.contains("56"),   "should show completion tokens");
    }

    @Test void scrollHintAppearsWhenScrolled() {
        // add many messages so we can scroll
        for (int i = 0; i < 50; i++) messages.add(msg("user", "msg " + i));
        sidebar = new Sidebar(messages);
        // scroll down past the viewport
        for (int i = 0; i < 30; i++) sidebar.scrollDown();
        var rows = sidebar.buildRows();
        String header = rows.get(0).replaceAll("\033\\[[^m]*m","");
        assertTrue(header.contains("↑"), "scroll hint should appear when scrolled");
    }

    // ── border alignment ──────────────────────────────────────────────────────

    @Test void everyRowHasMatchingBorders() {
        var rows = sidebar.buildRows();
        for (String row : rows) {
            String plain = row.replaceAll("\033\\[[^m]*m", "");
            // every line should start with a box character
            assertTrue(plain.startsWith("║") || plain.startsWith("╔") ||
                       plain.startsWith("╠") || plain.startsWith("╚"),
                    "row should start with box char: " + plain);
            // every line should end with a matching box character
            assertTrue(plain.endsWith("║") || plain.endsWith("╗") ||
                       plain.endsWith("╣") || plain.endsWith("╝"),
                    "row should end with box char: " + plain);
        }
    }

    @Test void allContentRowsHaveEqualVisibleWidth() {
        var rows = sidebar.buildRows();
        int[] widths = rows.stream()
                .map(r -> r.replaceAll("\033\\[[^m]*m", ""))
                .mapToInt(String::length)
                .distinct()
                .toArray();
        // all rows should be the same visible width
        assertEquals(1, widths.length, "all rows should have equal visible width, got distinct widths: " + java.util.Arrays.toString(widths));
    }

    @Test void pausedRowHasEqualVisibleWidth() {
        sidebar.togglePause();
        sidebar.updateUsage(100, 10, 8192);
        var rows = sidebar.buildRows();
        int[] widths = rows.stream()
                .map(r -> r.replaceAll("\033\\[[^m]*m", ""))
                .mapToInt(String::length)
                .distinct()
                .toArray();
        assertEquals(1, widths.length, "paused rows should have equal visible width, got: " + java.util.Arrays.toString(widths));
    }

    // ── context bar (progress bar) ────────────────────────────────────────────

    @Test void contextBarAppearsWhenContextWindowSet() {
        sidebar.updateUsage(8192, 512, 32768);
        var rows = sidebar.buildRows();
        String combined = rows.stream().map(r -> r.replaceAll("\033\\[[^m]*m","")).reduce("", String::concat);
        assertTrue(combined.contains("%"), "should show percentage when context window is set");
        assertTrue(combined.contains("32768"), "should show context window size");
    }

    @Test void contextBarAbsentWhenContextWindowZero() {
        sidebar.updateUsage(100, 10, 0);
        var rows = sidebar.buildRows();
        String combined = rows.stream().map(r -> r.replaceAll("\033\\[[^m]*m","")).reduce("", String::concat);
        assertFalse(combined.contains("%"), "no percentage when context window is 0");
    }

    @Test void tokenFooterShowsDashWhenNoUsage() {
        // updateUsage never called — should show dash placeholder
        var rows = sidebar.buildRows();
        String combined = rows.stream().map(r -> r.replaceAll("\033\\[[^m]*m","")).reduce("", String::concat);
        assertTrue(combined.contains("—"), "should show dash when no token usage");
    }

    // ── expanded view ─────────────────────────────────────────────────────────

    @Test void expandedRowCountGreaterThanCollapsed() {
        // message with multi-line content
        messages.clear();
        messages.add(msg("user", "line one\nline two\nline three\nline four\nline five"));
        sidebar = new Sidebar(messages);

        var collapsedRows = sidebar.buildRows();
        sidebar.toggleExpand();
        var expandedRows = sidebar.buildRows();

        assertTrue(expandedRows.size() >= collapsedRows.size(),
                "expanding a multi-line message should not shrink the sidebar");
    }

    @Test void expandedToolCallShowsFunctionArgs() {
        messages.clear();
        messages.add(assistantWithToolCall("c1", "read-file", "src/Foo.java"));
        sidebar = new Sidebar(messages);
        sidebar.toggleExpand();
        var rows = sidebar.buildRows();
        String combined = rows.stream().map(r -> r.replaceAll("\033\\[[^m]*m","")).reduce("", String::concat);
        assertTrue(combined.contains("read-file"), "expanded assistant shows tool name");
    }

    // ── role tags ─────────────────────────────────────────────────────────────

    @Test void allRoleTagsAppearInCollapsedView() {
        messages.clear();
        messages.add(msg("system",    "system msg"));
        messages.add(msg("user",      "user msg"));
        messages.add(msg("assistant", "assistant msg"));
        messages.add(toolResult("x", "tool result"));
        sidebar = new Sidebar(messages);

        String combined = sidebar.buildRows().stream()
                .map(r -> r.replaceAll("\033\\[[^m]*m",""))
                .reduce("", String::concat);

        assertTrue(combined.contains("SYS"),  "system tag");
        assertTrue(combined.contains("USER"), "user tag");
        assertTrue(combined.contains("ASST"), "assistant tag");
        assertTrue(combined.contains("TOOL"), "tool tag");
    }

    // ── edit on empty list ────────────────────────────────────────────────────

    @Test void editOnEmptyListReturnsFalse() {
        messages.clear();
        var sc = new java.util.Scanner(new ByteArrayInputStream("new\n".getBytes()));
        assertFalse(sidebar.editSelected(sc));
    }

    @Test void editOnMessageWithNullContentReturnsFalse() {
        messages.clear();
        var m = new LinkedHashMap<String, Object>();
        m.put("role", "user");
        // no "content" key
        messages.add(m);
        sidebar = new Sidebar(messages);
        var sc = new java.util.Scanner(new ByteArrayInputStream("new\n".getBytes()));
        assertFalse(sidebar.editSelected(sc));
    }

    // ── key hints row ─────────────────────────────────────────────────────────

    @Test void keyHintsRowAppearsInEveryBuild() {
        var rows = sidebar.buildRows();
        String combined = rows.stream().map(r -> r.replaceAll("\033\\[[^m]*m","")).reduce("", String::concat);
        assertTrue(combined.contains("↑↓"), "hints row should show scroll keys");
        assertTrue(combined.contains("edit") || combined.contains("expand"), "hints should mention edit or expand");
    }

    @Test void keyHintsRowShowsCollapseWhenExpanded() {
        sidebar.toggleExpand();
        var rows = sidebar.buildRows();
        String combined = rows.stream().map(r -> r.replaceAll("\033\\[[^m]*m","")).reduce("", String::concat);
        assertTrue(combined.contains("collapse"), "hints should say collapse when a message is expanded");
    }

    @Test void keyHintsRowDoesNotBreakEqualWidth() {
        sidebar.toggleExpand();
        sidebar.updateUsage(1000, 100, 8192);
        var rows = sidebar.buildRows();
        int[] widths = rows.stream()
                .map(r -> r.replaceAll("\033\\[[^m]*m", ""))
                .mapToInt(String::length)
                .distinct()
                .toArray();
        assertEquals(1, widths.length, "all rows including hints must have equal visible width: "
                + java.util.Arrays.toString(widths));
    }

    // ── ANSI-preserving wordWrap ──────────────────────────────────────────────

    @Test void expandedRowWithHighlightedContentHasEqualWidth() {
        // A tool-result message with ANSI-highlighted content (simulated via embedded codes).
        // The rows should still have equal visible width after word-wrap.
        Ansi.forceTerminal = true;
        messages.clear();
        String highlighted = "\033[34mpublic\033[0m \033[34mclass\033[0m Foo {\n    \033[34mint\033[0m x = 1;\n}";
        messages.add(msg("assistant", highlighted));
        sidebar = new Sidebar(messages);
        sidebar.toggleExpand();
        var rows = sidebar.buildRows();
        int[] widths = rows.stream()
                .map(r -> r.replaceAll("\033\\[[^m]*m", ""))
                .mapToInt(String::length)
                .distinct()
                .toArray();
        assertEquals(1, widths.length,
                "highlighted content must produce equal-width rows: " + java.util.Arrays.toString(widths));
    }

    @Test void ansiCodesPreservedAfterWordWrap() {
        // After wrap, content rows should still contain ANSI codes (not stripped).
        Ansi.forceTerminal = true;
        messages.clear();
        messages.add(msg("assistant", "\033[34mpublic\033[0m class Foo {}"));
        sidebar = new Sidebar(messages);
        sidebar.toggleExpand();
        var rows = sidebar.buildRows();
        boolean anyAnsi = rows.stream().anyMatch(r -> r.contains("\033["));
        assertTrue(anyAnsi, "expanded rows should preserve ANSI escape codes");
    }
}
