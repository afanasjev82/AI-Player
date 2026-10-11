package net.shasankp000.OllamaClient;

import net.shasankp000.AIPlayer;

/**
 * Single source of truth for the Ollama server address.
 *
 * <p>Before this class, eight files hard-coded {@code http://localhost:11434},
 * so there was no way to move the embedding/chat backend without editing the
 * source in eight places. Every Ollama client (chat, embeddings, RAG, model
 * listing) now asks here instead.
 *
 * <p>Resolution order, first match wins:
 * <ol>
 *   <li>the {@code aiplayer.ollamaBaseUrl} system property (set from {@code .env});</li>
 *   <li>the host of the configured {@code customApiUrl} — the OpenAI-compatible
 *       form is {@code http://host:port/v1}, but Ollama's <em>native</em> API
 *       lives at the bare root, so a trailing {@code /v1} is stripped;</li>
 *   <li>{@link #DEFAULT_BASE_URL}.</li>
 * </ol>
 *
 * <p>Step 2 covers the common case where {@code customApiUrl} already points at
 * Ollama, which is why this needs no new configuration to keep working as
 * before. Step 1 exists for deployments where the two must differ.
 */
public final class OllamaEndpoint {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private static final String BASE_URL_PROPERTY = "aiplayer.ollamaBaseUrl";

    /** Resolved lazily so a class-level initialiser can call us before config exists. */
    private static volatile String cached;

    private OllamaEndpoint() {
    }

    /**
     * The Ollama root URL with no trailing slash, e.g. {@code http://localhost:11434}.
     * This is what native {@code /api/chat} and {@code /api/tags} calls need.
     */
    public static String baseUrl() {
        String resolved = cached;
        if (resolved == null) {
            resolved = resolve();
            cached = resolved;
        }
        return resolved;
    }

    /**
     * The same address with a trailing slash, which is the form the ollama4j
     * {@code OllamaAPI} constructor expects.
     */
    public static String baseUrlSlash() {
        return baseUrl() + "/";
    }

    /**
     * Whether a configured endpoint looks like an Ollama server rather than a
     * generic OpenAI-compatible one. Used to pick the native JSON chat path and
     * to decide whether a provider can serve Ollama-only models such as
     * {@code nomic-embed-text}.
     */
    public static boolean pointsAtOllama(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("11434") || lower.contains("ollama");
    }

    /** Drop caches, so a later {@link #baseUrl()} re-reads configuration. */
    public static void reset() {
        cached = null;
    }

    private static String resolve() {
        String explicit = System.getProperty(BASE_URL_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return normalize(explicit);
        }
        try {
            String custom = AIPlayer.CONFIG.getCustomApiUrl();
            if (pointsAtOllama(custom)) {
                return normalize(custom);
            }
        } catch (Throwable ignored) {
            // Config not available yet (early class init, unit tests) — fall through.
        }
        return DEFAULT_BASE_URL;
    }

    /**
     * Strip the OpenAI-compatible {@code /v1} suffix and any trailing slashes so
     * callers always receive the bare root the native API is served from.
     */
    private static String normalize(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith("/v1")) {
            result = result.substring(0, result.length() - 3);
        }
        return result;
    }
}
