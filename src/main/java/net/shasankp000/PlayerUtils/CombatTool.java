package net.shasankp000.PlayerUtils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.player.Player;
import net.shasankp000.Entity.FaceClosestEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Combat tool (Phase C): find the nearest hostile mob, arm the bot with its
 * best melee weapon, face it, and perform a single vanilla melee attack.
 *
 * <p>Reuses the same hostile-detection rules as
 * {@code AutoFaceEntity.hostileEntities} ({@link Monster} and {@link Slime},
 * excluding projectiles and the bot itself) so the tool's notion of "hostile"
 * matches what the rest of the mod already targets.
 */
public final class CombatTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("combat-tool");

    private static final double SEARCH_RADIUS = 8.0;

    private CombatTool() {}

    /**
     * Attack the nearest hostile mob within {@value #SEARCH_RADIUS} blocks.
     * Equips the best melee weapon, faces the target, and swings once.
     *
     * @return a human-readable result describing the target and distance, or
     *         a failure reason when there is nothing to attack.
     */
    public static CompletableFuture<String> combat(ServerPlayer bot) {
        return runOnServer(bot, () -> {
            List<Entity> hostiles = findHostiles(bot, SEARCH_RADIUS);
            if (hostiles.isEmpty()) {
                return "No hostile mobs within " + (int) SEARCH_RADIUS + " blocks.";
            }

            // Prefer the nearest hostile (most immediate threat to hit).
            Entity target = hostiles.stream()
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(bot)))
                    .orElse(null);
            if (target == null) return "No hostile mobs within " + (int) SEARCH_RADIUS + " blocks.";

            double distance = Math.sqrt(target.distanceToSqr(bot));

            // Arm with the best melee weapon we have (fists otherwise).
            boolean armed = WeaponUtils.equipBestMeleeWeapon(bot);

            // Face the nearest hostile before swinging so the hit lands.
            FaceClosestEntity.faceClosestEntity(bot, hostiles);

            bot.swing(InteractionHand.MAIN_HAND);
            bot.attack(target);

            return "Attacked " + target.getName().getString()
                    + " at " + String.format("%.1f", distance) + "m"
                    + (armed ? " (melee weapon equipped)." : " (no melee weapon — used fists).");
        });
    }

    /** Count of nearby hostile mobs, for quick combat awareness. */
    public static CompletableFuture<String> nearbyHostiles(ServerPlayer bot) {
        return runOnServer(bot, () -> {
            List<Entity> hostiles = findHostiles(bot, SEARCH_RADIUS);
            if (hostiles.isEmpty()) {
                return "0 hostile mobs within " + (int) SEARCH_RADIUS + " blocks.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(hostiles.size()).append(" hostile mob(s) within ")
              .append((int) SEARCH_RADIUS).append(" blocks:");
            for (Entity e : hostiles) {
                double d = Math.sqrt(e.distanceToSqr(bot));
                sb.append(" ").append(e.getName().getString())
                  .append(" (").append(String.format("%.1f", d)).append("m);");
            }
            return sb.toString();
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Mirror of {@code AutoFaceEntity}'s hostile filter: mobs ({@link Monster}
     * and {@link Slime}) plus retaliated hostile players, excluding the bot
     * itself and all projectile entities.
     */
    private static List<Entity> findHostiles(ServerPlayer bot, double radius) {
        List<Entity> nearby = bot.level().getEntities(
                bot, bot.getBoundingBox().inflate(radius, radius, radius));
        return nearby.stream()
                .filter(e -> !(e instanceof net.minecraft.world.entity.projectile.Projectile))
                .filter(e -> {
                    if (e instanceof Monster || e instanceof Slime) return true;
                    if (e instanceof Player player && !player.getUUID().equals(bot.getUUID())) {
                        return net.shasankp000.PlayerUtils.PlayerRetaliationTracker.isPlayerHostile(bot, player);
                    }
                    return false;
                })
                .toList();
    }

    private static CompletableFuture<String> runOnServer(ServerPlayer bot, Callable<String> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (bot == null || !bot.isAlive() || bot.hasDisconnected()) {
                    return "Bot is unavailable.";
                }
                var server = bot.createCommandSourceStack().getServer();
                if (server.isSameThread()) return task.call();
                CompletableFuture<String> future = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        future.complete(task.call());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
                return future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.error("Combat operation failed: {}", e.getMessage(), e);
                return "Combat failed: " + e.getMessage();
            }
        });
    }
}
