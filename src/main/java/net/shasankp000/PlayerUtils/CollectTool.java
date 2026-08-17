package net.shasankp000.PlayerUtils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Collect tool (Phase C follow-up): pull nearby dropped items into the bot's
 * inventory.
 *
 * <p>When a block is mined the drop becomes an {@link ItemEntity} with a short
 * pickup delay. In the gather pipeline the bot is already standing adjacent to
 * the block, but the autonomous loop often moves it to the next goal before the
 * delay expires — so the drop sits on the ground and the gather/mine reward
 * reads 0. This tool deterministically clears the delay and triggers the vanilla
 * {@code playerTouch} path for every nearby item, so the resource is collected
 * before the bot moves on.
 */
public final class CollectTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("collect-tool");

    private static final double COLLECT_RADIUS = 2.5;

    private CollectTool() {}

    /**
     * Collect all nearby item entities into the bot's inventory.
     *
     * @return a human-readable summary of what was collected.
     */
    public static CompletableFuture<String> collectNearby(ServerPlayer bot) {
        return runOnServer(bot, () -> {
            List<ItemEntity> items = bot.level().getEntities(
                    bot,
                    bot.getBoundingBox().inflate(COLLECT_RADIUS),
                    e -> e instanceof ItemEntity
            ).stream().map(e -> (ItemEntity) e).toList();

            if (items.isEmpty()) {
                return "No items nearby to collect.";
            }

            int collected = 0;
            for (ItemEntity item : items) {
                // Skip items already being consumed by another mechanic (trade
                // handoff / a fresh throw with a long pickup delay).
                if (item.isRemoved()) continue;

                // Clear the pickup delay so the vanilla playerTouch path accepts
                // the item immediately, then run the same code the game runs on
                // player collision.
                item.setPickUpDelay(0);
                item.playerTouch(bot);
                if (item.isRemoved()) collected++;
            }

            return "Collected " + collected + " item(s) nearby.";
        });
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
                LOGGER.error("Collect operation failed: {}", e.getMessage(), e);
                return "Collect failed: " + e.getMessage();
            }
        });
    }
}
