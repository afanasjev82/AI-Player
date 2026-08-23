package net.shasankp000.GameAI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the ActionTracker extraction: the in-flight action lifecycle and
 * timeout auto-completion must behave exactly as the old BotEventHandler
 * static methods did.
 */
class ActionTrackerTest {

    @Test
    void startAndCompleteLifecycle() {
        ActionTracker tracker = new ActionTracker();

        assertFalse(tracker.isInProgress("Paul"));
        tracker.start("Paul", "ATTACK");
        assertTrue(tracker.isInProgress("Paul"));
        assertEquals("ATTACK", tracker.getCurrentAction("Paul"));

        tracker.complete("Paul");
        assertFalse(tracker.isInProgress("Paul"));
        assertNull(tracker.getCurrentAction("Paul"));
    }

    @Test
    void unknownBotIsNotInProgress() {
        ActionTracker tracker = new ActionTracker();
        assertFalse(tracker.isInProgress("Nobody"));
        assertNull(tracker.getCurrentAction("Nobody"));
    }

    @Test
    void completeIsIdempotent() {
        ActionTracker tracker = new ActionTracker();
        tracker.start("Paul", "MINE");
        tracker.complete("Paul");
        tracker.complete("Paul"); // no-op, must not throw
        assertFalse(tracker.isInProgress("Paul"));
    }
}
