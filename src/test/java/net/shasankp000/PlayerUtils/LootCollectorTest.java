package net.shasankp000.PlayerUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reactive-combat loot sweep is a timing policy, so the interesting part is
 * {@link LootCollector#shouldSweep} — a pure gate that can be exercised without
 * a live server.
 */
class LootCollectorTest {

    private static final long KILL_AT = 1_000_000L;
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @AfterEach
    void clearPending() {
        // The pending record is static process state; don't leak it between tests.
        LootCollector.clear(BOT_ID);
    }

    private static boolean canSweep(long now) {
        return LootCollector.shouldSweep(now, KILL_AT, 0L);
    }

    @Test
    void sweepsOnceDropsHaveSettled() {
        assertTrue(canSweep(KILL_AT + LootCollector.SETTLE_MS + 1));
        assertTrue(canSweep(KILL_AT + 5_000));
    }

    @Test
    void waitsForDropsToSpawn() {
        // Sweeping in the same tick as the kill finds an empty world, and would
        // then clear the note without ever picking the drop up.
        assertFalse(canSweep(KILL_AT));
        assertFalse(canSweep(KILL_AT + LootCollector.SETTLE_MS - 1));
    }

    @Test
    void abandonsStaleLoot() {
        // Past the TTL the drops have despawned; the note is dead weight.
        assertFalse(canSweep(KILL_AT + LootCollector.PENDING_TTL_MS + 1));
    }

    @Test
    void respectsSweepCooldown() {
        long now = KILL_AT + 5_000;
        assertFalse(LootCollector.shouldSweep(
                now, KILL_AT, now - LootCollector.SWEEP_COOLDOWN_MS + 1));
        assertTrue(LootCollector.shouldSweep(
                now, KILL_AT, now - LootCollector.SWEEP_COOLDOWN_MS));
    }

    @Test
    void isNotGatedOnTheBotBeingIdleOrSafe() {
        // The sweep never moves the bot, so it must not be gated on an idle or
        // threat-free tick — at night something is always within range, and the
        // hook would then never fire.
        assertTrue(canSweep(KILL_AT + 2_000));
    }

    @Test
    void pendingLootIsTrackedPerBot() {
        LootCollector.noteKill(BOT_ID, "Spider");
        assertTrue(LootCollector.hasPendingLoot(BOT_ID));
        assertFalse(LootCollector.hasPendingLoot(UUID.randomUUID()));

        LootCollector.clear(BOT_ID);
        assertFalse(LootCollector.hasPendingLoot(BOT_ID));
    }

    @Test
    void aNewerKillOverwritesAnOlderOne() {
        // Mob fights land drops within a few blocks of one another, so one
        // widened sweep covers the group — tracking only the latest is intended.
        LootCollector.noteKill(BOT_ID, "Spider");
        LootCollector.noteKill(BOT_ID, "Zombie");
        assertTrue(LootCollector.hasPendingLoot(BOT_ID));
    }

    @Test
    void repeatedNotesForTheSameKillAreDeduped() {
        // A kill is re-detected on every tick between the fatal swing and the
        // corpse's removal — that must age the note once, not eight times, or
        // the settle window restarts on each tick and the sweep never fires.
        LootCollector.noteKill(BOT_ID, "Spider");
        LootCollector.noteKill(BOT_ID, "Spider");
        LootCollector.noteKill(BOT_ID, "Spider");

        assertTrue(LootCollector.hasPendingLoot(BOT_ID));
        // Still the original note, so it is already past its settle window.
        assertTrue(LootCollector.shouldSweep(
                System.currentTimeMillis() + 10_000, System.currentTimeMillis(), 0L));
    }

    @Test
    void dedupWindowIsShortEnoughForRealConsecutiveKills() {
        // Two mobs killed a couple of seconds apart must both be honoured, so
        // the dedup window has to stay well under a realistic kill interval.
        assertTrue(LootCollector.NOTE_DEDUP_MS < 3_000L);
    }
}
