package net.shasankp000.ServiceLLMClients;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.shasankp000.OllamaClient.OllamaEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic embedding client using Voyage AI (Anthropic's recommended embedding provider).
 * Note: Anthropic doesn't provide native embeddings, so we use Voyage AI's API.
 */
public class AnthropicEmbeddingClient implements EmbeddingClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Anthropic-Embedding-Client");
    private static final String EMBEDDING_ENDPOINT = "https://api.voyageai.com/v1/embeddings";
    private static final String DEFAULT_MODEL = "voyage-2"; // Recommended by Anthropic

    private final String apiKey;
    private final String modelName;
    private final HttpClient client;

    public AnthropicEmbeddingClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public AnthropicEmbeddingClient(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public List<Double> generateEmbedding(String text) throws Exception {
        try {
            // Build request body
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);
            requestBody.addProperty("input", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(EMBEDDING_ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warn("Voyage AI embedding failed, falling back to OpenAI-compatible format");
                // Fallback: Try Anthropic's OpenAI-compatible endpoint if available
                return generateEmbeddingFallback(text);
            }

            // Parse response
            JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray embeddingArray = jsonResponse.getAsJsonArray("data")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("embedding");

            List<Double> embedding = new ArrayList<>();
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding.add(embeddingArray.get(i).getAsDouble());
            }

            return embedding;

        } catch (Exception e) {
            LOGGER.error("Error generating Anthropic embedding: {}", e.getMessage(), e);
            throw e;
        }
    }

    private List<Double> generateEmbeddingFallback(String text) throws Exception {
        // Fallback to nomic-embed-text via Ollama if Voyage AI fails
        LOGGER.warn("Using Ollama fallback for embeddings");
        OllamaEmbeddingClient fallback = new OllamaEmbeddingClient(
            new io.github.amithkoujalgi.ollama4j.core.OllamaAPI(OllamaEndpoint.baseUrlSlash())
        );
        return fallback.generateEmbedding(text);
    }

    @Override
    public String getEmbeddingModel() {
        return modelName;
    }

    @Override
    public int getEmbeddingDimension() {
        // voyage-2 produces 1024-dimensional embeddings
        return 1024;
    }

    @Override
    public String getProvider() {
        return "Anthropic (Voyage AI)";
    }

    @Override
    public boolean isReachable() {
        try {
            // Simple ping test
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(EMBEDDING_ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}

