package net.shasankp000.ChatUtils.Classifier;

import java.util.Map;

/**
 * A classifier's typed answer: the predicted label, the confidence in it, and
 * the full probability distribution over labels when the classifier exposes one.
 *
 * <p>The {@code label} is a plain string so the interface stays independent of
 * any particular label vocabulary (intent enum, goal id, etc.). Callers map it
 * to their own types.
 *
 * @param label         the argmax label (e.g. {@code REQUEST_ACTION}, {@code mine})
 * @param confidence    confidence in {@code label}, in {@code [0, 1]}
 * @param probabilities optional per-label probabilities (label -> probability)
 */
public record ClassificationResult(String label, double confidence, Map<String, Double> probabilities) {
}
