package net.shasankp000.ChatUtils.Classifier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level client for a self-hosted {@code laya-serve} instance speaking the
 * TypeSafe Jev {@code POST /v1/systemone} wire protocol.
 *
 * <p>This is the shared HTTP surface behind every Laya-backed classifier, and
 * the home of batching: {@link #predict} carries several questions in one
 * request (one non-autoregressive forward pass), and {@link #decide} asks the
 * canonical intent + goal + urgency triple together.
 *
 * <p>One JVM-wide instance owns the pooled {@link HttpClient}; {@link #getInstance}
 * builds it lazily from the {@code aiplayer.layaBaseUrl} /
 * {@code aiplayer.layaApiKey} system properties.
 */
public final class LayaClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("LayaClient");

    private static final String CLASSIFIER_PROP = "aiplayer.classifier";
    private static final String BASE_URL_PROP = "aiplayer.layaBaseUrl";
    private static final String API_KEY_PROP = "aiplayer.layaApiKey";

    /** Default endpoint when none is configured (matches the shipped .env). */
    public static final String DEFAULT_BASE_URL = "http://localhost:9000";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private static volatile LayaClient INSTANCE;

    private final String baseUrl;
    private final String apiKey;

    private LayaClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** @return true when {@code aiplayer.classifier=laya} is set. */
    public static boolean isEnabled() {
        return "laya".equals(System.getProperty(CLASSIFIER_PROP, "bert").trim().toLowerCase());
    }

    /** @return the shared client, lazily built from system properties. */
    public static LayaClient getInstance() {
        LayaClient instance = INSTANCE;
        if (instance == null) {
            synchronized (LayaClient.class) {
                instance = INSTANCE;
                if (instance == null) {
                    String url = System.getProperty(BASE_URL_PROP, DEFAULT_BASE_URL);
                    String key = System.getProperty(API_KEY_PROP, "");
                    instance = new LayaClient(url, key);
                    INSTANCE = instance;
                }
            }
        }
        return instance;
    }

    /**
     * Send one request carrying several questions over {@code state}; Laya
     * evaluates them all in a single forward pass. Results are keyed by
     * question id. Returns {@code null} on transport error, non-200, or a
     * malformed body.
     */
    public Map<String, LayaAnswer> predict(String state, Map<String, JsonObject> questions) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("state", state);
            JsonObject wrapped = new JsonObject();
            questions.forEach(wrapped::add);
            body.add("questions", wrapped);

            HttpResponse<String> response = HTTP.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("Laya returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            return parseAnswers(response.body());
        } catch (Exception e) {
            LOGGER.warn("Laya request failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Convenience for a single-question call; see {@link #predict}.
     */
    public LayaAnswer predictOne(String state, String questionId, JsonObject question) {
        Map<String, JsonObject> single = new LinkedHashMap<>();
        single.put(questionId, question);
        Map<String, LayaAnswer> answers = predict(state, single);
        return answers == null ? null : answers.get(questionId);
    }

    /**
     * The batched path: intent + goal + urgency in one request (one forward
     * pass). Returns {@code null} if the call fails or a required answer is
     * missing. The urgency score is the {@code score} primitive's expected
     * level index (0=casual … 2=urgent); see {@link LayaQuestions#urgency}.
     */
    public LayaDecisions decide(String state) {
        Map<String, JsonObject> questions = new LinkedHashMap<>();
        questions.put("intent", LayaQuestions.intent());
        questions.put("goal", LayaQuestions.goal());
        questions.put("urgency", LayaQuestions.urgency());

        Map<String, LayaAnswer> answers = predict(state, questions);
        if (answers == null) {
            return null;
        }
        LayaAnswer intent = answers.get("intent");
        LayaAnswer goal = answers.get("goal");
        if (intent == null || goal == null || intent.choice() == null || goal.choice() == null) {
            return null;
        }
        LayaAnswer urgency = answers.get("urgency");
        return new LayaDecisions(
                intent.toResult(),
                goal.toResult(),
                urgency == null ? 0.0 : urgency.score(),
                urgency == null ? Map.of() : urgency.probabilities());
    }

    /**
     * Multi-state batch: send several states against one shared question set in
     * a single request. The {@code /v1/systemone/batch} route groups them into
     * shared forward passes; results preserve input order. Returns {@code null}
     * on transport error, non-200, or a malformed body.
     *
     * <p>No caller in the single-message chat flow — this is the throughput
     * surface for future batch workloads (e.g. classifying a backlog of
     * messages in one round-trip).
     */
    public List<Map<String, LayaAnswer>> predictBatch(List<String> states, Map<String, JsonObject> questions) {
        if (states == null || states.isEmpty()) {
            return null;
        }
        try {
            JsonArray requests = new JsonArray();
            for (String state : states) {
                JsonObject body = new JsonObject();
                body.addProperty("state", state);
                JsonObject wrapped = new JsonObject();
                questions.forEach(wrapped::add);
                body.add("questions", wrapped);
                requests.add(body);
            }

            HttpResponse<String> response = HTTP.send(buildRequest(requests), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("Laya batch returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            JsonArray results = JsonParser.parseString(response.body()).getAsJsonArray();
            List<Map<String, LayaAnswer>> out = new ArrayList<>();
            for (JsonElement element : results) {
                out.add(parseAnswers(element.toString()));
            }
            return out;
        } catch (Exception e) {
            LOGGER.warn("Laya batch request failed: {}", e.getMessage());
            return null;
        }
    }

    private HttpRequest buildRequest(JsonElement body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/systemone"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }

    private static Map<String, LayaAnswer> parseAnswers(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject answers = root.getAsJsonObject("answers");
        if (answers == null) {
            return null;
        }
        Map<String, LayaAnswer> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : answers.entrySet()) {
            JsonObject answer = entry.getValue().getAsJsonObject();
            if (answer != null) {
                out.put(entry.getKey(), parseAnswer(answer));
            }
        }
        return out;
    }

    private static LayaAnswer parseAnswer(JsonObject answer) {
        String choice = optString(answer, "choice");
        double score = answer.has("score") ? answer.get("score").getAsDouble() : 0.0;
        double noul = answer.has("noul") ? answer.get("noul").getAsDouble() : 0.0;
        double confidence = answer.has("answer_confidence")
                ? answer.get("answer_confidence").getAsDouble()
                : 0.0;

        Map<String, Double> probabilities = new LinkedHashMap<>();
        JsonObject probs = answer.getAsJsonObject("probabilities");
        if (probs != null) {
            for (Map.Entry<String, JsonElement> entry : probs.entrySet()) {
                probabilities.put(entry.getKey(), entry.getValue().getAsDouble());
            }
        }
        return new LayaAnswer(choice, score, noul, confidence, probabilities);
    }

    private static String optString(JsonObject object, String key) {
        return (object.has(key) && !object.get(key).isJsonNull())
                ? object.get(key).getAsString()
                : null;
    }
}
