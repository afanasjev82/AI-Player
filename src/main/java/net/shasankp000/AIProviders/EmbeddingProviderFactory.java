package net.shasankp000.AIProviders;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import net.shasankp000.OllamaClient.OllamaEndpoint;
import net.shasankp000.FilingSystem.EnvOverride;
import net.shasankp000.AIPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating EmbeddingProvider instances based on current configuration.
 * Automatically selects the appropriate embedding model based on the LLM provider.
 */
public class EmbeddingProviderFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("ai-player-embedding-factory");

    /**
     * Create an embedding provider based on the current AI provider configuration.
     * This method automatically determines the correct embedding endpoint and model
     * based on the selected LLM provider from JVM arguments.
     *
     * @param ollamaAPI Ollama API instance (used only when the ollama provider is explicitly selected)
     * @return Configured EmbeddingProvider
     */
    public static EmbeddingProvider createEmbeddingProvider(OllamaAPI ollamaAPI) {
        try {
            // Get provider from JVM argument
            String provider = System.getProperty("aiplayer.llmMode", "custom");
            LOGGER.info("🔍 Creating embedding provider for: {}", provider);

            String embeddingModel = getDefaultEmbeddingModel(provider);
            String apiKey;
            String endpoint;

            switch (provider.toLowerCase()) {
                case "ollama":
                    LOGGER.info("✅ Using Ollama embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(ollamaAPI, embeddingModel);

                case "openai":
                    apiKey = AIPlayer.CONFIG.getOpenAIKey();
                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalStateException("OpenAI API key is not configured");
                    }
                    LOGGER.info("✅ Using OpenAI embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(
                            "https://api.openai.com",
                            apiKey,
                            embeddingModel,
                            EmbeddingProvider.AIProviderType.OPENAI_COMPATIBLE
                    );

                case "gemini":
                    apiKey = AIPlayer.CONFIG.getGeminiKey();
                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalStateException("Gemini API key is not configured");
                    }
                    LOGGER.info("✅ Using Gemini embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(
                            "https://generativelanguage.googleapis.com",
                            apiKey,
                            embeddingModel,
                            EmbeddingProvider.AIProviderType.GEMINI
                    );

                case "grok":
                    apiKey = AIPlayer.CONFIG.getGrokKey();
                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalStateException("Grok API key is not configured");
                    }
                    LOGGER.info("✅ Using Grok (OpenAI-compatible) embedding model: {}", embeddingModel);
                    return new EmbeddingProvider(
                            "https://api.x.ai",
                            apiKey,
                            embeddingModel,
                            EmbeddingProvider.AIProviderType.OPENAI_COMPATIBLE
                    );

                case "custom":
                    String chatEndpoint = AIPlayer.CONFIG.getCustomApiUrl();
                    apiKey = AIPlayer.CONFIG.getCustomApiKey();

                    if (chatEndpoint == null || chatEndpoint.isEmpty()) {
                        throw new IllegalStateException("Custom OpenAI-compatible endpoint is not configured");
                    }

                    // If no API key is required for custom endpoint (e.g., LM Studio, local VLLM)
                    if (apiKey == null) {
                        apiKey = "";
                    }

                    // Embeddings are resolved SEPARATELY from chat, because the two
                    // stop being the same server once chat moves to a chat-only
                    // backend. The local vLLM serves gemma-4 for chat and exposes no
                    // embedding model at all, so following customApiUrl here would
                    // request /v1/embeddings from it and get a 404 — which is exactly
                    // what happened when chat was first pointed at vLLM. Ollama is
                    // where nomic-embed-text lives, so it is the default whenever the
                    // chat endpoint is not itself Ollama. Set aiplayer.embedBaseUrl
                    // when the custom endpoint really is an embedding server.
                    endpoint = EnvOverride.resolve(null, System.getProperty("aiplayer.embedBaseUrl"));
                    if (endpoint == null) {
                        endpoint = isOllamaEndpoint(chatEndpoint)
                                ? chatEndpoint
                                : OllamaEndpoint.baseUrl() + "/v1";
                    }

                    // Ollama's OpenAI-compatible endpoint only serves models it has
                    // pulled (nomic-embed-text here), not the OpenAI ada-002 default.
                    if (isOllamaEndpoint(endpoint)) {
                        embeddingModel = "nomic-embed-text";
                        apiKey = "";
                    } else if (chatEndpoint != null && !endpoint.equals(chatEndpoint)) {
                        LOGGER.warn("Embedding endpoint {} differs from the chat endpoint {}; "
                                + "make sure it serves {}", endpoint, chatEndpoint, embeddingModel);
                    }

                    LOGGER.info("✅ Using embedding endpoint: {} (model {})", endpoint, embeddingModel);
                    return new EmbeddingProvider(
                            endpoint,
                            apiKey,
                            embeddingModel,
                            EmbeddingProvider.AIProviderType.OPENAI_COMPATIBLE
                    );

                case "claude":
                case "anthropic":
                    throw new IllegalStateException("Anthropic/Claude does not provide embedding endpoints. Configure a custom OpenAI-compatible embedding endpoint.");

                default:
                    throw new IllegalStateException("Unknown embedding provider: " + provider);
            }
        } catch (Exception e) {
            LOGGER.error("❌ Failed to create embedding provider", e);
            throw new IllegalStateException("Failed to create embedding provider", e);
        }
    }

    /**
     * Get the default embedding model for a given provider.
     * These are industry-standard defaults that work with most providers.
     */
    private static String getDefaultEmbeddingModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "ollama" -> "nomic-embed-text";
            case "openai" -> "text-embedding-3-small"; // Latest OpenAI embedding model
            case "gemini" -> "text-embedding-004"; // Latest Gemini embedding model
            case "grok", "custom" ->
                // For OpenAI-compatible endpoints (Grok, LM Studio, VLLM, etc.)
                // Use a common embedding model name that most providers support
                    "text-embedding-ada-002";
            default -> "text-embedding-3-small";
        };
    }

    /**
     * Heuristic: an endpoint on localhost port 11434 is the Ollama OpenAI-compatible
     * API, which serves only pulled Ollama models (nomic-embed-text), not the OpenAI
     * ada-002 name the generic default picks.
     */
    private static boolean isOllamaEndpoint(String endpoint) {
        return OllamaEndpoint.pointsAtOllama(endpoint);
    }
}
