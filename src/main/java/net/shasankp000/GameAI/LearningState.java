package net.shasankp000.GameAI;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * The mutable RL learning state attached to a {@link State} — the part that
 * actually changes as the bot trains, as opposed to the immutable
 * {@link WorldSnapshot} observation.
 *
 * <p>Extracted from {@link State} (Phase: god-object split, step 2). {@link State}
 * previously held these four fields directly: {@code actionTaken} (the action
 * chosen for this state), {@code riskAppetite} (the bot's current willingness
 * to take risk), and the two mutable maps {@code riskMap}/{@code podMap}
 * (risk and probability-of-death estimates per candidate action, updated
 * in-place by the RL loop). This holder owns those four fields.
 *
 * <p>{@link State} composes a {@code LearningState} and delegates its
 * {@code getActionTaken}/{@code getRiskAppetite}/{@code getRiskMap}/
 * {@code getPodMap}/{@code setRiskMap}/{@code setPodMap} accessors to it, so
 * every existing caller keeps the same API.
 *
 * <p>{@link Serializable} to match {@link State}'s persistence contract.
 */
public final class LearningState implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final StateActions.Action actionTaken;
    private final double riskAppetite;
    private Map<StateActions.Action, Double> riskMap;
    private Map<StateActions.Action, Double> podMap;

    public LearningState(
            StateActions.Action actionTaken,
            double riskAppetite,
            Map<StateActions.Action, Double> riskMap,
            Map<StateActions.Action, Double> podMap) {
        this.actionTaken = actionTaken;
        this.riskAppetite = riskAppetite;
        this.riskMap = riskMap;
        this.podMap = podMap;
    }

    public StateActions.Action getActionTaken() { return actionTaken; }
    public double getRiskAppetite() { return riskAppetite; }
    public Map<StateActions.Action, Double> getRiskMap() { return riskMap; }
    public Map<StateActions.Action, Double> getPodMap() { return podMap; }

    public void setRiskMap(Map<StateActions.Action, Double> riskMap) {
        this.riskMap = riskMap;
    }

    public void setPodMap(Map<StateActions.Action, Double> podMap) {
        this.podMap = podMap;
    }
}
