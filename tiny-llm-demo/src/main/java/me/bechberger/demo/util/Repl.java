package me.bechberger.demo.util;

import java.io.IOException;
import java.util.Scanner;
import java.util.function.Supplier;

/**
 * Minimal REPL framework for the chatbots - prompt loop, slash-commands, multi-line
 * input. One boring piece of plumbing instead of a hand-rolled while/Scanner loop
 * in every bot.
 * <p>
 * - prompt comes from a {@link Supplier}, so a mode badge can change it live
 *   ({@code /mode}, {@code /yolo})
 * - a trailing {@code \} continues input on the next line (pasting multi-line text)
 * - piped input (no console) is echoed, so scripted sessions stay readable in logs
 * - slash-commands dispatch via {@link Commands} (auto-registers exit/quit + /help);
 *   unknown slash-commands are rejected locally instead of burning an LLM call
 * - ends on exit/quit or EOF (Ctrl-D)
 * <pre>{@code
 * var repl = new Repl("\nYou: ", sharedScanner);
 * repl.setPrompt(() -> "\n" + mode.badge() + "You: ");
 * repl.commands().on("todos", "show TODOs", args -> printTodos());
 * repl.greet("Bot ready.");
 * repl.run(input -> chat(input));
 * }</pre>
 */
public final class Repl {

    /** Receives one non-command input (possibly multi-line). */
    @FunctionalInterface
    public interface Chat {
        void chat(String input) throws IOException;
    }

    private final Scanner scanner;
    private final Commands commands = Commands.create();
    private Supplier<String> prompt;
    private boolean stopped = false;

    /**
     * @param prompt printed before every input line, e.g. "\nYou: "
     * @param scanner shared System.in scanner - pass one in so sibling prompts
     *                (confirmations, plan acceptance) don't fight over the stream
     */
    public Repl(String prompt, Scanner scanner) {
        this.scanner = scanner;
        this.prompt = () -> prompt;
        commands.on("exit", "leave the chat", args -> stop(), "quit");
    }

    /** Dynamic prompt - evaluated before every input line (mode badges, cwd, ...). */
    public void setPrompt(Supplier<String> prompt) {
        this.prompt = prompt;
    }

    /** The command table - add your own slash-commands here. */
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

    /** Run prompt - dispatch commands - chat, until exit/quit or EOF. */
    public void run(Chat chat) throws IOException {
        while (!stopped) {
            System.out.print(prompt.get());
            if (!scanner.hasNextLine()) break; // EOF (Ctrl-D / piped input exhausted)
            String input = readLogicalLine().trim();
            if (input.isEmpty()) continue;
            if (commands.handle(input)) continue;
            chat.chat(input);
        }
    }

    /**
     * One logical input line: a trailing backslash continues onto the next line
     * (the classic shell paste idiom), with a continuation prompt per extra line.
     */
    private String readLogicalLine() {
        String line = scanner.nextLine();
        echoIfPiped(line);
        var sb = new StringBuilder(line);
        while (sb.toString().endsWith("\\")) {
            sb.setLength(sb.length() - 1);
            System.out.print("  ... ");
            if (!scanner.hasNextLine()) break;
            line = scanner.nextLine();
            echoIfPiped(line);
            sb.append('\n').append(line);
        }
        return sb.toString();
    }

    private static void echoIfPiped(String line) {
        if (System.console() == null) {
            System.out.println(line); // scripted sessions: make logs/slide excerpts readable
        }
    }
}
