package me.bechberger.demo.util;

import me.bechberger.demo.LLMClient;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Visual smoke test — prints the sidebar to stdout so a developer can eyeball the layout.
 * Not an assertion-based test; run with -s (show output) to see the result:
 *   mvn test -pl . -Dtest=SidebarVisualTest -Dsurefire.useFile=false
 */
class SidebarVisualTest {

    @Test
    void printSidebarLayouts() {
        Ansi.forceTerminal = true;
        try {
            var messages = buildRealisticMessages();
            var sidebar = new Sidebar(messages);
            sidebar.updateUsage(1842, 312, 32768);

            section("COLLAPSED (normal view)");
            print(sidebar.buildRows());

            section("PAUSED");
            sidebar.togglePause();
            print(sidebar.buildRows());
            sidebar.togglePause();

            section("SELECTED = row 3 (tool result, Java file)");
            sidebar.scrollDown(); sidebar.scrollDown(); sidebar.scrollDown();
            print(sidebar.buildRows());

            section("EXPANDED row 3 (tool result — Java file, should be syntax-highlighted)");
            sidebar.toggleExpand();
            print(sidebar.buildRows());
            sidebar.toggleExpand();

            section("EXPANDED row 5 (diff tool result — should be diff-highlighted)");
            sidebar.scrollDown(); sidebar.scrollDown();
            sidebar.toggleExpand();
            print(sidebar.buildRows());
            sidebar.toggleExpand();

            section("EXPAND ALL");
            sidebar.toggleExpandAll();
            print(sidebar.buildRows());
            sidebar.toggleExpandAll();

            section("SCROLLED DOWN 20 MESSAGES (shows scroll hint ↑ in header)");
            for (int i = 0; i < 20; i++) messages.add(LLMClient.user("extra message " + i));
            sidebar = new Sidebar(messages);
            sidebar.updateUsage(8000, 400, 32768);
            for (int i = 0; i < 25; i++) sidebar.scrollDown();
            print(sidebar.buildRows());

        } finally {
            Ansi.forceTerminal = false;
        }
    }

    private static List<Map<String, Object>> buildRealisticMessages() {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system("You are a coding assistant. Use tools only, keep replies brief."));
        messages.add(LLMClient.user("create a Calculator.java that supports +, -, *, /"));

        // assistant tool call: read-file
        var fn1 = Map.of("name", "read-file", "arguments", "{\"path\":\"src/Calculator.java\"}");
        var tc1 = List.of(Map.of("id", "call_1", "function", fn1));
        var a1 = new LinkedHashMap<String, Object>();
        a1.put("role", "assistant"); a1.put("tool_calls", tc1);
        messages.add(a1);

        // tool result: Java file
        var t1 = new LinkedHashMap<String, Object>();
        t1.put("role", "tool"); t1.put("tool_call_id", "call_1");
        t1.put("content", "public class Calculator {\n    public double add(double a, double b) { return a + b; }\n    public double sub(double a, double b) { return a - b; }\n    public double mul(double a, double b) { return a * b; }\n    public double div(double a, double b) {\n        if (b == 0) throw new ArithmeticException(\"div by zero\");\n        return a / b;\n    }\n}");
        messages.add(t1);

        // assistant tool call: edit
        var fn2 = Map.of("name", "edit", "arguments", "{\"path\":\"src/Calculator.java\",\"old\":\"a + b\",\"new\":\"a + b // add\"}");
        var tc2 = List.of(Map.of("id", "call_2", "function", fn2));
        var a2 = new LinkedHashMap<String, Object>();
        a2.put("role", "assistant"); a2.put("tool_calls", tc2);
        messages.add(a2);

        // tool result: diff
        var t2 = new LinkedHashMap<String, Object>();
        t2.put("role", "tool"); t2.put("tool_call_id", "call_2");
        t2.put("content", "- return a + b;\n+ return a + b; // add\n  (context line)");
        messages.add(t2);

        messages.add(LLMClient.assistant("Done! Calculator.java created with all four operations and verified."));
        return messages;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("══════════════════════════════════════════ " + title + " ══");
    }

    private static void print(List<String> rows) {
        // simulate left column occupying ~44 chars
        String indent = " ".repeat(44);
        for (var row : rows) {
            System.out.println(indent + row);
        }
    }
}
