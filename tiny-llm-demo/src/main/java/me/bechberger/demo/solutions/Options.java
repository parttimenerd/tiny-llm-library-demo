package me.bechberger.demo.solutions;

import me.bechberger.demo.http.Config;
import me.bechberger.demo.util.ModelSize;
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

    @Option(names = {"--verbose"}, description = "Show the live message sidebar")
    boolean verbose;

    /** Resolve --model (name or raw ID) against the config file, falling back to fast. */
    public String resolveModel() {
        return ModelSize.resolveModelId(
                model != null ? model : Config.load().modelFor(baseUrl, ModelSize.FAST.getModelId()));
    }

    /**
     * Create a fully configured {@link me.bechberger.demo.LLMClient} wired to
     * {@code builder.tokenCallback} (which already handles pause when --verbose is active).
     * Also calls {@link Repl.Builder#showSidebar} when --verbose is set.
     */
    public me.bechberger.demo.LLMClient createClient(Repl.Builder builder) {
        var client = new me.bechberger.demo.LLMClient(baseUrl, resolveModel(), builder.tokenCallback)
                .withThinking(!noThinking)
                .withThinkingBudget(thinkingBudget);
        if (verbose) builder.showSidebar(client::lastUsage);
        return client;
    }
}
