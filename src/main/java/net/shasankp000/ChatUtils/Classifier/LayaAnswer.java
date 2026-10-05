package net.shasankp000.ChatUtils.Classifier;

import java.util.Map;

/**
 * One typed answer from Laya's {@code /v1/systemone} response, per question id.
 *
 * <p>Exactly one of {@code choice} / {@code score} / {@code noul} is populated
 * depending on the question type; the others are their zero values. Only the
 * fields present in the JSON are set.
 *
 * @param choice            the argmax option label (choice questions; {@code null} otherwise)
 * @param score             the expected level index (score questions)
 * @param noul              P(yes) in {@code [0,1]} (noul questions)
 * @param answerConfidence  {@code max(p)} — the calibrated confidence in the reported answer
 * @param probabilities     per-option or per-level probability distribution
 */
public record LayaAnswer(
        String choice,
        double score,
        double noul,
        double answerConfidence,
        Map<String, Double> probabilities) {

    /** Project this answer onto the generic result shape used by callers. */
    public ClassificationResult toResult() {
        return new ClassificationResult(choice, answerConfidence, probabilities);
    }
}
