package net.shasankp000.GameAI.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the disk-backed everyday-task outcome store (Phase C).
 */
class SkillExperienceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void accumulatesAttemptsSuccessesAndReward() {
        SkillExperienceStore store = new SkillExperienceStore(tempDir.resolve("skills.json"));

        store.recordOutcome("gather:minecraft:oak_log", true, 5.0);
        store.recordOutcome("gather:minecraft:oak_log", false, 0.0);
        store.recordOutcome("gather:minecraft:oak_log", true, 3.0);

        SkillExperienceStore.SkillStats s = store.getStats("gather:minecraft:oak_log");
        assertEquals(3, s.attempts);
        assertEquals(2, s.successes);
        assertEquals(8.0, s.totalReward, 1e-6);
        assertEquals(2.0 / 3.0, s.successRate(), 1e-6);
    }

    @Test
    void persistsAcrossInstances() {
        Path file = tempDir.resolve("skills.json");
        SkillExperienceStore first = new SkillExperienceStore(file);
        first.recordOutcome("mine:minecraft:stone", true, 4.0);

        // New instance loads from disk.
        SkillExperienceStore second = new SkillExperienceStore(file);
        SkillExperienceStore.SkillStats s = second.getStats("mine:minecraft:stone");
        assertEquals(1, s.attempts);
        assertEquals(1, s.successes);
        assertEquals(4.0, s.totalReward, 1e-6);
    }

    @Test
    void unknownSkillReturnsEmptyStats() {
        SkillExperienceStore store = new SkillExperienceStore(tempDir.resolve("skills.json"));
        SkillExperienceStore.SkillStats s = store.getStats("nope");
        assertEquals(0, s.attempts);
        assertEquals(0.0, s.successRate(), 1e-6);
        assertEquals(0.0, s.averageReward(), 1e-6);
    }

    @Test
    void averageRewardIsComputed() {
        SkillExperienceStore store = new SkillExperienceStore(tempDir.resolve("skills.json"));
        store.recordOutcome("navigate", true, 1.0);
        store.recordOutcome("navigate", false, -1.0);
        store.recordOutcome("navigate", true, 1.0);

        assertEquals(1.0 / 3.0, store.getStats("navigate").averageReward(), 1e-6);
    }
}
