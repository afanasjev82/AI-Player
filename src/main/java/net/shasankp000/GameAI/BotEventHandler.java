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

    // State transition tracking for lookahead learning
    private static final StateTransition.TransitionHistory transitionHistory =
        new StateTransition.TransitionHistory(50); // Keep last 50 transitions
    private static State previousState = null;
    private static StateActions.Action previousAction = null;
    private static double previousReward = 0.0;
    private static final Map<UUID, Long> lastNightSleepDecision = new HashMap<>();
    private static final long NIGHT_SLEEP_DECISION_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    private static final Map<UUID, Long> lastLowHungerDecision = new HashMap<>();
    private static final long LOW_HUNGER_DECISION_INTERVAL_MS = TimeUnit.SECONDS.toMillis(2);

    // Singleton RLAgent – lazily created and cached for external callers
    private static RLAgent cachedRLAgent = null;

    // Periodic reflection scheduler
    private static long lastReflectionTime = System.currentTimeMillis();
    private static final long REFLECTION_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5); // Reflect every 5 minutes

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
        ActionExecutor.executeAction(StateActions.Action.SLEEP, source, server, bot);

        State nextState = createInitialState(candidateBot);
        double reward = ActionExecutor.wasLastSleepActionSuccessful() ? 50.0 : -20.0;
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
                ActionExecutor.wasLastSleepActionSuccessful() ? "slept" : "could not sleep");
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
        ActionExecutor.executeAction(chosenAction, source, server, bot);

        State nextState = createInitialState(candidateBot);
        double reward = chosenAction == StateActions.Action.USE_ITEM
                ? ActionExecutor.applyFoodReward(chosenAction, 0.0)
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
                        ? ActionExecutor.getLastFoodConsumption().message()
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

                ActionExecutor.executeAction(chosenAction, botSource, server, bot);


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

                reward = ActionExecutor.applySleepReward(chosenAction, reward);
                reward = ActionExecutor.applyFoodReward(chosenAction, reward);
                reward = ActionExecutor.applySurfaceDepthPenalty(bot, reward);

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

                ActionExecutor.executeAction(chosenAction, botSource, server, bot);

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

                reward = ActionExecutor.applySleepReward(chosenAction, reward);
                reward = ActionExecutor.applyFoodReward(chosenAction, reward);
                reward = ActionExecutor.applySurfaceDepthPenalty(bot, reward);

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
                LOGGER.info("[RL-LOOP] Waiting for action '{}' to complete...", ActionExecutor.getCurrentAction(botName));
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
                ActionExecutor.attackFallbackRateLimited(bot, "No Q-table");
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

                    // Compute the real per-action risk map (mirrors the training
                    // path). The old code used currentState.getRiskMap(), which
                    // createInitialState leaves EMPTY — chooseActionPlayMode then
                    // skipped every Q-table entry and always degraded to STAY
                    // (the "play-mode RL inert" bug: a trained policy was never
                    // consulted).
                    List<StateActions.Action> potentialActions = rlAgentHook.suggestPotentialActions(currentState);
                    Map<StateActions.Action, Double> riskMap = rlAgentHook.calculateRisk(currentState, potentialActions, bot);

                    // Choose action via the trained policy.
                    StateActions.Action chosenAction = rlAgentHook.chooseActionPlayMode(currentState, qTable, riskMap, "detectAndReactPlayMode", transitionHistory);

                    // If the Q-table is empty the policy degenerates to STAY
                    // ("No viable actions available. Defaulting to STAY"), which
                    // makes the bot a passive target. Fall back to the
                    // deterministic combat primitive so an untrained bot still
                    // defends itself. A trained policy that picks a real action
                    // (ATTACK / EVADE / SHOOT_ARROW / SPRINT…) is left untouched.
                    if (qTable.getTable().isEmpty() || chosenAction == StateActions.Action.STAY) {
                        ActionExecutor.attackFallbackRateLimited(bot, "Q-table empty or policy chose STAY");
                    } else {
                        // Log chosen action for debugging
                        LOGGER.debug("Play Mode - Chosen action: {}", chosenAction);
                        ActionExecutor.executeAction(chosenAction, botSource, server, bot);
                    }
                }
                else if (DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) <= 5.0 && DangerZoneDetector.detectDangerZone(bot, 10, 10, 10) > 0.0) {

                    // Gather state information
                    State currentState = createInitialState(bot);

                    // Same fix as the hostile branch: compute a real risk map so
                    // the policy can act instead of defaulting to STAY.
                    List<StateActions.Action> potentialActions = rlAgentHook.suggestPotentialActions(currentState);
                    Map<StateActions.Action, Double> riskMap = rlAgentHook.calculateRisk(currentState, potentialActions, bot);


                    // Choose action
                    StateActions.Action chosenAction = rlAgentHook.chooseActionPlayMode(currentState, qTable, riskMap, "detectAndReactPlayMode", transitionHistory);


                    // Log chosen action for debugging
                    LOGGER.debug("Play Mode - Chosen action: {}", chosenAction);

                    // Execute action
                    ActionExecutor.executeAction(chosenAction, botSource, server, bot);
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
    // Delegating wrappers over ActionExecutor's tracker; kept as public statics
    // so external callers (modCommandRegistry, AutoFaceEntity) are unchanged.

    public static boolean isActionInProgress(String botName) {
        return ActionExecutor.isActionInProgress(botName);
    }

    public static void startAction(String botName, String actionName) {
        ActionExecutor.startAction(botName, actionName);
    }

    public static void completeAction(String botName) {
        ActionExecutor.completeAction(botName);
    }

    public static void waitForActionCompletion(String botName, long timeoutMs) {
        ActionExecutor.waitForActionCompletion(botName, timeoutMs);
    }
}
