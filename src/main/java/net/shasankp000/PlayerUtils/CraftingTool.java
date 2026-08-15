package net.shasankp000.PlayerUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side crafting for the AI bot (Phase C).
 *
 * <p>A curated catalog of common early-game recipes. Each recipe maps a target
 * item to its required ingredient items (by registry key) so the bot can craft
 * without a crafting table UI. This is deliberately a small, honest subset
 * (planks, sticks, torches, crafting table, basic wooden/stone tools) rather
 * than the full recipe system, because Minecraft 26.2's {@code SlotDisplay} /
 * {@code ContextMap} display API is not stable to drive directly.
 *
 * <p>Resolution: friendly name → item key → catalog recipe → consume inputs →
 * produce output. All inventory mutations run on the server thread.
 */
public final class CraftingTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("crafting-tool");

    /** One craft recipe: output item + the items consumed to make one. */
    private record Recipe(String output, String[] ingredients) {}

    /** Friendly-name aliases → item registry key. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("crafting table", "minecraft:crafting_table"),
        Map.entry("workbench", "minecraft:crafting_table"),
        Map.entry("crafting_table", "minecraft:crafting_table"),
        Map.entry("stick", "minecraft:stick"),
        Map.entry("sticks", "minecraft:stick"),
        Map.entry("torch", "minecraft:torch"),
        Map.entry("planks", "minecraft:oak_planks"),
        Map.entry("oak planks", "minecraft:oak_planks"),
        Map.entry("oak_planks", "minecraft:oak_planks"),
        Map.entry("wooden pickaxe", "minecraft:wooden_pickaxe"),
        Map.entry("wooden axe", "minecraft:wooden_axe"),
        Map.entry("wooden sword", "minecraft:wooden_sword"),
        Map.entry("wooden shovel", "minecraft:wooden_shovel"),
        Map.entry("wooden hoe", "minecraft:wooden_hoe"),
        Map.entry("stone pickaxe", "minecraft:stone_pickaxe"),
        Map.entry("stone axe", "minecraft:stone_axe"),
        Map.entry("stone sword", "minecraft:stone_sword"),
        Map.entry("furnace", "minecraft:furnace"),
        Map.entry("chest", "minecraft:chest")
    );

    /**
     * Catalog of recipes. Ingredient keys are item registry keys; each is
     * consumed once per craft. Output keys are the canonical registry key.
     */
    private static final Map<String, Recipe> RECIPES = Map.ofEntries(
        Map.entry("minecraft:oak_planks", new Recipe("minecraft:oak_planks", new String[]{"minecraft:oak_log"})),
        Map.entry("minecraft:stick", new Recipe("minecraft:stick", new String[]{"minecraft:oak_planks", "minecraft:oak_planks"})),
        Map.entry("minecraft:crafting_table", new Recipe("minecraft:crafting_table", new String[]{"minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks"})),
        Map.entry("minecraft:torch", new Recipe("minecraft:torch", new String[]{"minecraft:coal", "minecraft:stick"})),
        Map.entry("minecraft:wooden_pickaxe", new Recipe("minecraft:wooden_pickaxe", new String[]{"minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:stick", "minecraft:stick"})),
        Map.entry("minecraft:wooden_axe", new Recipe("minecraft:wooden_axe", new String[]{"minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:stick", "minecraft:stick"})),
        Map.entry("minecraft:wooden_sword", new Recipe("minecraft:wooden_sword", new String[]{"minecraft:oak_planks", "minecraft:oak_planks", "minecraft:stick"})),
        Map.entry("minecraft:wooden_shovel", new Recipe("minecraft:wooden_shovel", new String[]{"minecraft:oak_planks", "minecraft:stick", "minecraft:stick"})),
        Map.entry("minecraft:wooden_hoe", new Recipe("minecraft:wooden_hoe", new String[]{"minecraft:oak_planks", "minecraft:oak_planks", "minecraft:stick", "minecraft:stick"})),
        Map.entry("minecraft:stone_pickaxe", new Recipe("minecraft:stone_pickaxe", new String[]{"minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:stick", "minecraft:stick"})),
        Map.entry("minecraft:stone_axe", new Recipe("minecraft:stone_axe", new String[]{"minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:stick", "minecraft:stick"})),
        Map.entry("minecraft:stone_sword", new Recipe("minecraft:stone_sword", new String[]{"minecraft:cobblestone", "minecraft:cobblestone", "minecraft:stick"})),
        Map.entry("minecraft:furnace", new Recipe("minecraft:furnace", new String[]{"minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone"})),
        Map.entry("minecraft:chest", new Recipe("minecraft:chest", new String[]{"minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks"}))
    );

    private CraftingTool() {}

    /**
     * Craft {@code count} of the item described by {@code itemDescription}.
     *
     * @return a CompletableFuture resolving to a human-readable result message.
     */
    public static CompletableFuture<String> craft(ServerPlayer bot, String itemDescription, int count) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (bot == null || !bot.isAlive() || bot.hasDisconnected()) {
                    return "❌ Bot is unavailable.";
                }

                Item target = resolveItem(itemDescription);
                if (target == null) {
                    return "❌ Unknown item: " + itemDescription;
                }

                String key = keyOf(target);
                Recipe recipe = RECIPES.get(key);
                if (recipe == null) {
                    return "❌ No recipe available for " + itemDescription
                            + " (supported: planks, sticks, torches, crafting table, wooden/stone tools, furnace, chest).";
                }

                return callOnServer(bot, () -> performCraft(bot, target, recipe, Math.max(1, count)));
            } catch (Exception e) {
                LOGGER.error("Craft failed for '{}': {}", itemDescription, e.getMessage(), e);
                return "❌ Craft failed: " + e.getMessage();
            }
        });
    }

    private static String performCraft(ServerPlayer bot, Item target, Recipe recipe, int count) {
        // Consume the inputs (each recipe makes ONE output; loop for count).
        for (int round = 0; round < count; round++) {
            for (String ingredientKey : recipe.ingredients()) {
                Item ingredient = itemByKey(ingredientKey);
                if (ingredient == null) {
                    return "❌ Unknown ingredient: " + ingredientKey;
                }
                if (!consumeOne(bot, ingredient)) {
                    return "❌ Missing ingredient: " + ingredientKey
                            + " (need " + recipe.ingredients().length + " items per craft).";
                }
            }
        }

        ItemStack result = new ItemStack(target, count);
        boolean added = bot.getInventory().add(result);
        bot.getInventory().setChanged();
        bot.containerMenu.broadcastChanges();

        if (added) {
            return "✅ Crafted " + count + "× " + target.getDescriptionId() + ".";
        }
        return "❌ Could not add crafted item to inventory (full?).";
    }

    /** Consume one item of {@code item} from the bot's inventory (any slot). */
    private static boolean consumeOne(ServerPlayer bot, Item item) {
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                stack.shrink(1);
                if (stack.isEmpty()) bot.getInventory().setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    private static Item resolveItem(String description) {
        if (description == null) return null;
        String d = description.trim().toLowerCase();
        String key = ALIASES.get(d);
        if (key == null) {
            key = d.startsWith("minecraft:") ? d : "minecraft:" + d;
        }
        return itemByKey(key);
    }

    private static Item itemByKey(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null) return null;
        return BuiltInRegistries.ITEM.get(id)
                .map(net.minecraft.core.Holder.Reference::value)
                .orElse(null);
    }

    private static String keyOf(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : id.toString();
    }

    /** Runs {@code task} on the server thread and blocks for the result. */
    private static String callOnServer(ServerPlayer bot, java.util.concurrent.Callable<String> task) throws Exception {
        var server = bot.createCommandSourceStack().getServer();
        if (server.isSameThread()) return task.call();
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
