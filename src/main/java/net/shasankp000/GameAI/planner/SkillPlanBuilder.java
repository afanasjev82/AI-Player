package net.shasankp000.GameAI.planner;

import net.shasankp000.GameAI.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic multi-step skill plans for goals the bot can actually execute.
 *
 * <p>Phase B of the everyday-work roadmap. The A* graph planner produces a
 * single-step plan (usually {@code goTo} or {@code mineBlock}) because it has
 * no notion of the {@code searchBlocks → goTo → mineBlock} pipeline that the
 * function-caller tools actually chain together through {@code SharedState}
 * ({@code found_block_x/y/z}). This class encodes those pipelines explicitly.
 *
 * <p>It only builds plans for goals with a real tool implementation:
 * <ul>
 *   <li>{@code gather} / {@code mine} → search → navigate → mine</li>
 *   <li>{@code explore} → look around → move to a nearby offset</li>
 *   <li>{@code navigate} → go to coordinates parsed from the goal text</li>
 *   <li>{@code build} → place a block near the bot (single-block placeholder;
 *       full structure specs are an open design question)</li>
 * </ul>
 * For {@code craft}, {@code farm}, {@code combat}, {@code trade} there is no
 * tool yet, so this returns {@code null} and the caller falls back to the
 * graph planner / LLM rather than emitting a misleading 1-step plan.
 */
public class SkillPlanBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-plan-builder");

    // Coordinate pattern: "go to 12 64 -30" or "(12, 64, -30)".
    private static final Pattern COORD3 = Pattern.compile(
            "\\(?(-?\\d+)\\s*[, ]\\s*(-?\\d+)\\s*[, ]\\s*(-?\\d+)\\)?");

    private SkillPlanBuilder() {}

    /**
     * Build a deterministic plan for {@code goalId}, or {@code null} when no
     * skill template covers the goal (caller should fall back).
     */
    public static Plan buildPlan(short goalId, String goalText, State state) {
        switch (goalId) {
            case GoalMapper.GOAL_GATHER:
            case GoalMapper.GOAL_MINE:
                return gatherPlan(goalId, goalText, state);
            case GoalMapper.GOAL_EXPLORE:
                return explorePlan(goalId, state);
            case GoalMapper.GOAL_NAVIGATE:
                return navigatePlan(goalId, goalText, state);
            case GoalMapper.GOAL_BUILD:
                return buildStructurePlan(goalId, goalText, state);
            default:
                return null; // craft / farm / combat / trade: no tool support yet
        }
    }

    // ── gather / mine: searchBlocks → goTo → mineBlock ───────────────────────

    private static Plan gatherPlan(short goalId, String goalText, State state) {
        String blockType = inferBlockType(goalText);
        LOGGER.info("[skill] gather/mine plan: target block '{}'", blockType);

        List<PlannedStep> steps = new ArrayList<>();
        // 1. Find the target block and store its coords in SharedState.
        steps.add(step("searchBlocks", String.format("%s,10,100,20", blockType)));
        // 2. Walk adjacent to the found block (params resolved from SharedState).
        steps.add(step("goTo", ""));
        // 3. Mine the found block (params resolved from SharedState).
        steps.add(step("mineBlock", ""));

        return toPlan(goalId, steps);
    }

    // ── explore: look around + move to a nearby offset ───────────────────────

    private static Plan explorePlan(short goalId, State state) {
        List<PlannedStep> steps = new ArrayList<>();
        steps.add(step("look", "north"));
        steps.add(step("goTo", String.format("%d,%d,%d,true",
                state.getBotX() + 8, state.getBotY(), state.getBotZ())));
        return toPlan(goalId, steps);
    }

    // ── navigate: parse explicit coordinates from the goal text ──────────────

    private static Plan navigatePlan(short goalId, String goalText, State state) {
        Matcher m = COORD3.matcher(goalText);
        if (m.find()) {
            int x = Integer.parseInt(m.group(1));
            int y = Integer.parseInt(m.group(2));
            int z = Integer.parseInt(m.group(3));
            LOGGER.info("[skill] navigate plan: target ({}, {}, {})", x, y, z);
            List<PlannedStep> steps = new ArrayList<>();
            steps.add(step("goTo", String.format("%d,%d,%d,true", x, y, z)));
            return toPlan(goalId, steps);
        }
        LOGGER.warn("[skill] navigate goal without coordinates — falling back");
        return null;
    }

    // ── build: place a single block near the bot (placeholder) ───────────────

    private static Plan buildStructurePlan(short goalId, String goalText, State state) {
        String blockType = inferPlacementBlock(goalText);
        List<PlannedStep> steps = new ArrayList<>();
        steps.add(step("placeBlock", String.format("%d,%d,%d,%s",
                state.getBotX() + 1, state.getBotY() - 1, state.getBotZ(), blockType)));
        return toPlan(goalId, steps);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static PlannedStep step(String actionName, String params) {
        byte actionId = ActionRegistry.getActionByte(actionName);
        return new PlannedStep(actionId, actionName, 0.0, params);
    }

    private static Plan toPlan(short goalId, List<PlannedStep> steps) {
        Plan plan = new Plan(UUID.randomUUID(), goalId, steps);
        plan.score = 0.0;
        plan.estimatedRisk = 0.0;
        return plan;
    }

    /** Map natural-language block references to a Minecraft block identifier. */
    static String inferBlockType(String goalText) {
        String lower = goalText.toLowerCase();
        if (lower.contains("wood") || lower.contains("log") || lower.contains("tree") || lower.contains("oak")) {
            return "minecraft:oak_log";
        }
        if (lower.contains("cobble") || lower.contains("stone") || lower.contains("rock")) {
            return "minecraft:stone";
        }
        if (lower.contains("coal")) {
            return "minecraft:coal_ore";
        }
        if (lower.contains("iron")) {
            return "minecraft:iron_ore";
        }
        if (lower.contains("diamond")) {
            return "minecraft:diamond_ore";
        }
        if (lower.contains("dirt")) {
            return "minecraft:dirt";
        }
        if (lower.contains("sand")) {
            return "minecraft:sand";
        }
        // Default: the most universally useful gathering target.
        return "minecraft:oak_log";
    }

    /** Map build/place goal text to a placeable block identifier. */
    static String inferPlacementBlock(String goalText) {
        String lower = goalText.toLowerCase();
        if (lower.contains("plank") || lower.contains("wood") || lower.contains("oak")) {
            return "minecraft:oak_planks";
        }
        if (lower.contains("stone") || lower.contains("cobble")) {
            return "minecraft:cobblestone";
        }
        if (lower.contains("dirt")) {
            return "minecraft:dirt";
        }
        if (lower.contains("glass")) {
            return "minecraft:glass";
        }
        return "minecraft:oak_planks";
    }
}
