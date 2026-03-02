package me.bechberger.demo.solutions;

import me.bechberger.demo.util.ModelSize;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p>
 * Uses femtocli for CLI argument parsing.
 */
@Command(name = "chatbot", description = "A simple streaming chatbot", version = "1.0.0")
public class ChatBot implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, description = "Model size: fast (1.7B), medium (9B), slow (27B) (default: ${DEFAULT-VALUE})",
            defaultValue = "fast")
    ModelSize modelSize;

    @Option(names = {"-u", "--base-url"}, description = "LLM API base URL (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    /**
     * Main REPL loop.
     * <p>
     * Flow:
     * 1. Create LLMClient with streaming callback (System.out::print for live output)
     * 2. Initialize empty messages list for conversation history
     * 3. Loop: read user input → append user message → call chatStream → append assistant message
     * <p>
     * Implementation: Build message maps with "role" and "content" keys → pass to chatStream
     */
    @Override
    public Integer call() {
        String model = modelSize.getModelId();
        
        var client = new LLMClient(baseUrl, model, System.out::print);
        var messages = new ArrayList<Map<String, Object>>();
        var scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou: ");
            if (!scanner.hasNextLine()) {
                return 0;
            }
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            var userMessage = new LinkedHashMap<String, Object>();
            userMessage.put("role", "user");
            userMessage.put("content", input);
            messages.add(userMessage);

            System.out.print("\nAssistant: ");
            String response = client.chatStream(messages);

            var assistantMessage = new LinkedHashMap<String, Object>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", response);
            messages.add(assistantMessage);

            System.out.println();
        }
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new ChatBot(), args));
    }
}
