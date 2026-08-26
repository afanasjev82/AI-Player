package net.shasankp000.GameAI.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight entity/parameter extractor from raw player chat input.
 * Uses a regex + dictionary approach — zero LLM calls, runs in < 1ms.
 *
 * Extracts:
 *  - Block/item types ("iron", "oak wood", "coal" → Minecraft IDs)
 *  - XYZ coordinates from text
 *  - Quantities ("5 logs", "64 stone")
 *  - Cardinal directions ("north", "east", …)
 */
public class EntityExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger("EntityExtractor");

    /**
     * Sentinel block type meaning "any log" (oak/spruce/birch/jungle/acacia/
     * dark_oak/mangrove/cherry). Used when a goal references wood generically
     * ("gather wood") without naming a species, so the search is biome-agnostic.
     */
    public static final String ANY_LOG = "minecraft:*_log";

    // ── Block alias dictionary ────────────────────────────────────────────────
    private static final Map<String, String> BLOCK_ALIASES = new HashMap<>();

    static {
        // Wood / logs
        BLOCK_ALIASES.put("oak",          "minecraft:oak_log");
        BLOCK_ALIASES.put("oak log",      "minecraft:oak_log");
        BLOCK_ALIASES.put("oak wood",     "minecraft:oak_log");
        BLOCK_ALIASES.put("wood",         ANY_LOG);
        BLOCK_ALIASES.put("log",          ANY_LOG);
        BLOCK_ALIASES.put("logs",         ANY_LOG);
        BLOCK_ALIASES.put("birch",        "minecraft:birch_log");
        BLOCK_ALIASES.put("spruce",       "minecraft:spruce_log");
        BLOCK_ALIASES.put("jungle",       "minecraft:jungle_log");
        BLOCK_ALIASES.put("acacia",       "minecraft:acacia_log");
        BLOCK_ALIASES.put("dark oak",     "minecraft:dark_oak_log");
        BLOCK_ALIASES.put("mangrove",     "minecraft:mangrove_log");
        BLOCK_ALIASES.put("cherry",       "minecraft:cherry_log");

        // Stone / ores
        BLOCK_ALIASES.put("stone",        "minecraft:stone");
        BLOCK_ALIASES.put("cobblestone",  "minecraft:cobblestone");
        BLOCK_ALIASES.put("cobble",       "minecraft:cobblestone");
        BLOCK_ALIASES.put("gravel",       "minecraft:gravel");
        BLOCK_ALIASES.put("dirt",         "minecraft:dirt");
        BLOCK_ALIASES.put("sand",         "minecraft:sand");
        BLOCK_ALIASES.put("coal",         "minecraft:coal_ore");
        BLOCK_ALIASES.put("iron",         "minecraft:iron_ore");
        BLOCK_ALIASES.put("iron ore",     "minecraft:iron_ore");
        BLOCK_ALIASES.put("gold",         "minecraft:gold_ore");
        BLOCK_ALIASES.put("gold ore",     "minecraft:gold_ore");
        BLOCK_ALIASES.put("diamond",      "minecraft:diamond_ore");
        BLOCK_ALIASES.put("diamond ore",  "minecraft:diamond_ore");
        BLOCK_ALIASES.put("emerald",      "minecraft:emerald_ore");
        BLOCK_ALIASES.put("lapis",        "minecraft:lapis_ore");
        BLOCK_ALIASES.put("redstone",     "minecraft:redstone_ore");
        BLOCK_ALIASES.put("copper",       "minecraft:copper_ore");
        BLOCK_ALIASES.put("deepslate",    "minecraft:deepslate");
        BLOCK_ALIASES.put("obsidian",     "minecraft:obsidian");
        BLOCK_ALIASES.put("netherrack",   "minecraft:netherrack");
        BLOCK_ALIASES.put("nether quartz","minecraft:nether_quartz_ore");
        BLOCK_ALIASES.put("quartz",       "minecraft:nether_quartz_ore");

        // Building blocks
        BLOCK_ALIASES.put("planks",       "minecraft:oak_planks");
        BLOCK_ALIASES.put("oak planks",   "minecraft:oak_planks");
        BLOCK_ALIASES.put("glass",        "minecraft:glass");
        BLOCK_ALIASES.put("brick",        "minecraft:bricks");
        BLOCK_ALIASES.put("bricks",       "minecraft:bricks");
        BLOCK_ALIASES.put("wool",         "minecraft:white_wool");
        BLOCK_ALIASES.put("white wool",   "minecraft:white_wool");
        BLOCK_ALIASES.put("leaves",       "minecraft:oak_leaves");
        BLOCK_ALIASES.put("grass",        "minecraft:grass_block");
        BLOCK_ALIASES.put("grass block",  "minecraft:grass_block");
        BLOCK_ALIASES.put("chest",        "minecraft:chest");
        BLOCK_ALIASES.put("crafting table","minecraft:crafting_table");
        BLOCK_ALIASES.put("furnace",      "minecraft:furnace");
        BLOCK_ALIASES.put("torch",        "minecraft:torch");
        BLOCK_ALIASES.put("ladder",       "minecraft:ladder");

        // Nether / End
        BLOCK_ALIASES.put("nether brick", "minecraft:nether_bricks");
        BLOCK_ALIASES.put("end stone",    "minecraft:end_stone");
        BLOCK_ALIASES.put("glowstone",    "minecraft:glowstone");
        BLOCK_ALIASES.put("soul sand",    "minecraft:soul_sand");
    }

    // ── Regex patterns ────────────────────────────────────────────────────────
    /** Matches "10 20 30",  "10, 20, 30",  "-10 64 -30" */
    private static final Pattern COORD_PATTERN =
        Pattern.compile("(-?\\d+)[,\\s]+(-?\\d+)[,\\s]+(-?\\d+)");

    /** Matches a leading/trailing integer quantity, e.g. "5 logs" or "get 64 stone" */
    private static final Pattern QUANTITY_PATTERN =
        Pattern.compile("\\b(\\d+)\\b");

    /** Cardinal directions */
    private static final Pattern DIRECTION_PATTERN =
        Pattern.compile("\\b(north|south|east|west)\\b", Pattern.CASE_INSENSITIVE);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Extract the most likely Minecraft block ID from a raw player instruction.
     * Tries multi-word aliases first, then single-word aliases.
     * Falls back to {@code defaultBlockType} if nothing matches.
     */
    public static String extractBlockType(String input, String defaultBlockType) {
        String lower = input.toLowerCase();

        // Try multi-word aliases first (longer keys take priority)
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> entry : BLOCK_ALIASES.entrySet()) {
            if (lower.contains(entry.getKey()) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }

        if (best != null) {
            LOGGER.debug("Extracted block type '{}' from '{}'", best, input);
            return best;
        }

        LOGGER.debug("No block type found in '{}', using default: {}", input, defaultBlockType);
        return defaultBlockType;
    }

    /** Overload with hardcoded default (oak log). */
    public static String extractBlockType(String input) {
        return extractBlockType(input, "minecraft:oak_log");
    }

    /**
     * Extract XYZ coordinates from text.
     * Returns {@code null} if none found.
     */
    public static int[] extractCoords(String input) {
        Matcher m = COORD_PATTERN.matcher(input);
        if (m.find()) {
            return new int[]{
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))
            };
        }
        return null;
    }

    /**
     * Extract a quantity number from text (e.g. "get 32 logs" → 32).
     * Returns 1 if none found.
     */
    public static int extractQuantity(String input) {
        Matcher m = QUANTITY_PATTERN.matcher(input);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    /**
     * Extract a cardinal direction from text.
     * Returns "north" as a safe default.
     */
    public static String extractDirection(String input) {
        Matcher m = DIRECTION_PATTERN.matcher(input);
        if (m.find()) {
            return m.group(1).toLowerCase();
        }
        return "north";
    }

    /** Returns true if the input contains an explicit coordinate triple. */
    public static boolean hasCoords(String input) {
        return COORD_PATTERN.matcher(input).find();
    }
}
