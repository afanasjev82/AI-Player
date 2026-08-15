package net.shasankp000.GameAI.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the planner's semantic-similarity foundation.
 *
 * <p>Regression target: {@code GoalVector.embedGoal()} previously filled 48 of
 * 64 embedding dimensions with {@code Math.random()}, so cosine similarity
 * between a goal and an action was dominated by uncorrelated noise and the
 * "closest action to goal" selection was effectively random — which is why
 * {@code /bot plan} collapsed to trivial steps like "look".
 */
class GoalVectorTest {

    private final GoalVector goalVector = new GoalVector();

    @Test
    void embeddingIsDeterministic() {
        float[] a = goalVector.embedGoal("mine some wood");
        float[] b = goalVector.embedGoal("mine some wood");

        assertArrayEquals(a, b, 1e-6f, "Same input must produce identical embedding (no random noise)");
    }

    @Test
    void identicalTextHasCosineSimilarityOne() {
        float[] a = goalVector.embedGoal("build a house");
        assertEquals(1.0, goalVector.cosineSimilarity(a, a), 1e-6);
    }

    @Test
    void semanticallyRelatedGoalsAreCloserThanUnrelatedGoals() {
        float[] mineWood = goalVector.embedGoal("mine wood");
        float[] mineStone = goalVector.embedGoal("mine stone");
        float[] buildHouse = goalVector.embedGoal("build a house");

        double related = goalVector.cosineSimilarity(mineWood, mineStone);
        double unrelated = goalVector.cosineSimilarity(mineWood, buildHouse);

        assertTrue(
            related > unrelated,
            "mining goals should be closer to each other than to a building goal "
                + "(related=" + related + ", unrelated=" + unrelated + ")"
        );
    }

    @Test
    void unrelatedKeywordsHaveZeroSimilarity() {
        // "attack" (dims 8,9) and "build" (dims 10,11) share no keyword dims.
        float[] attack = goalVector.embedGoal("attack the zombie");
        float[] build = goalVector.embedGoal("build a wall");
        assertEquals(0.0, goalVector.cosineSimilarity(attack, build), 1e-6);
    }
}
