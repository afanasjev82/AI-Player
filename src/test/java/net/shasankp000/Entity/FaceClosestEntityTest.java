package net.shasankp000.Entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The combat aim maths is pure, so it is tested directly.
 *
 * <p>This matters more than it looks: carpet's {@code /player attack} resolves
 * its target with a ray cast from the bot's <em>eyes</em>, so a bad pitch means
 * the ray never touches the mob. The old feet-to-feet aim produced pitch 0
 * against a level mob — a horizontal ray from eye height 1.62 that passes
 * straight over a 0.9-tall spider, which is why melee barely ever landed.
 */
class FaceClosestEntityTest {

    @Test
    void yawMatchesMinecraftCompassConventions() {
        // Minecraft yaw: 0 = south (+Z), -90 = east (+X), 90 = west (-X).
        assertEquals(0.0F, FaceClosestEntity.aimYaw(0, 1), 0.001F);
        assertEquals(-90.0F, FaceClosestEntity.aimYaw(1, 0), 0.001F);
        assertEquals(90.0F, FaceClosestEntity.aimYaw(-1, 0), 0.001F);
        assertEquals(180.0F, Math.abs(FaceClosestEntity.aimYaw(0, -1)), 0.001F);
    }

    @Test
    void pitchIsPositiveWhenLookingDown() {
        // Minecraft pitch: positive = down. Target below the eye -> positive.
        assertTrue(FaceClosestEntity.aimPitch(-1.0, 1.0) > 0);
        assertTrue(FaceClosestEntity.aimPitch(1.0, 1.0) < 0);
    }

    @Test
    void levelTargetStillNeedsPitchBecauseOfEyeHeight() {
        // The regression this fixes: at equal feet, the eye is 1.62 above the
        // mob's feet but the aim point is only 0.54 up (0.9-tall spider x 0.6),
        // so a correct aim looks DOWN. Feet-to-feet aiming produced 0 here and
        // the ray flew over the spider.
        double dy = (0.9 * FaceClosestEntity.AIM_HEIGHT_FRACTION) - 1.62;
        float pitch = FaceClosestEntity.aimPitch(dy, 1.0);
        assertTrue(pitch > 40.0F, "expected a steep downward look, got " + pitch);
        assertTrue(pitch < 50.0F, "expected a steep downward look, got " + pitch);
    }

    @Test
    void aimPointIsOnTheBodyNotTheFeet() {
        // Aiming at 0.6 of height keeps short mobs in the ray and still lands on
        // tall ones; 0.0 (the old behaviour) is what missed spiders entirely.
        assertTrue(FaceClosestEntity.AIM_HEIGHT_FRACTION > 0.0);
        assertTrue(FaceClosestEntity.AIM_HEIGHT_FRACTION <= 1.0);
    }

    @Test
    void pitchClampsForVerticalAndHorizontalExtremes() {
        // Directly overhead / underfoot must stay within +/-90.
        assertEquals(-90.0F, FaceClosestEntity.aimPitch(1.0, 0.0), 0.001F);
        assertEquals(90.0F, FaceClosestEntity.aimPitch(-1.0, 0.0), 0.001F);
        // Straight ahead, level with the eye -> no pitch at all.
        assertEquals(0.0F, FaceClosestEntity.aimPitch(0.0, 3.0), 0.001F);
    }
}
