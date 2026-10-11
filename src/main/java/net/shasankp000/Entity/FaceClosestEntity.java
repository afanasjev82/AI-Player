package net.shasankp000.Entity;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

public class FaceClosestEntity {

    /**
     * Fraction of a mob's height that aiming targets.
     *
     * <p>Aiming at the body rather than the feet is what makes an attack
     * raycast land. Carpet's {@code /player attack} picks its target with a ray
     * cast from the bot's <em>eyes</em>, so aiming feet-to-feet (which yields
     * pitch 0 against a level mob) sends the ray clean over the head of anything
     * shorter than the bot — a 0.9-tall spider is below a 1.62-high eye line.
     * Matches the convention already used for ranged aiming in
     * {@code RangedWeaponUtils}.
     */
    public static final double AIM_HEIGHT_FRACTION = 0.6;

    /** Yaw/pitch pair, in Minecraft's convention. */
    public record Aim(float yaw, float pitch) {}

    /**
     * Minecraft yaw for a horizontal displacement. Split out as a pure function
     * so the aim maths is testable without a live world.
     *
     * @param dx east/west displacement to the target
     * @param dz north/south displacement to the target
     * @return yaw where 0 is south (+Z) and -90 is east (+X)
     */
    public static float aimYaw(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /**
     * Minecraft pitch for a vertical displacement. Split out as a pure function
     * so the aim maths is testable without a live world.
     *
     * @param dy                 vertical displacement from the bot's eye to the aim point
     * @param horizontalDistance horizontal distance to the target
     * @return pitch, positive when looking down
     */
    public static float aimPitch(double dy, double horizontalDistance) {
        return (float) Math.toDegrees(-Math.atan2(dy, horizontalDistance));
    }

    /** Aim angles from {@code from} to {@code to}. */
    public static Aim aimAngles(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        return new Aim(aimYaw(d.x, d.z), aimPitch(d.y, horizontal));
    }

    /** The point on {@code target} that aiming should look at. */
    private static Vec3 aimPoint(Entity target) {
        return target.position().add(0, target.getBbHeight() * AIM_HEIGHT_FRACTION, 0);
    }

    /**
     * Face a specific entity, aiming from the bot's eye at the entity's body.
     *
     * <p>Prefer this over {@link #faceClosestEntity} whenever the caller already
     * knows which entity it is about to attack: the combat path can select a
     * <em>high-threat</em> target that is not the nearest one, and facing the
     * nearest would point the attack raycast at the wrong mob.
     */
    public static void faceEntity(ServerPlayer bot, Entity target) {
        if (bot == null || target == null) {
            return;
        }
        Vec3 botEyePos = bot.position().add(0, bot.getEyeHeight(), 0);
        Aim aim = aimAngles(botEyePos, aimPoint(target));
        bot.setYRot(aim.yaw());
        bot.setXRot(aim.pitch());
    }

    public static void faceClosestEntity(ServerPlayer bot, List<Entity> entities) {
        if (bot == null || entities == null || entities.isEmpty()) {
            return;
        }

        Entity closestEntity = null;
        double closestDistance = Double.MAX_VALUE;

        // Find the closest entity
        for (Entity entity : entities) {
            double distance = bot.distanceToSqr(entity);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestEntity = entity;
            }
        }

        faceEntity(bot, closestEntity);
    }

    /**
     * Face a specific projectile entity - used for defense (blocking/tracking)
     */
    public static void faceProjectile(ServerPlayer bot, Projectile projectile) {
        if (projectile == null || !projectile.isAlive()) {
            return;
        }
        faceEntity(bot, projectile);
    }
}
