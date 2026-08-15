package net.shasankp000.GameAI.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards {@link Plan#getTotalScore()} — the hybrid planner stores its path
 * score in {@code Plan.score}, while the Markov planner stores risk in
 * {@code Plan.estimatedRisk}. Callers must never see a spurious 0.0.
 */
class PlanTest {

    @Test
    void returnsScoreWhenOnlyScoreIsSet() {
        Plan plan = new Plan(UUID.randomUUID(), (short) 1, List.of());
        plan.score = 42.5;
        plan.estimatedRisk = 0.0;

        assertEquals(42.5, plan.getTotalScore(), 1e-6);
    }

    @Test
    void returnsRiskWhenOnlyRiskIsSet() {
        Plan plan = new Plan(UUID.randomUUID(), (short) 1, List.of());
        plan.score = 0.0;
        plan.estimatedRisk = 17.0;

        assertEquals(17.0, plan.getTotalScore(), 1e-6);
    }

    @Test
    void prefersScoreWhenBothAreSet() {
        Plan plan = new Plan(UUID.randomUUID(), (short) 1, List.of());
        plan.score = 3.0;
        plan.estimatedRisk = 99.0;

        assertEquals(3.0, plan.getTotalScore(), 1e-6);
    }

    @Test
    void returnsZeroWhenNeitherIsSet() {
        Plan plan = new Plan(UUID.randomUUID(), (short) 1, List.of());
        assertEquals(0.0, plan.getTotalScore(), 1e-6);
    }
}
