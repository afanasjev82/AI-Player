package net.shasankp000.GameAI.autonomous;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.planner.GoalMapper;
import net.shasankp000.GameAI.planner.HybridPlanner;
import net.shasankp000.GameAI.planner.SkillExperienceStore;
import net.shasankp000.PathFinding.PathTracer;
import net.shasankp000.ServiceLLMClients.LLMClient;
import net.shasankp000.ServiceLLMClients.LLMServiceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core autonomous loop for the AI bot.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>On bot spawn (or explicit trigger) ask the LLM to generate a JSON array
 *       of natural-language goal strings and enqueue them.</li>
 *   <li>Pop goals one at a time and dispatch them via {@link GoalMapper} +
 *       {@link HybridPlanner}.</li>
 *   <li>Accept priority goal injections from {@link WorldEventListener} via
 *       {@link #injectUrgentGoal(String)} or from the companion system via
 *       {@link #injectGoalWithPriority(String, int)}.</li>
 *   <li>Pause autonomous execution while a human player is directly addressing
 *       the bot ({@link #setPlayerControlled(boolean)}).</li>
 * </ol>
 *
 * <p>The goal queue is a {@link PriorityBlockingQueue} so urgent world-event
 * goals (priority=10) always surface before normal LLM plan goals (priority=0).
 * Companion FOLLOW goals use priority 15 and STAY return goals use priority 20.
 * Maximum depth is capped at {@value #MAX_QUEUE_DEPTH} to prevent runaway
 * re-plans from stacking up.
 */
public class AutonomousGoalEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("autonomous-goal-engine");

    /** Maximum number of queued goals at any time. */
    private static final int MAX_QUEUE_DEPTH = 10;

    /**
     * Minimum wall-clock gap between two executions of the <em>same</em> build
     * goal. The LLM re-plan loop repeatedly emits "build a shelter" at random
     * wander locations, which keeps colliding with terrain ("occupied by Stone")
     * and recording spurious failures. Rate-limiting build goals to one attempt
     * per this window breaks that redundant re-trigger loop.
     */
    private static final long BUILD_GOAL_COOLDOWN_MS = 60_000L;

    /**
     * Depth (blocks below the surface) beyond which the autonomous loop stops
     * generating new self-directed goals that would send the bot further
     * underground. Mirrors the RL surface-depth penalty; the deterministic
     * planner still honours explicit mining goals, but aimless self-directed
     * burrowing is suppressed.
     */
    private static final int UNDERGROUND_SUPPRESS_DEPTH = 5;

    /**
     * Consecutive failures of a skill before it is backed off (skipped during
     * re-plans). Breaks the bootstrap deadlock where the LLM re-proposes the
     * same impossible goal (e.g. "gather wood" with no trees in range) forever.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** How long a backed-off skill stays skipped before it may be retried. */
    private static final long FAILURE_BACKOFF_MS = 180_000L; // 3 minutes

    /**
     * Deterministic starter plan used when the LLM fails to return parseable
     * goals (qwen3 frequently answers with chain-of-thought prose instead of a
     * JSON array, even with JSON mode requested). Ordered as a survival
     * bootstrap: gather wood → craft a crafting table → explore for more.
     */
    private static final List<String> BOOTSTRAP_GOALS =
            List.of("gather 16 wood", "craft a crafting table", "explore");

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final String   botName;
    private final UUID     botUUID;

    /** True while a player is talking directly to the bot; autonomous loop yields. */
    private final AtomicBoolean playerControlled = new AtomicBoolean(false);

    /** Monotonic wall-clock timestamp of the last executed build goal (ms). */
    private volatile long lastBuildGoalExecutedAt = 0L;

    /** Consecutive failures per skill key ("gather:minecraft:oak_log", …). */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /** Wall-clock time the skill last crossed the failure threshold. */
    private final Map<String, Long> lastFailureAt = new ConcurrentHashMap<>();

    /**
     * Disk-backed everyday-task outcomes. The skill selector reads this to
     * deprioritize goals whose skill keeps failing and favour proven ones —
     * this is the "learn which skill to attempt next" layer, as opposed to
     * re-learning how to perform a skill (which stays deterministic).
     */
    private final SkillExperienceStore skillStore = new SkillExperienceStore();

    /** True once shutdown() has been called. */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** True while the serial worker is executing a dequeued goal. */
    private final AtomicBoolean goalExecuting = new AtomicBoolean(false);

    /** Deterministic survival behavior executed on this engine's serial goal worker. */
    private final NearbyBedSleepController sleepController;

    /**
     * Priority queue — higher {@link GoalQueueEntry#priority()} values are
     * dequeued first.  Capacity is a soft hint; we enforce MAX_QUEUE_DEPTH
     * manually on insert.
     */
    private final PriorityBlockingQueue<GoalQueueEntry> goalQueue =
            new PriorityBlockingQueue<>(MAX_QUEUE_DEPTH);

    /** Single-thread executor that drives the goal execution loop. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> Thread.ofVirtual().name("autonomous-goal-loop").unstarted(r));

    // -------------------------------------------------------------------------
    // Construction & lifecycle
    // -------------------------------------------------------------------------

    public AutonomousGoalEngine(String botName, UUID botUUID) {
        this.botName  = botName;
        this.botUUID  = botUUID;
        this.sleepController = new NearbyBedSleepController(botName, playerControlled::get);
    }

    /**
     * Call once after the bot has joined the server.
     * Generates the initial goal list and starts the execution loop.
     */
    public void start() {
        LOGGER.info("[autonomous] Starting for bot '{}'", botName);
        CompletableFuture.runAsync(this::generateAndEnqueueGoals, executor);
        executor.submit(this::executionLoop);
    }

    /** Graceful shutdown — drains the queue and stops the executor. */
    public void shutdown() {
        stopped.set(true);
        goalQueue.clear();
        sleepController.shutdown();
        executor.shutdownNow();
        LOGGER.info("[autonomous] Shut down for bot '{}'", botName);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Inject a high-priority goal from {@link WorldEventListener}.
     * Silently dropped if the queue is already at max depth.
     */
    public void injectUrgentGoal(String goalText) {
        enqueue(new GoalQueueEntry(goalText, 10, GoalQueueEntry.Source.WORLD_EVENT));
    }

    /**
     * Inject a player-requested goal (normal priority).
     * Also flips the bot back to autonomous mode once done.
     */
    public void injectPlayerGoal(String goalText) {
        enqueue(new GoalQueueEntry(goalText, 5, GoalQueueEntry.Source.PLAYER));
    }

    /**
     * Inject a goal with an explicit priority value.
     *
     * <p>Used by the companion system:
     * <ul>
     *   <li>FOLLOW navigation goals — priority 15 (above world-events, below hard interrupts)</li>
     *   <li>STAY return-to-anchor goals — priority 20</li>
     * </ul>
     *
     * Silently dropped if the queue is already at max depth.
     *
     * @param goalText Human-readable goal string forwarded to {@link GoalMapper}.
     * @param priority Numeric priority; higher values dequeue first.
     */
    public void injectGoalWithPriority(String goalText, int priority) {
        enqueue(new GoalQueueEntry(goalText, priority, GoalQueueEntry.Source.PLAYER));
    }

    /**
     * Pause or resume autonomous execution.
     * Call with {@code true} when a human addresses the bot directly;
     * call with {@code false} when the interaction is complete.
     */
    public void setPlayerControlled(boolean controlled) {
        playerControlled.set(controlled);
        if (!controlled) {
            LOGGER.debug("[autonomous] Resuming autonomous mode for bot '{}'", botName);
        }
    }

    public boolean isPlayerControlled() {
        return playerControlled.get();
    }

    /** Returns the current number of queued goals. */
    public int queueSize() {
        return goalQueue.size();
    }

    /** Returns true while the serial autonomous worker is executing a goal. */
    boolean isExecutingGoal() {
        return goalExecuting.get();
    }

    /** Returns true while the bot entity is currently sleeping. */
    boolean isBotSleeping() {
        return sleepController.isBotSleeping();
    }

    /**
     * Trigger a fresh LLM re-plan and replace the current queue.
     * Called by {@link AutonomousScheduler} on its idle re-plan tick.
     */
    public void triggerReplan() {
        goalQueue.clear();
        generateAndEnqueueGoals();
    }

    // -------------------------------------------------------------------------
    // Goal generation (LLM call)
    // -------------------------------------------------------------------------

    /**
     * Calls the configured LLM with a structured state-aware prompt and
     * expects a JSON array of natural-language goal strings in return.
     *
     * Example expected response:
     * {@code ["gather 32 wood", "craft a crafting table", "mine stone", "build a shelter"]}
     */
    void generateAndEnqueueGoals() {
        if (stopped.get()) return;

        String llmProvider = System.getProperty("aiplayer.llmMode", "custom");
        LOGGER.info("[autonomous] Requesting goal plan from LLM (provider={})", llmProvider);

        try {
            ServerPlayer bot = resolveBot();
            String stateSnapshot = buildStateSnapshot(bot);

            String systemPrompt =
                    "You are controlling a Minecraft bot. Based on the bot's current state, " +
                    "generate a prioritised list of 4-6 short, achievable goals for the bot to " +
                    "complete right now. Reply with ONLY a valid JSON array of strings. " +
                    "Each string must be a single concise goal in plain English. " +
                    "Example: [\"gather 32 wood\", \"craft a crafting table\", \"mine 16 stone\"]\n" +
                    "IMPORTANT: if the bot's game mode is 'creative', it already has unlimited " +
                    "resources, so do NOT generate gather/mine/craft goals (there is nothing to " +
                    "collect or craft). Focus instead on building, exploring, and using blocks " +
                    "already available in the creative inventory.\n" +
                    "IMPORTANT: if the bot is already deep underground (depth below surface is " +
                    "large), do NOT generate goals that would send it deeper (mining, digging, " +
                    "caving). Prefer goals that return it to the surface or work near the " +
                    "surface.";

            String userPrompt = "Bot state:\n" + stateSnapshot +
                    "\n\nGenerate the goal list JSON array now:";

            String response = callLLM(llmProvider, systemPrompt, userPrompt);
            if (response == null || response.isBlank()) {
                LOGGER.warn("[autonomous] LLM returned empty response — no goals enqueued");
                return;
            }

            List<String> goals = parseGoalArray(response);
            if (goals.isEmpty()) {
                // Robustness against a non-compliant model: never leave the bot
                // idle just because the LLM returned prose instead of JSON.
                // Fall back to a deterministic survival bootstrap so the bot
                // always has work (also escapes the no-tools bootstrap deadlock).
                LOGGER.warn("[autonomous] Could not parse goal array ({} chars) — using bootstrap plan",
                        response.length());
                goals = BOOTSTRAP_GOALS;
            }

            // Skill selector: order goals by observed skill success so proven
            // everyday tasks run first and repeatedly-failing ones are pushed
            // back. The LLM's order is preserved as a tie-breaker. This is the
            // explore/exploit policy over *skills* (not over individual RL
            // actions), reading the same SkillExperienceStore HybridPlanner
            // writes to.
            List<String> ranked = rankGoalsBySkillExperience(goals);

            // In creative mode the bot has unlimited blocks, so gather/mine/
            // craft goals are pointless. Drop them from the autonomous LLM plan
            // (player-requested goals via injectPlayerGoal are unaffected).
            boolean creative = bot != null && bot.gameMode.getGameModeForPlayer().isCreative();

            // If the bot is already deep underground, suppress self-directed
            // "mine" goals (which would drive it deeper). Explicit player goals
            // and the deterministic planner are unaffected.
            boolean alreadyDeep = bot != null && depthBelowSurface(bot) > UNDERGROUND_SUPPRESS_DEPTH;

            int enqueued = 0;
            int backedOff = 0;
            for (String goal : ranked) {
                if (creative && isRedundantInCreative(goal)) {
                    LOGGER.info("[autonomous] Skipping redundant '{}' in creative mode", goal);
                    continue;
                }
                if (alreadyDeep && isUndergroundDescentGoal(goal)) {
                    LOGGER.info("[autonomous] Skipping underground-descent '{}' (already {} blocks deep)",
                            goal, depthBelowSurface(bot));
                    continue;
                }
                // Failure backoff: a skill that keeps failing must not be
                // re-enqueued every re-plan — that is the bootstrap deadlock
                // (e.g. "gather wood" with no trees within search range).
                String skillKey = HybridPlanner.skillKeyForGoal(GoalMapper.parseGoal(goal), goal);
                if (isBackedOff(skillKey)) {
                    backedOff++;
                    LOGGER.info("[autonomous] Backing off '{}' (skill '{}' failed {}x) — exploring instead",
                            goal, skillKey, consecutiveFailures.get(skillKey));
                    continue;
                }
                if (enqueue(new GoalQueueEntry(goal.trim(), 0, GoalQueueEntry.Source.LLM_PLAN))) {
                    enqueued++;
                }
            }
            if (enqueued == 0 && backedOff > 0) {
                // Every LLM-proposed goal keeps failing: escape the local
                // deadlock by exploring so the bot moves to fresh terrain
                // (e.g. off a treeless mountain where it can finally gather).
                enqueue(new GoalQueueEntry("explore", 0, GoalQueueEntry.Source.LLM_PLAN));
                LOGGER.info("[autonomous] All {} LLM goals backed off — enqueued 'explore' to escape deadlock",
                        backedOff);
            }
            LOGGER.info("[autonomous] Enqueued {} goals from LLM plan (skill-ranked)", enqueued);

        } catch (Exception e) {
            LOGGER.error("[autonomous] Goal generation failed: {}", e.getMessage(), e);
        }
    }

    /** Whether a goal is redundant in creative mode (package-visible for tests). */
    static boolean isRedundantInCreative(String goal) {
        short goalId = GoalMapper.parseGoal(goal);
        return goalId == GoalMapper.GOAL_GATHER
                || goalId == GoalMapper.GOAL_MINE
                || goalId == GoalMapper.GOAL_CRAFT;
    }

    /** Whether a self-directed goal would send the bot further underground. */
    static boolean isUndergroundDescentGoal(String goal) {
        short goalId = GoalMapper.parseGoal(goal);
        // "mine" is the descent driver; "gather" of ore/logs can also dig, but
        // is more ambiguous — keep it simple and suppress only mine.
        return goalId == GoalMapper.GOAL_MINE;
    }

    /**
     * Order LLM-produced goals by observed skill success (descending), keeping
     * the LLM's original order as a tie-breaker.
     *
     * <p>Scoring: a skill's score is its success rate minus a small penalty for
     * untried skills so they get a chance (exploration), and a flat floor for
     * skills with no data at all. Failed skills sink, proven skills float to
     * the front. This is deliberately simple and side-effect-free — it only
     * reorders, never drops.
     */
    List<String> rankGoalsBySkillExperience(List<String> goals) {
        if (goals.size() <= 1) return new ArrayList<>(goals);

        List<String> ranked = new ArrayList<>(goals);
        ranked.sort(Comparator.comparingDouble((String goal) -> {
            short goalId = GoalMapper.parseGoal(goal);
            String skillKey = HybridPlanner.skillKeyForGoal(goalId, goal);
            SkillExperienceStore.SkillStats s = skillStore.getStats(skillKey);
            if (s.attempts == 0) {
                return 0.5; // untried: neutral, above failures below proven
            }
            return s.successRate();
        }).reversed());

        if (LOGGER.isDebugEnabled()) {
            for (String g : ranked) {
                short id = GoalMapper.parseGoal(g);
                var s = skillStore.getStats(HybridPlanner.skillKeyForGoal(id, g));
                LOGGER.debug("[autonomous] skill-rank '{}' → {} ({} attempts, {:.0f}% success)",
                        g, s.attempts == 0 ? "untried" : String.format("%.0f%%", s.successRate() * 100),
                        s.attempts, s.successRate() * 100);
            }
        }
        return ranked;
    }

    // -------------------------------------------------------------------------
    // Execution loop
    // -------------------------------------------------------------------------

    private void executionLoop() {
        LOGGER.info("[autonomous] Execution loop started for bot '{}'", botName);
        while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Yield while a player is talking to the bot
                if (playerControlled.get()) {
                    Thread.sleep(500);
                    continue;
                }

                // Sleeping is itself the active survival action. Do not dequeue
                // ordinary goals until vanilla wakes the bot.
                if (sleepController.isBotSleeping()) {
                    Thread.sleep(500);
                    continue;
                }

                // Yield while the bot is physically traversing a path (player or
                // companion navigation). Autonomous goals (build/gather/…)
                // otherwise fire flushAllMovementTasks() mid-navigation and
                // abort it. The autonomous loop's OWN navigation is synchronous
                // inside executeGoal, so this never deadlocks — it only blocks
                // the *next* goal while a foreign navigation is in flight.
                if (PathTracer.BotSegmentManager.getBotMovementStatus()) {
                    Thread.sleep(200);
                    continue;
                }

                // Block up to 5 s waiting for a goal
                GoalQueueEntry entry = goalQueue.poll(5, TimeUnit.SECONDS);
                if (entry == null) continue;

                goalExecuting.set(true);
                try {
                    executeGoal(entry);
                } finally {
                    goalExecuting.set(false);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("[autonomous] Unexpected error in execution loop: {}", e.getMessage(), e);
            }
        }
        LOGGER.info("[autonomous] Execution loop ended for bot '{}'", botName);
    }

    private void executeGoal(GoalQueueEntry entry) {
        LOGGER.info("[autonomous] Executing goal '{}' (priority={}, source={})",
                entry.goalText(), entry.priority(), entry.source());

        String llmProvider = System.getProperty("aiplayer.llmMode", "custom");

        // For WORLD_EVENT goals that are purely conversational, route through
        // LLMServiceHandler so the bot produces a natural chat response.
        if (entry.source() == GoalQueueEntry.Source.WORLD_EVENT) {
            try {
                LLMClient client = buildClient(llmProvider);
                if (client != null) {
                    LLMServiceHandler.runFromChat(entry.goalText(), botName, botUUID, client);
                }
            } catch (Exception e) {
                LOGGER.error("[autonomous] Chat response failed for '{}': {}", entry.goalText(), e.getMessage());
            }
            return;
        }

        // For LLM_PLAN / PLAYER goals, parse via GoalMapper and dispatch to HybridPlanner
        short goalId = GoalMapper.parseGoal(entry.goalText());
        if (goalId == GoalMapper.GOAL_UNKNOWN) {
            LOGGER.warn("[autonomous] Could not map '{}' to a known goal — skipping", entry.goalText());
            return;
        }

        // Build-redundancy guard: the LLM re-plan loop repeatedly emits "build"
        // goals at random wander locations, which keeps failing against terrain.
        // Rate-limit build goals to one attempt per cooldown window.
        if (goalId == GoalMapper.GOAL_BUILD) {
            long now = System.currentTimeMillis();
            long last = lastBuildGoalExecutedAt;
            if (now - last < BUILD_GOAL_COOLDOWN_MS) {
                LOGGER.info("[autonomous] Skipping redundant build goal '{}' — {}s cooldown remaining",
                        entry.goalText(), (BUILD_GOAL_COOLDOWN_MS - (now - last)) / 1000);
                return;
            }
            lastBuildGoalExecutedAt = now;
        }

        String skillKey = HybridPlanner.skillKeyForGoal(goalId, entry.goalText());

        boolean achieved = false;
        try {
            ServerPlayer bot = resolveBot();
            if (bot == null) {
                LOGGER.warn("[autonomous] Bot '{}' not found on server — skipping goal", botName);
                return;
            }
            achieved = HybridPlanner.executeGoal(bot, goalId, entry.goalText());
        } catch (Exception e) {
            LOGGER.error("[autonomous] HybridPlanner execution failed for '{}': {}", entry.goalText(), e.getMessage());
        }

        recordFailure(entry, skillKey, achieved);
    }

    /**
     * Update consecutive-failure tracking for a skill after one attempt.
     * Only LLM_PLAN goals feed this backoff signal; player/world-event goals
     * always run regardless.
     */
    private void recordFailure(GoalQueueEntry entry, String skillKey, boolean achieved) {
        if (entry.source() != GoalQueueEntry.Source.LLM_PLAN) return;
        if (achieved) {
            consecutiveFailures.remove(skillKey);
            lastFailureAt.remove(skillKey);
            return;
        }
        int failures = consecutiveFailures.merge(skillKey, 1, Integer::sum);
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            lastFailureAt.put(skillKey, System.currentTimeMillis());
        }
    }

    /** True if a skill has repeatedly failed and is still within its cooldown. */
    private boolean isBackedOff(String skillKey) {
        Integer failures = consecutiveFailures.get(skillKey);
        if (failures == null || failures < MAX_CONSECUTIVE_FAILURES) return false;
        Long last = lastFailureAt.get(skillKey);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < FAILURE_BACKOFF_MS;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean enqueue(GoalQueueEntry entry) {
        if (goalQueue.size() >= MAX_QUEUE_DEPTH) {
            LOGGER.debug("[autonomous] Queue full ({}) — dropping goal '{}'",
                    MAX_QUEUE_DEPTH, entry.goalText());
            return false;
        }
        goalQueue.offer(entry);
        return true;
    }

    private ServerPlayer resolveBot() {
        if (AIPlayer.serverInstance == null) return null;
        return AIPlayer.serverInstance.getPlayerList().getPlayerByName(botName);
    }

    /**
     * Builds a compact, LLM-readable snapshot of the bot's current game state.
     * Gracefully degrades when the bot entity is not available.
     */
    private String buildStateSnapshot(ServerPlayer bot) {
        if (bot == null) return "(bot not found — assume fresh spawn, daytime, overworld)";

        StringBuilder sb = new StringBuilder();
        sb.append("- Health: ").append(String.format("%.1f", bot.getHealth()))
          .append(" / ").append(String.format("%.1f", bot.getMaxHealth())).append("\n");
        sb.append("- Hunger: ").append(bot.getFoodData().getFoodLevel()).append(" / 20\n");
        sb.append("- Position: ").append(bot.blockPosition()).append("\n");
        sb.append("- Dimension: ").append(bot.level().dimension().identifier()).append("\n");

        // Game mode: creative means the bot has unlimited blocks and does NOT
        // need to mine/craft — the LLM must know this to avoid generating
        // pointless gather/mine/craft goals.
        String gameMode = bot.gameMode.getGameModeForPlayer().isCreative() ? "creative"
                : (bot.gameMode.getGameModeForPlayer().isSurvival() ? "survival" : "other");
        sb.append("- Game mode: ").append(gameMode).append("\n");

        // Surface depth: tells the LLM whether the bot is already underground
        // so it avoids generating goals that would send it deeper.
        int depth = depthBelowSurface(bot);
        sb.append("- Depth below surface: ").append(depth)
          .append(" blocks").append(depth > UNDERGROUND_SUPPRESS_DEPTH ? " (already underground)" : "").append("\n");

        long timeOfDay = bot.level().getDefaultClockTime() % 24000;
        String period = (timeOfDay < 6000) ? "morning" :
                        (timeOfDay < 12000) ? "afternoon" :
                        (timeOfDay < 13000) ? "sunset" : "night";
        sb.append("- Time: ").append(period).append(" (").append(timeOfDay).append(")\n");

        // Main hand item
        String held = bot.getMainHandItem().isEmpty() ? "nothing"
                : bot.getMainHandItem().getHoverName().getString();
        sb.append("- Holding: ").append(held).append("\n");

        // Inventory item count (non-empty slots)
        int itemCount = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            if (!bot.getInventory().getItem(i).isEmpty()) itemCount++;
        }
        sb.append("- Inventory slots used: ").append(itemCount).append(" / 36\n");

        return sb.toString();
    }

    /**
     * Depth (blocks) of the bot below the highest solid (non-leaf) block at its
     * XZ column. Non-negative; 0 means at/near the surface. Uses
     * {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} so tree canopies don't
     * skew the result.
     */
    private static int depthBelowSurface(ServerPlayer bot) {
        if (bot == null || bot.level() == null || bot.level().isClientSide()) return 0;
        int surfaceY = bot.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                bot.blockPosition().getX(),
                bot.blockPosition().getZ());
        return Math.max(0, surfaceY - bot.blockPosition().getY());
    }

    private String callLLM(String provider, String systemPrompt, String userPrompt) {
        try {
            LLMClient client = buildClient(provider);
            if (client == null) return null;
            // Goal planning output is machine-parsed, so request structured JSON
            // where the provider supports it (fixes qwen3 returning chain-of-
            // thought prose instead of a JSON array).
            return client.sendPromptJson(systemPrompt, userPrompt);
        } catch (Exception e) {
            LOGGER.error("[autonomous] LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    private LLMClient buildClient(String provider) {
        if (provider.equals("player2")) {
            LOGGER.warn("[autonomous] Player2 provider not supported in autonomous mode (no chatCallback available here)");
            return null;
        }
        return LLMClientFactory.createClient(provider);
    }

    /**
     * Extracts a JSON array of strings from the LLM response.
     *
     * <p>The local model (qwen3) frequently returns long reasoning prose that
     * happens to contain square brackets, which made the old
     * {@code indexOf('[') / lastIndexOf(']')} approach over-capture and produce
     * malformed JSON. This implementation:
     * <ol>
     *   <li>Locates the first {@code '['} and its <em>matching</em> {@code ']'}
     *       via a balanced-bracket scan (no over-capture).</li>
     *   <li>Parses that substring strictly, then leniently as a fallback.</li>
     *   <li>If no well-formed array is found, extracts bare quoted strings
     *       (e.g. {@code "gather 32 wood"}) as a last resort.</li>
     * </ol>
     */
    static List<String> parseGoalArray(String response) {
        if (response == null || response.isBlank()) return List.of();

        // 1. Balanced-bracket extraction: first '[' to its matching ']'.
        int start = response.indexOf('[');
        if (start >= 0) {
            int end = findMatchingBracket(response, start);
            if (end > start) {
                String json = response.substring(start, end + 1);

                // 2a. Strict parse first.
                List<String> strict = tryParseArray(json, false);
                if (strict != null && !strict.isEmpty()) {
                    return strict;
                }
                // 2b. Lenient fallback (handles minor formatting deviations).
                List<String> lenient = tryParseArray(json, true);
                if (lenient != null && !lenient.isEmpty()) {
                    LOGGER.info("[autonomous] Parsed goal array in lenient mode");
                    return lenient;
                }
                LOGGER.warn("[autonomous] Found bracket pair but could not parse as JSON array: {}", json);
            }
        }

        // 3. Last resort: extract bare quoted strings from the response.
        List<String> extracted = extractQuotedStrings(response);
        if (!extracted.isEmpty()) {
            LOGGER.info("[autonomous] Extracted {} goals from prose (no JSON array)", extracted.size());
            return extracted;
        }

        // Log only the length, not the raw chain-of-thought — dumping the full
        // reasoning into the server log is noisy and useless.
        LOGGER.warn("[autonomous] JSON parse error — {} chars of unparseable prose", response.length());
        return List.of();
    }

    /** Returns the index of the bracket matching the opening bracket at {@code openIdx}. */
    private static int findMatchingBracket(String s, int openIdx) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Attempts to parse {@code json} as a JSON array of strings; null on failure. */
    private static List<String> tryParseArray(String json, boolean lenient) {
        try {
            JsonArray arr;
            if (lenient) {
                arr = com.google.gson.JsonParser.parseReader(
                        new java.io.StringReader(json)).getAsJsonArray();
            } else {
                arr = JsonParser.parseString(json).getAsJsonArray();
            }
            List<String> goals = new java.util.ArrayList<>();
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) {
                    String goal = el.getAsString().trim();
                    if (!goal.isEmpty()) goals.add(goal);
                }
            }
            return goals;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extracts double-quoted substrings from a raw prose response. */
    private static List<String> extractQuotedStrings(String response) {
        List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([^\"]+)\"")
                .matcher(response);
        while (m.find()) {
            String s = m.group(1).trim();
            if (!s.isEmpty() && s.length() < 200) {
                out.add(s);
            }
        }
        return out;
    }
}
