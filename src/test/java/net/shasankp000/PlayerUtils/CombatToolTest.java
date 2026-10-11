package net.shasankp000.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatToolTest {
    @Test
    void sameLevelTargetIsReachable() {
        assertFalse(CombatTool.isBelowUnreachableCliff(136.0, 136.0));
        assertFalse(CombatTool.isBelowUnreachableCliff(136.0, 132.5));
    }

    @Test
    void smallDropIsReachable() {
        // PathFinder's DROP transition handles up to ~3 blocks; the pre-filter
        // threshold (MAX_SAFE_DROP=4) must not block a reachable 3-block drop.
        assertFalse(CombatTool.isBelowUnreachableCliff(136.0, 133.0));
    }

    @Test
    void cliffBaseIsUnreachable() {
        // The spawn cliff: Paul at y~136, mobs fall to y~110-115.
        assertTrue(CombatTool.isBelowUnreachableCliff(136.0, 115.0));
        assertTrue(CombatTool.isBelowUnreachableCliff(136.0, 110.0));
        assertTrue(CombatTool.isBelowUnreachableCliff(100.0, 95.9));
    }

    @Test
    void thresholdBoundaryUsesStrictGreaterThan() {
        assertFalse(CombatTool.isBelowUnreachableCliff(100.0, 100.0 - CombatTool.MAX_SAFE_DROP));
        assertTrue(CombatTool.isBelowUnreachableCliff(100.0, 100.0 - CombatTool.MAX_SAFE_DROP - 0.001));
    }

    @Test
    void targetAboveBotIsAlwaysReachableByGate() {
        // The gate only guards descents; an elevated target is left to PathFinder.
        assertFalse(CombatTool.isBelowUnreachableCliff(100.0, 120.0));
    }

    @Test
    void swingIsHeldUntilTheAttackCooldownRecharges() {
        // Carpet's /player attack resets the attack-strength ticker on every
        // entity hit, so a swing at low strength deals NO damage and pins the
        // ticker low. Only a recharged swing may be issued.
        assertFalse(CombatTool.isAttackStrengthSufficient(0.0F));
        assertFalse(CombatTool.isAttackStrengthSufficient(0.5F));
        assertFalse(CombatTool.isAttackStrengthSufficient(0.9F));
        assertFalse(CombatTool.isAttackStrengthSufficient(CombatTool.MIN_ATTACK_STRENGTH));
        assertTrue(CombatTool.isAttackStrengthSufficient(1.0F));
        assertTrue(CombatTool.isAttackStrengthSufficient(0.91F));
    }

    @Test
    void attackStrengthThresholdMirrorsVanilla() {
        // Vanilla's own damage gate is `getAttackStrengthScale(0.5F) > 0.9F`.
        assertEquals(0.9F, CombatTool.MIN_ATTACK_STRENGTH);
        assertEquals(0.5F, CombatTool.ATTACK_STRENGTH_ADJUST_TICKS);
    }
}
