package net.shasankp000.GameAI.autonomous;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the WANDER-mode "home anchor" return decision — the pure helper that
 * decides when a bot that has built a shelter should navigate back to it.
 */
class HomeAnchorTest {

    @Test
    void nightReturnsHomeWhenFar() {
        assertTrue(AutonomousGoalEngine.shouldReturnHome(13.0, true, false), "night + far -> return");
        assertTrue(AutonomousGoalEngine.shouldReturnHome(30.0, true, true), "night + far + idle -> return");
    }

    @Test
    void nightStaysPutWhenNear() {
        assertFalse(AutonomousGoalEngine.shouldReturnHome(11.9, true, false), "night + near -> stay");
        assertFalse(AutonomousGoalEngine.shouldReturnHome(0.0, true, true), "night + at home -> stay");
    }

    @Test
    void dayIdleReturnsHomeWhenFar() {
        assertTrue(AutonomousGoalEngine.shouldReturnHome(25.0, false, true), "day + idle + far -> return");
    }

    @Test
    void dayIdleStaysWhenNear() {
        assertFalse(AutonomousGoalEngine.shouldReturnHome(23.9, false, true), "day + idle + near -> stay");
    }

    @Test
    void dayBusyNeverReturnsHome() {
        // During the day with a non-empty queue the bot is allowed to roam freely.
        assertFalse(AutonomousGoalEngine.shouldReturnHome(50.0, false, false), "day + busy -> roam");
    }

    @Test
    void nightReturnOutranksIdleReturn() {
        assertTrue(AutonomousGoalEngine.HOME_RETURN_NIGHT_PRIORITY > AutonomousGoalEngine.HOME_RETURN_IDLE_PRIORITY,
                "night return must dominate the idle return");
    }

    @Test
    void wanderGoalsAreMovementGoals() {
        assertTrue(AutonomousGoalEngine.isWanderGoal("gather 32 wood"));
        assertTrue(AutonomousGoalEngine.isWanderGoal("mine 16 stone"));
        assertTrue(AutonomousGoalEngine.isWanderGoal("explore the area"));
        assertTrue(AutonomousGoalEngine.isWanderGoal("navigate to 10 64 -20"));
    }

    @Test
    void indoorGoalsAreNotWanderGoals() {
        assertFalse(AutonomousGoalEngine.isWanderGoal("craft a stone pickaxe"));
        assertFalse(AutonomousGoalEngine.isWanderGoal("build a shelter"));
        assertFalse(AutonomousGoalEngine.isWanderGoal("farm some wheat"));
        assertFalse(AutonomousGoalEngine.isWanderGoal("trade with the villager"));
    }
}
