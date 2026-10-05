package net.shasankp000.ChatUtils.Classifier;

/**
 * Selects the active goal classifier from JVM configuration.
 *
 * <p>Mirrors {@link IntentClassifierFactory}: {@code aiplayer.classifier=laya}
 * returns a Laya-backed classifier; otherwise {@code null}, so
 * {@code GoalMapper} runs its built-in weighted-token-scorer + edge-LLM
 * pipeline.
 */
public final class GoalClassifierFactory {

    private GoalClassifierFactory() {
    }

    /**
     * @return the configured classifier, or {@code null} to use the built-in
     *         pipeline (the default).
     */
    public static GoalClassifier create() {
        if (!LayaClient.isEnabled()) {
            return null;
        }
        return new LayaGoalClassifier(LayaClient.getInstance());
    }
}
