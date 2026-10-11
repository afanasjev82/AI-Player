package net.shasankp000.FilingSystem;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.shasankp000.Exception.ollamaNotReachableException;
import net.shasankp000.OllamaClient.OllamaEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class getLanguageModels {

    private static final Logger LOGGER = LoggerFactory.getLogger("getLanguageModels");

    /**
     * Retrieves available language models from Ollama server.
     * Returns an empty list if server is not reachable instead of crashing.
     *
     * @return List of available model names, or empty list if server is unavailable
     */
    public static List<String> get() {
        Set<String> modelSet = new HashSet<>();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(OllamaEndpoint.baseUrl() + "/api/tags"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String responseBody = response.body();
                JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
                JsonArray modelsArray = jsonObject.getAsJsonArray("models");

                if (modelsArray != null) {
                    for (JsonElement element : modelsArray) {
                        JsonObject modelObject = element.getAsJsonObject();
                        String modelName = modelObject.get("name").getAsString();
                        modelSet.add(modelName);
                    }
                }
                LOGGER.info("Successfully retrieved {} language models from Ollama", modelSet.size());
            } else {
                LOGGER.warn("Ollama Server returned status code: {}. Model list will be empty.", response.statusCode());
            }

        } catch (ConnectException e) {
            LOGGER.warn("⚠ Ollama server is not running on {}. Please start Ollama to use AI chat features.", OllamaEndpoint.baseUrl());
            LOGGER.warn("The mod will continue to work, but AI chat will be unavailable until Ollama is started.");
        } catch (URISyntaxException | IOException | InterruptedException e) {
            LOGGER.error("Error connecting to Ollama Server: {}. Model list will be empty.", e.getMessage());
        }

        return new ArrayList<>(modelSet);
    }

    /**
     * Safe version that throws exception if server is not reachable.
     * Use this when you want to explicitly handle the connection failure.
     *
     * @return List of available model names
     * @throws ollamaNotReachableException if server cannot be reached
     */
    public static List<String> getOrThrow() throws ollamaNotReachableException {
        List<String> models = get();
        if (models.isEmpty()) {
            throw new ollamaNotReachableException("Ollama server is not reachable or no models are available");
        }
        return models;
    }
}