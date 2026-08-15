package net.shasankp000.GameAI.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a planned action sequence for achieving a goal.
 */
public class Plan {
    public final UUID planId;
    public final short goalId;
    public final List<PlannedStep> steps;

    public double estimatedRisk;
    public double score; // Overall score for plan comparison (used by hybrid planner)
    public long createdAt;

    public Plan(UUID planId, short goalId, List<PlannedStep> steps) {
        this.planId = planId;
        this.goalId = goalId;
        this.steps = new ArrayList<>(steps);
        this.estimatedRisk = 0.0;
        this.score = 0.0;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Get total score (risk) of the plan.
     * The hybrid planner stores its path score in {@link #score}; the Markov
     * planner stores its risk in {@link #estimatedRisk}. Report whichever is
     * populated so callers never see a spurious 0.0.
     */
    public double getTotalScore() {
        return score > 0.0 ? score : estimatedRisk;
    }

    /**
     * Check if plan is empty.
     */
    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * Get length of plan (number of steps).
     */
    public int length() {
        return steps.size();
    }

    /**
     * Get plan summary for logging.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Plan %s (Goal: %d, Risk: %.2f):\n",
                               planId.toString().substring(0, 8), goalId, estimatedRisk));

        for (int i = 0; i < steps.size(); i++) {
            PlannedStep step = steps.get(i);
            sb.append(String.format("  %d. %s (risk: %.2f)",
                                  i + 1, step.actionName, step.estimatedRisk));

            if (step.params != null) {
                sb.append(String.format(" [%s]", step.params));
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Check if plan is complete (all steps executed).
     */
    public boolean isComplete(int currentStep) {
        return currentStep >= steps.size();
    }

    /**
     * Get next step to execute.
     */
    public PlannedStep getNextStep(int currentStep) {
        if (currentStep < 0 || currentStep >= steps.size()) {
            return null;
        }
        return steps.get(currentStep);
    }

    /**
     * Get total estimated time cost.
     */
    public int getTotalTimeCost() {
        // This would be computed by CheapForward during planning
        // For now, return step count as proxy
        return steps.size() * 5; // ~5 ticks per action average
    }

    @Override
    public String toString() {
        return String.format("Plan[id=%s, goal=%d, steps=%d, risk=%.2f]",
                           planId.toString().substring(0, 8),
                           goalId,
                           steps.size(),
                           estimatedRisk);
    }
}

