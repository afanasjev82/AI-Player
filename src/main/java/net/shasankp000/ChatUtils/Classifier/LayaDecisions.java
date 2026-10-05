package net.shasankp000.ChatUtils.Classifier;

import java.util.Map;

/**
 * The batched result of {@link LayaClient#decide}: intent, goal and urgency
 * scored from a single state in one forward pass.
 *
 * @param intent               the intent classification (choice)
 * @param goal                 the goal classification (choice)
 * @param urgencyScore         the urgency {@code score} expected level index
 * @param urgencyProbabilities per-level probability distribution for urgency
 */
public record LayaDecisions(
        ClassificationResult intent,
        ClassificationResult goal,
        double urgencyScore,
        Map<String, Double> urgencyProbabilities) {
}
