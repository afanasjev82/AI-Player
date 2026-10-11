package net.shasankp000.WebSearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.shasankp000.AIPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public class AISearchConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-search-config");
    public static String GEMINI_API_KEY = "";
    public static String SERPER_API_KEY = "";
    public static String PREFERRED_PROVIDER = "gemini";

    // DeepSeek (paid, OpenAI-compatible) and a generic local OpenAI-compatible
    // endpoint (free — e.g. a self-hosted vLLM/Ollama server). Both speak the
    // standard /chat/completions contract, so they share one code path.
    public static String DEEPSEEK_API_KEY = "";
    public static String DEEPSEEK_MODEL = "deepseek-chat";
    public static String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    public static String LOCAL_SEARCH_BASE_URL = "";
    public static String LOCAL_SEARCH_MODEL = "";
    public static String LOCAL_SEARCH_KEY = "";

    static {
        if (!AIPlayer.CONFIG.getGeminiKey().isEmpty()) {
            GEMINI_API_KEY = AIPlayer.CONFIG.getGeminiKey(); // using the same api key for web search purposes as well.
        }

        // System properties are the deployment source of truth: the container
        // sets them from `.env` via JVM_OPTS / JVM_DD_OPTS, so the search
        // provider can be changed without editing settings.json5 or the JSON
        // config file. The JSON file below is only a fallback.
        PREFERRED_PROVIDER = firstNonBlank(System.getProperty("aiplayer.searchProvider"), PREFERRED_PROVIDER);
        SERPER_API_KEY = firstNonBlank(System.getProperty("aiplayer.serperKey"), SERPER_API_KEY);
        DEEPSEEK_API_KEY = firstNonBlank(System.getProperty("aiplayer.deepseekKey"), DEEPSEEK_API_KEY);
        DEEPSEEK_MODEL = firstNonBlank(System.getProperty("aiplayer.deepseekModel"), DEEPSEEK_MODEL);
        DEEPSEEK_BASE_URL = firstNonBlank(System.getProperty("aiplayer.deepseekBaseUrl"), DEEPSEEK_BASE_URL);
        LOCAL_SEARCH_BASE_URL = firstNonBlank(System.getProperty("aiplayer.localSearchBaseUrl"), LOCAL_SEARCH_BASE_URL);
        LOCAL_SEARCH_MODEL = firstNonBlank(System.getProperty("aiplayer.localSearchModel"), LOCAL_SEARCH_MODEL);
        LOCAL_SEARCH_KEY = firstNonBlank(System.getProperty("aiplayer.localSearchKey"), LOCAL_SEARCH_KEY);

        loadConfig();
    }

    /** Return {@code override} when set, else {@code fallback}. */
    private static String firstNonBlank(String override, String fallback) {
        if (override != null && !override.isBlank()) return override.trim();
        return fallback;
    }

    private static void loadConfig() {
        try {
            Path configPath = Paths.get("config", "ai_search_config.json");
            if (!Files.exists(configPath)) {
                setupIfMissing();
                return; // No need to load after creation
            }
            String json = Files.readString(configPath);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(json);

            // System properties (from .env) win over the JSON file.
            if (System.getProperty("aiplayer.searchProvider") == null)
                PREFERRED_PROVIDER = node.path("preferred_provider").asText(PREFERRED_PROVIDER);
            if (System.getProperty("aiplayer.deepseekKey") == null)
                DEEPSEEK_API_KEY = node.path("deepseek_api_key").asText(DEEPSEEK_API_KEY);
            if (System.getProperty("aiplayer.deepseekModel") == null)
                DEEPSEEK_MODEL = node.path("deepseek_model").asText(DEEPSEEK_MODEL);
            if (System.getProperty("aiplayer.deepseekBaseUrl") == null)
                DEEPSEEK_BASE_URL = node.path("deepseek_base_url").asText(DEEPSEEK_BASE_URL);
            if (System.getProperty("aiplayer.localSearchBaseUrl") == null)
                LOCAL_SEARCH_BASE_URL = node.path("local_search_base_url").asText(LOCAL_SEARCH_BASE_URL);
            if (System.getProperty("aiplayer.localSearchModel") == null)
                LOCAL_SEARCH_MODEL = node.path("local_search_model").asText(LOCAL_SEARCH_MODEL);
            if (System.getProperty("aiplayer.localSearchKey") == null)
                LOCAL_SEARCH_KEY = node.path("local_search_key").asText(LOCAL_SEARCH_KEY);

            GEMINI_API_KEY = node.path("gemini_api_key").asText(GEMINI_API_KEY);
            // Serper key: system property (.env) wins, JSON file is the fallback.
            if (System.getProperty("aiplayer.serperKey") == null)
                SERPER_API_KEY = node.path("serper_api_key").asText(SERPER_API_KEY);
        } catch (IOException e) {
            LOGGER.error("❌ Failed to load AI Search config: {}", e.getMessage());
        }
    }

    public static void setupIfMissing() {
        try {
            Path configDir = Paths.get("config");
            Path configPath = configDir.resolve("ai_search_config.json");

            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            if (!Files.exists(configPath)) {
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode root = mapper.createObjectNode();
                root.put("gemini_api_key", "YOUR_GEMINI_API_KEY_HERE");
                root.put("serper_api_key", "YOUR_SERPER_API_KEY_HERE");
                root.put("preferred_provider", "gemini");
                root.put("deepseek_api_key", "");
                root.put("deepseek_model", "deepseek-chat");
                root.put("deepseek_base_url", "https://api.deepseek.com");
                root.put("local_search_base_url", "");
                root.put("local_search_model", "");
                root.put("local_search_key", "");

                Files.writeString(
                        configPath,
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                        StandardCharsets.UTF_8
                );
                LOGGER.info("✅ Created default ai_search_config.json in config folder.");
            } else {
                LOGGER.info("ℹ️ ai_search_config.json already exists, no setup needed.");
            }
        } catch (IOException e) {
            LOGGER.error("❌ Failed to create AI Search config: {}", e.getMessage());
        }
    }
}
