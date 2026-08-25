package net.shasankp000.GameAI;

import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.DangerZoneDetector.DangerZoneDetector;
import net.shasankp000.Database.QTable;
import net.shasankp000.Database.QTableStorage;
import net.shasankp000.Database.StateActionPair;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.GameAI.StateTransition; // Ensure this import exists
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.shasankp000.Entity.FaceClosestEntity;
import net.shasankp000.LauncherDetection.LauncherEnvironment;
import net.shasankp000.PlayerUtils.*;
import net.shasankp000.WorldUitls.GetTime;
import net.shasankp000.Entity.EntityDetails;
import net.shasankp000.WorldUitls.isBlockItem;
import net.shasankp000.GameAI.mood.MoodEngine;
import net.shasankp000.GameAI.persona.PersonaRegistry;
import net.shasankp000.GameAI.autonomous.NearbyBedSleepController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.shasankp000.GameAI.State.isStateConsistent;


public class BotEventHandler {
    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    private static MinecraftServer server = null;
    public static ServerPlayer bot = null;
    public static final String qTableDir = LauncherEnvironment.getStorageDirectory("qtable_storage");
    private static final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private static final Object monitorLock = new Object();
    private static boolean isExecuting = false;
    private static final double DEFAULT_RISK_APPETITE = 0.5; // Default value upon respawn
    public static boolean botDied = false; // Flag to track if the bot died
    public static boolean hasRespawned = false; // flag to track if the bot has respawned before or not

    // ForkJoinPool for parallel synchronous computation (uses all CPU cores efficiently)
    private static final java.util.concurrent.ForkJoinPool parallelComputePool =
        new java.util.concurrent.ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2), // Use half of CPU cores
            java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null,
            false // FIFO mode for predictable behavior
        );
    public static int botSpawnCount = 0;
    private static State currentState = null;

    // Action execution tracking - prevents action spam and ensures completion.
    // State + logic owned by ActionTracker (god-object split, step 3); the
    // static methods below are thin delegating wrappers to preserve the API.
    private static final ActionTracker actionTracker = new ActionTracker();

    // State transition tracking for lookahead learning
    private static final StateTransition.TransitionHistory transitionHistory =
        new StateTransition.TransitionHistory(50); // Keep last 50 transitions
    private static State previousState = null;
    private static StateActions.Action previousAction = null;
    private static double previousReward = 0.0;
    private static volatile boolean lastSleepActionSucceeded = false;
    private static volatile FoodConsumptionTool.ConsumptionResult lastFoodConsumption =
            FoodConsumptionTool.ConsumptionResult.notAttempted();
    private static final Map<UUID, Long> lastNightSleepDecision = new HashMap<>();
    private static final long NIGHT_SLEEP_DECISION_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    private static final Map<UUID, Long> lastLowHungerDecision = new HashMap<>();
    private static final long LOW_HUNGER_DECISION_INTERVAL_MS = TimeUnit.SECONDS.toMillis(2);

    // Surface-depth penalty: discourage aimless burrowing below ground level.
    // Only applies beyond this depth below the surface, so normal walking on
    // slightly-undulating terrain isn't punished. Deliberate mining/tasks that
    // bring the bot underground still work — this only shapes the *reward*, it
    // does not block any action.
    private static final int UNDERGROUND_THRESHOLD = 5;        // blocks below surface before penalty kicks in
    private static final double UNDERGROUND_PENALTY_PER_BLOCK = 1.5; // reward lost per block below threshold

    // Singleton RLAgent – lazily created and cached for external callers
    private static RLAgent cachedRLAgent = null;

    // Periodic reflection scheduler
    private static long lastReflectionTime = System.currentTimeMillis();
    private static final long REFLECTION_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5); // Reflect every 5 minutes

    // Deterministic self-defense rate-limit. AutoFaceEntity re-invokes the
    // combat handler every tick (33ms), so without a cooldown the fallback
    // would spam an attack (and a log line) every tick. One attack per this
    // interval is plenty for a reactive self-defense response.
    private static volatile long lastCombatFallbackAt = 0L;
    private static final long COMBAT_FALLBACK_COOLDOWN_MS = 1000L; // 1 second



    public BotEventHandler(MinecraftServer server, ServerPlayer bot) {
        setActiveBot(server, bot);
    }

    /**
     * Records the active AI bot as soon as its lifecycle starts. Survival
     * controllers must not have to wait for a combat event to discover it.
     */
    public static void setActiveBot(MinecraftServer server, ServerPlayer bot) {
        BotEventHandler.server = server;
        BotEventHandler.bot = bot;
    }

    // ── Mood / Persona lifecycle ──────────────────────────────────────────────

    /**
     * Called when a bot despawns (death, /bot despawn, server stop).
     * Cleans up per-bot MoodEngine and PersonaRegistry entries so stale
     * state does not bleed into the next spawn.
     */
    public static void onBotDespawn(String botName) {
        if (bot != null && bot.getName().getString().equals(botName)) {
            synchronized (lastLowHungerDecision) {
                lastLowHungerDecision.remove(bot.getUUID());
            }
        }
        MoodEngine.evict(botName);
        PersonaRegistry.evict(botName);
        LOGGER.info("[mood/persona] Evicted state for bot '{}'", botName);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the shared TransitionHistory used by this handler's RL loop.
     * Callers such as {@code modCommandRegistry} can use this to surface
     * recent state-action transitions for diagnostics or the trade evaluator.
     */
    public static StateTransition.TransitionHistory getTransitionHistory() {
        return transitionHistory;
    }

    /**
     * Returns (or lazily creates) a shared {@link RLAgent} instance.
     * When an active agent is passed into {@link #detectAndReact} its epsilon
     * is kept in sync with this cached copy so external callers always see
     * an up-to-date agent.
     *
     * @return the singleton RLAgent for this handler
     */
    public static RLAgent getRLAgent() {
        if (cachedRLAgent == null) {
            // Try to restore epsilon from disk so training progress is preserved
            double savedEpsilon = 1.0;
            try {
                Double loaded = QTableStorage.loadEpsilon(qTableDir + File.separator + "epsilon.bin");
                if (loaded != null) savedEpsilon = loaded;
            } catch (Exception ignored) { /* first run – start fresh */ }
            cachedRLAgent = new RLAgent(savedEpsilon, null);
        }
        return cachedRLAgent;
    }

    /**
     * Gives the learned policy an opportunity to select {@link StateActions.Action#SLEEP}
     * during a safe night.  This deliberately does not invoke sleeping directly: the
     * action must first be selected by the RL policy and its result is then learned.
     */
    public static void considerNightSleep(RLAgent rlAgentHook, QTable qTable, ServerPlayer candidateBot) {
        if (rlAgentHook == null || qTable == null || candidateBot == null
                || !candidateBot.isAlive() || candidateBot.isSleeping()
                || GetTime.getTimeOfWorld(candidateBot) < 12000) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (lastNightSleepDecision) {
            long previous = lastNightSleepDecision.getOrDefault(candidateBot.getUUID(), 0L);
            if (now - previous < NIGHT_SLEEP_DECISION_INTERVAL_MS) return;
            lastNightSleepDecision.put(candidateBot.getUUID(), now);
        }

        List<Entity> nearby = AutoFaceEntity.detectNearbyEntities(candidateBot, 32);
        boolean hostileNearby = nearby.stream().anyMatch(entity -> entity instanceof Monster
                || (entity instanceof net.minecraft.world.entity.player.Player player
                && !player.getUUID().equals(candidateBot.getUUID())
                && net.shasankp000.PlayerUtils.PlayerRetaliationTracker.isPlayerHostile(candidateBot, player)));
        if (hostileNearby) return;

        State state = createInitialState(candidateBot);
        List<StateActions.Action> candidates = rlAgentHook.suggestPotentialActions(state);
        Map<StateActions.Action, Double> allRisks = rlAgentHook.calculateRisk(state, candidates, candidateBot);
        Map<StateActions.Action, Double> risks = new EnumMap<>(StateActions.Action.class);
        risks.put(StateActions.Action.USE_ITEM,
                allRisks.getOrDefault(StateActions.Action.USE_ITEM, 0.0));
        risks.put(StateActions.Action.STAY,
                allRisks.getOrDefault(StateActions.Action.STAY, 0.0));
        Map<StateActions.Action, Double> choice = rlAgentHook.chooseAction(
                state, rlAgentHook.calculateRiskAppetite(state), risks, transitionHistory);
        Map.Entry<StateActions.Action, Double> selected = choice.entrySet().iterator().next();
        if (selected.getKey() != StateActions.Action.SLEEP) return;

        CommandSourceStack source = candidateBot.createCommandSourceStack()
                .withSuppressedOutput()
                .withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
        executeAction(StateActions.Action.SLEEP, source);

        State nextState = createInitialState(candidateBot);
        double reward = lastSleepActionSucceeded ? 50.0 : -20.0;
        double qValue = rlAgentHook.calculateQValue(
                state, StateActions.Action.SLEEP, reward, nextState, qTable);
        qTable.addEntry(state, StateActions.Action.SLEEP, qValue, nextState);
        transitionHistory.addTransition(new StateTransition(
                state, nextState, StateActions.Action.SLEEP, reward,
                nextState.getPodMap().getOrDefault(StateActions.Action.SLEEP, 0.0), false, -1));
        rlAgentHook.decayEpsilon();
        QTableStorage.saveQTable(qTable, "qtable.bin");
        try {
            QTableStorage.saveEpsilon(rlAgentHook.getEpsilon(),
                    qTableDir + File.separator + "epsilon.bin");
        } catch (IOException e) {
            LOGGER.warn("Could not persist sleep-action epsilon", e);
        }
        LOGGER.info("RL selected SLEEP for '{}': {}", candidateBot.getName().getString(),
                lastSleepActionSucceeded ? "slept" : "could not sleep");
    }

    /** Gives the learned policy an opportunity to select USE_ITEM at low hunger. */
    public static void considerLowHunger(RLAgent rlAgentHook, QTable qTable, ServerPlayer candidateBot) {
        if (rlAgentHook == null || qTable == null || candidateBot == null
                || !candidateBot.isAlive() || candidateBot.isSleeping()
                || candidateBot.getFoodData().getFoodLevel() > 8
                || !FoodConsumptionTool.hasSafeFood(candidateBot)) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (lastLowHungerDecision) {
            long previous = lastLowHungerDecision.getOrDefault(candidateBot.getUUID(), 0L);
            if (now - previous < LOW_HUNGER_DECISION_INTERVAL_MS) return;
            lastLowHungerDecision.put(candidateBot.getUUID(), now);
        }

        State state = createInitialState(candidateBot);
        List<StateActions.Action> candidates = rlAgentHook.suggestPotentialActions(state);
        Map<StateActions.Action, Double> risks = rlAgentHook.calculateRisk(state, candidates, candidateBot);
        Map<StateActions.Action, Double> choice = rlAgentHook.chooseAction(
                state, rlAgentHook.calculateRiskAppetite(state), risks, transitionHistory);
        Map.Entry<StateActions.Action, Double> selected = choice.entrySet().iterator().next();
        StateActions.Action chosenAction = selected.getKey();

        CommandSourceStack source = candidateBot.createCommandSourceStack()
                .withSuppressedOutput()
                .withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
        executeAction(chosenAction, source);

        State nextState = createInitialState(candidateBot);
        double reward = chosenAction == StateActions.Action.USE_ITEM
                ? applyFoodReward(chosenAction, 0.0)
                : -10.0;
        Map<StateActions.Action, Double> podMap = rlAgentHook.assessRiskOutcome(
                state, nextState, chosenAction);
        nextState.setPodMap(podMap);
        double qValue = rlAgentHook.calculateQValue(
                state, chosenAction, reward, nextState, qTable);
        qTable.addEntry(state, chosenAction, qValue, nextState);
        transitionHistory.addTransition(new StateTransition(
                state, nextState, chosenAction, reward,
                nextState.getPodMap().getOrDefault(chosenAction, 0.0), false, -1));
        rlAgentHook.decayEpsilon();
        QTableStorage.saveQTable(qTable, "qtable.bin");
        try {
            QTableStorage.saveEpsilon(rlAgentHook.getEpsilon(),
                    qTableDir + File.separator + "epsilon.bin");
        } catch (IOException e) {
            LOGGER.warn("Could not persist food-action epsilon", e);
        }
        LOGGER.info("RL low-hunger decision for '{}': {} (result={}, reward={})",
                candidateBot.getName().getString(), chosenAction,
                chosenAction == StateActions.Action.USE_ITEM
                        ? lastFoodConsumption.message()
                        : "remained hungry",
                reward);
    }

    /**
     * Handle bot death - learn from the sequence of actions that led to death
     */
    public static void handleBotDeath(QTable qTable, RLAgent rlAgent) {
        LOGGER.info("💀 Bot died - analyzing death sequence for learning...");

        // Keep cached agent in sync
        if (rlAgent != null) cachedRLAgent = rlAgent;

        // Evict mood/persona state on death so the next spawn starts fresh
        if (bot != null) {
            onBotDespawn(bot.getName().getString());
        }

        executor.submit(() -> {
            try {
                LookaheadLearning.learnFromDeath(transitionHistory, qTable, rlAgent);
                QTableStorage.saveQTable(qTable, "qtable.bin");
                LOGGER.info("✓ Death learning complete, Q-table updated");
                LookaheadLearning.cleanupOldTransitions(transitionHistory);
            } catch (Exception e) {
                LOGGER.error("Error during death learning", e);
            }
        });
    }

    /**
     * Perform periodic reflection on past experiences
     */
    private static void performPeriodicReflection(QTable qTable, RLAgent rlAgent) {
        long now = System.currentTimeMillis();
        if (now - lastReflectionTime >= REFLECTION_INTERVAL_MS) {
            LOGGER.info("⏰ Time for periodic reflection...");

            executor.submit(() -> {
                try {
                    LookaheadLearning.periodicReflection(transitionHistory, qTable, rlAgent);
                    QTableStorage.saveQTable(qTable, "qtable.bin");
                    lastReflectionTime = System.currentTimeMillis();
                } catch (Exception e) {
                    LOGGER.error("Error during periodic reflection", e);
                }
            });
        }
    }

    private static State initializeBotState(QTable qTable) {
        State initialState = null;

        if (qTable == null || qTable.getTable().isEmpty()) {
            System.out.println("No initial state available. Q-table is empty.");
        } else {
            System.out.println("Loaded Q-table: Total state-action pairs = " + qTable.getTable().size());

            // Get the most recent state from the Q-table
            StateActionPair recentPair = qTable.getTable().keySet().iterator().next();
            initialState = recentPair.getState();

            System.out.println("Setting initial state to: " + initialState);
        }

        return initialState;
    }

    public void detectAndReact(RLAgent rlAgentHook, double distanceToHostileEntity, QTable qTable) throws IOException {
        // Keep cached agent in sync with whatever the caller passes in
        if (rlAgentHook != null) cachedRLAgent = rlAgentHook;

        synchronized (monitorLock) {
            if (isExecuting) {
                System.out.println("Executing detection code - already processing threat");
                return; // Skip if already executing
            }
            isExecuting = true;
        }

        try {
            CommandSourceStack botSource = bot.createCommandSourceStack().withSuppressedOutput().withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);

            System.out.println("Distance from danger zone: " + DangerZoneDetector.detectDangerZone(bot, 10, 10 , 10) + " blocks");

            List<Entity> nearbyEntities = AutoFaceEntity.detectNearbyEntities(bot, 32); // Increased detection range for better awareness
            List<Entity> hostileEntities = nearbyEntities.stream()
                    .filter(entity -> {
                        // Include HostileEntity mobs
                        if (entity instanceof Monster) {
                            return true;
                        }
                        // Include hostile players tracked by retaliation system
                        if (entity instanceof net.minecraft.world.entity.player.Player player &&
                            !player.getUUID().equals(bot.getUUID())) {
                            return net.shasankp000.PlayerUtils.PlayerRetaliationTracker.isPlayerHostile(bot, player);
                        }
                        return false;
                    })
                    .toList();


            BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);

            List<String> nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

            boolean hasSculkNearby = nearbyBlocks.stream()
                    .anyMatch(block -> block.contains("Sculk Sensor") || block.contains("Sculk Shrieker"));
            System.out.println("Nearby blocks: " + nearbyBlocks);

            int timeofDay = GetTime.getTimeOfWorld(bot);
            String time = (timeofDay >= 12000 && timeofDay < 24000) ? "night" : "day";

            Level world = bot.createCommandSourceStack().withSuppressedOutput().withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS).getLevel();
            ResourceKey<Level> dimType = world.dimension();
            String dimension = dimType.identifier().toString();

            if (!hostileEntities.isEmpty()) {
                List<EntityDetails> nearbyEntitiesList = new ArrayList<>();
                for (Entity entity : nearbyEntities) {
                    String directionToBot = AutoFaceEntity.determineDirectionToBot(bot, entity);
                    nearbyEntitiesList.add(new EntityDetails(
                            entity.getName().getString(),
                            entity.getX(),
                            entity.getY(),
                            entity.getZ(),
                            entity instanceof Monster,
                            directionToBot
                    ));
                }

                State currentState;

                if (hasRespawned && botDied) {
                    State lastKnownState = QTableStorage.loadLastKnownState(qTableDir + File.separator + "lastKnownState.bin");
                    currentState = createInitialState(bot);
                    BotEventHandler.botDied = false;

                    if (isStateConsistent(lastKnownState, currentState)) {
                        System.out.println("Merged values from last known state.");
                        currentState.setRiskMap(lastKnownState.getRiskMap());
                        currentState.setPodMap(lastKnownState.getPodMap());
                    }
                } else {
                    currentState = initializeBotState(qTable);

                    System.out.println("Created initial state");
                }

                if (botSpawnCount == 0) {
                    currentState = createInitialState(bot);
                }

                double riskAppetite = rlAgentHook.calculateRiskAppetite(currentState);
                List<StateActions.Action> potentialActionList = rlAgentHook.suggestPotentialActions(currentState);
                Map<StateActions.Action, Double> riskMap = rlAgentHook.calculateRisk(currentState, potentialActionList, bot);

                Map<StateActions.Action, Double> chosenActionMap =
                        rlAgentHook.chooseAction(currentState, riskAppetite, riskMap, transitionHistory);
                Map.Entry<StateActions.Action, Double> entry = chosenActionMap.entrySet().iterator().next();

                StateActions.Action chosenAction = entry.getKey();
                double risk = entry.getValue();

                System.out.println("Chosen action: " + chosenAction);

                executeAction(chosenAction, botSource);


                List<ItemStack> hotBarItems = hotBarUtils.getHotbarItems(bot);
                SelectedItemDetails selectedItem = new SelectedItemDetails(
                        hotBarUtils.getSelectedHotbarItemStack(bot).getHoverName().getString(),
                        hotBarUtils.getSelectedHotbarItemStack(bot).getComponents().has(DataComponents.FOOD), // as per 1.20.6 changes.
                        isBlockItem.checkBlockItem(hotBarUtils.getSelectedHotbarItemStack(bot))
                );

                double dangerDistance = DangerZoneDetector.detectDangerZone(bot, 10, 5, 5);
                int botHungerLevel = getPlayerHunger.getBotHungerLevel(bot);
                int botOxygenLevel = getPlayerOxygen.getBotOxygenLevel(bot);
                int botFrostLevel = getFrostLevel.calculateFrostLevel(bot);
                Map<String, ItemStack> armorItems = getArmorStack.getArmorItems(bot);
                ItemStack offhandItem = getOffHandStack.getOffhandItem(bot);

                State nextState = new State(
                        (int) bot.getX(),
                        (int) bot.getY(),
                        (int) bot.getZ(),
                        nearbyEntitiesList,
                        nearbyBlocks,
                        distanceToHostileEntity,
                        (int) bot.getHealth(),
                        dangerDistance,
                        hotBarItems,
                        selectedItem,
                        time,
                        dimension,
                        botHungerLevel,
                        botOxygenLevel,
                        botFrostLevel,
                        offhandItem,
                        armorItems,
                        chosenAction,
                        riskMap,
                        riskAppetite,
                        currentState.getPodMap()
                );

                // Log if bot is in a dangerous structure
                if (nextState.isInDangerousStructure()) {
                    System.out.println("WARNING: Bot is in a dangerous structure (Trial Chamber/Dungeon/Nether Fortress/Bastion)!");
                    System.out.println("Structure risk modifier applied: +20.0 to all action risks");
                }

                rlAgentHook.decayEpsilon();
                Map<StateActions.Action, Double> actionPodMap = rlAgentHook.assessRiskOutcome(currentState, nextState, chosenAction);
                nextState.setPodMap(actionPodMap);

                double reward = rlAgentHook.calculateReward(
                        (int) bot.getX(),
                        (int) bot.getY(),
                        (int) bot.getZ(),
                        nearbyEntitiesList,
                        nearbyBlocks,
                        distanceToHostileEntity,
                        (int) bot.getHealth(),
                        dangerDistance,
                        hotBarItems,
                        selectedItem.getName(),
                        time,
                        dimension,
                        botHungerLevel,
                        botOxygenLevel,
                        offhandItem,
                        armorItems,
                        chosenAction,
                        risk,
                        actionPodMap.getOrDefault(chosenAction, 0.0)
                );

                reward = applySleepReward(chosenAction, reward);
                reward = applyFoodReward(chosenAction, reward);
                reward = applySurfaceDepthPenalty(bot, reward);

                System.out.println("Reward: " + reward);

                // Check for death risk patterns before updating Q-value
                double deathRiskPenalty = LookaheadLearning.analyzeDeathRisk(currentState, chosenAction, transitionHistory);
                double adjustedReward = reward - deathRiskPenalty;

                double qValue = rlAgentHook.calculateQValue(currentState, chosenAction, adjustedReward, nextState, qTable);
                qTable.addEntry(currentState, chosenAction, qValue, nextState);

                // Record the transition for future learning
                double podValue = actionPodMap.getOrDefault(chosenAction, 0.0);
                StateTransition transition = new StateTransition(
                    currentState,
                    nextState,
                    chosenAction,
                    adjustedReward,
                    podValue,
                    false, // Will be marked true if death occurs
                    -1
                );
                transitionHistory.addTransition(transition);

                // Periodic reflection check
                performPeriodicReflection(qTable, rlAgentHook);

                QTableStorage.saveQTable(qTable, "qtable.bin");
                QTableStorage.saveEpsilon(rlAgentHook.getEpsilon(), qTableDir + File.separator + "epsilon.bin");

                BotEventHandler.currentState = nextState;
                previousState = currentState;
                previousAction = chosenAction;
                previousReward = adjustedReward;

            } else if ((DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) <= 5.0 && DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) > 0.0) || hasSculkNearby) {
                System.out.println("Danger zone detected within 5 blocks");

                System.out.println("Triggered handler for danger zone case.");

                List<EntityDetails> nearbyEntitiesList = new ArrayList<>();
                for (Entity entity : nearbyEntities) {
                    String directionToBot = AutoFaceEntity.determineDirectionToBot(bot, entity);
                    nearbyEntitiesList.add(new EntityDetails(
                            entity.getName().getString(),
                            entity.getX(),
                            entity.getY(),
                            entity.getZ(),
                            entity instanceof Monster,
                            directionToBot
                    ));
                }

                State currentState;

                if (hasRespawned && botDied) {
                    State lastKnownState = QTableStorage.loadLastKnownState(qTableDir + File.separator + "lastKnownState.bin");
                    currentState = createInitialState(bot);
                    BotEventHandler.botDied = false;

                    if (isStateConsistent(lastKnownState, currentState)) {
                        System.out.println("Merged values from last known state.");
                        currentState.setRiskMap(lastKnownState.getRiskMap());
                        currentState.setPodMap(lastKnownState.getPodMap());
                    }
                } else {
                    currentState = initializeBotState(qTable);
                }

                if (botSpawnCount == 0) {
                    currentState = createInitialState(bot);
                }

                double riskAppetite = rlAgentHook.calculateRiskAppetite(currentState);
                List<StateActions.Action> potentialActionList = rlAgentHook.suggestPotentialActions(currentState);
                Map<StateActions.Action, Double> riskMap = rlAgentHook.calculateRisk(currentState, potentialActionList, bot);

                Map<StateActions.Action, Double> chosenActionMap = rlAgentHook.chooseAction(currentState, riskAppetite, riskMap, transitionHistory);
                Map.Entry<StateActions.Action, Double> entry = chosenActionMap.entrySet().iterator().next();

                StateActions.Action chosenAction = entry.getKey();
                double risk = entry.getValue();

                System.out.println("Chosen action: " + chosenAction);

                executeAction(chosenAction, botSource);

                nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

                List<ItemStack> hotBarItems = hotBarUtils.getHotbarItems(bot);
                SelectedItemDetails selectedItem = new SelectedItemDetails(
                        hotBarUtils.getSelectedHotbarItemStack(bot).getHoverName().getString(),
                        hotBarUtils.getSelectedHotbarItemStack(bot).getComponents().has(DataComponents.FOOD), // as per 1.20.6 changes.,
                        isBlockItem.checkBlockItem(hotBarUtils.getSelectedHotbarItemStack(bot))
                );

                double dangerDistance = DangerZoneDetector.detectDangerZone(bot, 10, 5, 5);
                int botHungerLevel = getPlayerHunger.getBotHungerLevel(bot);
                int botOxygenLevel = getPlayerOxygen.getBotOxygenLevel(bot);
                int botFrostLevel = getFrostLevel.calculateFrostLevel(bot);
                Map<String, ItemStack> armorItems = getArmorStack.getArmorItems(bot);
                ItemStack offhandItem = getOffHandStack.getOffhandItem(bot);

                State nextState = new State(
                        (int) bot.getX(),
                        (int) bot.getY(),
                        (int) bot.getZ(),
                        nearbyEntitiesList,
                        nearbyBlocks,
                        distanceToHostileEntity,
                        (int) bot.getHealth(),
                        dangerDistance,
                        hotBarItems,
                        selectedItem,
                        time,
                        dimension,
                        botHungerLevel,
                        botOxygenLevel,
                        botFrostLevel,
                        offhandItem,
                        armorItems,
                        chosenAction,
                        riskMap,
                        riskAppetite,
                        currentState.getPodMap()
                );

                // Log if bot is in a dangerous structure
                if (nextState.isInDangerousStructure()) {
                    System.out.println("WARNING: Bot is in a dangerous structure (Trial Chamber/Dungeon/Nether Fortress/Bastion)!");
                    System.out.println("Structure risk modifier applied: +20.0 to all action risks");
                }

                rlAgentHook.decayEpsilon();
                Map<StateActions.Action, Double> actionPodMap = rlAgentHook.assessRiskOutcome(currentState, nextState, chosenAction);
                nextState.setPodMap(actionPodMap);

                double reward = rlAgentHook.calculateReward(
                        (int) bot.getX(),
                        (int) bot.getY(),
                        (int) bot.getZ(),
                        nearbyEntitiesList,
                        nearbyBlocks,
                        distanceToHostileEntity,
                        (int) bot.getHealth(),
                        dangerDistance,
                        hotBarItems,
                        selectedItem.getName(),
                        time,
                        dimension,
                        botHungerLevel,
                        botOxygenLevel,
                        offhandItem,
                        armorItems,
                        chosenAction,
                        risk,
                        actionPodMap.getOrDefault(chosenAction, 0.0)
                );

                reward = applySleepReward(chosenAction, reward);
                reward = applyFoodReward(chosenAction, reward);
                reward = applySurfaceDepthPenalty(bot, reward);

                System.out.println("Reward: " + reward);

                // Check for death risk patterns before updating Q-value (danger zone case)
                double deathRiskPenalty = LookaheadLearning.analyzeDeathRisk(currentState, chosenAction, transitionHistory);
                if (deathRiskPenalty > 0) {
                    reward -= deathRiskPenalty; // Apply risk penalty from past death patterns
                    System.out.println("Applied death risk penalty: -" + String.format("%.1f", deathRiskPenalty));
                }

                double qValue = rlAgentHook.calculateQValue(currentState, chosenAction, reward, nextState, qTable);
                qTable.addEntry(currentState, chosenAction, qValue, nextState);

                // Record state transition for lookahead learning (danger zone case)
                StateTransition transition = new StateTransition(
                    currentState,
                    nextState,
                    chosenAction,
                    reward,
                    actionPodMap.getOrDefault(chosenAction, 0.0),
                    false, // Not known to lead to death yet
                    -1
                );
                transitionHistory.addTransition(transition);

                // Store for next iteration
                previousState = currentState;
                previousAction = chosenAction;
                previousReward = reward;

                QTableStorage.saveQTable(qTable, "qtable.bin");
                QTableStorage.saveEpsilon(rlAgentHook.getEpsilon(), qTableDir + File.separator + "epsilon.bin");

                // Perform periodic reflection if needed
                performPeriodicReflection(qTable, rlAgentHook);

                BotEventHandler.currentState = nextState;
            }


        } finally {
            // ── Mood decay: one tick per RL loop iteration ────────────────────────
            if (bot != null) {
                MoodEngine.decayTick(bot.getName().getString());
            }
            // ─────────────────────────────────────────────────────────────────────

            // ⏸ Wait for any ongoing action to complete before next RL loop iteration
            String botName = bot.getName().getString();
            if (isActionInProgress(botName)) {
                LOGGER.info("[RL-LOOP] Waiting for action '{}' to complete...", actionTracker.getCurrentAction(botName));
                waitForActionCompletion(botName, 3000); // Wait up to 3 seconds
            }

            // Small cooldown between RL decisions to prevent action spam
            try {
                Thread.sleep(200); // 200ms cooldown
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            synchronized (monitorLock) {
                isExecuting = false;
                AutoFaceEntity.isHandlerTriggered = false;
            }
        }
    }


    public static State getCurrentState() {
        // Lazily build the game State when the static field is null. In play
        // mode the RL loop that populates `currentState` may never run, so
        // callers (planner, FunctionCaller, etc.) would otherwise get a null
        // State and NPE. This mirrors the previously applied bytecode patch.
        State state = BotEventHandler.currentState;
        if (state == null && BotEventHandler.bot != null) {
            state = createInitialState(BotEventHandler.bot);
            BotEventHandler.currentState = state;
        }
        return state;
    }

    public void detectAndReactPlayMode(RLAgent rlAgentHook, QTable qTable) {
        // Keep cached agent in sync
        if (rlAgentHook != null) cachedRLAgent = rlAgentHook;

        synchronized (monitorLock) {
            if (isExecuting) {
                System.out.println("Already executing detection code, skipping...");
                return; // Skip if already executing
            }
            isExecuting = true;
        }

        try {
            CommandSourceStack botSource = bot.createCommandSourceStack().withSuppressedOutput().withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);


            if (qTable == null) {
                // No Q-table at all: skip the (empty) RL path and fight back
                // deterministically so the bot is never a passive target.
                attackFallbackRateLimited(bot, "No Q-table");
            }

            else {
                // Detect nearby hostile entities (including hostile players)
                List<Entity> nearbyEntities = AutoFaceEntity.detectNearbyEntities(bot, 32); // Increased detection range for better awareness
                List<Entity> hostileEntities = nearbyEntities.stream()
                        .filter(entity -> {
                            // Include HostileEntity mobs
                            if (entity instanceof Monster) {
                                return true;
                            }
                            // Include hostile players tracked by retaliation system
                            if (entity instanceof net.minecraft.world.entity.player.Player player &&
                                !player.getUUID().equals(bot.getUUID())) {
                                return net.shasankp000.PlayerUtils.PlayerRetaliationTracker.isPlayerHostile(bot, player);
                            }
                            return false;
                        })
                        .toList();

                if (!hostileEntities.isEmpty()) {
                    // Gather state information
                    State currentState = createInitialState(bot);

                    Map<StateActions.Action, Double> riskMap = currentState.getRiskMap();

                    // Choose action via the trained policy.
                    StateActions.Action chosenAction = rlAgentHook.chooseActionPlayMode(currentState, qTable, riskMap, "detectAndReactPlayMode", transitionHistory);

                    // If the Q-table is empty the policy degenerates to STAY
                    // ("No viable actions available. Defaulting to STAY"), which
                    // makes the bot a passive target. Fall back to the
                    // deterministic combat primitive so an untrained bot still
                    // defends itself. A trained policy that picks a real action
                    // (ATTACK / EVADE / SHOOT_ARROW / SPRINT…) is left untouched.
                    if (qTable.getTable().isEmpty() || chosenAction == StateActions.Action.STAY) {
                        attackFallbackRateLimited(bot, "Q-table empty or policy chose STAY");
                    } else {
                        // Log chosen action for debugging
                        LOGGER.debug("Play Mode - Chosen action: {}", chosenAction);
                        executeAction(chosenAction, botSource);
                    }
                }
                else if (DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) <= 5.0 && DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) > 0.0) {

                    // Gather state information
                    State currentState = createInitialState(bot);

                    Map<StateActions.Action, Double> riskMap = currentState.getRiskMap();


                    // Choose action
                    StateActions.Action chosenAction = rlAgentHook.chooseActionPlayMode(currentState, qTable, riskMap, "detectAndReactPlayMode", transitionHistory);


                    // Log chosen action for debugging
                    LOGGER.debug("Play Mode - Chosen action: {}", chosenAction);

                    // Execute action
                    executeAction(chosenAction, botSource);
                }


            }
        } finally {
            // ── Mood decay: one tick per RL loop iteration ────────────────────────
            if (bot != null) {
                MoodEngine.decayTick(bot.getName().getString());
            }
            // ─────────────────────────────────────────────────────────────────────

            synchronized (monitorLock) {
                isExecuting = false;
                AutoFaceEntity.isHandlerTriggered = false; // Reset the trigger flag
            }
        }
    }

    /**
     * Rate-limited deterministic self-defense attack.
     *
     * <p>AutoFaceEntity re-invokes the combat handler every tick, so an
     * unthrottled fallback would attack (and log) every ~33ms. This wrapper
     * enforces a 1s cooldown between fallback attacks while keeping the
     * "never passive" guarantee. It only logs when a real attack is performed;
     * the "nothing within reach" case is silent to avoid re-spamming the log.
     */
    private static void attackFallbackRateLimited(ServerPlayer bot, String reason) {
        long now = System.currentTimeMillis();
        long last = lastCombatFallbackAt;
        if (now - last < COMBAT_FALLBACK_COOLDOWN_MS) {
            return; // within cooldown — skip silently
        }
        lastCombatFallbackAt = now;

        String result = CombatTool.attackNearestHostileBlocking(bot, 8.0);
        // Only log a real attack; "No hostile mobs within N blocks" means the
        // detected hostile was beyond melee reach, which is not worth a log
        // line every second.
        if (result != null && result.startsWith("Attacked")) {
            LOGGER.info("[combat] {} — {}", reason, result);
        }
    }

    private static void executeAction(StateActions.Action chosenAction, CommandSourceStack botSource) {
        lastSleepActionSucceeded = false;
        lastFoodConsumption = FoodConsumptionTool.ConsumptionResult.notAttempted();
        switch (chosenAction) {
            case MOVE_FORWARD -> performAction("moveForward", botSource);
            case MOVE_BACKWARD -> performAction("moveBackward", botSource);
            case TURN_LEFT -> performAction("turnLeft", botSource);
            case TURN_RIGHT -> performAction("turnRight", botSource);
            case JUMP -> performAction("jump", botSource);
            case SNEAK -> performAction("sneak", botSource);
            case SPRINT -> performAction("sprint", botSource);
            case STOP_SNEAKING -> performAction("unsneak", botSource);
            case STOP_SPRINTING -> performAction("unsprint", botSource);
            case STOP_MOVING -> performAction("stopMoving", botSource);
            case USE_ITEM -> {
                ServerPlayer actingBot = botSource.getPlayer();
                if (actingBot != null
                        && actingBot.getFoodData().getFoodLevel() <= 13
                        && FoodConsumptionTool.hasSafeFood(actingBot)) {
                    lastFoodConsumption = FoodConsumptionTool.consumeBestFood(actingBot);
                    LOGGER.info("RL food action for '{}': {}", botSource.getTextName(),
                            lastFoodConsumption.message());
                } else {
                    performAction("useItem", botSource);
                }
            }
            case EQUIP_ARMOR -> armorUtils.autoEquipArmor(bot);
            case ATTACK -> performAction("attack", botSource);
            case SHOOT_ARROW -> performAction("shootArrow", botSource);
            case EVADE -> performAction("evade", botSource);
            case SLEEP -> {
                lastSleepActionSucceeded = NearbyBedSleepController.attemptFromRl(botSource.getPlayer());
                LOGGER.info("RL sleep action for '{}' {}", botSource.getTextName(),
                        lastSleepActionSucceeded ? "succeeded" : "did not find a usable bed");
            }
            case HOTBAR_1 -> performAction("hotbar1", botSource);
            case HOTBAR_2 -> performAction("hotbar2", botSource);
            case HOTBAR_3 -> performAction("hotbar3", botSource);
            case HOTBAR_4 -> performAction("hotbar4", botSource);
            case HOTBAR_5 -> performAction("hotbar5", botSource);
            case HOTBAR_6 -> performAction("hotbar6", botSource);
            case HOTBAR_7 -> performAction("hotbar7", botSource);
            case HOTBAR_8 -> performAction("hotbar8", botSource);
            case HOTBAR_9 -> performAction("hotbar9", botSource);
            case STAY -> System.out.println("Performing action: Stay and do nothing");
        }
    }

    private static double applySleepReward(StateActions.Action action, double reward) {
        if (action != StateActions.Action.SLEEP) return reward;
        return reward + (lastSleepActionSucceeded ? 50.0 : -20.0);
    }

    private static double applyFoodReward(StateActions.Action action, double reward) {
        if (action != StateActions.Action.USE_ITEM) return reward;
        if (!lastFoodConsumption.attempted()) return reward;
        if (!lastFoodConsumption.success()) return reward - 15.0;
        return reward + 20.0 + (lastFoodConsumption.hungerGained() * 4.0);
    }

    /**
     * Penalize the bot for going deep underground on its own.
     *
     * <p>Returns the reward unchanged when the bot is at/near the surface
     * (within {@value #UNDERGROUND_THRESHOLD} blocks below the highest solid
     * block at its XZ column), or when the depth is part of a legitimate
     * goal context. Otherwise subtracts a depth-proportional penalty, so the
     * bot learns that aimless burrowing is undesirable but deliberate
     * mining/tasks are not discouraged.
     *
     * <p>The surface reference uses {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES},
     * which tracks the top solid (non-leaf) block — a stable "ground level"
     * unaffected by tree canopies.
     */
    private static double applySurfaceDepthPenalty(ServerPlayer bot, double reward) {
        if (bot == null || bot.level() == null || bot.level().isClientSide()) {
            return reward;
        }
        int botY = bot.blockPosition().getY();
        int surfaceY = bot.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                bot.blockPosition().getX(),
                bot.blockPosition().getZ());

        int depth = surfaceY - botY;
        if (depth <= UNDERGROUND_THRESHOLD) {
            return reward; // at/near surface — no penalty
        }

        // Progressive penalty: shallow dips are cheap, deep burrows are not.
        double penalty = UNDERGROUND_PENALTY_PER_BLOCK * (depth - UNDERGROUND_THRESHOLD);
        LOGGER.debug("[reward] Surface-depth penalty: botY={}, surfaceY={}, depth={}, penalty={}",
                botY, surfaceY, depth, penalty);
        return reward - penalty;
    }


    public static State createInitialState(ServerPlayer bot) {
        List<ItemStack> hotBarItems = hotBarUtils.getHotbarItems(bot);
        ItemStack selectedItemStack = hotBarUtils.getSelectedHotbarItemStack(bot);

        BlockDistanceLimitedSearch blockDistanceLimitedSearch = new BlockDistanceLimitedSearch(bot, 3, 5);

        List<String> nearbyBlocks = blockDistanceLimitedSearch.detectNearbyBlocks();

        SelectedItemDetails selectedItem = new SelectedItemDetails(
                selectedItemStack.getHoverName().getString(),
                selectedItemStack.getComponents().has(DataComponents.FOOD),
                isBlockItem.checkBlockItem(selectedItemStack)
        );

        List<Entity> nearbyEntities = AutoFaceEntity.detectNearbyEntities(bot, 32);

        List<EntityDetails> nearbyEntitiesList = new ArrayList<>();

        String directionToBot;

        for(Entity entity: nearbyEntities) {

            directionToBot = AutoFaceEntity.determineDirectionToBot(bot, entity);

            // Determine if entity is hostile (either HostileEntity mob or hostile player)
            boolean isHostile = entity instanceof Monster;
            if (entity instanceof net.minecraft.world.entity.player.Player player &&
                !player.getUUID().equals(bot.getUUID())) {
                isHostile = net.shasankp000.PlayerUtils.PlayerRetaliationTracker.isPlayerHostile(bot, player);
            }

            nearbyEntitiesList.add(new EntityDetails(
                    entity.getName().getString(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    isHostile,
                    directionToBot
            ));

        }

        double dangerDistance = DangerZoneDetector.detectDangerZone(bot, 10, 5, 5);
        int botHungerLevel = getPlayerHunger.getBotHungerLevel(bot);
        int botOxygenLevel = getPlayerOxygen.getBotOxygenLevel(bot);
        int botFrostLevel = getFrostLevel.calculateFrostLevel(bot);
        Map<String, ItemStack> armorItems = getArmorStack.getArmorItems(bot);
        ItemStack offhandItem = getOffHandStack.getOffhandItem(bot);
        String time = GetTime.getTimeOfWorld(bot) >= 12000 ? "night" : "day";
        String dimension = bot.createCommandSourceStack().getLevel().dimension().identifier().toString();
        Map<StateActions.Action, Double> riskMap = new HashMap<>();

        Map<StateActions.Action, Double> podMap = new HashMap<>(); // blank pod map for now.

        State initialState = new State(
                (int) bot.getX(),
                (int) bot.getY(),
                (int) bot.getZ(),
                nearbyEntitiesList,
                nearbyBlocks,
                0.0, // Distance to hostile can be updated dynamically elsewhere
                (int) bot.getHealth(),
                dangerDistance,
                hotBarItems,
                selectedItem,
                time,
                dimension,
                botHungerLevel,
                botOxygenLevel,
                botFrostLevel,
                offhandItem,
                armorItems,
                StateActions.Action.STAY,
                riskMap,
                DEFAULT_RISK_APPETITE,
                podMap
        );

        // Log if bot is in a dangerous structure during initial state creation
        if (initialState.isInDangerousStructure()) {
            LOGGER.info("Bot spawned/initialized in a dangerous structure! Extra caution advised.");
        }

        return initialState;
    }


    private static void performAction(String action, CommandSourceStack botSource) {

        String botName = botSource.getTextName();


        switch (action) {
            case "moveForward":
                System.out.println("Performing action: move forward");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " move forward");
                AutoFaceEntity.isBotMoving = true;
                break;
            case "moveBackward":
                System.out.println("Performing action: move backward");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " move backward");
                AutoFaceEntity.isBotMoving = true;
                break;
            case "turnLeft":
                System.out.println("Performing action: turn left");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " turn left");
                break;
            case "turnRight":
                System.out.println("Performing action: turn right");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " turn right");
                break;
            case "jump":
                System.out.println("Performing action: jump");
                bot.jumpFromGround();
                break;
            case "sneak":
                System.out.println("Performing action: sneak");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " sneak");
                break;
            case "sprint":
                System.out.println("Performing action: sprint");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " sprint");
                break;
            case "unsneak":
                System.out.println("Performing action: unsneak");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " unsneak");
                break;
            case "unsprint":
                System.out.println("Performing action: unsprint");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " unsprint");
                break;
            case "stopMoving":
                System.out.println("Performing action: stop moving");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " stop");
                AutoFaceEntity.isBotMoving = false;
                break;
            case "useItem":
                System.out.println("Performing action: use currently selected item");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " use");
                break;
            case "attack":
                System.out.println("Performing action: ATTACK (intelligent combat)");

                // ⏸ BLOCK if action in progress
                if (isActionInProgress(botName)) {
                    System.out.println("❌ ATTACK blocked - another action in progress: " + actionTracker.getCurrentAction(botName));
                    break;
                }

                startAction(botName, "ATTACK");

                // Find highest threat hostile entity (not just closest!)
                if (AutoFaceEntity.hostileEntities == null || AutoFaceEntity.hostileEntities.isEmpty()) {
                    System.out.println("No hostile entities to attack");
                    completeAction(botName);
                    break;
                }

                // ✨ INTELLIGENT TARGETING: Prioritize high-threat entities (e.g., Creeper > Zombie)
                Entity attackTarget = ThreatEvaluator.selectHighestThreatTarget(bot, AutoFaceEntity.hostileEntities);

                if (attackTarget == null) {
                    System.out.println("Could not find attack target");
                    completeAction(botName);
                    break;
                }

                double distanceToTarget = Math.sqrt(attackTarget.distanceToSqr(bot));
                boolean hasRangedWeapon = RangedWeaponUtils.hasBowOrCrossbow(bot);
                boolean hasAmmo = RangedWeaponUtils.hasArrows(bot);

                System.out.println("Target: " + attackTarget.getName().getString() +
                                 " at " + String.format("%.1f", distanceToTarget) + "m");
                System.out.println("Ranged weapon: " + hasRangedWeapon + ", Ammo: " + hasAmmo);

                // Decision logic: Use ranged if available and target is far, otherwise melee
                if (hasRangedWeapon && hasAmmo && distanceToTarget > 4.0) {
                    // RANGED ATTACK STRATEGY
                    System.out.println("Using RANGED attack (distance > 4m)");

                    // Execute shooting command synchronously
                    server.getCommands().performPrefixedCommand(botSource, "/bot shoot_arrow " + botName + " false");

                    // Wait for shoot to complete (with timeout)
                    waitForActionCompletion(botName, 3000); // 3 second max wait
                } else {
                    // MELEE ATTACK STRATEGY
                    System.out.println("Using MELEE attack (close range or no ranged weapon)");

                    // ⚔ AUTO-EQUIP BEST MELEE WEAPON (if not already holding one)
                    boolean weaponEquipped = net.shasankp000.PlayerUtils.WeaponUtils.equipBestMeleeWeapon(bot);
                    if (weaponEquipped) {
                        System.out.println("✓ Best melee weapon equipped for combat");
                    } else {
                        System.out.println("⚠ No melee weapon found, attacking with current item");
                    }

                    FaceClosestEntity.faceClosestEntity(bot, AutoFaceEntity.hostileEntities);
                    server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " attack");

                    // Melee completes instantly
                    completeAction(botName);
                }
                break;
            case "shootArrow":
                System.out.println("Performing action: SHOOT_ARROW");

                // ⏸ BLOCK if action in progress
                if (isActionInProgress(botName)) {
                    System.out.println("❌ SHOOT_ARROW blocked - another action in progress: " + actionTracker.getCurrentAction(botName));
                    break;
                }

                startAction(botName, "SHOOT_ARROW");
                server.getCommands().performPrefixedCommand(botSource, "/bot shoot_arrow " + botName + " false");

                // Wait for action completion
                waitForActionCompletion(botName, 3000);
                break;

            case "hotbar1":
                System.out.println("Performing action: Select hotbar slot 1");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 1");
                break;
            case "hotbar2":
                System.out.println("Performing action: Select hotbar slot 2");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 2");
                break;
            case "hotbar3":
                System.out.println("Performing action: Select hotbar slot 3");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 3");
                break;
            case "hotbar4":
                System.out.println("Performing action: Select hotbar slot 4");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 4");
                break;
            case "hotbar5":
                System.out.println("Performing action: Select hotbar slot 5");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 5");
                break;
            case "hotbar6":
                System.out.println("Performing action: Select hotbar slot 6");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 6");
                break;
            case "hotbar7":
                System.out.println("Performing action: Select hotbar slot 7");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 7");
                break;
            case "hotbar8":
                System.out.println("Performing action: Select hotbar slot 8");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 8");
                break;
            case "hotbar9":
                System.out.println("Performing action: Select hotbar slot 9");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 9");
                break;

            case "evade":
                System.out.println("Performing action: EVADE");

                // ⏸ BLOCK if action in progress
                if (isActionInProgress(botName)) {
                    System.out.println("❌ EVADE blocked - another action in progress: " + actionTracker.getCurrentAction(botName));
                    break;
                }

                startAction(botName, "EVADE");

                // Find nearest hostile entity to evade from
                List<Entity> nearbyHostiles = AutoFaceEntity.detectNearbyEntities(bot, 20.0).stream()
                    .filter(e -> e instanceof Monster || e instanceof Slime)
                    .toList();

                if (!nearbyHostiles.isEmpty()) {
                    // PRIORITY 1: Check for dangerous creepers first (critical/ignited phase)
                    net.minecraft.world.entity.monster.Creeper dangerousCreeper =
                        net.shasankp000.PlayerUtils.MobThreatEvaluator.getMostDangerousCreeper(nearbyHostiles, bot);

                    Entity closestThreat;
                    if (dangerousCreeper != null) {
                        // Prioritize creeper threat (ignited or critical phase)
                        closestThreat = dangerousCreeper;
                        double distance = Math.sqrt(dangerousCreeper.distanceToSqr(bot));
                        LOGGER.warn("🧨 Prioritizing dangerous CREEPER for evasion at {}m",
                            String.format("%.1f", distance));
                    } else {
                        // No critical creeper - find closest threat normally
                        closestThreat = nearbyHostiles.stream()
                            .min(Comparator.comparingDouble(e -> e.distanceToSqr(bot)))
                            .orElse(null);
                    }

                    if (closestThreat != null) {
                        double distance = Math.sqrt(closestThreat.distanceToSqr(bot));
                        LOGGER.info("⚠ Evading from {} at {}m",
                            closestThreat.getName().getString(),
                            String.format("%.1f", distance));

                        // Check if threat is using ranged weapon and bot has shield
                        boolean isRangedThreat = false;

                        // Check for ranged mobs
                        if (closestThreat instanceof net.minecraft.world.entity.monster.skeleton.Skeleton ||
                            closestThreat instanceof net.minecraft.world.entity.monster.skeleton.WitherSkeleton ||
                            closestThreat instanceof net.minecraft.world.entity.monster.skeleton.Stray ||
                            closestThreat instanceof net.minecraft.world.entity.monster.illager.Pillager) {
                            isRangedThreat = true;
                        }

                        // Check for hostile players with ranged weapons
                        if (closestThreat instanceof net.minecraft.world.entity.player.Player player) {
                            net.minecraft.world.item.ItemStack mainHand = player.getMainHandItem();
                            net.minecraft.world.item.ItemStack activeItem = player.getUseItem();
                            String mainHandId = mainHand.isEmpty() ? "" :
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString();
                            String activeItemId = activeItem.isEmpty() ? "" :
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(activeItem.getItem()).toString();

                            if (mainHandId.contains("bow") || mainHandId.contains("crossbow") ||
                                activeItemId.contains("bow") || activeItemId.contains("crossbow")) {
                                isRangedThreat = true;
                                LOGGER.info("🎯 Hostile player {} has ranged weapon - shield defense available",
                                    player.getName().getString());
                            }
                        }

                        boolean hasShield = ProjectileDefenseUtils.hasShield(bot);
                        boolean isCloseRange = distance <= 8.0;

                        if (isRangedThreat && hasShield && isCloseRange) {
                          // Shield blocking strategy for ranged threats
                          LOGGER.info("🛡 Ranged threat detected - attempting shield block");

                          // Equip shield if not already equipped
                          if (!ProjectileDefenseUtils.hasShieldEquipped(bot)) {
                            LOGGER.info("Equipping shield from inventory...");
                            boolean equipped = ProjectileDefenseUtils.equipShieldToOffhand(bot);
                            if (!equipped) {
                              LOGGER.warn("Failed to equip shield - falling back to dodge");
                            } else {
                              LOGGER.info("✓ Shield equipped successfully");
                              // Start persistent blocking - will continue until threat changes weapon/dies/goes far
                              if (closestThreat instanceof net.minecraft.world.entity.LivingEntity) {
                                AutoFaceEntity.startPersistentBlocking(bot, (net.minecraft.world.entity.LivingEntity) closestThreat, server);
                                break; // Exit case - persistent blocking handles everything
                              }
                            }
                          } else {
                            // Shield already equipped - start persistent blocking
                            if (closestThreat instanceof net.minecraft.world.entity.LivingEntity) {
                              LOGGER.info("✓ Shield already equipped - starting persistent block");
                              AutoFaceEntity.startPersistentBlocking(bot, (net.minecraft.world.entity.LivingEntity) closestThreat, server);
                              break; // Exit case - persistent blocking handles everything
                            }
                          }
                        }

                        // Dodge/evasion strategy
                        // Calculate escape direction away from threat
                        Vec3 botPos = bot.position();
                        Vec3 threatPos = closestThreat.position();
                        Vec3 awayFromThreat = botPos.subtract(threatPos).normalize();

                        // Add randomness for unpredictability
                        double randomAngle = (Math.random() - 0.5) * Math.PI / 2.0; // ±90°
                        double cos = Math.cos(randomAngle);
                        double sin = Math.sin(randomAngle);
                        Vec3 scrambledDir = new Vec3(
                            awayFromThreat.x * cos - awayFromThreat.z * sin,
                            0,
                            awayFromThreat.x * sin + awayFromThreat.z * cos
                        ).normalize();

                        // Check obstacle clearance (20 blocks ahead)
                        double clearance = ProjectileDefenseUtils.checkObstacleClearance(bot, scrambledDir, 20.0);

                        if (clearance > 15.0) {
                          // Path is clear - use direct adaptive evasion (fast!)
                          LOGGER.info("✓ Path clear ({}m) - Direct sprint evasion", String.format("%.1f", clearance));

                          // Create threat object for evasion
                          if (closestThreat instanceof net.minecraft.world.entity.LivingEntity) {
                            PredictiveThreatDetector.DrawingBowThreat threat =
                              new PredictiveThreatDetector.DrawingBowThreat(
                                (net.minecraft.world.entity.LivingEntity) closestThreat, bot);
                            AutoFaceEntity.executeAdaptivePanicEvasion(bot, threat, server);
                          }
                        } else {
                          // Obstacles detected - use PathFinder for smart routing
                          LOGGER.warn("⚠ Obstacles at {}m - Using PathFinder navigation", String.format("%.1f", clearance));

                          // Calculate target position (10 blocks in escape direction)
                          Vec3 targetVec = botPos.add(scrambledDir.scale(10.0));
                          net.minecraft.core.BlockPos targetPos = new net.minecraft.core.BlockPos(
                            (int) Math.floor(targetVec.x),
                            (int) Math.floor(targetVec.y),
                            (int) Math.floor(targetVec.z)
                          );

                          // Find path around obstacles
                          net.minecraft.server.level.ServerLevel world = (net.minecraft.server.level.ServerLevel) bot.level();
                          List<net.shasankp000.PathFinding.PathFinder.PathNode> path =
                            net.shasankp000.PathFinding.PathFinder.calculatePath(bot.blockPosition(), targetPos, world);

                          if (!path.isEmpty()) {
                            LOGGER.info("✓ PathFinder found route with {} nodes - executing", path.size());

                            // Simplify and convert to segments
                            List<net.shasankp000.PathFinding.PathFinder.PathNode> simplified =
                              net.shasankp000.PathFinding.PathFinder.simplifyPath(path, world);
                            java.util.Queue<net.shasankp000.PathFinding.Segment> segments =
                              net.shasankp000.PathFinding.PathFinder.convertPathToSegments(simplified, true); // Sprint!

                            // Execute path with PathTracer
                            net.shasankp000.PathFinding.PathTracer.BotSegmentManager manager =
                              new net.shasankp000.PathFinding.PathTracer.BotSegmentManager(server, botSource, botName);
                            segments.forEach(manager::addSegmentJob);
                            manager.startProcessing();

                            LOGGER.info("✓ PathFinder evasion started - navigating around obstacles");
                          } else {
                            // No path found - use direct evasion as fallback
                            LOGGER.warn("⚠ PathFinder failed - using direct evasion fallback");
                            if (closestThreat instanceof net.minecraft.world.entity.LivingEntity) {
                              PredictiveThreatDetector.DrawingBowThreat threat =
                                new PredictiveThreatDetector.DrawingBowThreat(
                                  (net.minecraft.world.entity.LivingEntity) closestThreat, bot);
                              AutoFaceEntity.executeAdaptivePanicEvasion(bot, threat, server);
                            }
                          }
                        }
                      }
                    } else {
                      // No hostile entities nearby - evasion pointless
                      LOGGER.info("No threats detected - evasion unnecessary");
                      System.out.println("No threats to evade from");
                      completeAction(botName); // Complete immediately if no threat
                    }

                    // Note: EVADE completion is also handled in AutoFaceEntity.executeAdaptivePanicEvasion
                    // when evasion finishes or times out
                    break;

                default:
                    System.out.println("Invalid action");
                    break;
        }
    }

    /**
     * Shutdown all executors when server stops to prevent resource leaks
     */
    public static void shutdown() {
        LOGGER.info("Shutting down BotEventHandler executors...");

        // Shutdown parallel compute pool
        parallelComputePool.shutdown();
        try {
            if (!parallelComputePool.awaitTermination(2, TimeUnit.SECONDS)) {
                parallelComputePool.shutdownNow();
            }
        } catch (InterruptedException e) {
            parallelComputePool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Shutdown scheduled executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("BotEventHandler executors shut down successfully");
    }

    // ==================== ACTION TRACKING HELPERS ====================

    public static boolean isActionInProgress(String botName) {
        return actionTracker.isInProgress(botName);
    }

    public static void startAction(String botName, String actionName) {
        actionTracker.start(botName, actionName);
    }

    public static void completeAction(String botName) {
        actionTracker.complete(botName);
    }

    public static void waitForActionCompletion(String botName, long timeoutMs) {
        actionTracker.waitForCompletion(botName, timeoutMs);
    }
}
