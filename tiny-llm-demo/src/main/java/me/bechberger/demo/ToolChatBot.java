package me.bechberger.demo;

import me.bechberger.demo.FileTools;
import me.bechberger.demo.LLMClient;
import me.bechberger.demo.ToolSupport;
import me.bechberger.demo.http.Config;
import me.bechberger.demo.util.ModelSize;
import me.bechberger.demo.util.Repl;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Tool-enabled chatbot with file system access.
 * <p>
 * The tool loop is the key idea: LLM says "call ls" → we call it → we send the result back →
 * LLM continues. {@link ToolSupport#handleToolLoop} manages this while-loop automatically.
 * Tool registration (JSON schemas) is pre-written in {@link FileTools#registerTools} — it's
 * just plumbing; the interesting part is the loop and the conversation structure below.
 * <p>
 * Uses femtocli for CLI argument parsing.
 */
@Command(name = "tool-chatbot", description = "A chatbot with file system tools", version = "1.0.0")
public class ToolChatBot implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, description = "Model: enum name (fast/medium/slow/kimi_k3/…) or raw model ID like kimi-k3 (default: endpoint's model from config, else fast)")
    String model;

    @Option(names = {"-u", "--base-url"}, description = "LLM API base URL (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"-r", "--root"}, description = "Sandbox root directory (default: ${DEFAULT-VALUE})",
            defaultValue = ".")
    String root;

    @Option(names = {"--no-thinking"}, description = "Disable thinking/reasoning mode")
    boolean noThinking;

    @Option(names = {"--thinking-budget"}, description = "Cap thinking tokens (e.g. 1000)", defaultValue = "-1")
    int thinkingBudget;
    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant with access to sandboxed file tools. " +
            "Use ls, read-file, grep and find-file to explore the sandbox, " +
            "answer from what you find, and keep replies short.";

    @Override
    public Integer call() throws IOException, InterruptedException {
        var client = new LLMClient(baseUrl, ModelSize.resolveModelId(this.model != null ? this.model
                : Config.load().modelFor(baseUrl, ModelSize.FAST.getModelId())), System.out::print)
                .withThinking(!noThinking)
                .withThinkingBudget(thinkingBudget);
        String model = client.detectServerModelId();
        var toolSupport = new ToolSupport();
        var fileTools = new FileTools(Path.of(root));

        // Tool registration: name + description + JSON schema + handler.
        // This is pre-written boilerplate — see FileTools.registerTools for the details.
        fileTools.registerTools(toolSupport);

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(LLMClient.system(SYSTEM_PROMPT));

        var repl = new Repl.Builder("\nYou: ", new Scanner(System.in)).build();
        repl.greet("Tool Chatbot ready. Model: " + model);
        repl.run(input -> {
            messages.add(LLMClient.user(input));
            System.out.print("\nAssistant: ");
            // TODO: call handleToolLoop, print the response, add it to messages
            throw new UnsupportedOperationException("TODO: live code");
        });
        return 0;
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new ToolChatBot(), args));
    }
}
