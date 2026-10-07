package net.shasankp000.GameAI.autonomous;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the dynamic-priority scorer ({@link ThreatHeuristicGoalPriorityScorer})
 * and {@link GoalQueueEntry#rescore} — the seams that let a queued goal's
 * priority change with the bot's situation without being re-enqueued.
 */
class GoalPriorityScorerTest {

    private final ThreatHeuristicGoalPriorityScorer scorer = new ThreatHeuristicGoalPriorityScorer();

    @Test
    void calmContextKeepsBasePriority() {
        GoalQueueEntry entry = new GoalQueueEntry("gather 16 wood", 0, GoalQueueEntry.Source.LLM_PLAN);
        assertEquals(0, scorer.computePriority(entry, GoalPriorityContext.CALM));
    }

    @Test
    void nightBoostsBuildGoalOnly() {
        GoalQueueEntry build = new GoalQueueEntry("build a shelter", 0, GoalQueueEntry.Source.LLM_PLAN);
        GoalQueueEntry gather = new GoalQueueEntry("gather 16 wood", 0, GoalQueueEntry.Source.LLM_PLAN);
        GoalPriorityContext night = new GoalPriorityContext(false, 0, true, 1.0f, 20);

        assertTrue(scorer.computePriority(build, night) > 0, "build should be boosted at night");
        assertEquals(0, scorer.computePriority(gather, night), "gather stays at base at night");
    }

    @Test
    void threatBoostsCombatGoalOnly() {
        GoalQueueEntry combat = new GoalQueueEntry("defeat the zombie", 5, GoalQueueEntry.Source.PLAYER);
        GoalQueueEntry build = new GoalQueueEntry("build a house", 0, GoalQueueEntry.Source.LLM_PLAN);
        GoalPriorityContext threat = new GoalPriorityContext(true, 3, false, 1.0f, 20);

        assertTrue(scorer.computePriority(combat, threat) > 5, "combat should jump on threat");
        assertEquals(0, scorer.computePriority(build, threat), "build unchanged on threat");
    }

    @Test
    void escalationNeverDropsBelowBase() {
        GoalQueueEntry entry = new GoalQueueEntry("gather 16 wood", 7, GoalQueueEntry.Source.PLAYER);
        for (GoalPriorityContext ctx : new GoalPriorityContext[]{
                GoalPriorityContext.CALM,
                new GoalPriorityContext(true, 5, true, 0.3f, 4),
                new GoalPriorityContext(false, 0, true, 1.0f, 20)}) {
            assertTrue(scorer.computePriority(entry, ctx) >= 7, "never below base priority");
        }
    }

    @Test
    void rescoreReturnsNewPriorityCopy() {
        GoalQueueEntry original = new GoalQueueEntry("build a shelter", 0, GoalQueueEntry.Source.LLM_PLAN);
        GoalQueueEntry rescored = original.rescore(30);

        assertEquals(0, original.priority(), "original priority unchanged");
        assertEquals(30, rescored.priority());
        assertEquals("build a shelter", rescored.goalText());
        assertEquals(GoalQueueEntry.Source.LLM_PLAN, rescored.source());
    }
}
