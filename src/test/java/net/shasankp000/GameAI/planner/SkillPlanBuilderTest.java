package net.shasankp000.GameAI.planner;

import net.shasankp000.GameAI.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the deterministic multi-step skill templates (Phase B).
 */
class SkillPlanBuilderTest {

    private State state;

    @BeforeEach
    void setUp() {
        // Minimal State with a known position and no inventory/entities.
        // hotBarItems/offhand/armorItems are net.minecraft ItemStack collections.
        state = new State(
                100, 64, 200,                       // botX, botY, botZ
                List.<net.shasankp000.Entity.EntityDetails>of(),   // nearbyEntities
                List.<String>of(),                  // nearbyBlocks
                0.0,                                // distanceToHostileEntity
                20,                                 // botHealth
                0.0,                                // distanceToDangerZone
                List.<net.minecraft.world.item.ItemStack>of(),     // hotBarItems
                new net.shasankp000.PlayerUtils.SelectedItemDetails("", false, false),
                "day",                              // timeOfDay
                "minecraft:overworld",              // dimension
                20,                                 // hunger
                15,                                 // oxygen
                0,                                  // frost
                null,                                          // offhand (null → serializeItemStack returns "empty" without touching Items)
                new java.util.HashMap<String, net.minecraft.world.item.ItemStack>(), // armorItems
                null,                               // actionTaken
                new java.util.HashMap<net.shasankp000.GameAI.StateActions.Action, Double>(), // riskMap
                0.5,                                // riskAppetite
                new java.util.HashMap<net.shasankp000.GameAI.StateActions.Action, Double>()  // podMap
        );
    }

    @Test
    void gatherProducesSearchGoMineChain() {
        Plan plan = SkillPlanBuilder.buildPlan(GoalMapper.GOAL_GATHER, "gather 32 wood", state);
        assertNotNull(plan);
        assertEquals(3, plan.steps.size());
        assertEquals("searchBlocks", plan.steps.get(0).actionName);
        assertEquals("goTo", plan.steps.get(1).actionName);
        assertEquals("mineBlock", plan.steps.get(2).actionName);
    }

    @Test
    void gatherInfersWoodBlockType() {
        assertEquals("minecraft:oak_log", SkillPlanBuilder.inferBlockType("gather wood"));
        assertEquals("minecraft:stone", SkillPlanBuilder.inferBlockType("mine stone"));
        assertEquals("minecraft:iron_ore", SkillPlanBuilder.inferBlockType("gather iron"));
        assertEquals("minecraft:coal_ore", SkillPlanBuilder.inferBlockType("get coal"));
    }

    @Test
    void mineUsesSameChainAsGather() {
        Plan plan = SkillPlanBuilder.buildPlan(GoalMapper.GOAL_MINE, "mine 16 stone", state);
        assertNotNull(plan);
        assertEquals(3, plan.steps.size());
        assertEquals("mineBlock", plan.steps.get(2).actionName);
    }

    @Test
    void navigateParsesCoordinates() {
        Plan plan = SkillPlanBuilder.buildPlan(GoalMapper.GOAL_NAVIGATE, "go to 120 70 -40", state);
        assertNotNull(plan);
        assertEquals(1, plan.steps.size());
        assertEquals("goTo", plan.steps.get(0).actionName);
        assertTrue(plan.steps.get(0).params.contains("120"));
        assertTrue(plan.steps.get(0).params.contains("-40"));
    }

    @Test
    void navigateWithoutCoordinatesReturnsNull() {
        Plan plan = SkillPlanBuilder.buildPlan(GoalMapper.GOAL_NAVIGATE, "walk somewhere", state);
        assertNull(plan);
    }

    @Test
    void unsupportedGoalsReturnNull() {
        assertNull(SkillPlanBuilder.buildPlan(GoalMapper.GOAL_COMBAT, "fight zombies", state));
        assertNull(SkillPlanBuilder.buildPlan(GoalMapper.GOAL_TRADE, "trade with villagers", state));
    }

    @Test
    void craftProducesCraftStep() {
        Plan plan = SkillPlanBuilder.buildPlan(GoalMapper.GOAL_CRAFT, "craft a wooden pickaxe", state);
        assertNotNull(plan);
        assertEquals(1, plan.steps.size());
        assertEquals("craft", plan.steps.get(0).actionName);
    }

    @Test
    void farmProducesSingleFarmStep() {
        Plan plan = SkillPlanBuilder.buildPlan(GoalMapper.GOAL_FARM, "farm wheat", state);
        assertNotNull(plan);
        assertEquals(1, plan.steps.size());
        assertEquals("farm", plan.steps.get(0).actionName);
        assertTrue(plan.steps.get(0).params.contains("wheat"));
    }

    @Test
    void exploreProducesLookThenMove() {
        Plan plan = SkillPlanBuilder.buildPlan(GoalMapper.GOAL_EXPLORE, "explore around", state);
        assertNotNull(plan);
        assertEquals(2, plan.steps.size());
        assertEquals("look", plan.steps.get(0).actionName);
        assertEquals("goTo", plan.steps.get(1).actionName);
    }
}
