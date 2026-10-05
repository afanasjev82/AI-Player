package net.shasankp000.ChatUtils.Classifier;

/**
 * Laya (TypeSafe Jev-compatible) goal classifier over HTTP.
 *
 * <p>Thin wrapper over the shared {@link LayaClient}: asks a single
 * {@code choice} question with the nine {@code GoalMapper} goals and maps
 * Laya's answer onto a {@link ClassificationResult}. The label is the goal
 * name ({@code mine}, {@code build}, …), which {@code GoalMapper} turns back
 * into a goal id.
 */
public class LayaGoalClassifier implements GoalClassifier {

    private static final String QUESTION_ID = "goal";

    private final LayaClient client;

    public LayaGoalClassifier(LayaClient client) {
        this.client = client;
    }

    @Override
    public ClassificationResult classify(String naturalLanguageGoal) {
        LayaAnswer answer = client.predictOne(naturalLanguageGoal, QUESTION_ID, LayaQuestions.goal());
        return (answer == null || answer.choice() == null) ? null : answer.toResult();
    }
}
