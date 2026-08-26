package net.shasankp000.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the category-ingredient matching that makes crafting wood-type
 * agnostic: generic wood recipes (sticks, crafting table, wooden tools) accept
 * planks of any species, so the bot can bootstrap in any biome.
 */
class CraftingToolTest {

    @Test
    void categorySentinelIsDetected() {
        assertTrue(CraftingTool.isCategory("#any_planks"));
        assertFalse(CraftingTool.isCategory("minecraft:oak_planks"));
        assertFalse(CraftingTool.isCategory(null));
    }

    @Test
    void anyPlanksMatchesAnySpecies() {
        assertTrue(CraftingTool.ingredientMatches("#any_planks", "minecraft:oak_planks"));
        assertTrue(CraftingTool.ingredientMatches("#any_planks", "minecraft:jungle_planks"));
        assertTrue(CraftingTool.ingredientMatches("#any_planks", "minecraft:dark_oak_planks"));
        assertFalse(CraftingTool.ingredientMatches("#any_planks", "minecraft:jungle_log"));
        assertFalse(CraftingTool.ingredientMatches("#any_planks", "minecraft:stick"));
    }

    @Test
    void concreteIngredientMatchesExactly() {
        assertTrue(CraftingTool.ingredientMatches("minecraft:oak_planks", "minecraft:oak_planks"));
        assertFalse(CraftingTool.ingredientMatches("minecraft:oak_planks", "minecraft:jungle_planks"));
        assertFalse(CraftingTool.ingredientMatches(null, "minecraft:oak_planks"));
    }
}
