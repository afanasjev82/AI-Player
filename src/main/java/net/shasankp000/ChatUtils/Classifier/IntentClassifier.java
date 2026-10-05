package net.shasankp000.ChatUtils.Classifier;

/**
 * The library-facing seam for intent classification.
 *
 * <p>AI-Player currently ships one implementation (the built-in
 * BERT + CART + LIDSNet ensemble inside {@code NLPProcessor}). This interface
 * lets an alternative classifier (e.g. a Jev-compatible {@code laya-serve}
 * instance) be plugged in behind the same call sites without changing the
 * surrounding API. See {@link IntentClassifierFactory} for how the
 * implementation is selected.
 */
public interface IntentClassifier {

    /**
     * Classify a raw player message into an intent label.
     *
     * @param playerMessage the player's message
     * @return the classification result, or {@code null} if the classifier
     *         could not produce an answer (callers should fall back).
     */
    ClassificationResult classify(String playerMessage);
}
