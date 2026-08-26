package net.shasankp000.PlayerUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
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
 * <p><b>Dependency chaining:</b> when the bot lacks a direct ingredient, it
 * recursively crafts that ingredient first (e.g. "craft a crafting table" from
 * raw logs → craft planks from logs → craft the table from planks). Raw
 * materials ({@code oak_log}, {@code cobblestone}, {@code coal}) must already
 * be in the inventory (the bot gathers those via the gather/mine skill).
 *
 * <p>Resolution: friendly name → item key → catalog recipe → ensure ingredients
 * (recursively) → consume inputs → produce output. All inventory mutations run
 * on the server thread.
 */
public final class CraftingTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("crafting-tool");

    /** Maximum recursion depth for dependency chaining (guards against cycles). */
    private static final int MAX_CRAFT_DEPTH = 8;

    /**
     * Ingredient sentinel meaning "any planks" (any {@code *_planks} item).
     * Generic wood recipes (sticks, crafting table, wooden tools, chest) accept
     * planks of any species so the bot can bootstrap in any biome — a jungle
     * log crafts jungle planks, which then satisfy the table/tool recipes.
     */
    private static final String ANY_PLANKS = "#any_planks";

    /** Overworld wood species, used to register log→planks recipes. */
    private static final List<String> WOOD_TYPES = List.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry");

    /** One craft recipe: output item, output count per craft, and the ingredients consumed. */
    private record Recipe(String output, int outputCount, String[] ingredients) {}

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
     * Catalog of recipes. Ingredient keys are item registry keys; each entry is
     * consumed once per craft (an ingredient listed N times means N are needed
     * per craft). Output counts mirror vanilla where sensible (1 log → 4 planks,
     * 2 planks → 4 sticks, coal+stick → 4 torches).
     */
    private static final Map<String, Recipe> RECIPES = buildRecipes();

    /** Builds the recipe catalog: per-species log→planks plus generic wood recipes. */
    private static Map<String, Recipe> buildRecipes() {
        Map<String, Recipe> recipes = new HashMap<>();

        // One log → 4 planks, for every overworld wood species.
        for (String wood : WOOD_TYPES) {
            recipes.put("minecraft:" + wood + "_planks",
                    new Recipe("minecraft:" + wood + "_planks", 4,
                            new String[]{"minecraft:" + wood + "_log"}));
        }

        recipes.put("minecraft:stick", new Recipe("minecraft:stick", 4, new String[]{ANY_PLANKS, ANY_PLANKS}));
        recipes.put("minecraft:crafting_table", new Recipe("minecraft:crafting_table", 1, new String[]{ANY_PLANKS, ANY_PLANKS, ANY_PLANKS, ANY_PLANKS}));
        recipes.put("minecraft:torch", new Recipe("minecraft:torch", 4, new String[]{"minecraft:coal", "minecraft:stick"}));
        recipes.put("minecraft:wooden_pickaxe", new Recipe("minecraft:wooden_pickaxe", 1, new String[]{ANY_PLANKS, ANY_PLANKS, ANY_PLANKS, "minecraft:stick", "minecraft:stick"}));
        recipes.put("minecraft:wooden_axe", new Recipe("minecraft:wooden_axe", 1, new String[]{ANY_PLANKS, ANY_PLANKS, ANY_PLANKS, "minecraft:stick", "minecraft:stick"}));
        recipes.put("minecraft:wooden_sword", new Recipe("minecraft:wooden_sword", 1, new String[]{ANY_PLANKS, ANY_PLANKS, "minecraft:stick"}));
        recipes.put("minecraft:wooden_shovel", new Recipe("minecraft:wooden_shovel", 1, new String[]{ANY_PLANKS, "minecraft:stick", "minecraft:stick"}));
        recipes.put("minecraft:wooden_hoe", new Recipe("minecraft:wooden_hoe", 1, new String[]{ANY_PLANKS, ANY_PLANKS, "minecraft:stick", "minecraft:stick"}));
        recipes.put("minecraft:stone_pickaxe", new Recipe("minecraft:stone_pickaxe", 1, new String[]{"minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:stick", "minecraft:stick"}));
        recipes.put("minecraft:stone_axe", new Recipe("minecraft:stone_axe", 1, new String[]{"minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:stick", "minecraft:stick"}));
        recipes.put("minecraft:stone_sword", new Recipe("minecraft:stone_sword", 1, new String[]{"minecraft:cobblestone", "minecraft:cobblestone", "minecraft:stick"}));
        recipes.put("minecraft:furnace", new Recipe("minecraft:furnace", 1, new String[]{"minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone"}));
        recipes.put("minecraft:chest", new Recipe("minecraft:chest", 1, new String[]{ANY_PLANKS, ANY_PLANKS, ANY_PLANKS, ANY_PLANKS, ANY_PLANKS, ANY_PLANKS, ANY_PLANKS, ANY_PLANKS}));
        return recipes;
    }

    private CraftingTool() {}

    /**
     * Craft {@code count} of the item described by {@code itemDescription},
     * automatically crafting any missing intermediate ingredients first.
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
                if (!RECIPES.containsKey(key)) {
                    return "❌ No recipe available for " + itemDescription
                            + " (supported: planks, sticks, torches, crafting table, wooden/stone tools, furnace, chest).";
                }

                return callOnServer(bot, () -> craftRecursive(bot, target, Math.max(1, count), 0));
            } catch (Exception e) {
                LOGGER.error("Craft failed for '{}': {}", itemDescription, e.getMessage(), e);
                return "❌ Craft failed: " + e.getMessage();
            }
        });
    }

    /**
     * Recursively ensure {@code count} of {@code target} exists, crafting
     * intermediate ingredients when missing. Returns "OK" when enough already
     * exist, or a "✅ …" / "❌ …" message.
     */
    private static String craftRecursive(ServerPlayer bot, Item target, int count, int depth) {
        if (depth > MAX_CRAFT_DEPTH) {
            return "❌ Crafting depth exceeded (possible recipe cycle).";
        }

        String key = keyOf(target);
        Recipe recipe = RECIPES.get(key);

        if (recipe == null) {
            // No recipe — this is a raw material. It must already be present.
            if (countOf(bot, target) >= count) {
                return "OK";
            }
            return "❌ Missing raw material: " + key
                    + " (need " + count + ", have " + countOf(bot, target) + ").";
        }

        // Ensure each ingredient is present in sufficient quantity, crafting
        // it recursively if possible. Category ingredients ("#any_planks") are
        // satisfied by crafting any concrete item in that category (e.g. planks
        // from whatever log the bot has on hand).
        for (String ingredientKey : distinct(recipe.ingredients())) {
            int needed = occurrences(recipe.ingredients(), ingredientKey) * count;
            if (isCategory(ingredientKey)) {
                while (countOfKey(bot, ingredientKey) < needed) {
                    String sub = craftOneOfCategory(bot, ingredientKey, depth + 1);
                    if (!sub.equals("OK") && !sub.startsWith("✅")) {
                        return sub; // propagate the missing-material failure
                    }
                }
            } else {
                Item ingredient = itemByKey(ingredientKey);
                if (ingredient == null) {
                    return "❌ Unknown ingredient: " + ingredientKey;
                }
                int have = countOf(bot, ingredient);
                if (have < needed) {
                    int deficit = needed - have;
                    // Craft the deficit of the intermediate ingredient.
                    String sub = craftRecursive(bot, ingredient, deficit, depth + 1);
                    if (!sub.equals("OK") && !sub.startsWith("✅")) {
                        return sub; // propagate the missing-material failure
                    }
                }
            }
        }

        // Consume ingredients and produce output.
        for (int round = 0; round < count; round++) {
            for (String ingredientKey : recipe.ingredients()) {
                if (isCategory(ingredientKey)) {
                    if (!consumeOneKey(bot, ingredientKey)) {
                        return "❌ Missing ingredient (post-check): " + ingredientKey;
                    }
                } else {
                    Item ingredient = itemByKey(ingredientKey);
                    if (ingredient == null || !consumeOne(bot, ingredient)) {
                        return "❌ Missing ingredient (post-check): " + ingredientKey;
                    }
                }
            }
        }

        int produced = recipe.outputCount() * count;
        ItemStack result = new ItemStack(target, produced);
        boolean added = bot.getInventory().add(result);
        bot.getInventory().setChanged();
        bot.containerMenu.broadcastChanges();

        if (added) {
            return "✅ Crafted " + produced + "× " + target.getDescriptionId() + ".";
        }
        return "❌ Could not add crafted item to inventory (full?).";
    }

    /** Whether an ingredient key is a category sentinel ("#any_planks") rather than a concrete item. */
    static boolean isCategory(String ingredientKey) {
        return ingredientKey != null && ingredientKey.startsWith("#");
    }

    /** Whether a concrete item key satisfies an ingredient (exact, or category "any X"). */
    static boolean ingredientMatches(String ingredientKey, String itemKey) {
        if (ingredientKey == null || itemKey == null) return false;
        if (!ingredientKey.startsWith("#any_")) return ingredientKey.equals(itemKey);
        String suffix = ingredientKey.substring("#any_".length()); // e.g. "planks"
        return itemKey.endsWith("_" + suffix);
    }

    /** Total stack count of items satisfying {@code ingredientKey} across the inventory. */
    private static int countOfKey(ServerPlayer bot, String ingredientKey) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && ingredientMatches(ingredientKey, id.toString())) total += stack.getCount();
        }
        return total;
    }

    /** Consume one item satisfying {@code ingredientKey} (any slot). */
    private static boolean consumeOneKey(ServerPlayer bot, String ingredientKey) {
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && ingredientMatches(ingredientKey, id.toString())) {
                stack.shrink(1);
                if (stack.isEmpty()) bot.getInventory().setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /**
     * Craft one concrete item belonging to an ingredient category. Currently the
     * only category is planks, crafted from whatever log species the bot holds
     * (jungle log → jungle planks, oak log → oak planks, …).
     */
    private static String craftOneOfCategory(ServerPlayer bot, String ingredientKey, int depth) {
        if (ANY_PLANKS.equals(ingredientKey)) {
            String logKey = findAnyLogInInventory(bot);
            if (logKey == null) {
                return "❌ Missing raw material: any *_log (need wood to craft planks)";
            }
            String planksKey = logKey.replaceAll("_log$", "_planks");
            Item planks = itemByKey(planksKey);
            if (planks == null) {
                return "❌ Unknown item: " + planksKey;
            }
            return craftRecursive(bot, planks, 1, depth + 1);
        }
        return "❌ Unknown ingredient category: " + ingredientKey;
    }

    /** Registry key of the first log (any {@code *_log}) found in the inventory, or null. */
    private static String findAnyLogInInventory(ServerPlayer bot) {
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && id.getPath().endsWith("_log")) return id.toString();
        }
        return null;
    }

    /** Total stack count of {@code item} across the bot's inventory. */
    private static int countOf(ServerPlayer bot, Item item) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) total += stack.getCount();
        }
        return total;
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

    private static int occurrences(String[] arr, String key) {
        int n = 0;
        for (String s : arr) if (key.equals(s)) n++;
        return n;
    }

    private static String[] distinct(String[] arr) {
        return java.util.Arrays.stream(arr).distinct().toArray(String[]::new);
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
