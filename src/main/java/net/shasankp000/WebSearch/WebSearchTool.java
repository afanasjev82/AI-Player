package net.shasankp000.WebSearch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.amithkoujalgi.ollama4j.core.models.chat.*;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.ServiceLLMClients.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebSearchTool {

    private static final Logger logger = LoggerFactory.getLogger("web-search-tool");
    // Force HTTP/1.1: a plain-TCP vLLM/uvicorn endpoint does not speak HTTP/2,
    // and Java's HttpClient defaults to HTTP/2 — which loses the request body
    // against such a server (vLLM then reports "Field required, loc body").
    // HTTP/1.1 is universally supported, so this is safe for the HTTPS cloud
    // providers (DeepSeek/Gemini/Serper) too.
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    // DuckDuckGo's Instant Answer API rejects Java's default User-Agent
    // ("Java-http-client/25") with HTTP 403, even though the same URL works from
    // curl with an empty UA. Any browser-like UA gets HTTP 200, so this is sent
    // on the DuckDuckGo request only.
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static String llmMode = System.getProperty("aiplayer.llmMode", "custom");
    private static final Path CACHE_DIR = Paths.get("web_cache");

    static {
        try {
            Files.createDirectories(CACHE_DIR);

        } catch (Exception e) {
            logger.error("❌ Could not create cache directory: {}", e.getMessage());
        }
    }

    public static String search(String query) {
        String cleanQuery = query.trim();
        String generatedQuery = createQuery(cleanQuery);
        String cacheKey = hash(generatedQuery + AISearchConfig.PREFERRED_PROVIDER);
        String cached = getCached(cacheKey);
        if (cached != null) {
            logger.info("✅ Using cached search result for: {}", cleanQuery);
            return cached;
        }

        String result;
        switch (AISearchConfig.PREFERRED_PROVIDER.toLowerCase()) {
            case "gemini" -> result = geminiSearch(cleanQuery);
            case "serper" -> result = serperSearch(cleanQuery);
            case "duckduckgo", "ddg" -> result = duckDuckGoSearch(cleanQuery);
            case "deepseek" -> result = openAiCompatibleSearch(cleanQuery,
                    AISearchConfig.DEEPSEEK_BASE_URL, AISearchConfig.DEEPSEEK_API_KEY,
                    AISearchConfig.DEEPSEEK_MODEL, "DeepSeek");
            case "openai_compatible", "local" -> result = openAiCompatibleSearch(cleanQuery,
                    AISearchConfig.LOCAL_SEARCH_BASE_URL, AISearchConfig.LOCAL_SEARCH_KEY,
                    AISearchConfig.LOCAL_SEARCH_MODEL, "local LLM");
            default -> result = "❌ No search provider configured.";
        }

        // Free, keyless fallback: if the configured provider failed — an
        // exhausted credit balance (Serper 402), a revoked key, a quota — try
        // DuckDuckGo's public Instant Answer API before giving up. It needs no
        // key and no account, so it keeps the answer path alive when a paid
        // provider stops working. It is deliberately NOT used as the primary
        // source: it only covers instant-answer style questions, not the open
        // web (see duckDuckGoSearch).
        boolean usedFallback = false;
        if (result.startsWith("❌") && !isDuckDuckGoProvider()) {
            String fallback = duckDuckGoSearch(cleanQuery);
            if (!fallback.startsWith("❌")) {
                logger.info("🌐 Primary provider '{}' failed; answered via the free DuckDuckGo fallback",
                        AISearchConfig.PREFERRED_PROVIDER);
                result = fallback;
                usedFallback = true;
            }
        }

        if (!result.startsWith("❌")) {
            // Cache under the provider that actually answered, so a later run
            // with a working paid key does not serve the fallback's answer.
            saveCache(hash(generatedQuery + (usedFallback ? "duckduckgo" : AISearchConfig.PREFERRED_PROVIDER)),
                    result);
        } else {
            logger.info("ℹ️ Not caching failed/empty result for: {}", cleanQuery);
        }

        return result;
    }

    /** Whether DuckDuckGo is the configured provider (so we skip self-fallback). */
    private static boolean isDuckDuckGoProvider() {
        String provider = AISearchConfig.PREFERRED_PROVIDER == null
                ? "" : AISearchConfig.PREFERRED_PROVIDER.toLowerCase();
        return provider.equals("duckduckgo") || provider.equals("ddg");
    }

    private static String geminiSearch(String query) {
        try {
            // Read the resolved config field, not AIPlayer.CONFIG directly: the
            // field is seeded from settings.json5 and can be overridden by
            // aiplayer.geminiKey (.env) / the JSON file, so this is the single
            // source of truth — matching how the other providers read their keys.
            String apiKey = AISearchConfig.GEMINI_API_KEY;
            if (apiKey.isBlank()) return "❌ Gemini API key is missing.";

            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + apiKey;
            String payload = """
            {
              "contents": [{
                "parts": [{
                  "text": "Please provide a factual web-style answer for this question: %s"
                }]
              }]
            }
            """.formatted(query);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            int status = res.statusCode();
            if (status < 200 || status >= 300) {
                logger.error("❌ Gemini search failed: HTTP {} — {}", status, brief(res.body()));
                return "❌ Gemini search failed: HTTP " + status + " " + brief(res.body());
            }

            JsonNode root = new ObjectMapper().readTree(res.body());
            String answer = root.at("/candidates/0/content/parts/0/text").asText();

            logger.info("🌐 Gemini result: {}", answer);
            return answer.isBlank() ? "❌ Gemini returned no answer." : answer;

        } catch (Exception e) {
            logger.error("❌ Gemini search failed: {}", e.getMessage());
            return "❌ Gemini search failed: " + e.getMessage();
        }
    }

    private static String serperSearch(String query) {
        try {
            String apiKey = AISearchConfig.SERPER_API_KEY;
            if (apiKey.isBlank()) return "❌ Serper API key is missing.";

            String url = "https://google.serper.dev/search";
            String payload = "{ \"q\": \"%s\" }".formatted(query);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-API-KEY", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            int status = res.statusCode();
            if (status < 200 || status >= 300) {
                // Serper reports an exhausted credit balance and an invalid key
                // here (402/403); without this the caller saw only "no results".
                logger.error("❌ Serper search failed: HTTP {} — {}", status, brief(res.body()));
                return "❌ Serper search failed: HTTP " + status + " " + brief(res.body());
            }

            JsonNode root = new ObjectMapper().readTree(res.body());
            JsonNode organic = root.path("organic");
            if (organic.isArray() && organic.size() > 0) {
                JsonNode first = organic.get(0);
                String title = first.path("title").asText();
                String snippet = first.path("snippet").asText();
                String link = first.path("link").asText();
                String result = "🔗 " + title + "\n" + snippet + "\n" + link;
                logger.info("🌐 Serper result: {}", result);
                return result;
            }

            return "❌ Serper returned no results.";

        } catch (Exception e) {
            logger.error("❌ Serper search failed: {}", e.getMessage());
            return "❌ Serper search failed: " + e.getMessage();
        }
    }

    /**
     * DuckDuckGo Instant Answer API — free, keyless, no account.
     *
     * <p><b>What this is and is not.</b> It is <em>not</em> a general web search:
     * it returns DuckDuckGo's curated instant answers, so open-ended factual
     * questions ("capital of France", "population of Tokyo") generally come back
     * empty. It is strong on entity/definition/calculation answers, and it has a
     * dedicated {@code minecraft} answer source that returns exact crafting
     * recipes — which is exactly what a Minecraft bot tends to ask about
     * ("minecraft bow" → "Bow ingredients: 3 Sticks + 3 String").
     *
     * <p>That is why it is wired as a <em>fallback</em> rather than the primary
     * provider: it keeps the answer path alive for free when a paid/keyed
     * provider fails, without pretending to replace it.
     */
    private static String duckDuckGoSearch(String query) {
        try {
            String url = "https://api.duckduckgo.com/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&format=json&no_html=1&skip_disambig=1";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            int status = res.statusCode();
            if (status < 200 || status >= 300) {
                logger.error("❌ DuckDuckGo search failed: HTTP {} — {}", status, brief(res.body()));
                return "❌ DuckDuckGo search failed: HTTP " + status;
            }

            JsonNode root = new ObjectMapper().readTree(res.body());

            // Preference order: instant answer (covers the minecraft recipes),
            // then the Wikipedia-style abstract, then a definition, then the
            // first related topic.
            String answer = root.path("Answer").asText("");
            if (answer.isBlank()) answer = root.path("AbstractText").asText("");
            if (answer.isBlank()) answer = root.path("Definition").asText("");
            if (answer.isBlank()) answer = duckDuckGoFirstRelatedTopic(root);

            if (answer.isBlank()) {
                logger.info("ℹ️ DuckDuckGo had no instant answer for: {}", query);
                return "❌ DuckDuckGo has no instant answer for this query.";
            }

            String source = root.path("AbstractSource").asText("");
            String sourceUrl = root.path("AbstractURL").asText("");
            StringBuilder sb = new StringBuilder();
            if (!source.isBlank()) sb.append("🔗 ").append(source).append('\n');
            sb.append(answer);
            if (!sourceUrl.isBlank()) sb.append('\n').append(sourceUrl);

            String result = sb.toString();
            logger.info("🌐 DuckDuckGo result: {}", result);
            return result;

        } catch (Exception e) {
            logger.error("❌ DuckDuckGo search failed: {}", e.getMessage());
            return "❌ DuckDuckGo search failed: " + e.getMessage();
        }
    }

    /** First usable {@code RelatedTopics[].Text}, skipping nested topic groups. */
    private static String duckDuckGoFirstRelatedTopic(JsonNode root) {
        JsonNode topics = root.path("RelatedTopics");
        if (!topics.isArray()) return "";
        for (JsonNode topic : topics) {
            String text = topic.path("Text").asText("");
            if (!text.isBlank()) return text;
        }
        return "";
    }

    /**
     * Ask an OpenAI-compatible chat-completions endpoint to answer the question
     * directly. Serves both DeepSeek and any local/free OpenAI-compatible server
     * (vLLM, Ollama's OpenAI endpoint, etc.) — same request contract, different
     * base URL / model / key.
     */
    private static String openAiCompatibleSearch(String query, String baseUrl,
                                                 String apiKey, String model, String providerLabel) {
        try {
            if (baseUrl == null || baseUrl.isBlank())
                return "❌ " + providerLabel + " base URL is missing.";
            if (model == null || model.isBlank())
                return "❌ " + providerLabel + " model is missing.";

            String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0);
            // Reserve explicit completion budget. Some reasoning models (gemma-4)
            // spend their default budget on internal reasoning and can truncate the
            // visible answer to empty when the endpoint is under load; a fixed
            // budget keeps the answer from being starved.
            body.put("max_tokens", 2048);
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", "Please provide a factual web-style answer for this question: " + query);

            String bodyJson = mapper.writeValueAsString(body);
            logger.info("🌐 {} search → {} (model={})", providerLabel, url, model);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> res = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int status = res.statusCode();
            if (status < 200 || status >= 300) {
                logger.error("❌ {} search failed: HTTP {} — {}", providerLabel, status,
                        res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
                return "❌ " + providerLabel + " search failed: HTTP " + status;
            }

            JsonNode root = mapper.readTree(res.body());
            JsonNode message = root.at("/choices/0/message");
            // Reasoning models may put the answer in `reasoning`/`reasoning_content`
            // and leave `content` empty; read whichever field actually carries text.
            String answer = message.path("content").asText("");
            if (answer.isBlank()) answer = message.path("reasoning").asText("");
            if (answer.isBlank()) answer = message.path("reasoning_content").asText("");
            if (answer.isBlank()) {
                String finish = root.at("/choices/0/finish_reason").asText("?");
                logger.warn("🌐 {} returned an empty answer (finish_reason={}, body={})", providerLabel, finish,
                        res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
            }

            logger.info("🌐 {} result: {}", providerLabel, answer);
            return answer.isBlank() ? "❌ " + providerLabel + " returned no answer." : answer;

        } catch (Exception e) {
            logger.error("❌ {} search failed: {}", providerLabel, e.getMessage());
            return "❌ " + providerLabel + " search failed: " + e.getMessage();
        }
    }

    private static String createQuery(String prompt) {
        String query_msg = "You are a first-principles reasoning search query AI agent. Your task is to a query which will be used to search the web on the topic of the prompt. The output must be a single, valid, properly formed, query. Here are a few examples of input strings you may receive:\n" +
                """
                 "How can I build an automatic farm in Minecraft?",
                 "What were the resources needed for the enchantment table?",
                 "Tell me about the mining strategy we discussed last time."
                 "Did you go somewhere recently?"
                 "What ores did you mine recently?"
                 "Please go to coordinates 10 20 30."
                 "Please be on the lookout for hostile mobs nearby."
                
                \n""" +
                "And here are examples of the format your output must follow:\n" +
                "What are the steps to build an automatic farm in Minecraft?, \n" +
                "What resources were listed for creating an enchantment table?, \n" +
                "What mining strategy was discussed on yyyy/mm/dd hh:mm:ss?, \n" +
                "Where did the bot go to recently?, \n" +
                "What ores did the bot mine recently?\n" +

                "Please remember that it is absolutely crucial that you generate queries relevant to the user prompts. DO NOT ANSWER AS AN ASSISTANT. THIS WILL CAUSE THE WEB SEARCH TOOL PROBLEMS. JUST OUTPUT THE QUERY. \n" +

                "Return only one properly drafted query for the user prompt.\n";


        List<Map<String, String>> queryConvo = new ArrayList<>();
        Map<String, String> queryMap1 = new HashMap<>();

        queryMap1.put("role", "system");
        queryMap1.put("content", query_msg);

        queryConvo.add(queryMap1);

        String response = "";

        switch (llmMode) {
            case "openai", "gpt", "gemini", "google", "claude", "anthropic", "grok", "xai", "xAI", "custom":
                LLMClient client = LLMClientFactory.createClient(llmMode);

                if (client != null)  {
                    if (client.isReachable()) {
                        response = client.sendPrompt(query_msg, prompt);
                    }
                    else {
                        logger.error("Error! {} client is not reachable. Please check your internet connection or try again later", client.getProvider());
                    }
                }
                else {
                    logger.error("Error! {} client is null!", llmMode);
                }
                break;

            default:
                logger.warn("Unsupported provider detected.");
                return "Unsupported LLM provider used. Queries could not be generated.";
        }

        return response;
    }

    private static String createQuery(String prompt, LLMClient client) {
        String query_msg = "You are a first-principles reasoning search query AI agent. Your task is to a query which will be used to search the web on the topic of the prompt. The output must be a single, valid, properly formed, query. Here are a few examples of input strings you may receive:\n" +
                """
                 "How can I build an automatic farm in Minecraft?",
                 "What were the resources needed for the enchantment table?",
                 "Tell me about the mining strategy we discussed last time."
                 "Did you go somewhere recently?"
                 "What ores did you mine recently?"
                 "Please go to coordinates 10 20 30."
                 "Please be on the lookout for hostile mobs nearby."
                
                \n""" +
                "And here are examples of the format your output must follow:\n" +
                "What are the steps to build an automatic farm in Minecraft?, \n" +
                "What resources were listed for creating an enchantment table?, \n" +
                "What mining strategy was discussed on yyyy/mm/dd hh:mm:ss?, \n" +
                "Where did the bot go to recently?, \n" +
                "What ores did the bot mine recently?\n" +

                "Please remember that it is absolutely crucial that you generate queries relevant to the user prompts. DO NOT ANSWER AS AN ASSISTANT. THIS WILL CAUSE THE WEB SEARCH TOOL PROBLEMS. JUST OUTPUT THE QUERY. \n" +

                "Return only one properly drafted query for the user prompt.\n";


        String response = "";

        if (client.isReachable()) {
            response = client.sendPrompt(query_msg, prompt);
        }
        else {
            logger.warn("{} is not reachable; cannot generate a web search query.", client.getProvider());
        }


        return response;
    }

    /**
     * Shorten an error body for logs/messages. Provider error payloads can be
     * long JSON documents, and the useful part (message/error/type) is at the
     * front — keeping this bounded stops a 4xx from flooding the console.
     */
    private static String brief(String body) {
        if (body == null) return "";
        String trimmed = body.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }

    private static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static void saveCache(String hash, String content) {
        try {
            Path file = CACHE_DIR.resolve(hash + ".txt");
            Files.writeString(file, content);
            logger.info("💾 Saved search result to cache.");
        } catch (Exception ignored) {}
    }

    private static String getCached(String hash) {
        Path file = CACHE_DIR.resolve(hash + ".txt");
        if (Files.exists(file)) {
            try {
                return Files.readString(file);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
