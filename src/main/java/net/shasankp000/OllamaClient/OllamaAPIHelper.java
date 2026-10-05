package net.shasankp000.OllamaClient;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessage;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Helper class for Ollama API that supports reasoning models with thinking mode
 * Provides methods to enable "think" parameter for models like deepseek-r1
 */
public class OllamaAPIHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("ai-player-ollama-helper");
    private static final Gson gson = new Gson();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Sends a chat request with thinking mode enabled
     *
     * @param ollamaAPI The OllamaAPI instance to use (currently unused, kept for API consistency)
     * @param host The Ollama server host (e.g., http://localhost:11434)
     * @param model The model to use (e.g., "deepseek-r1")
     * @param messages List of chat messages
     * @param enableThinking Whether to enable thinking mode
     * @param stream Whether to stream the response
     * @return OllamaThinkingResponse containing both content and thinking (if available)
     */
    @SuppressWarnings("unused")
    public static OllamaThinkingResponse chatWithThinking(
            OllamaAPI ollamaAPI,
            String host,
            String model,
            List<OllamaChatMessage> messages,
            boolean enableThinking,
            boolean stream) throws IOException, InterruptedException {

        // Build the JSON request
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", model);
        requestJson.add("messages", gson.toJsonTree(messages));
        requestJson.addProperty("stream", stream);

        // Add "think" parameter if enabled (for reasoning models)
        if (enableThinking) {
            requestJson.addProperty("think", true);
            LOGGER.info("🧠 Thinking mode ENABLED for model: {}", model);
        }

        // Send HTTP POST request
        String endpoint = host + "/api/chat";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOGGER.error("❌ Ollama API error: HTTP {}", response.statusCode());
            throw new IOException("Ollama API returned status: " + response.statusCode());
        }

        // Parse response
        String responseBody = response.body();
        JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();

        // Extract content and thinking from response
        JsonObject message = responseJson.getAsJsonObject("message");
        String content = message.has("content") ? message.get("content").getAsString() : "";
        String thinking = message.has("thinking") ? message.get("thinking").getAsString() : null;

        if (thinking != null && !thinking.isEmpty()) {
            LOGGER.info("💭 Received thinking response ({} chars)", thinking.length());
        }

        return new OllamaThinkingResponse(content, thinking);
    }

    /**
     * Native /api/chat call forcing JSON output with thinking DISABLED — the only
     * combination that makes a reasoning model (qwen3) return fast, machine-parsable
     * JSON. The OpenAI-compatible endpoint ignores `think:false` (and `format`),
     * so structured action calls must go through this native path.
     */
    public static OllamaThinkingResponse chatJson(
            String host,
            String model,
            List<OllamaChatMessage> messages) throws IOException, InterruptedException {
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", model);
        requestJson.add("messages", gson.toJsonTree(messages));
        requestJson.addProperty("stream", false);
        requestJson.addProperty("think", false);
        requestJson.addProperty("format", "json");

        String endpoint = host + "/api/chat";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            LOGGER.error("❌ Ollama API error: HTTP {}", response.statusCode());
            throw new IOException("Ollama API returned status: " + response.statusCode());
        }
        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject message = responseJson.getAsJsonObject("message");
        String content = message.has("content") ? message.get("content").getAsString() : "";
        String thinking = message.has("thinking") ? message.get("thinking").getAsString() : null;
        return new OllamaThinkingResponse(content, thinking);
    }

    /**
     * Fallback method that uses standard OllamaAPI for non-thinking models
     * or when thinking mode is not needed
     *
     * @param chatResult Standard OllamaChatResult from ollama4j library
     * @return OllamaThinkingResponse wrapping the standard response
     */
    public static OllamaThinkingResponse fromStandardResult(OllamaChatResult chatResult) {
        return new OllamaThinkingResponse(chatResult.getResponse(), null);
    }

    /**
     * Checks if a model is a reasoning model that supports thinking mode
     *
     * @param modelName The name of the model
     * @return true if the model supports thinking mode
     */
    public static boolean isReasoningModel(String modelName) {
        String lowerName = modelName.toLowerCase();
        return lowerName.contains("deepseek-r1") ||
               lowerName.contains("reasoning") ||
               lowerName.contains("qwen-qwq");
    }

    /**
     * Smart chat method that automatically enables thinking mode for reasoning models
     *
     * @param ollamaAPI The OllamaAPI instance
     * @param host The Ollama server host
     * @param model The model to use
     * @param messages List of chat messages
     * @return OllamaThinkingResponse with content and optional thinking
     */
    public static OllamaThinkingResponse smartChat(
            OllamaAPI ollamaAPI,
            String host,
            String model,
            List<OllamaChatMessage> messages) throws IOException, InterruptedException {

        boolean useThinking = isReasoningModel(model);

        if (useThinking) {
            LOGGER.info("🔍 Detected reasoning model '{}' - enabling thinking mode", model);
            return chatWithThinking(ollamaAPI, host, model, messages, true, false);
        } else {
            LOGGER.info("📝 Standard model '{}' - using regular chat", model);
            // For non-reasoning models, we still use the new API endpoint
            // but with think=false (or omitted)
            return chatWithThinking(ollamaAPI, host, model, messages, false, false);
        }
    }
}

