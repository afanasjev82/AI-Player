package net.shasankp000.PlayerUtils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.shasankp000.GameAI.handoff.TradeEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Trade tool (Phase C): report what the bot can trade and (when a player has
 * actually offered an item) resolve a fair counter-offer.
 *
 * <p>Trading is inherently player-interactive — the full two-phase
 * throw/confirm handshake already lives in {@code TradeListener}. This tool is
 * the bot's <em>active</em> side: it enumerates the bot's tiered inventory so
 * the planner/LLM can advertise what is on offer, and it evaluates a proposed
 * offer against {@link TradeEvaluator} for decision-making.
 */
public final class TradeTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("trade-tool");

    private TradeTool() {}

    /**
     * Summarise the bot's tradeable inventory (items with a known value tier),
     * keyed by tier, so the bot can advertise a fair offer.
     */
    public static CompletableFuture<String> inventory(ServerPlayer bot) {
        return runOnServer(bot, () -> {
            // tier → "count x Item, ..." preserving insertion order (1..5).
            Map<Integer, StringBuilder> byTier = new LinkedHashMap<>();
            for (int i = 1; i <= 5; i++) byTier.put(i, new StringBuilder());

            for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
                ItemStack stack = bot.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;
                int tier = TradeEvaluator.tierOf(stack.getItem());
                if (tier == 0) continue;
                byTier.get(tier)
                        .append(stack.getCount()).append("x ")
                        .append(TradeEvaluator.displayName(stack)).append(", ");
            }

            StringBuilder out = new StringBuilder("Tradeable inventory:");
            boolean any = false;
            for (Map.Entry<Integer, StringBuilder> e : byTier.entrySet()) {
                String s = e.getValue().toString();
                if (!s.isEmpty()) {
                    // strip trailing ", "
                    s = s.substring(0, s.length() - 2);
                    out.append(" [tier ").append(e.getKey()).append(": ").append(s).append("];");
                    any = true;
                }
            }
            return any ? out.toString()
                       : "No tradeable items in inventory (nothing with a known value tier).";
        });
    }

    /**
     * Evaluate a player's proposed offer and return the counter-offer the bot
     * is willing to make, or a refusal.
     *
     * @param offeredItem item id / friendly name the player is offering.
     */
    public static CompletableFuture<String> offer(ServerPlayer bot, String offeredItem) {
        return runOnServer(bot, () -> {
            ItemStack offered = resolveStack(offeredItem);
            if (offered == null || offered.isEmpty()) {
                return "Unknown item offered: " + offeredItem;
            }
            ItemStack counter = TradeEvaluator.evaluate(offered, bot);
            if (counter.isEmpty()) {
                return "Refuse: no fair counter-offer for " + TradeEvaluator.displayName(offered) + ".";
            }
            return "Would trade " + TradeEvaluator.displayName(counter)
                    + " for " + TradeEvaluator.displayName(offered) + ".";
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Resolve an item by registry id or friendly name, or null if unknown. */
    private static ItemStack resolveStack(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toLowerCase();
        if (!key.contains("minecraft:")) key = "minecraft:" + key;

        var id = net.minecraft.resources.Identifier.tryParse(key);
        if (id == null) return null;
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)
                .map(net.minecraft.core.Holder.Reference::value)
                .orElse(null);
        if (item == null) return null;
        if (TradeEvaluator.tierOf(item) == 0) return null; // untiered → won't trade
        return new ItemStack(item);
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
                LOGGER.error("Trade operation failed: {}", e.getMessage(), e);
                return "Trade failed: " + e.getMessage();
            }
        });
    }
}
