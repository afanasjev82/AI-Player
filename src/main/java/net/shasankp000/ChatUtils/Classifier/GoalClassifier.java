package net.shasankp000.ChatUtils.Classifier;

/**
 * The library-facing seam for goal classification (natural-language goal
 * phrase → a goal label, e.g. {@code "mine"}).
 *
 * <p>The built-in pipeline lives in {@code GoalMapper} (weighted token scorer +
 * edge-LLM fallback). This interface lets an alternative classifier (a
 * Jev-compatible {@code laya-serve} instance) be plugged in behind the same
 * call site. See {@link GoalClassifierFactory}.
 */
public interface GoalClassifier {

    /**
     * Classify a natural-language goal phrase into a goal label.
     *
     * @param naturalLanguageGoal the goal text (e.g. {@code "mine some stone"})
     * @return the classification result, or {@code null} if the classifier
     *         could not produce an answer (callers should fall back).
     */
    ClassificationResult classify(String naturalLanguageGoal);
}
