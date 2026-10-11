package net.shasankp000.FilingSystem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.shasankp000.ServiceLLMClients.*;
import net.shasankp000.LauncherDetection.LauncherEnvironment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handles all mod configuration settings using a plain JSON file.
 * This class replaces the owo-lib config wrapper to provide manual control
 * over saving and loading, resolving race conditions and initialization issues.
 */
public class ManualConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("ManualConfig");
    private static final String FILE_NAME = "settings.json5";

    // Resolved on first use rather than at class-init. As a static final this
    // called LauncherEnvironment.getStorageDirectory() -> FabricLoader during
    // static initialisation, which throws outside the game and made the WHOLE
    // class unloadable in a test JVM — including the pure rules below.
    private static String cachedFilePath;

    private static synchronized String filePath() {
        if (cachedFilePath == null) {
            cachedFilePath = getFilePath();
        }
        return cachedFilePath;
    }

    // --- Configuration fields ---
    private List<String> modelList = new ArrayList<>();
    private String selectedLanguageModel;
    private String llmMode = System.getProperty("aiplayer.llmMode", "custom");
    private String openAIKey = "";
    private String claudeKey = "";
    private String geminiKey = "";
    private String grokKey = "";
    private String customApiKey = "";
    private String customApiUrl = "";
    /** Model name for Ollama-native calls; see {@link #getOllamaModel()}. */
    private String ollamaModel = "";

    /**
     * The chat endpoint/model as CONFIGURED, captured before
     * {@link #applyChatOverrides()} replaced them with {@code .env} values.
     *
     * <p>{@code transient} so Gson never writes them, and they are what
     * {@link #save()} persists — see that method for why.
     */
    private transient String configuredApiUrl;
    private transient String configuredChatModel;

    private Map<String, String> botGameProfile = new HashMap<>();

    /**
     * Persists the active persona ID for each bot, keyed by bot name.
     *
     * <p>Written by {@code /bot persona <bot> <id>} and read on bot spawn so
     * the chosen personality survives server restarts.  Values are validated
     * against {@link net.shasankp000.GameAI.persona.PersonaRegistry} at read
     * time; unknown IDs fall back to
     * {@link net.shasankp000.GameAI.persona.PersonaRegistry#DEFAULT_ID}.
     *
     * <p>Example JSON representation:
     * <pre>
     * "botPersonaMap": {
     *   "Steve": "serious",
     *   "Alex":  "cheerful"
     * }
     * </pre>
     */
    private Map<String, String> botPersonaMap = new HashMap<>();

    /**
     * Private constructor to prevent direct instantiation.
     * Use the static load() method instead.
     */
    private ManualConfig() {
        // Initialize with default values
        this.selectedLanguageModel = null;
    }

    /**
     * Helper method to get the correct file path using the LauncherEnvironment class.
     * @return The absolute path to the settings file.
     */
    private static String getFilePath() {
        return LauncherEnvironment.getStorageDirectory("config") + File.separator + FILE_NAME;
    }

    /**
     * Asynchronously updates the list of available models based on the selected provider.
     * This method fetches the model list and then saves the updated configuration to the file.
     */
    public CompletableFuture<List<String>> updateModels() {
        // Return the future so callers can update their state only after the
        // network operation has finished.
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> fetchedModels = new ArrayList<>();
                ModelFetcher modelFetcher = null;
                String apiKey = "";

                switch (llmMode) {
                    case "ollama":
                        LOGGER.info("Using ollama");
                        fetchedModels = getLanguageModels.get();

                        if (fetchedModels.isEmpty()) {
                            LOGGER.warn("⚠ No models found. Ollama server may not be running.");
                            fetchedModels.add("Ollama is not reachable! Please start Ollama server.");
                        } else {
                            LOGGER.info("Fetched {} models: {}", fetchedModels.size(), this.modelList);
                        }

                        this.modelList = fetchedModels;
                        this.save();
                        return new ArrayList<>(this.modelList);
                    case "openai":
                        modelFetcher = new OpenAIModelFetcher();
                        apiKey = this.openAIKey;
                        break;
                    case "claude":
                        modelFetcher = new ClaudeModelFetcher();
                        apiKey = this.claudeKey;
                        break;
                    case "gemini":
                        modelFetcher = new GeminiModelFetcher();
                        apiKey = this.geminiKey;
                        break;
                    case "grok":
                        modelFetcher = new GrokModelFetcher();
                        apiKey = this.grokKey;
                        break;
                    case "custom":
                        if (!this.customApiUrl.isEmpty()) {
                            modelFetcher = new GenericOpenAIModelFetcher(this.customApiUrl);
                            apiKey = this.customApiKey;
                        } else {
                            LOGGER.error("Custom provider selected but no API URL configured");
                            return new ArrayList<>(this.modelList);
                        }
                        break;
                    default:
                        LOGGER.error("Unsupported provider: {}", llmMode);
                        return new ArrayList<>(this.modelList);
                }

                if (llmMode.equals("ollama")) {
                    // ollama is handled above, so we just skip API key check.
                    LOGGER.info("Skipping API key check for ollama");
                    this.modelList = fetchedModels;
                    LOGGER.info("ollama modelList: {}", this.modelList);
                    this.save();
                }
                else {
                    if (modelFetcher != null) {
                        if(!llmMode.equals("custom") && apiKey.isEmpty()) {
                            // in the event that a user removes their api key but still have a service based provider set.
                            fetchedModels = new ArrayList<>();
                            selectedLanguageModel="No models available. Please enter an API key";
                        }
                        else {
                            try {
                                fetchedModels = modelFetcher.fetchModels(apiKey);
                                LOGGER.info("Retrieved models {} for provider: {}", fetchedModels , llmMode);
                                if (selectedLanguageModel != null && selectedLanguageModel.equals("No models available. Please enter an API key")) {
                                    selectedLanguageModel="";
                                }
                            } catch (Exception e) {
                                LOGGER.error("Error fetching models: {}", e.getMessage(), e);
                                fetchedModels = new ArrayList<>();
                            }
                        }
                    }
                    this.modelList = fetchedModels;
                    LOGGER.debug("this.modelList: {}", this.modelList);
                    LOGGER.info("modelList: {}", this.modelList);
                    this.save();
                }
            } catch (Exception e) {
                LOGGER.error("Exception in updateModels: {}", e.getMessage(), e);
                this.modelList = new ArrayList<>();
                this.save();
            }

            return new ArrayList<>(this.modelList);
        });
    }

    /**
     * Saves the current configuration to the settings.json5 file.
     */
    public void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        // Env overrides are runtime state, not configuration. Serialising `this`
        // wrote them to disk, which made an override permanent: spawning a bot
        // calls save(), so AI_CHAT_BASE_URL/AI_CHAT_MODEL got baked into
        // settings.json5 and clearing them from .env stopped reverting anything.
        // Persist the configured values; the overrides stay in memory only.
        JsonObject json = gson.toJsonTree(this).getAsJsonObject();
        if (configuredApiUrl != null) {
            json.addProperty("customApiUrl", configuredApiUrl);
        }
        if (configuredChatModel != null) {
            json.addProperty("selectedLanguageModel", configuredChatModel);
        }

        try (FileWriter writer = new FileWriter(filePath())) {
            writer.write(gson.toJson(json));
        } catch (IOException e) {
            LOGGER.error("Failed to save config file: {}", e.getMessage());
        }
    }

    /**
     * Loads the configuration from the settings.json5 file. If the file does not exist,
     * it creates and returns a new default configuration instance.
     *
     * @return A loaded ManualConfig instance, or a new one if the file is not found.
     */
    public static ManualConfig load() {
        File file = new File(filePath());
        // Ensure the directory for the file exists before attempting to write.
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        if (!file.exists()) {
            LOGGER.info("Config file not found. Creating a new one.");
            ManualConfig defaultConfig = new ManualConfig();
            defaultConfig.applyChatOverrides();
            defaultConfig.save(); // Save the new config to create the file
            return defaultConfig;
        }

        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<ManualConfig>(){}.getType();
            ManualConfig loadedConfig = gson.fromJson(reader, type);
            // After loading, ensure the model list is updated.
            String currentProvider = System.getProperty("aiplayer.llmMode", "custom");
            loadedConfig.checkAndUpdateProvider(currentProvider);
            // Ensure the persona map is non-null after deserialisation
            if (loadedConfig.botPersonaMap == null) {
                loadedConfig.botPersonaMap = new HashMap<>();
            }
            // Environment overrides for the chat backend. settings.json5 is
            // edited by hand and is not deployable, so without these there is no
            // way to point the bot at a different chat model from .env. Applied
            // after checkAndUpdateProvider, which can clear selectedLanguageModel
            // when the provider changes.
            loadedConfig.applyChatOverrides();
            return loadedConfig;
        } catch (IOException e) {
            LOGGER.error("Failed to load config file. Using default config.", e);
            ManualConfig fallback = new ManualConfig();
            fallback.applyChatOverrides();
            return fallback;
        }
    }

    /**
     * The {@code .env} override rule for the chat endpoint/model: an absent or
     * blank override keeps the configured value, a present one wins and is
     * trimmed. Delegates to {@link EnvOverride} so there is one implementation
     * of the rule, not two that can drift apart.
     */
    public static String withOverride(String configured, String override) {
        return EnvOverride.resolve(configured, override);
    }

    /**
     * Apply {@code aiplayer.chatBaseUrl} / {@code aiplayer.chatModel} over
     * {@code settings.json5}, so the chat backend can be chosen from {@code .env}
     * without editing the config file on disk.
     *
     * <p>Both are optional: when unset the file's own values stand, which keeps
     * the previous behaviour for every existing deployment.
     */
    void applyChatOverrides() {
        // Capture ONCE, before anything is replaced: a later call (config reload)
        // must not mistake the already-overridden values for the configured ones,
        // or the override would become permanent through save().
        if (configuredApiUrl == null) {
            configuredApiUrl = this.customApiUrl;
            configuredChatModel = this.selectedLanguageModel;
        }

        // Capture the file's own model before the chat override replaces it:
        // the native-Ollama calls must keep naming a model Ollama actually has.
        String configuredModel = this.selectedLanguageModel;

        this.ollamaModel = EnvOverride.resolve(configuredModel, System.getProperty("aiplayer.ollamaModel"));

        String baseUrl = EnvOverride.resolve(this.customApiUrl, System.getProperty("aiplayer.chatBaseUrl"));
        if (!baseUrl.equals(this.customApiUrl)) {
            LOGGER.info("Chat base URL overridden to {} (was {})", baseUrl, this.customApiUrl);
        }
        this.customApiUrl = baseUrl;

        String model = EnvOverride.resolve(configuredModel, System.getProperty("aiplayer.chatModel"));
        if (!model.equals(this.selectedLanguageModel)) {
            LOGGER.info("Chat model overridden to {} (was {})", model, this.selectedLanguageModel);
            LOGGER.info("Native Ollama calls will keep using '{}'", this.ollamaModel);
        }
        this.selectedLanguageModel = model;
    }

    /**
     * The model name for the Ollama-native calls (native {@code /api/chat},
     * RAG answers, embeddings-adjacent work).
     *
     * <p>Distinct from {@link #getSelectedLanguageModel()} because the two stop
     * being the same model once the chat backend moves to an OpenAI-compatible
     * server: asking Ollama for {@code gemma-4-26b-it} fails, since that model
     * lives in vLLM. Falls back to the configured chat model when unset, so
     * single-backend deployments behave exactly as before.
     */
    public String getOllamaModel() {
        if (ollamaModel != null && !ollamaModel.isBlank()) {
            return ollamaModel;
        }
        return selectedLanguageModel;
    }

    /**
     * Checks if the provider has changed, and if so, updates llmMode, clears modelList, and fetches new models.
     * @param newProvider The newly selected provider (llmMode)
     */
    public void checkAndUpdateProvider(String newProvider) {
        if (!this.llmMode.equals(newProvider)) {
            LOGGER.info("Provider changed from {} to {}. Invalidating modelList and updating config.", this.llmMode, newProvider);
            this.llmMode = newProvider;
            this.modelList = new ArrayList<>();
            this.selectedLanguageModel = null;
            this.save();
            this.updateModels();
        }
    }

    // --- Getters and setters ---

    public String getOpenAIKey() {
        return openAIKey;
    }

    public void setOpenAIKey(String openAIKey) {
        this.openAIKey = openAIKey != null ? openAIKey.trim() : "";
    }

    public String getClaudeKey() {
        return claudeKey;
    }

    public void setClaudeKey(String claudeKey) {
        this.claudeKey = claudeKey != null ? claudeKey.trim() : "";
    }

    public String getGeminiKey() {
        return geminiKey;
    }

    public void setGeminiKey(String geminiKey) {
        this.geminiKey = geminiKey != null ? geminiKey.trim() : "";
    }

    public String getGrokKey() {
        return grokKey;
    }

    public void setGrokKey(String grokKey) {
        this.grokKey = grokKey != null ? grokKey.trim() : "";
    }

    public String getCustomApiKey() {
        return customApiKey;
    }

    public void setCustomApiKey(String customApiKey) {
        this.customApiKey = customApiKey != null ? customApiKey.trim() : "";
    }

    public String getCustomApiUrl() {
        return customApiUrl;
    }

    public void setCustomApiUrl(String customApiUrl) {
        this.customApiUrl = customApiUrl != null ? customApiUrl.trim() : "";
    }

    public List<String> getModelList() {
        return modelList;
    }

    public void setModelList(List<String> modelList) {
        this.modelList = modelList;
    }

    public String getSelectedLanguageModel() {
        return selectedLanguageModel;
    }

    public void setSelectedLanguageModel(String selectedLanguageModel) {
        this.selectedLanguageModel = selectedLanguageModel;
    }

    public String getLlmMode() {
        return llmMode;
    }

    public Map<String, String> getBotGameProfile() {
        return botGameProfile;
    }

    public void setBotGameProfile(Map<String, String> botGameProfile) {
        this.botGameProfile = botGameProfile;
    }

    // --- Persona map ---

    /**
     * Returns the full persona map (bot name → persona ID).
     * Callers should prefer {@link #getBotPersona(String)} for single-bot lookups.
     */
    public Map<String, String> getBotPersonaMap() {
        return botPersonaMap;
    }

    public void setBotPersonaMap(Map<String, String> botPersonaMap) {
        this.botPersonaMap = botPersonaMap != null ? botPersonaMap : new HashMap<>();
    }

    /**
     * Returns the saved persona ID for {@code botName}, or
     * {@link net.shasankp000.GameAI.persona.PersonaRegistry#DEFAULT_ID} if none has been set.
     *
     * @param botName The in-game bot name.
     * @return A non-null persona ID string (may not yet exist in the registry
     *         if the config was hand-edited; callers should run it through
     *         {@link net.shasankp000.GameAI.persona.PersonaRegistry#getOrDefault}).
     */
    public String getBotPersona(String botName) {
        if (botPersonaMap == null) return net.shasankp000.GameAI.persona.PersonaRegistry.DEFAULT_ID;
        return botPersonaMap.getOrDefault(
                botName,
                net.shasankp000.GameAI.persona.PersonaRegistry.DEFAULT_ID);
    }

    /**
     * Sets the active persona ID for {@code botName} and persists the config.
     *
     * @param botName   The in-game bot name.
     * @param personaId A persona ID known to
     *                  {@link net.shasankp000.GameAI.persona.PersonaRegistry}.
     */
    public void setBotPersona(String botName, String personaId) {
        if (botPersonaMap == null) botPersonaMap = new HashMap<>();
        botPersonaMap.put(botName, personaId);
        save();
    }
}
