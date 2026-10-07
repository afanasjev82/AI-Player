package net.shasankp000.GameAI.autonomous;

/**
 * Zero-latency default {@link GoalPriorityScorer}.
 *
 * <p>Keeps the enqueue-time priority as the floor and applies a small, bounded
 * safety escalation:
 * <ul>
 *   <li>At night, a queued shelter/build goal surfaces above the leisurely
 *       gather/explore goals the LLM proposed at spawn, so the bot seeks cover
 *       before hostile spawns peak.</li>
 *   <li>Under an active close threat, a queued combat/defend goal (if any is
 *       ever injected) jumps to the front; other goals keep their floor so the
 *       reactive combat path owns the bot until the threat clears.</li>
 * </ul>
 *
 * <p>Goal type is detected with a <em>lightweight keyword match</em>, not
 * {@code GoalMapper.parseGoal}: this runs inside the autonomous loop's re-score
 * tick and must stay pure, deterministic, and network-free (the authoritative
 * classification still happens in the executor, which calls {@code parseGoal}).
 *
 * <p>Deliberately conservative: it never <em>drops</em> a priority and never
 * exceeds {@value #MAX_ESCALATION}, so it can only re-order, never starve.
 */
public final class ThreatHeuristicGoalPriorityScorer implements GoalPriorityScorer {

    /** Upper bound on any single escalation, keeping re-ordering from dominating. */
    static final int MAX_ESCALATION = 100;

    private static final int NIGHT_BUILD_BOOST = 30;
    private static final int THREAT_COMBAT_BOOST = 100;

    private static final String[] BUILD_HINTS =
            {"build", "shelter", "house", "wall", "room", "roof", "hut", "shack", "base"};
    private static final String[] COMBAT_HINTS =
            {"combat", "attack", "fight", "kill", "defeat", "defend",
             "zombie", "creeper", "skeleton", "spider", "mob", "hostile", "threat"};

    @Override
    public int computePriority(GoalQueueEntry entry, GoalPriorityContext context) {
        int priority = entry.priority();
        String text = entry.goalText() == null ? "" : entry.goalText().toLowerCase();

        if (context.closeThreatActive() && containsAny(text, COMBAT_HINTS)) {
            return priority + THREAT_COMBAT_BOOST;
        }
        if (context.night() && containsAny(text, BUILD_HINTS)) {
            return priority + NIGHT_BUILD_BOOST;
        }
        return priority;
    }

    private static boolean containsAny(String text, String[] hints) {
        for (String hint : hints) {
            if (text.contains(hint)) return true;
        }
        return false;
    }
}
