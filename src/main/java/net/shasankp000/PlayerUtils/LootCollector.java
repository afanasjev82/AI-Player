package net.shasankp000.PlayerUtils;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Post-kill loot collection for the <b>reactive</b> combat path.
 *
 * <p>The autonomous planner already models combat as {@code attack → collect}
 * ({@code SkillPlanBuilder.combatPlan}), so plan-driven fights pick their drops
 * up. Reactive combat does not: {@code AutoFaceEntity} re-invokes the combat
 * handler every 33ms, each tick is one swing, and nothing observes that the
 * swing landed the killing blow. A spider killed that way left its string on
 * the ground, so the "kill a spider → get string → craft a bow" chain never
 * closed in practice.
 *
 * <p>This class closes it with a two-call seam:
 * <ol>
 *   <li>{@link #noteKill} — called by the melee code the instant a swing kills
 *       a target. Records that loot is owed. Cheap, no world access.</li>
 *   <li>{@link #tick} — called from the reactive loop each tick. Once the drops
 *       have settled it sweeps them up and clears the note.</li>
 * </ol>
 *
 * <p>The sweep runs on its own daemon thread because {@link CollectTool}
 * blocks the caller while it marshals onto the server thread — doing that
 * inline from the 33ms tick would stall the whole reactive loop.
 *
 * <p><b>Only one kill is tracked at a time.</b> A newer kill overwrites an
 * uncollected older one; in a mob fight the drops all land within a few blocks
 * of each other anyway, so a single widened sweep covers the group.
 */
public final class LootCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger("loot-collector");

    /**
     * Sweep radius for kill drops. Melee reach is ~3 blocks, so a mob killed at
     * arm's length drops its loot ~1-3 blocks away — comfortably inside this.
     *
     * <p>Kept tight on purpose: the sweep is a <em>reach</em> collect
     * ({@link CollectTool} touches every item in range), so a wide radius would
     * also hoover up unrelated dropped items near the bot.
     */
    private static final double SWEEP_RADIUS = 4.0;

    /**
     * Grace period before sweeping. Mob drops are spawned by the death handling
     * that follows the killing blow, so sweeping in the same tick would find an
     * empty world and clear the note for nothing.
     */
    static final long SETTLE_MS = 700L;

    /** A note older than this is abandoned — the drops have despawned. */
    static final long PENDING_TTL_MS = 30_000L;

    /** Minimum spacing between sweeps, so the tick loop cannot spin on one. */
    static final long SWEEP_COOLDOWN_MS = 1_000L;

    /**

     * How long a fresh note suppresses a repeat note. The melee paths re-detect
     * the same dead target on the ticks between the killing blow and its removal
     * from the world, so without this one kill logs (and re-arms) ~8 times.
     */
    static final long NOTE_DEDUP_MS = 1_500L;

    /** Record of a kill whose drops have not been picked up yet. */
    private record PendingLoot(UUID botId, String mobName, long at) {}

    private static volatile PendingLoot pending;
    private static volatile long lastSweepAt;

    private static final AtomicBoolean sweepInFlight = new AtomicBoolean(false);

    private static final ExecutorService SWEEP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "loot-collector");
        t.setDaemon(true);
        return t;
    });

    private LootCollector() {}

    /**
     * Record that {@code bot} just landed the killing blow on {@code mobName}.
     *
     * <p>Safe to call from the server thread; performs no world access.
     */
    public static void noteKill(ServerPlayer bot, String mobName) {
        if (bot == null) return;
        noteKill(bot.getUUID(), mobName);
    }

    /** {@link #noteKill(ServerPlayer, String)} without needing a live player. */
    static void noteKill(UUID botId, String mobName) {
        if (botId == null) return;
        long now = System.currentTimeMillis();
        PendingLoot previous = pending;
        // Same kill re-detected on the ticks before the corpse is removed.
        if (previous != null
                && previous.botId().equals(botId)
                && now - previous.at() < NOTE_DEDUP_MS) {
            return;
        }
        pending = new PendingLoot(botId, mobName, now);
        LOGGER.info("[loot] kill noted: {} — drop owed", mobName);
    }

    /** Forget any owed loot for {@code botId} — the bot died, or moved on. */
    public static void clear(UUID botId) {
        PendingLoot p = pending;
        if (p != null && p.botId().equals(botId)) {
            pending = null;
        }
    }

    /** Whether loot from a recent kill is still owed to {@code botId}. */
    public static boolean hasPendingLoot(UUID botId) {
        PendingLoot p = pending;
        return p != null && p.botId().equals(botId)
                && System.currentTimeMillis() - p.at() < PENDING_TTL_MS;
    }

    /**
     * Drive the loot sweep. Called from the reactive loop every tick, so it
     * must stay cheap: it reads two volatile fields and returns in the common
     * case (nothing owed).
     *
     * <p>Deliberately <b>not</b> gated on the bot being idle, safe, or fighting
     * something else. The sweep does not move the bot — it clears the pickup
     * delay and touches the items in range — so it cannot derail a path or pull
     * the bot out of a fight. Gating on "no threat in range" would in practice
     * mean never looting, because at night something is always within range.
     */
    public static void tick(ServerPlayer bot) {
        if (bot == null || !bot.isAlive() || bot.hasDisconnected()) return;

        PendingLoot p = pending;
        if (p == null || !p.botId().equals(bot.getUUID())) return;

        long now = System.currentTimeMillis();

        if (now - p.at() > PENDING_TTL_MS) {
            pending = null;
            return;
        }

        if (!shouldSweep(now, p.at(), lastSweepAt)) return;
        if (!sweepInFlight.compareAndSet(false, true)) return;

        lastSweepAt = now;
        final String mobName = p.mobName();
        LOGGER.info("[loot] sweeping r={} for {} drop", SWEEP_RADIUS, mobName);
        SWEEP_EXECUTOR.submit(() -> {
            try {
                String result = CollectTool.collectNearby(bot, SWEEP_RADIUS).join();
                LOGGER.info("[loot] {} sweep result: {}", mobName, result);
                pending = null;
            } catch (Throwable t) {
                LOGGER.warn("[loot] sweep failed for {} drop: {}", mobName, t.getMessage());
            } finally {
                sweepInFlight.set(false);
            }
        });
    }

    /**
     * Pure gate deciding whether owed loot may be swept right now. Extracted so
     * the timing policy is testable without a live server.
     *
     * @param now         current wall-clock millis
     * @param killAt      when the killing blow landed
     * @param lastSweepAt when the previous sweep started (0 if never)
     */
    static boolean shouldSweep(long now, long killAt, long lastSweepAt) {
        if (now - killAt > PENDING_TTL_MS) return false; // drops long gone
        if (now - killAt < SETTLE_MS) return false;      // drops not spawned yet
        return now - lastSweepAt >= SWEEP_COOLDOWN_MS;
    }
}
