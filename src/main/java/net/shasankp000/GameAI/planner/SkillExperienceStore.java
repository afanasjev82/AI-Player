package net.shasankp000.GameAI.planner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.shasankp000.LauncherDetection.LauncherEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-backed store of everyday-task (skill) outcomes.
 *
 * <p>Phase C. The combat/survival Q-table learned by {@code training} mode is
 * never consulted by the everyday pipeline (GoalMapper → SkillPlanBuilder →
 * FunctionCaller), so everyday tasks had no learning signal at all. This store
 * records, per skill, how often it succeeded and the average reward observed,
 * persisted as JSON alongside the Q-table so it survives restarts.
 *
 * <p>Reward for a gather/mine skill is the inventory delta of the target
 * resource; reward for navigate is negative distance to target; etc. This is
 * the foundation on which a curriculum sampler in {@code training} mode can
 * later choose which skills to practise.
 */
public class SkillExperienceStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-experience-store");

    private static final String SUBDIR = "qtable_storage";
    private static final String FILE_NAME = "skill_experience.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, SkillStats>>() {}.getType();

    private final Path file;
    private final Map<String, SkillStats> stats;

    /** Per-skill aggregate of observed outcomes. */
    public static final class SkillStats {
        public long attempts;
        public long successes;
        public double totalReward;

        public SkillStats() {}

        public double successRate() {
            return attempts == 0 ? 0.0 : (double) successes / attempts;
        }

        public double averageReward() {
            return attempts == 0 ? 0.0 : totalReward / attempts;
        }
    }

    public SkillExperienceStore() {
        this.file = Paths.get(
                LauncherEnvironment.getStorageDirectory(SUBDIR), FILE_NAME);
        this.stats = new ConcurrentHashMap<>();
        load();
    }

    /** Visible for testing: construct against an explicit file path. */
    SkillExperienceStore(Path file) {
        this.file = file;
        this.stats = new ConcurrentHashMap<>();
        load();
    }

    /**
     * Record one observed outcome for a skill.
     *
     * @param skill    e.g. "gather:minecraft:oak_log", "navigate", "mine:stone"
     * @param success  whether the outcome was achieved
     * @param reward   numeric reward (e.g. items gained, or -distance)
     */
    public void recordOutcome(String skill, boolean success, double reward) {
        stats.compute(skill, (k, v) -> {
            SkillStats s = (v == null) ? new SkillStats() : v;
            s.attempts++;
            if (success) s.successes++;
            s.totalReward += reward;
            return s;
        });
        persist();
    }

    /** Current aggregate stats for a skill (or an empty stats object). */
    public SkillStats getStats(String skill) {
        return stats.getOrDefault(skill, new SkillStats());
    }

    /** Snapshot of all recorded skill stats. */
    public Map<String, SkillStats> getAllStats() {
        return Map.copyOf(stats);
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            Map<String, SkillStats> loaded = GSON.fromJson(json, MAP_TYPE);
            if (loaded != null) {
                stats.putAll(loaded);
                LOGGER.info("Loaded {} skill experience entries from {}", stats.size(), file);
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            LOGGER.warn("Could not load skill experience store from {}: {}", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, GSON.toJson(stats));
        } catch (IOException e) {
            LOGGER.warn("Could not persist skill experience store to {}: {}", file, e.getMessage());
        }
    }
}
