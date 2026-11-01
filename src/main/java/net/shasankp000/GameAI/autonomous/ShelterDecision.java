package net.shasankp000.GameAI.autonomous;

/**
 * Pure decision for whether the bot should retreat to shelter instead of
 * fighting a nearby threat.
 *
 * <p>The rule exists because "fight back" is not always an option. A flying mob
 * (a Phantom, say) cannot be reached by a melee swing — it dives and climbs out
 * of reach — so only a ranged weapon can answer it. And a bot with no weapon at
 * all cannot answer anything. In either case the only survivable move is to get
 * under a roof and wait the threat out, which is why the decision lives in its
 * own class: the combat and navigation layers disagree on almost everything
 * else, but they must agree on when a fight is hopeless.
 *
 * <p>Pure on purpose: {@code boolean}s in, {@code boolean} out, so it can be
 * tested without a running Minecraft client (the same seam as
 * {@link AutonomousGoalEngine#shouldReturnHome}).
 */
public final class ShelterDecision {

    private ShelterDecision() {
    }

    /**
     * @param flyingThreat    the threat flies and cannot be reliably melee'd
     * @param hasMeleeWeapon  the bot holds a melee weapon somewhere in inventory
     * @param hasRangedWeapon the bot has a ranged weapon <em>and</em> ammunition
     * @return {@code true} when the bot cannot fight this threat and should retreat
     */
    public static boolean shouldTakeShelter(boolean flyingThreat, boolean hasMeleeWeapon, boolean hasRangedWeapon) {
        if (flyingThreat) {
            // Melee cannot reach a flying mob; only a ranged weapon can answer it.
            return !hasRangedWeapon;
        }
        // A ground threat is fightable with any weapon; only a fully unarmed bot retreats.
        return !hasMeleeWeapon && !hasRangedWeapon;
    }
}
