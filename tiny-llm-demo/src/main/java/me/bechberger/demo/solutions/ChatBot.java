package me.bechberger.demo.solutions;

import me.bechberger.demo.LLMClient;
import me.bechberger.demo.util.Ansi;
import me.bechberger.demo.util.Repl;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Mixin;

import java.util.ArrayList;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Simple streaming chatbot with conversation history.
 * <p>
 * Features:
 * - Uses LLMClient with streaming (tokens printed as they arrive)
 * - Maintains conversation history in messages list
 * - REPL loop: read user input → add to messages → stream response → add to messages → repeat
 * <p>
 * Message history format: {@code [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]}
 */
@Command(name = "chatbot", description = "A simple streaming chatbot", version = "1.0.0")
public class ChatBot implements Callable<Integer> {

    @Mixin
    Options options;

    /**
     * Main REPL loop.
     * <p>
     * Flow:
     * 1. Create LLMClient with streaming callback (System.out::print for live output)
     * 2. Initialize empty messages list for conversation history
     * 3. Loop: read user input → append user message → call chatStream → append assistant message
     */
    @Override
    public Integer call() {
        var messages = new ArrayList<Map<String, Object>>();
        var builder = new Repl.Builder("\nYou: ", new Scanner(System.in), messages);

        // @stub: createClient; build repl; greet; repl.run: add user msg, print "\nAssistant: ", chatStream, add assistant msg
        var client = options.createClient(builder);
        var repl = builder.build();
        repl.greet("ChatBot ready. Model: " + options.resolveModel());
        repl.run(input -> {
            messages.add(LLMClient.user(input));
            System.out.print(Ansi.bold(Ansi.green("\nAssistant: ")));
            String response = client.chatStream(messages);
            messages.add(LLMClient.assistant(response));
            System.out.println();
        });
        return 0;
        // @end
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new ChatBot(), args));
    }
}
