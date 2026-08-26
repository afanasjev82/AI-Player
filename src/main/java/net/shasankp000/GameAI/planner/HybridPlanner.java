package net.shasankp000.GameAI.planner;

import net.minecraft.server.level.ServerPlayer;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.RLAgent;
import net.shasankp000.GameAI.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Hybrid planner that combines:
 * - Semantic goal embeddings
 * - Bi-directional A* pathfinding in action space
 * - Markov chain for action sequence guidance
 * - RL-based risk evaluation
 */
public class HybridPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("hybrid-planner");

    private final ActionGraph actionGraph;
    private final GoalVector goalVector;
    private final MarkovChain2 markovChain;

    // Reserved for future risk-based path filtering
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final RLAgent rlAgent;

    // Reserved for future plan scoring integration
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final SequenceRiskAnalyzer riskAnalyzer;

    // Planning parameters
    private static final int MAX_SEARCH_DEPTH = 12;

    // Reserved for future goal filtering enhancement
    @SuppressWarnings("unused")
    private static final double GOAL_SIMILARITY_THRESHOLD = 0.7;

    // Reserved for future beam search refinement
    @SuppressWarnings("unused")
    private static final int BEAM_WIDTH = 5;

    // Executor for parallel search
    private final ExecutorService searchExecutor;

    public HybridPlanner(ActionGraph actionGraph, GoalVector goalVector,
                        MarkovChain2 markovChain, RLAgent rlAgent,
                        SequenceRiskAnalyzer riskAnalyzer) {
        this.actionGraph = actionGraph;
        this.goalVector = goalVector;
        this.markovChain = markovChain;
        this.rlAgent = rlAgent;
        this.riskAnalyzer = riskAnalyzer;

        this.searchExecutor = Executors.newFixedThreadPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "HybridSearch-" + System.identityHashCode(r));
                t.setDaemon(true);
                return t;
            }
        );
    }

    // -------------------------------------------------------------------------
    // Static entry-point called by AutonomousGoalEngine
    // -------------------------------------------------------------------------

    /**
     * Static facade used by {@link net.shasankp000.GameAI.autonomous.AutonomousGoalEngine}
     * to dispatch a mapped goal without needing to hold a planner instance.
     *
     * <p>Builds a transient {@link HybridPlanner} from fresh planner components,
     * calls {@link #buildPlan(State, String, short)}, and executes each
     * {@link PlannedStep} in sequence via {@link net.shasankp000.FunctionCaller.FunctionCallerV2}.
     *
     * @param bot         The bot {@link ServerPlayer} executing the goal.
     * @param goalId      Numeric goal identifier from {@link GoalMapper}.
     * @param goalText    Human-readable goal description for plan generation.
     */
    /**
     * Execute a mapped goal and report whether it was actually <em>achieved</em>
     * (a measurable, world-visible outcome happened), as opposed to merely
     * "the plan ran without throwing". See {@link #recordOutcome} for the
     * per-goal-type definition of achievement.
     */
    public static boolean executeGoal(ServerPlayer bot, short goalId, String goalText) {
        LOGGER.info("[HybridPlanner] executeGoal called -- bot='{}', goalId={}, goal='{}'",
                bot.getName().getString(), goalId, goalText);

        // 1. Obtain the current bot State snapshot from BotEventHandler
        // FIX: getCurrentState() takes no arguments
        State currentState = BotEventHandler.getCurrentState();
        if (currentState == null) {
            LOGGER.warn("[HybridPlanner] Could not obtain State for bot '{}' -- skipping goal '{}'",
                    bot.getName().getString(), goalText);
            return false;
        }

        // 1b. Snapshot inventory before execution so we can measure the real
        //     outcome (Phase C learning signal). For gather/mine we track the
        //     specific drop item; for other goals total item count is enough.
        String skillKey = skillKeyForGoal(goalId, goalText);
        String rewardItemKey = rewardItemKeyForGoal(goalId, goalText);
        int inventoryBefore = rewardItemKey != null
                ? countItemQuantity(bot, rewardItemKey)
                : countTotalItems(bot);

        // 2. Build planner components.
        // The action graph must be populated from the ActionRegistry, otherwise
        // it has zero nodes and every goal fails with "no valid start/goal nodes".
        // (This is the autonomous path; the /bot plan path initialises via
        // FunctionCallerV2.initializePlanner, which does call buildFromRegistry.)
        GoalVector goalVector             = new GoalVector();
        ActionGraph actionGraph           = new ActionGraph(goalVector);
        ActionRegistry.ensureInitialized();
        actionGraph.buildFromRegistry();
        MarkovChain2 markovChain          = new MarkovChain2();
        // FIX: getRLAgent() takes no arguments
        RLAgent rlAgent                   = BotEventHandler.getRLAgent();
        // FIX: SequenceRiskAnalyzer constructor requires RLAgent
        SequenceRiskAnalyzer riskAnalyzer = new SequenceRiskAnalyzer(rlAgent);

        HybridPlanner planner = new HybridPlanner(
                actionGraph, goalVector, markovChain, rlAgent, riskAnalyzer);

        try {
            // 3. Build the plan
            Plan plan = planner.buildPlan(currentState, goalText, goalId);
            // FIX: Plan uses a public field `steps`, not a getSteps() getter
            if (plan == null || plan.steps.isEmpty()) {
                LOGGER.warn("[HybridPlanner] No plan produced for goal '{}' -- logging fallback",
                        goalText);
                // Record the attempt as failed so unsupported goals (craft/farm/
                // combat/trade) aren't silently counted as successes.
                recordOutcome(goalId, skillKey, rewardItemKey, bot, inventoryBefore, false);
                LOGGER.info("[HybridPlanner] Fallback: goal='{}' could not be planned by HybridPlanner; " +
                        "AutonomousGoalEngine should retry or route via LLM.", goalText);
                return false;
            }

            // FIX: plan.getSteps() → plan.steps
            LOGGER.info("[HybridPlanner] Executing plan with {} step(s) for goal '{}'",
                    plan.steps.size(), goalText);

            new net.shasankp000.FunctionCaller.FunctionCallerV2(
                    bot.createCommandSourceStack().withSuppressedOutput().withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS),
                    bot.getUUID());
            boolean executed = net.shasankp000.FunctionCaller.FunctionCallerV2
                    .executePlan(plan, null, currentState)
                    .join();

            if (!executed) {
                LOGGER.warn("[HybridPlanner] Plan execution failed for goal '{}'", goalText);
                recordOutcome(goalId, skillKey, rewardItemKey, bot, inventoryBefore, false);
                return false;
            }

            LOGGER.info("[HybridPlanner] Plan complete for goal '{}'", goalText);
            return recordOutcome(goalId, skillKey, rewardItemKey, bot, inventoryBefore, true);

        } finally {
            planner.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Phase C: everyday-task outcome measurement
    // -------------------------------------------------------------------------

    /**
     * Singleton skill-experience store (disk-backed, survives restarts).
     * Lazily initialized: {@code SkillExperienceStore} resolves its file path
     * via {@code FabricLoader.getGameDir()}, which throws "invoked too early?"
     * outside a running game (e.g. unit tests). Deferring creation keeps pure
     * helper methods ({@link #blockTypeToDropItem}, {@link #skillKeyForGoal})
     * testable without a Fabric runtime.
     */
    private static volatile SkillExperienceStore SKILL_STORE = null;

    private static SkillExperienceStore skillStore() {
        SkillExperienceStore store = SKILL_STORE;
        if (store == null) {
            synchronized (HybridPlanner.class) {
                store = SKILL_STORE;
                if (store == null) {
                    store = new SkillExperienceStore();
                    SKILL_STORE = store;
                }
            }
        }
        return store;
    }

    /**
     * Total item quantity in the bot's inventory (sum of all stack counts).
     * This measures <em>items</em>, not slots — the old slot-count proxy was
     * blind to stacks merging (e.g. 3 cobblestone + 1 mined = same slot count).
     */
    private static int countTotalItems(ServerPlayer bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            total += bot.getInventory().getItem(i).getCount();
        }
        return total;
    }

    /** Count of a specific item id (by registry key) in the bot's inventory. */
    private static int countItemQuantity(ServerPlayer bot, String itemKey) {
        // The ANY_LOG sentinel measures "any log gained", so generic "gather
        // wood" rewards correctly regardless of the species actually found.
        boolean anyLog = EntityExtractor.ANY_LOG.equals(itemKey);
        int total = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            var stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) continue;
            boolean match = anyLog ? id.getPath().endsWith("_log") : id.toString().equals(itemKey);
            if (match) total += stack.getCount();
        }
        return total;
    }

    /** Map a mined block type to the item it actually drops (package-visible for tests). */
    static String blockTypeToDropItem(String blockType) {
        if (blockType == null) return null;
        return switch (blockType) {
            case "minecraft:stone"        -> "minecraft:cobblestone";
            case "minecraft:coal_ore"     -> "minecraft:coal";
            case "minecraft:iron_ore"     -> "minecraft:raw_iron";
            case "minecraft:copper_ore"   -> "minecraft:raw_copper";
            case "minecraft:gold_ore"     -> "minecraft:raw_gold";
            case "minecraft:diamond_ore"  -> "minecraft:diamond";
            case "minecraft:redstone_ore" -> "minecraft:redstone";
            case "minecraft:lapis_ore"    -> "minecraft:lapis_lazuli";
            case "minecraft:emerald_ore"  -> "minecraft:emerald";
            default                       -> blockType; // oak_log, dirt, sand drop themselves
        };
    }

    /**
     * Skill key derived from goal id + text (e.g. "gather:minecraft:oak_log").
     * Public so {@code AutonomousGoalEngine} can rank goals by this key.
     */
    public static String skillKeyForGoal(short goalId, String goalText) {
        String name = GoalMapper.getGoalName(goalId);
        if (goalId == GoalMapper.GOAL_GATHER || goalId == GoalMapper.GOAL_MINE) {
            return name + ":" + SkillPlanBuilder.inferBlockType(goalText);
        }
        return name;
    }

    /**
     * The concrete item whose quantity delta measures a gather/mine goal's
     * reward, or {@code null} for goals that use a simple success signal.
     */
    private static String rewardItemKeyForGoal(short goalId, String goalText) {
        if (goalId == GoalMapper.GOAL_GATHER || goalId == GoalMapper.GOAL_MINE) {
            return blockTypeToDropItem(SkillPlanBuilder.inferBlockType(goalText));
        }
        if (goalId == GoalMapper.GOAL_CRAFT) {
            return craftOutputItemKey(goalText);
        }
        return null;
    }

    /**
     * The concrete item a craft goal produces, used as its reward signal so a
     * successful craft (log → planks → table) is measured by the output item
     * appearing rather than by total item count (which is blind to 1:1 type
     * swaps, e.g. one log consumed for one table produced). Returns {@code null}
     * when the output can't be determined, falling back to total-item counting.
     */
    static String craftOutputItemKey(String goalText) {
        String item = SkillPlanBuilder.inferCraftItem(goalText);
        return switch (item) {
            case "crafting table", "workbench" -> "minecraft:crafting_table";
            case "stick", "sticks"           -> "minecraft:stick";
            case "torch"                     -> "minecraft:torch";
            case "wooden pickaxe"            -> "minecraft:wooden_pickaxe";
            case "wooden axe"                -> "minecraft:wooden_axe";
            case "wooden sword"              -> "minecraft:wooden_sword";
            case "wooden shovel"             -> "minecraft:wooden_shovel";
            case "wooden hoe"                -> "minecraft:wooden_hoe";
            case "stone pickaxe"             -> "minecraft:stone_pickaxe";
            case "stone axe"                 -> "minecraft:stone_axe";
            case "stone sword"               -> "minecraft:stone_sword";
            case "furnace"                   -> "minecraft:furnace";
            case "chest"                     -> "minecraft:chest";
            // "planks" (species-ambiguous) and unmapped items fall back.
            default                          -> null;
        };
    }

    /**
     * Record the observed outcome of a goal attempt, separating <em>action
     * completed</em> (the plan ran without throwing) from <em>goal achieved</em>
     * (a measurable, world-visible outcome happened).
     *
     * <p>This distinction is the fix for the "false success" bug: a plan can
     * execute cleanly while achieving nothing (craft with an empty inventory,
     * mine stone without a pickaxe, build with no blocks). Counting such runs
     * as successes poisoned the skill-experience store — every skill looked
     * ~100% successful and the skill selector had no real signal to rank on.
     *
     * <p>Semantics:
     * <ul>
     *   <li>gather/mine — achieved iff the target drop item actually increased
     *       (reward = items gained).</li>
     *   <li>craft/build — achieved iff the inventory changed (something was
     *       crafted or placed) AND the plan executed.</li>
     *   <li>navigate/explore/farm/combat/trade — no item signal; achieved iff
     *       the plan executed.</li>
     *   <li>creative — unlimited resources, so gather/mine deltas are
     *       meaningless; reward the decision rather than item gain.</li>
     * </ul>
     */
    private static boolean recordOutcome(short goalId, String skillKey, String rewardItemKey,
                                      ServerPlayer bot, int before, boolean executed) {
        boolean creative = bot != null
                && bot.gameMode.getGameModeForPlayer().isCreative();

        double reward;
        boolean achieved;
        int after;

        if (creative) {
            // Creative: unlimited resources → gather/mine deltas are meaningless.
            // Reward the *decision* (successful execution) rather than item gain.
            after = countTotalItems(bot);
            achieved = executed;
            reward = executed ? 0.5 : -1.0;
        } else if (rewardItemKey != null) {
            // gather/mine: measure the target drop item directly. Success means
            // the bot actually gained that resource (0→0 means nothing was mined).
            after = countItemQuantity(bot, rewardItemKey);
            int gained = after - before;
            achieved = gained > 0;
            reward = gained;
        } else {
            after = countTotalItems(bot);
            achieved = isAchieved(goalId, executed, after - before);
            reward = achieved ? 1.0 : -1.0;
        }

        skillStore().recordOutcome(skillKey, achieved, reward);
        LOGGER.info("[skill] outcome '{}': achieved={}, reward={} ({} {}→{})",
                skillKey, achieved, reward,
                rewardItemKey != null ? rewardItemKey : "items", before, after);
        return achieved;
    }

    /**
     * Whether a goal with no specific drop-item signal (craft/build vs the
     * movement/social skills) counts as achieved.
     *
     * <p>craft/build have an observable inventory effect: inputs are consumed
     * and an output appears, so the item count changes. If the count is
     * unchanged (0→0), the plan "succeeded" but nothing actually happened —
     * that is NOT an achievement. Movement/social skills (navigate/explore/
     * farm/combat/trade) legitimately produce no inventory change, so they
     * count as achieved purely on execution.
     */
    static boolean isAchieved(short goalId, boolean executed, int itemDelta) {
        if (!executed) return false;
        if (goalId == GoalMapper.GOAL_CRAFT || goalId == GoalMapper.GOAL_BUILD) {
            return itemDelta != 0;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Instance methods
    // -------------------------------------------------------------------------

    /**
     * Generates an optimal action plan for the given goal.
     */
    public Plan buildPlan(State currentState, String goalDescription, short goalId) {
        long startTime = System.currentTimeMillis();
        LOGGER.info("Building hybrid plan for goal: {} (ID: {})", goalDescription, goalId);

        // Step 0: Deterministic skill templates (Phase B). For goals with a real
        // tool pipeline (gather/mine/explore/navigate/build) this produces a
        // multi-step plan (searchBlocks → goTo → mineBlock) that the graph
        // planner cannot express. Returns null for unsupported goals (craft,
        // farm, combat, trade) so we fall through to graph search.
        Plan skillPlan = SkillPlanBuilder.buildPlan(goalId, goalDescription, currentState);
        if (skillPlan != null && !skillPlan.steps.isEmpty()) {
            LOGGER.info("[skill] Using deterministic skill plan with {} step(s) for goal '{}'",
                    skillPlan.steps.size(), goalDescription);
            return skillPlan;
        }

        // Unsupported goals (craft/farm/combat/trade) have no tool implementation.
        // Do NOT fall through to the graph planner: it would emit a misleading
        // 1-step "goTo" plan and the goal would be falsely recorded as a success.
        // Return null so the caller treats it as unplanned (and, in Phase C,
        // records it as a failed/unsupported skill).
        if (!SkillPlanBuilder.isSupportedGoal(goalId)) {
            LOGGER.warn("[skill] Goal '{}' (ID {}) has no tool support — not planned", goalDescription, goalId);
            return null;
        }

        // Step 1: Embed the goal
        float[] goalEmbedding = goalVector.embedGoal(goalDescription);

        // Step 2: Find candidate start nodes (actions relevant to current state)
        List<ActionNode> startNodes = findStartNodes(currentState, goalEmbedding);

        // Step 3: Find candidate goal nodes (actions that satisfy the goal)
        List<ActionNode> goalNodes = actionGraph.findNodesForGoal(goalEmbedding, 3);

        if (startNodes.isEmpty() || goalNodes.isEmpty()) {
            LOGGER.warn("Could not find valid start or goal nodes for: {}", goalDescription);
            return null;
        }

        // Step 4: Run bi-directional A* search in parallel for multiple start-goal pairs
        List<Future<SearchResult>> searchFutures = new ArrayList<>();

        for (ActionNode startNode : startNodes) {
            for (ActionNode goalNode : goalNodes) {
                Future<SearchResult> future = searchExecutor.submit(() ->
                    bidirectionalAStar(startNode, goalNode, currentState, goalEmbedding)
                );
                searchFutures.add(future);
            }
        }

        // Step 5: Collect results and find best path
        SearchResult bestResult = null;
        double bestScore = Double.MAX_VALUE;

        for (Future<SearchResult> future : searchFutures) {
            try {
                SearchResult result = future.get(2, TimeUnit.SECONDS);
                if (result != null && result.score < bestScore) {
                    bestScore = result.score;
                    bestResult = result;
                }
            } catch (TimeoutException e) {
                future.cancel(true);
                LOGGER.debug("Search thread timed out");
            } catch (Exception e) {
                LOGGER.error("Search failed", e);
            }
        }

        if (bestResult == null) {
            LOGGER.warn("No valid path found for goal: {}", goalDescription);
            return null;
        }

        // Step 6: Convert path to Plan
        Plan plan = convertToplan(bestResult, goalId, currentState);

        long elapsed = System.currentTimeMillis() - startTime;
        LOGGER.info("Hybrid plan completed in {}ms with score {}", elapsed, String.format("%.2f", bestScore));

        return plan;
    }

    /**
     * Bi-directional A* search in action space.
     */
    @SuppressWarnings("unused") // currentState reserved for future state-aware heuristics
    private SearchResult bidirectionalAStar(ActionNode startNode, ActionNode goalNode,
                                           State currentState, float[] goalEmbedding) {
        // Forward search from start
        PriorityQueue<SearchNode> forwardQueue = new PriorityQueue<>(
            Comparator.comparingDouble(n -> n.fScore)
        );
        Map<ActionNode, SearchNode> forwardVisited = new HashMap<>();

        SearchNode forwardStart = new SearchNode(startNode, null, 0.0,
            heuristic(startNode, goalNode, goalEmbedding));
        forwardQueue.offer(forwardStart);
        forwardVisited.put(startNode, forwardStart);

        // Backward search from goal
        PriorityQueue<SearchNode> backwardQueue = new PriorityQueue<>(
            Comparator.comparingDouble(n -> n.fScore)
        );
        Map<ActionNode, SearchNode> backwardVisited = new HashMap<>();

        SearchNode backwardStart = new SearchNode(goalNode, null, 0.0,
            heuristic(goalNode, startNode, goalEmbedding));
        backwardQueue.offer(backwardStart);
        backwardVisited.put(goalNode, backwardStart);

        // Search state
        SearchResult bestMeeting = null;
        double bestMeetingScore = Double.MAX_VALUE;

        int iterations = 0;
        int maxIterations = MAX_SEARCH_DEPTH * 10;

        // Bi-directional search loop
        while (!forwardQueue.isEmpty() && !backwardQueue.isEmpty() && iterations < maxIterations) {
            iterations++;

            // Expand forward frontier
            SearchNode forwardNode = forwardQueue.poll();
            if (forwardNode == null) break;

            // Check if we've met the backward search
            if (backwardVisited.containsKey(forwardNode.node)) {
                SearchNode backwardNode = backwardVisited.get(forwardNode.node);
                double meetingScore = forwardNode.gScore + backwardNode.gScore;

                if (meetingScore < bestMeetingScore) {
                    bestMeetingScore = meetingScore;
                    bestMeeting = new SearchResult(
                        mergePaths(forwardNode, backwardNode),
                        meetingScore
                    );
                }
            }

            // Expand forward neighbors
            for (ActionNode neighbor : forwardNode.node.getNeighbors()) {
                if (forwardNode.depth >= MAX_SEARCH_DEPTH) continue;

                double edgeWeight = forwardNode.node.getEdgeWeight(neighbor);
                double newGScore = forwardNode.gScore + edgeWeight + neighbor.getBaseRisk();

                SearchNode existingNode = forwardVisited.get(neighbor);
                if (existingNode == null || newGScore < existingNode.gScore) {
                    SearchNode newNode = new SearchNode(
                        neighbor,
                        forwardNode,
                        newGScore,
                        heuristic(neighbor, goalNode, goalEmbedding),
                        forwardNode.depth + 1
                    );
                    forwardQueue.offer(newNode);
                    forwardVisited.put(neighbor, newNode);
                }
            }

            // Expand backward frontier
            SearchNode backwardNode = backwardQueue.poll();
            if (backwardNode == null) break;

            // Check if we've met the forward search
            if (forwardVisited.containsKey(backwardNode.node)) {
                SearchNode forwardNodeMeet = forwardVisited.get(backwardNode.node);
                double meetingScore = forwardNodeMeet.gScore + backwardNode.gScore;

                if (meetingScore < bestMeetingScore) {
                    bestMeetingScore = meetingScore;
                    bestMeeting = new SearchResult(
                        mergePaths(forwardNodeMeet, backwardNode),
                        meetingScore
                    );
                }
            }

            // Expand backward neighbors (reverse edges)
            for (ActionNode possibleParent : actionGraph.getAllNodes()) {
                if (possibleParent.getNeighbors().contains(backwardNode.node)) {
                    if (backwardNode.depth >= MAX_SEARCH_DEPTH) continue;

                    double edgeWeight = possibleParent.getEdgeWeight(backwardNode.node);
                    double newGScore = backwardNode.gScore + edgeWeight + backwardNode.node.getBaseRisk();

                    SearchNode existingNode = backwardVisited.get(possibleParent);
                    if (existingNode == null || newGScore < existingNode.gScore) {
                        SearchNode newNode = new SearchNode(
                            possibleParent,
                            backwardNode,
                            newGScore,
                            heuristic(possibleParent, startNode, goalEmbedding),
                            backwardNode.depth + 1
                        );
                        backwardQueue.offer(newNode);
                        backwardVisited.put(possibleParent, newNode);
                    }
                }
            }
        }

        return bestMeeting;
    }

    /**
     * Heuristic function for A* (estimated distance to goal).
     */
    private double heuristic(ActionNode current, ActionNode goal, float[] goalEmbedding) {
        double semanticDist = goalVector.euclideanDistance(current.getEmbedding(), goal.getEmbedding());
        double timeCost = current.getEstimatedTimeCost();
        double goalSimilarity = 1.0 - goalVector.cosineSimilarity(current.getEmbedding(), goalEmbedding);
        return (semanticDist * 0.5) + (timeCost * 0.3) + (goalSimilarity * 0.2);
    }

    /**
     * Merges forward and backward paths at meeting point.
     */
    private List<ActionNode> mergePaths(SearchNode forwardNode, SearchNode backwardNode) {
        LinkedList<ActionNode> path = new LinkedList<>();

        SearchNode current = forwardNode;
        while (current != null) {
            path.addFirst(current.node);
            current = current.parent;
        }

        current = backwardNode.parent; // Skip meeting point (already in forward path)
        while (current != null) {
            path.add(current.node);
            current = current.parent;
        }

        return new ArrayList<>(path);
    }

    /**
     * Finds starting nodes based on current state.
     */
    private List<ActionNode> findStartNodes(State currentState, float[] goalEmbedding) {
        Set<String> stateConditions = extractStateConditions(currentState);

        return actionGraph.getAllNodes().stream()
            .filter(node -> node.preconditionsSatisfied(stateConditions))
            .sorted(Comparator.comparingDouble(node ->
                -goalVector.cosineSimilarity(node.getEmbedding(), goalEmbedding)
            ))
            .limit(3)
            .collect(Collectors.toList());
    }

    /**
     * Extracts state conditions from current State.
     */
    private Set<String> extractStateConditions(State state) {
        Set<String> conditions = new HashSet<>();

        List<String> hotbarItems = state.getHotBarItems();
        for (String item : hotbarItems) {
            if (item.contains("log") || item.contains("wood")) conditions.add("has_item:wood");
            if (item.contains("stone") || item.contains("cobblestone")) conditions.add("has_item:stone");
            if (item.contains("axe") || item.contains("pickaxe") || item.contains("sword")) conditions.add("has_tool");
        }

        if (state.getBotHealth() > 15) conditions.add("health:good");
        else if (state.getBotHealth() > 5) conditions.add("health:moderate");
        else conditions.add("health:critical");

        if (!hotbarItems.isEmpty() && !hotbarItems.getFirst().equals("minecraft:air")) {
            conditions.add("has_equipment");
        }

        return conditions;
    }

    /**
     * Converts search result to Plan.
     */
    private Plan convertToplan(SearchResult result, short goalId, State state) {
        List<PlannedStep> steps = new ArrayList<>();

        for (ActionNode node : result.path) {
            String params = inferParameters(node, state);
            PlannedStep step = new PlannedStep(node.getActionId(), node.getActionName(), 0.0, params);
            steps.add(step);
        }

        Plan plan = new Plan(UUID.randomUUID(), goalId, steps);
        plan.score = result.score;
        plan.estimatedRisk = result.score;
        return plan;
    }

    /**
     * Infers parameters for an action based on context.
     */
    private String inferParameters(ActionNode node, State state) {
        switch (node.getActionName()) {
            case "searchBlocks": {
                Object targetBlock = markovChain.getSharedState("targetBlockType");
                String blockType = (targetBlock != null) ? targetBlock.toString() : "minecraft:oak_log";
                return String.format("%s,10,100,20", blockType);
            }
            case "goTo":
            case "moveToCoordinates": {
                Object foundX = markovChain.getSharedState("foundBlock.x");
                if (foundX != null) {
                    Object foundY = markovChain.getSharedState("foundBlock.y");
                    Object foundZ = markovChain.getSharedState("foundBlock.z");
                    return String.format("%s,%s,%s,true", foundX, foundY, foundZ);
                }
                return String.format("%d,%d,%d,true",
                    state.getBotX() + 2, state.getBotY(), state.getBotZ());
            }
            case "mineBlock":
            case "breakBlock": {
                Object targetX = markovChain.getSharedState("foundBlock.x");
                if (targetX != null) {
                    Object targetY = markovChain.getSharedState("foundBlock.y");
                    Object targetZ = markovChain.getSharedState("foundBlock.z");
                    return String.format("%s,%s,%s", targetX, targetY, targetZ);
                }
                return String.format("%d,%d,%d",
                    state.getBotX() + 2, state.getBotY(), state.getBotZ());
            }
            case "placeBlock":
                return String.format("%d,%d,%d,minecraft:dirt",
                    state.getBotX() + 1, state.getBotY() - 1, state.getBotZ());
            case "turn":
                return "right";
            case "look":
                return "north";
            case "detectBlocks": {
                Object detectBlock = markovChain.getSharedState("targetBlockType");
                return (detectBlock != null) ? detectBlock.toString() : "minecraft:oak_log";
            }
            default:
                return "";
        }
    }

    /**
     * Shuts down the search executor thread pool.
     */
    @SuppressWarnings("unused") // Public API method for resource cleanup
    public void shutdown() {
        searchExecutor.shutdown();
        try {
            if (!searchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                searchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            searchExecutor.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    private static class SearchNode {
        final ActionNode node;
        final SearchNode parent;
        final double gScore;
        final double hScore;
        final double fScore;
        final int depth;

        SearchNode(ActionNode node, SearchNode parent, double gScore, double hScore) {
            this(node, parent, gScore, hScore, parent == null ? 0 : parent.depth + 1);
        }

        SearchNode(ActionNode node, SearchNode parent, double gScore, double hScore, int depth) {
            this.node = node;
            this.parent = parent;
            this.gScore = gScore;
            this.hScore = hScore;
            this.fScore = gScore + hScore;
            this.depth = depth;
        }
    }

    private static class SearchResult {
        final List<ActionNode> path;
        final double score;

        SearchResult(List<ActionNode> path, double score) {
            this.path = path;
            this.score = score;
        }
    }
}
