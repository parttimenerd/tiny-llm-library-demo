package me.bechberger.demo.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny DSL for REPL chat commands such as {@code "/plan write tests"} or {@code "exit"} —
 * keeps the chatbot's main loop declarative. (Program CLI parsing stays with femtocli.)
 * <p>
 * A command is triggered slash-prefixed — the handler receives the rest of the line as
 * args — or as a bare full-line word, so both {@code /exit} and {@code exit} work.
 * Unknown slash commands are rejected with a hint instead of being sent to the LLM.
 * <pre>{@code
 * var commands = Commands.create()
 *         .on("exit", "leave the agent", args -> quit = true, "quit")
 *         .on("plan", "planning mode: /plan <goal>", args -> plan(args));
 * while (running) {
 *     if (commands.handle(input)) continue; // it was a command
 *     ...                                   // otherwise it's a chat message
 * }
 * }</pre>
 */
public final class Commands {

    /** Receives the text after the command name ({@code ""} if there is none). */
    @FunctionalInterface
    public interface Handler {
        void handle(String args) throws IOException;
    }

    private final Map<String, Handler> byName = new LinkedHashMap<>();
    private final List<String> helpLines = new ArrayList<>();

    private Commands() {}

    /** Creates a command set with {@code /help} already registered. */
    public static Commands create() {
        var commands = new Commands();
        commands.on("help", "list available commands", args -> System.out.println(commands.help()));
        return commands;
    }

    /**
     * Register a command; aliases are alternative names (all matched case-insensitively).
     *
     * @param name primary name without the slash, e.g. "plan"
     * @param description one-liner shown by /help
     * @param handler receives the text after the command name
     * @param aliases optional alternative names, e.g. "quit" for "exit"
     */
    public Commands on(String name, String description, Handler handler, String... aliases) {
        Handler previous = byName.put(name.toLowerCase(), handler);
        if (previous != null) {
            throw new IllegalArgumentException("duplicate command: " + name);
        }
        for (var alias : aliases) {
            byName.put(alias.toLowerCase(), handler);
        }
        helpLines.add("/" + name + (aliases.length == 0 ? "" : " (" + String.join(", ", aliases) + ")")
                + "  —  " + description);
        return this;
    }

    /**
     * Dispatch the input if it is a known command.
     *
     * @return true if the input was consumed as a command — including an unknown
     *         slash command, which is rejected with a help hint here
     */
    public boolean handle(String input) throws IOException {
        String s = input.trim();
        if (s.isEmpty()) return false;
        String name, args;
        if (s.startsWith("/")) {
            int space = s.indexOf(' ');
            name = space < 0 ? s.substring(1) : s.substring(1, space);
            args = space < 0 ? "" : s.substring(space + 1).trim();
        } else {
            // bare words only count as commands when the whole line is the command name ("exit")
            name = s;
            args = "";
        }
        var handler = byName.get(name.toLowerCase());
        if (handler == null) {
            if (s.startsWith("/")) {
                System.out.println("Unknown command: /" + name + "\n" + help());
                return true;
            }
            return false; // not a command — let the caller treat it as a chat message
        }
        handler.handle(args);
        return true;
    }

    /** All registered commands as a printable help text. */
    public String help() {
        return "Commands:\n  " + String.join("\n  ", helpLines);
    }
}
