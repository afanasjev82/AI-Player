package net.shasankp000.GameAI.autonomous;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the "retreat vs fight" decision. The rule matters because Paul was being
 * killed by Phantoms: a flying mob his melee attacks whiffed on, while the RL
 * policy kept choosing ATTACK (he looked "well-armed & healthy" on the surface).
 * The only survivable move against a flying mob without a ranged weapon is to
 * retreat under a roof.
 */
class ShelterDecisionTest {

    @Test
    void flyingThreatIsUnfightableWithoutARangedWeapon() {
        // Phantom, sword in hand but no bow — melee cannot reach it.
        assertTrue(ShelterDecision.shouldTakeShelter(true, true, false),
                "a flying mob with no ranged weapon must be retreated from");
        assertTrue(ShelterDecision.shouldTakeShelter(true, false, false),
                "a flying mob with nothing at all is hopeless");
    }

    @Test
    void flyingThreatIsFightableWithARangedWeapon() {
        assertFalse(ShelterDecision.shouldTakeShelter(true, false, true),
                "a bow with ammo can shoot a flying mob");
        assertFalse(ShelterDecision.shouldTakeShelter(true, true, true),
                "any weapon plus a ranged option can answer a flying mob");
    }

    @Test
    void groundThreatIsFightableWithEitherWeapon() {
        assertFalse(ShelterDecision.shouldTakeShelter(false, true, false),
                "a melee weapon is enough against a ground mob");
        assertFalse(ShelterDecision.shouldTakeShelter(false, false, true),
                "a ranged weapon is enough against a ground mob");
        assertFalse(ShelterDecision.shouldTakeShelter(false, true, true),
                "both weapons trivially fightable");
    }

    @Test
    void groundThreatWithNoWeaponAtAllRetreats() {
        assertTrue(ShelterDecision.shouldTakeShelter(false, false, false),
                "an unarmed bot cannot fight a ground mob either");
    }
}
