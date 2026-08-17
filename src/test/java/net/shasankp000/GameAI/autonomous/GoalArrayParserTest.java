package net.shasankp000.GameAI.autonomous;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards {@link AutonomousGoalEngine#parseGoalArray(String)} against the
 * regression where qwen3's long reasoning prose (containing stray brackets)
 * caused {@code MalformedJsonException} and dropped the entire goal plan.
 */
class GoalArrayParserTest {

    @Test
    void parsesCleanArray() {
        List<String> goals = AutonomousGoalEngine.parseGoalArray(
                "[\"gather 32 wood\", \"craft a crafting table\", \"mine 16 stone\"]");
        assertEquals(List.of("gather 32 wood", "craft a crafting table", "mine 16 stone"), goals);
    }

    @Test
    void toleratesLeadingAndTrailingProse() {
        List<String> goals = AutonomousGoalEngine.parseGoalArray(
                "Here is my plan:\n[\"gather 32 wood\", \"build a shelter\"]\nHope this helps!");
        assertEquals(List.of("gather 32 wood", "build a shelter"), goals);
    }

    @Test
    void doesNotOverCaptureWithStrayBrackets() {
        // A reasoning block that references "[1]" and "[note]" later must not
        // swallow those into the array and break JSON parsing.
        String prose = "I considered [option 1] first. Plan: [\"mine stone\", \"craft a pickaxe\"]. "
                + "Then see [appendix] for details.";
        List<String> goals = AutonomousGoalEngine.parseGoalArray(prose);
        assertEquals(List.of("mine stone", "craft a pickaxe"), goals);
    }

    @Test
    void extractsBareQuotedStringsWhenNoArray() {
        // If the model returns prose with quoted goals but no JSON array.
        List<String> goals = AutonomousGoalEngine.parseGoalArray(
                "Goals: \"gather 32 wood\" then \"craft a crafting table\"");
        assertEquals(List.of("gather 32 wood", "craft a crafting table"), goals);
    }

    @Test
    void returnsEmptyForGarbage() {
        assertTrue(AutonomousGoalEngine.parseGoalArray("no goals here at all").isEmpty());
        assertTrue(AutonomousGoalEngine.parseGoalArray("").isEmpty());
        assertTrue(AutonomousGoalEngine.parseGoalArray(null).isEmpty());
    }

    @Test
    void creativeModeMarksGatherMineCraftRedundant() {
        assertTrue(AutonomousGoalEngine.isRedundantInCreative("gather 32 wood"));
        assertTrue(AutonomousGoalEngine.isRedundantInCreative("mine 16 stone"));
        assertTrue(AutonomousGoalEngine.isRedundantInCreative("craft a crafting table"));
    }

    @Test
    void creativeModeKeepsBuildExploreNavigate() {
        assertFalse(AutonomousGoalEngine.isRedundantInCreative("build a shelter"));
        assertFalse(AutonomousGoalEngine.isRedundantInCreative("explore around"));
        assertFalse(AutonomousGoalEngine.isRedundantInCreative("go to 120 70 -40"));
    }

    @Test
    void undergroundDescentGoalIsMineOnly() {
        assertTrue(AutonomousGoalEngine.isUndergroundDescentGoal("mine 16 stone"));
        assertFalse(AutonomousGoalEngine.isUndergroundDescentGoal("gather 32 wood"));
        assertFalse(AutonomousGoalEngine.isUndergroundDescentGoal("build a shelter"));
        assertFalse(AutonomousGoalEngine.isUndergroundDescentGoal("explore around"));
    }
}
