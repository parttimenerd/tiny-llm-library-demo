package me.bechberger.demo.util;

import java.io.IOException;
import java.util.Scanner;

/**
 * Minimal REPL for the chatbots — one boring piece of plumbing instead of a
 * hand-rolled while/Scanner loop in every bot.
 * <p>
 * Prints the prompt, skips empty lines, dispatches slash-commands via {@link Commands}
 * (auto-registers {@code exit}/{@code quit} to end the loop after {@code help}), and
 * feeds everything else to the chat handler. Ends on Ctrl-D / piped-input EOF.
 * <pre>{@code
 * var repl = new Repl("\nYou: ", sharedScanner);
 * repl.commands().on("todos", "show TODOs", args -> printTodos());
 * repl.greet("Bot ready.");
 * repl.run(input -> chat(input));
 * }</pre>
 */
public final class Repl {

    /** Receives one non-command input line. */
    @FunctionalInterface
    public interface Chat {
        void chat(String input) throws IOException;
    }

    private final String prompt;
    private final Scanner scanner;
    private final Commands commands = Commands.create();
    private boolean stopped = false;

    /**
     * @param prompt printed before every input line, e.g. "\nYou: "
     * @param scanner shared System.in scanner — pass one in so sibling prompts
     *                (confirmations, plan acceptance) don't fight over the stream
     */
    public Repl(String prompt, Scanner scanner) {
        this.prompt = prompt;
        this.scanner = scanner;
        commands.on("exit", "leave the chat", args -> stop(), "quit");
    }

    /** The command table — add your own slash-commands here. */
    public Commands commands() {
        return commands;
    }

    /** End the run loop after the current iteration. */
    public void stop() {
        stopped = true;
    }

    /** Print a one-line greeting followed by the command help. */
    public void greet(String line) {
        System.out.println(line);
        System.out.println(commands.help());
    }

    /** Run prompt → dispatch commands → chat, until exit/quit or EOF. */
    public void run(Chat chat) throws IOException {
        while (!stopped) {
            System.out.print(prompt);
            if (!scanner.hasNextLine()) break; // EOF (Ctrl-D / piped input exhausted)
            String input = scanner.nextLine().trim();
            if (System.console() == null) System.out.println(input); // piped: echo, so logs stay readable
            if (input.isEmpty()) continue;
            if (commands.handle(input)) continue;
            chat.chat(input);
        }
    }
}
