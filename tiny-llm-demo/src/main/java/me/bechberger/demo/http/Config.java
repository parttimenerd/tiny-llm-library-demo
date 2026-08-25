package me.bechberger.demo.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps base URLs to API keys, loaded from a plain-text config file:
 * <p>
 *   {@code ~/.config/tiny-llm-library/config.config}
 *   (honors {@code $XDG_CONFIG_HOME}; the {@value #PATH_ENV_VAR} environment variable
 *   overrides the location entirely)
 * <p>
 * Format — one key per URL, one entry per line:
 * <pre>
 * # &lt;base-url&gt;=&lt;api-key&gt;
 * https://api.openai.com/v1=sk-...
 * http://localhost:8080=local-dev-key
 * </pre>
 * Blank lines and lines starting with {@code #} are ignored. Lines are split at the
 * first {@code =}, so URLs keep their colons — this is deliberately not
 * {@link java.util.Properties}, which would misparse "{@code http://...}" keys.
 * A missing file simply means "no keys configured", which is fine for local servers.
 */
public final class Config {

    /** Environment variable that overrides the config file location. */
    public static final String PATH_ENV_VAR = "TINY_LLM_CONFIG";

    private final Map<String, String> apiKeysByBaseUrl;

    private Config(Map<String, String> apiKeysByBaseUrl) {
        this.apiKeysByBaseUrl = apiKeysByBaseUrl;
    }

    /** An empty config — every lookup returns {@code null}. */
    public static Config empty() {
        return new Config(Map.of());
    }

    /** Loads from {@link #defaultPath()}, or from the path in {@value #PATH_ENV_VAR} if set. */
    public static Config load() {
        String override = System.getenv(PATH_ENV_VAR);
        return load(override != null && !override.isBlank() ? Path.of(override.trim()) : defaultPath());
    }

    /**
     * Default config file location:
     * {@code $XDG_CONFIG_HOME/tiny-llm-library/config.config}, falling back to
     * {@code ~/.config/tiny-llm-library/config.config}.
     */
    public static Path defaultPath() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank()
                ? Path.of(xdg.trim())
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("tiny-llm-library").resolve("config.config");
    }

    /**
     * Loads the key mappings from the given file.
     * <p>
     * A non-existent file yields an empty config rather than an error —
     * most local LLM servers don't need a key at all.
     *
     * @throws UncheckedIOException if the file exists but cannot be read
     */
    public static Config load(Path path) {
        if (!Files.exists(path)) {
            return empty();
        }
        Map<String, String> keys = new LinkedHashMap<>();
        try (var lines = Files.lines(path)) {
            lines.map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> {
                        int eq = line.indexOf('=');
                        if (eq <= 0) {
                            return; // no '=' or empty URL — skip malformed line
                        }
                        String url = normalize(line.substring(0, eq));
                        String key = line.substring(eq + 1).trim();
                        if (!url.isEmpty() && !key.isEmpty()) {
                            keys.put(url, key);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read API key config: " + path, e);
        }
        return new Config(keys);
    }

    /**
     * The API key configured for {@code baseUrl}, or {@code null} if there is none.
     * Trailing slashes are insignificant: {@code "http://host/"} and {@code "http://host"}
     * refer to the same endpoint.
     */
    public String apiKeyFor(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        return apiKeysByBaseUrl.get(normalize(baseUrl));
    }

    private static String normalize(String baseUrl) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
