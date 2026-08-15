package net.shasankp000.GameAI.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Graph of all available actions and their relationships.
 * Edges represent compatibility/sequencing between actions.
 */
public class ActionGraph {
    private static final Logger LOGGER = LoggerFactory.getLogger("action-graph");

    private final Map<Byte, ActionNode> nodes;
    private final GoalVector goalVector;

    public ActionGraph(GoalVector goalVector) {
        this.nodes = new HashMap<>();
        this.goalVector = goalVector;
    }

    /**
     * Registers an action in the graph.
     */
    public void addAction(byte actionId, String actionName,
                         Set<String> preconditions, Set<String> effects,
                         double baseRisk, double timeCost) {
        // Generate embedding for this action
        float[] embedding = goalVector.embedGoal(actionName);

        ActionNode node = new ActionNode(actionId, actionName, embedding,
                                        preconditions, effects, baseRisk, timeCost);
        nodes.put(actionId, node);

        LOGGER.debug("Registered action: {} (ID: {})", actionName, actionId);
    }

    /**
     * Builds edges between compatible actions.
     * Two actions are compatible if one's effects satisfy the other's preconditions.
     */
    public void buildEdges() {
        for (ActionNode source : nodes.values()) {
            for (ActionNode target : nodes.values()) {
                if (source.equals(target)) continue;

                // Check if source's effects enable target's preconditions
                Set<String> sourceEffects = source.getEffects();
                double compatibilityScore = computeCompatibility(sourceEffects, target);

                if (compatibilityScore < Double.MAX_VALUE) {
                    source.addNeighbor(target, compatibilityScore);
                }
            }
        }

        LOGGER.info("✓ Built action graph with {} nodes and {} edges",
                   nodes.size(), countEdges());
    }

    /**
     * Computes compatibility score between two actions.
     */
    private double computeCompatibility(Set<String> effects, ActionNode target) {
        // Base cost of transitioning
        double cost = 1.0;

        // Add penalty if target has unsatisfied preconditions
        // (would need to be satisfied by other means)
        cost += target.getBaseRisk() * 0.1;

        // Add time cost
        cost += target.getEstimatedTimeCost() * 0.5;

        return cost;
    }

    /**
     * Finds nodes that could satisfy a goal (by embedding similarity).
     */
    public List<ActionNode> findNodesForGoal(float[] goalEmbedding, int topK) {
        return nodes.values().stream()
                .sorted(Comparator.comparingDouble(node ->
                    -goalVector.cosineSimilarity(node.getEmbedding(), goalEmbedding)
                ))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * Gets all nodes in the graph.
     */
    public Collection<ActionNode> getAllNodes() {
        return nodes.values();
    }

    /**
     * Builds the action graph from ActionRegistry.
     * Populates nodes with all registered actions and establishes edges.
     */
    public void buildFromRegistry() {
        Set<String> functionNames = ActionRegistry.getAllFunctionNames();
        LOGGER.info("Building action graph from {} registered functions", functionNames.size());

        // Register each action as a node
        for (String functionName : functionNames) {
            if (functionName.equals("unknown")) continue;

            byte actionId = ActionRegistry.getActionByte(functionName);

            // Create simple preconditions/effects based on function name
            Set<String> preconditions = inferPreconditions(functionName);
            Set<String> effects = inferEffects(functionName);

            // Estimate base risk and time cost based on action type
            double baseRisk = estimateBaseRisk(functionName);
            double timeCost = estimateTimeCost(functionName);

            addAction(actionId, functionName, preconditions, effects, baseRisk, timeCost);
        }

        // Build edges between compatible actions
        buildEdges();

        LOGGER.info("✓ Action graph built with {} nodes and {} edges", nodes.size(), countEdges());
    }

    /**
     * Infers preconditions for an action based on its name.
     *
     * <p>Returns an empty set for every action. The old keyword-derived
     * preconditions ({@code path_exists}, {@code tool_equipped},
     * {@code block_detected}, …) used a vocabulary that never matches the state
     * conditions produced by {@code HybridPlanner.extractStateConditions}
     * ({@code has_item:*}, {@code health:*}, {@code has_equipment}). As a result
     * {@code findStartNodes} filtered out every real action (mineBlock, goTo,
     * placeBlock) and plans collapsed to trivial "look"/"turn" steps. Action
     * reachability is instead governed by edge weights and the A* heuristic.
     */
    private Set<String> inferPreconditions(String actionName) {
        return new HashSet<>();
    }

    /**
     * Infers effects for an action based on its name.
     */
    private Set<String> inferEffects(String actionName) {
        Set<String> effects = new HashSet<>();
        String lower = actionName.toLowerCase();

        if (lower.contains("mine")) {
            effects.add("item_in_inventory");
            effects.add("block_removed");
        } else if (lower.contains("place")) {
            effects.add("block_placed");
        } else if (lower.contains("goto") || lower.contains("navigate")) {
            effects.add("position_changed");
        } else if (lower.contains("search") || lower.contains("detect")) {
            effects.add("block_detected");
        } else if (lower.contains("craft")) {
            effects.add("item_crafted");
        }

        return effects;
    }

    /**
     * Estimates base risk for an action.
     */
    private double estimateBaseRisk(String actionName) {
        String lower = actionName.toLowerCase();

        if (lower.contains("attack") || lower.contains("combat")) {
            return 15.0;
        } else if (lower.contains("mine") || lower.contains("place")) {
            return 5.0;
        } else if (lower.contains("navigate") || lower.contains("goto")) {
            return 8.0;
        } else if (lower.contains("search") || lower.contains("detect")) {
            return 2.0;
        } else if (lower.contains("get") || lower.contains("look")) {
            return 1.0;
        }

        return 3.0; // Default
    }

    /**
     * Estimates time cost for an action in seconds.
     */
    private double estimateTimeCost(String actionName) {
        String lower = actionName.toLowerCase();

        if (lower.contains("navigate") || lower.contains("goto")) {
            return 5.0;
        } else if (lower.contains("mine")) {
            return 3.0;
        } else if (lower.contains("search")) {
            return 2.0;
        } else if (lower.contains("craft")) {
            return 4.0;
        } else if (lower.contains("place")) {
            return 1.5;
        } else if (lower.contains("get") || lower.contains("look") || lower.contains("detect")) {
            return 0.5;
        }

        return 2.0; // Default
    }

    /**
     * Gets a specific node by ID.
     */
    public ActionNode getNode(byte actionId) {
        return nodes.get(actionId);
    }

    private int countEdges() {
        return nodes.values().stream()
                .mapToInt(node -> node.getNeighbors().size())
                .sum();
    }

    public int size() {
        return nodes.size();
    }
}

