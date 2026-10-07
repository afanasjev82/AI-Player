package net.shasankp000.GameAI.autonomous;

/**
 * Computes a goal's <em>current</em> priority from live context.
 *
 * <p>This is the pluggable seam that turns the otherwise-static goal queue into
 * a dynamic-priority queue. The engine holds one of these and re-scores pending
 * goals whenever the bot's situation meaningfully changes (a threat appears,
 * night falls, health drops), so a task can rise or fall in the queue without
 * being re-enqueued.
 *
 * <p>The default implementation ({@link ThreatHeuristicGoalPriorityScorer}) is a
 * zero-latency in-process heuristic. A Laya-backed scorer can implement the same
 * interface by sending a {@code score} question over the Jev protocol, but the
 * per-tick nature of re-scoring makes an in-process function the right default
 * — an HTTP round-trip belongs behind a slower, richer re-evaluation cadence.
 */
@FunctionalInterface
public interface GoalPriorityScorer {

    /**
     * @param entry   the queued goal (its {@link GoalQueueEntry#priority()} is the
     *                priority captured at enqueue time — the scorer's floor).
     * @param context the bot's current situation.
     * @return the goal's priority for this instant; higher dequeues first.
     */
    int computePriority(GoalQueueEntry entry, GoalPriorityContext context);
}
