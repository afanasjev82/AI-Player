package net.shasankp000.GameAI.autonomous;

/**
 * Immutable snapshot of the live signals a goal priority scorer may weight.
 *
 * <p>This is the "context" half of the dynamic-priority seam: every re-score
 * tick the engine captures the bot's current situation into one of these and
 * passes it to a {@link GoalPriorityScorer}. A richer scorer (e.g. one backed
 * by Laya's {@code score} primitive) reads the same fields, so swapping the
 * scorer never changes what the engine collects.
 */
public record GoalPriorityContext(
        boolean closeThreatActive,
        int hostileCount,
        boolean night,
        float healthFraction,
        int foodLevel
) {
    /** A safe, daytime, full-health baseline used when no bot context is available. */
    public static final GoalPriorityContext CALM =
            new GoalPriorityContext(false, 0, false, 1.0f, 20);
}
