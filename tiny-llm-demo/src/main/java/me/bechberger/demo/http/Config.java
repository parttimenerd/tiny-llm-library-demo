package me.bechberger.demo.http;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Named LLM endpoints with credentials and default models, loaded from a standard
 * properties file:
 * <p>
 *   {@code ~/.config/tiny-llm-library/config.config}
 *   (honors {@code $XDG_CONFIG_HOME}; the {@value #PATH_ENV_VAR} environment variable
 *   overrides the location entirely)
 * <p>
 * Format - {@link Properties}, one named endpoint per key prefix:
 * <pre>
 * gardener.url   = https://models.answering-machine.utility.gardener.cloud.sap
 * gardener.key   = d7f...          # sent as Authorization: Bearer
 * gardener.model = kimi-k3         # default model for this endpoint
 * default.model  = kimi-k3         # global fallback when --model is not passed
 * </pre>
 * Properties is safe now because URLs live in the <i>values</i> - in the old
 * {@code url=key} format their colons broke the parsing. So you can just say
 * {@code --base-url gardener} and get the URL, the API key and the default model
 * in one go. A missing file simply means "no endpoints configured", which is
 * fine for local servers like llama.cpp.
 */
public final class Config {

    /** Environment variable that overrides the config file location. */
    public static final String PATH_ENV_VAR = "TINY_LLM_CONFIG";

    /** A named endpoint from the config file ({@code key} and {@code defaultModel} may be null). */
    public record Endpoint(String name, String url, String key, String defaultModel) {}

    private final Map<String, Endpoint> byName;   // lower-cased name -> endpoint
    private final Map<String, Endpoint> byUrl;    // normalized url -> endpoint
    private final String defaultModel;            // "default.model" property, may be null

    private Config(Map<String, Endpoint> byName, String defaultModel) {
        this.byName = byName;
        this.byUrl = new LinkedHashMap<>();
        this.defaultModel = defaultModel;
        byName.values().forEach(e -> byUrl.putIfAbsent(normalizeUrl(e.url()), e));
    }

    /** An empty config - every lookup returns {@code null}. */
    public static Config empty() {
        return new Config(Map.of(), null);
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
     * Loads the endpoints from the given properties file.
     * <p>
     * A non-existent file yields an empty config rather than an error -
     * most local LLM servers need no credentials at all.
     *
     * @throws UncheckedIOException if the file exists but cannot be read
     */
    public static Config load(Path path) {
        if (!Files.exists(path)) {
            return empty();
        }
        var props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read config: " + path, e);
        }
        var endpoints = new LinkedHashMap<String, Endpoint>();
        for (var prop : props.stringPropertyNames()) {
            if (!prop.endsWith(".url")) continue; // ".url" keys declare the endpoints
            String name = prop.substring(0, prop.length() - ".url".length());
            endpoints.put(name.toLowerCase(), new Endpoint(name,
                    normalizeUrl(props.getProperty(prop)),
                    blankToNull(props.getProperty(name + ".key")),
                    blankToNull(props.getProperty(name + ".model"))));
        }
        return new Config(endpoints, blankToNull(props.getProperty("default.model")));
    }

    /** The endpoint registered under {@code name} (case-insensitive), or {@code null}. */
    public Endpoint endpoint(String name) {
        return name == null ? null : byName.get(name.toLowerCase());
    }

    /**
     * Resolves a --base-url argument: an endpoint name from the config ("gardener")
     * or a literal URL, which passes through (normalized). Unknown names fail with
     * the list of known ones.
     */
    public String resolveBaseUrl(String nameOrUrl) {
        if (isUrl(nameOrUrl)) {
            return normalizeUrl(nameOrUrl);
        }
        var endpoint = endpoint(nameOrUrl);
        if (endpoint == null) {
            throw new IllegalArgumentException("Unknown endpoint '" + nameOrUrl
                    + "' in config - known: " + byName.keySet()
                    + " (or pass a full URL like http://localhost:8080)");
        }
        return endpoint.url();
    }

    /**
     * The API key configured for the given endpoint name or URL, or {@code null}
     * if there is none. ({@code url#token} fragments still win, see {@link HttpHelper}.)
     */
    public String apiKeyFor(String nameOrUrl) {
        if (nameOrUrl == null) return null;
        var endpoint = endpoint(nameOrUrl);
        if (endpoint == null) {
            endpoint = byUrl.get(normalizeUrl(nameOrUrl));
        }
        return endpoint != null ? endpoint.key() : null;
    }

    /** The default model for the given endpoint name or URL, else the global default, else {@code fallback}. */
    public String modelFor(String nameOrUrl, String fallback) {
        var endpoint = endpoint(nameOrUrl);
        if (endpoint == null && nameOrUrl != null) {
            endpoint = byUrl.get(normalizeUrl(nameOrUrl));
        }
        if (endpoint != null && endpoint.defaultModel() != null) return endpoint.defaultModel();
        return defaultModel != null ? defaultModel : fallback;
    }

    private static boolean isUrl(String s) {
        return s != null && s.contains(":/");
    }

    private static String normalizeUrl(String url) {
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
