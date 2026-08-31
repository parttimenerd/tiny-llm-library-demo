package me.bechberger.demo.solutions;

import me.bechberger.demo.http.Config;
import me.bechberger.demo.util.Repl;
import me.bechberger.femtocli.annotations.Option;

/**
 * Shared CLI options for all chatbot commands.
 * Declare as {@code @Mixin Options options;} in any {@code @Command} class.
 * <p>
 * Also acts as a factory: call {@link #createClient(Repl.Builder)} to get a
 * fully configured {@link me.bechberger.demo.LLMClient} wired to the REPL's token callback.
 */
public class Options {

    @Option(names = {"-m", "--model"},
            description = "Model: fast/medium/slow/kimi_k3 or a raw model ID (default: endpoint's model from config, else fast)")
    String model;

    @Option(names = {"-u", "--base-url"},
            description = "LLM endpoint: name from config (e.g. 'gardener'), URL, or url#token (default: ${DEFAULT-VALUE})",
            defaultValue = "http://localhost:8080")
    String baseUrl;

    @Option(names = {"--no-thinking"}, description = "Disable thinking/reasoning mode")
    boolean noThinking;

    @Option(names = {"--thinking-budget"}, description = "Cap thinking tokens (e.g. 1000)", defaultValue = "-1")
    int thinkingBudget;

    /** Resolve --model against the config file; returns null if nothing is configured (endpoint default). */
    public String resolveModel() {
        return model != null ? model : Config.load().modelFor(baseUrl, null);
    }

    /**
     * Create a fully configured {@link LLMClient} wired to {@code builder.tokenCallback}.
     */
    public LLMClient createClient(Repl.Builder builder) {
        return new LLMClient(baseUrl, resolveModel(), builder.tokenCallback)
                .withThinking(!noThinking)
                .withThinkingBudget(thinkingBudget);
    }
}
