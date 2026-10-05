package net.shasankp000.ChatUtils.Classifier;

/**
 * Selects the active intent classifier from JVM configuration.
 *
 * <p>Mirrors the existing {@code aiplayer.llmMode} pattern: the value is set as
 * a {@code -D} flag (from {@code JVM_DD_OPTS} in the docker-compose file, or a
 * system property at launch).
 *
 * <ul>
 *   <li>{@code aiplayer.classifier=bert} (default) — the built-in BERT + CART +
 *       LIDSNet ensemble; this factory returns {@code null} and {@link
 *       net.shasankp000.ChatUtils.NLPProcessor} runs its existing pipeline.</li>
 *   <li>{@code aiplayer.classifier=laya} — a self-hosted Laya (Jev-compatible)
 *       decision server via {@link LayaIntentClassifier}.</li>
 * </ul>
 */
public final class IntentClassifierFactory {

    private IntentClassifierFactory() {
    }

    /**
     * @return the configured primary classifier. Laya (Jev-compatible) when
     *         {@code aiplayer.classifier=laya}; otherwise the built-in
     *         BERT + CART + LIDSNet ensemble.
     */
    public static IntentClassifier create() {
        if (LayaClient.isEnabled()) {
            return new LayaIntentClassifier(LayaClient.getInstance());
        }
        return new BertEnsembleClassifier();
    }

    /** @return true when {@code aiplayer.classifier=laya} is set. */
    public static boolean isLayaEnabled() {
        return LayaClient.isEnabled();
    }
}

