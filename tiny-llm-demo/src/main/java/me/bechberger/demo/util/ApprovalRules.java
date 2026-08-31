package me.bechberger.demo.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny in-memory allow/deny rule engine for agent action strings.
 * Rules are checked in insertion order; first match wins.
 * Pattern syntax: literal text with {@code *} as a wildcard matching any sequence of characters.
 * <p>
 * Action strings have the form {@code "run: <command>"}, {@code "delete: <path>"}, {@code "plan: <text>"}.
 */
public final class ApprovalRules {

    public enum Effect { ALLOW, DENY }
    public record Rule(Effect effect, String pattern) {}

    private final List<Rule> rules = new ArrayList<>();

    public void allow(String pattern) { rules.add(new Rule(Effect.ALLOW, pattern)); }
    public void deny(String pattern)  { rules.add(new Rule(Effect.DENY,  pattern)); }

    /** Returns ALLOW, DENY, or null (no match — fall through to mode-based logic). */
    public Effect match(String action) {
        for (var rule : rules)
            if (globMatches(rule.pattern(), action)) return rule.effect();
        return null;
    }

    public List<Rule> rules() { return List.copyOf(rules); }

    private static boolean globMatches(String pattern, String text) {
        int pi = 0, ti = 0, starPi = -1, starTi = -1;
        while (ti < text.length()) {
            if (pi < pattern.length() && pattern.charAt(pi) == '*') { starPi = pi++; starTi = ti; }
            else if (pi < pattern.length() && pattern.charAt(pi) == text.charAt(ti)) { pi++; ti++; }
            else if (starPi >= 0) { pi = starPi + 1; ti = ++starTi; }
            else return false;
        }
        while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
        return pi == pattern.length();
    }
}
