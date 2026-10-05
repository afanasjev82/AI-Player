package net.shasankp000.ChatUtils.Classifier;

/**
 * Laya (TypeSafe Jev-compatible) intent classifier over HTTP.
 *
 * <p>Thin wrapper over the shared {@link LayaClient}: asks a single
 * {@code choice} question with the three canonical AI-Player intents and maps
 * Laya's answer onto a {@link ClassificationResult}. The label is the
 * {@code REQUEST_ACTION} / {@code ASK_INFORMATION} /
 * {@code GENERAL_CONVERSATION} enum name, so it can be fed straight into
 * {@code NLPProcessor.Intent.valueOf}.
 *
 * <p>Errors return {@code null} (never throw), so the caller's fallback path is
 * undisturbed.
 */
public class LayaIntentClassifier implements IntentClassifier {

    private static final String QUESTION_ID = "intent";

    private final LayaClient client;

    public LayaIntentClassifier(LayaClient client) {
        this.client = client;
    }

    @Override
    public ClassificationResult classify(String playerMessage) {
        LayaAnswer answer = client.predictOne(playerMessage, QUESTION_ID, LayaQuestions.intent());
        return (answer == null || answer.choice() == null) ? null : answer.toResult();
    }
}

