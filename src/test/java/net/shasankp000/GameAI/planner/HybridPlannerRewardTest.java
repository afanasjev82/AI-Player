package net.shasankp000.GameAI.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the Phase C reward measurement mapping (block type → drop item) so
 * gather/mine rewards track the resource the bot actually gains.
 */
class HybridPlannerRewardTest {

    @Test
    void stoneDropsCobblestone() {
        assertEquals("minecraft:cobblestone", HybridPlanner.blockTypeToDropItem("minecraft:stone"));
    }

    @Test
    void oresMapToTheirDrops() {
        assertEquals("minecraft:coal", HybridPlanner.blockTypeToDropItem("minecraft:coal_ore"));
        assertEquals("minecraft:raw_iron", HybridPlanner.blockTypeToDropItem("minecraft:iron_ore"));
        assertEquals("minecraft:diamond", HybridPlanner.blockTypeToDropItem("minecraft:diamond_ore"));
    }

    @Test
    void selfDroppingBlocksPassThrough() {
        assertEquals("minecraft:oak_log", HybridPlanner.blockTypeToDropItem("minecraft:oak_log"));
        assertEquals("minecraft:dirt", HybridPlanner.blockTypeToDropItem("minecraft:dirt"));
    }

    @Test
    void nullAndUnknownAreSafe() {
        assertNull(HybridPlanner.blockTypeToDropItem(null));
        assertEquals("minecraft:some_unknown", HybridPlanner.blockTypeToDropItem("minecraft:some_unknown"));
    }

    @Test
    void skillKeyIncludesTargetForGatherAndMine() {
        assertEquals("gather:" + EntityExtractor.ANY_LOG,
                HybridPlanner.skillKeyForGoal(GoalMapper.GOAL_GATHER, "gather 32 wood"));
        assertEquals("mine:minecraft:stone",
                HybridPlanner.skillKeyForGoal(GoalMapper.GOAL_MINE, "mine 16 stone"));
        assertEquals("craft",
                HybridPlanner.skillKeyForGoal(GoalMapper.GOAL_CRAFT, "craft a crafting table"));
    }

    @Test
    void craftOutputItemKeyMapsGoalToProducedItem() {
        assertEquals("minecraft:crafting_table", HybridPlanner.craftOutputItemKey("craft a crafting table"));
        assertEquals("minecraft:wooden_sword", HybridPlanner.craftOutputItemKey("craft a wooden sword"));
        assertEquals("minecraft:torch", HybridPlanner.craftOutputItemKey("craft 4 torches"));
        assertNull(HybridPlanner.craftOutputItemKey("craft planks"));
    }

    @Test
    void craftAndBuildRequireInventoryChange() {
        // "Plan executed but nothing happened" (0→0) must NOT count as achieved.
        assertFalse(HybridPlanner.isAchieved(GoalMapper.GOAL_CRAFT, true, 0));
        assertFalse(HybridPlanner.isAchieved(GoalMapper.GOAL_BUILD, true, 0));
        // A real inventory change (inputs consumed / output produced) IS achieved.
        assertTrue(HybridPlanner.isAchieved(GoalMapper.GOAL_CRAFT, true, -3));
        assertTrue(HybridPlanner.isAchieved(GoalMapper.GOAL_BUILD, true, -1));
    }

    @Test
    void movementAndSocialGoalsAchieveOnExecution() {
        assertTrue(HybridPlanner.isAchieved(GoalMapper.GOAL_NAVIGATE, true, 0));
        assertTrue(HybridPlanner.isAchieved(GoalMapper.GOAL_EXPLORE, true, 0));
        assertTrue(HybridPlanner.isAchieved(GoalMapper.GOAL_COMBAT, true, 0));
        assertFalse(HybridPlanner.isAchieved(GoalMapper.GOAL_COMBAT, false, 0));
    }
}
