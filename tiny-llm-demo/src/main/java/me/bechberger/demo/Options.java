package me.bechberger.demo;

import me.bechberger.demo.http.Config;
import me.bechberger.demo.http.HttpHelper;
import me.bechberger.demo.util.Repl;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;

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

    /** Resolve --model against the config file; falls back to first model from /v1/models. */
    public String resolveModel() {
        if (model != null) return model;
        String configured = Config.load().modelFor(baseUrl, null);
        if (configured != null) return configured;
        try {
            var json = Util.asMap(JSONParser.parse(new HttpHelper(baseUrl).get("/v1/models")));
            var data = Util.asList(json.get("data"));
            if (!data.isEmpty()) return (String) Util.asMap(data.getFirst()).get("id");
        } catch (Exception ignored) {}
        return null;
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
