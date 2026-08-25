package net.shasankp000.GameAI;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Threat-scoring and target-selection for reactive combat.
 *
 * <p>Extracted from {@code BotEventHandler} (god-object split, step 4) so that
 * combat target prioritisation is a self-contained deep module behind a small
 * interface: {@link #selectHighestThreatTarget}, {@link #calculateBaseThreatForEntity},
 * {@link #getTargetSelectionReason}. This is pure computation over entity type,
 * distance, and equipment — no world mutation and no shared mutable state.
 */
public final class ThreatEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-player");

    private ThreatEvaluator() {
    }

    /**
     * Selects the highest-threat hostile entity from a list, prioritising by a
     * type- and distance-based threat score.
     *
     * @param bot             the bot selecting a target
     * @param hostileEntities nearby hostile entities
     * @return the highest-threat entity, or null if none suitable
     */
    public static Entity selectHighestThreatTarget(ServerPlayer bot, List<Entity> hostileEntities) {
        if (hostileEntities.isEmpty()) {
            return null;
        }

        Entity highestThreatEntity = null;
        double highestThreat = -1.0;

        for (Entity entity : hostileEntities) {
            double distance = Math.sqrt(entity.distanceToSqr(bot));

            // Calculate base threat based on entity type
            double baseThreat = calculateBaseThreatForEntity(entity, distance);

            // Apply distance modifier (closer = more dangerous)
            double distanceModifier = 0.0;
            if (distance < 3.0) {
                distanceModifier = 10.0; // Critical threat - very close
            } else if (distance < 6.0) {
                distanceModifier = 5.0; // High threat - close range
            } else if (distance < 10.0) {
                distanceModifier = 2.0; // Medium threat
            }

            double totalThreat = baseThreat + distanceModifier;

            System.out.println("Target analysis: " + entity.getName().getString() +
                " at " + String.format("%.1f", distance) + "m" +
                " - Base: " + String.format("%.1f", baseThreat) +
                ", Distance bonus: " + String.format("%.1f", distanceModifier) +
                ", Total: " + String.format("%.1f", totalThreat));

            // Select highest threat
            if (totalThreat > highestThreat) {
                highestThreat = totalThreat;
                highestThreatEntity = entity;
            }
        }

        if (highestThreatEntity != null) {
            String targetName = highestThreatEntity.getName().getString();
            double distance = Math.sqrt(highestThreatEntity.distanceToSqr(bot));

            System.out.println("⚔ Selected Priority Target: " + targetName +
                " (Threat: " + String.format("%.1f", highestThreat) +
                ", Distance: " + String.format("%.1f", distance) + "m)");

            // Log reason if multiple enemies
            if (hostileEntities.size() > 1) {
                String reason = getTargetSelectionReason(highestThreatEntity, distance);
                System.out.println("Reason: " + reason);
            }
        }

        return highestThreatEntity;
    }

    /**
     * Calculates the base threat value for an entity based on type and distance.
     */
    public static double calculateBaseThreatForEntity(Entity entity, double distance) {
        // HOSTILE PLAYERS - HIGH PRIORITY THREATS
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            double baseThreat = 30.0; // Base threat for hostile player

            // Analyze player equipment to assess threat level
            net.minecraft.world.item.ItemStack mainHand = player.getMainHandItem();
            net.minecraft.world.item.ItemStack offHand = player.getOffhandItem();

            // Check for weapons
            if (mainHand.getItem() instanceof net.minecraft.world.item.Item) {
                baseThreat += 15.0; // Sword wielding player
            } else if (mainHand.getItem() instanceof net.minecraft.world.item.AxeItem) {
                baseThreat += 12.0; // Axe wielding player
            } else if (mainHand.getItem() instanceof net.minecraft.world.item.BowItem ||
                      mainHand.getItem() instanceof net.minecraft.world.item.CrossbowItem) {
                baseThreat += 20.0; // Ranged weapon - very dangerous
            } else if (mainHand.getItem() instanceof net.minecraft.world.item.TridentItem) {
                baseThreat += 18.0; // Trident
            }

            // Check for shield (defensive capability)
            if (offHand.getItem() instanceof net.minecraft.world.item.ShieldItem) {
                baseThreat += 8.0; // Player with shield is more dangerous
            }

            // Check armor (increases survivability = higher threat)
            int armorPieces = 0;
            for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                net.minecraft.world.item.ItemStack armorSlot = player.getItemBySlot(slot);
                if (!armorSlot.isEmpty()) {
                    armorPieces++;
                    // Diamond/Netherite armor is particularly dangerous
                    String armorName = armorSlot.getItem().toString().toLowerCase();
                    if (armorName.contains("diamond") || armorName.contains("netherite")) {
                        baseThreat += 5.0;
                    } else {
                        baseThreat += 2.0;
                    }
                }
            }

            // Close range player = critical threat
            if (distance < 4.0) {
                baseThreat += 15.0;
            }

            LOGGER.info("⚔ Hostile player threat analysis: {} - Base: {}, Equipment bonus included",
                player.getName().getString(), String.format("%.1f", baseThreat));

            return baseThreat;
        }

        // MOB THREATS
        String entityType = entity.getName().getString().toLowerCase();
        double baseThreat = 5.0;

        // EXPLOSIVE THREATS - HIGHEST PRIORITY
        if (entityType.contains("creeper")) {
            baseThreat = 50.0;
            if (distance <= 3.0) baseThreat += 30.0;
        }
        // MAXIMUM DANGER MOBS
        else if (entityType.contains("warden")) baseThreat = 100.0;
        else if (entityType.contains("ravager")) baseThreat = 40.0;
        // RANGED ATTACKERS
        else if (entityType.contains("skeleton") || entityType.contains("stray")) baseThreat = 20.0;
        else if (entityType.contains("witch")) baseThreat = 25.0;
        else if (entityType.contains("blaze")) baseThreat = 30.0;
        else if (entityType.contains("ghast")) baseThreat = 35.0;
        else if (entityType.contains("drowned") && distance > 5.0) baseThreat = 15.0;
        else if (entityType.contains("pillager")) baseThreat = 18.0;
        // FLYING THREATS
        else if (entityType.contains("phantom")) baseThreat = 22.0;
        // MELEE THREATS
        else if (entityType.contains("zombie") || entityType.contains("husk")) baseThreat = 8.0;
        else if (entityType.contains("spider") || entityType.contains("cave_spider")) baseThreat = 12.0;
        else if (entityType.contains("enderman")) baseThreat = 15.0;
        else if (entityType.contains("vindicator")) baseThreat = 25.0;
        else if (entityType.contains("piglin")) baseThreat = 10.0;
        else if (entityType.contains("slime") || entityType.contains("magma_cube")) baseThreat = 6.0;
        else if (entityType.contains("silverfish")) baseThreat = 4.0;

        return baseThreat;
    }

    /**
     * Returns a human-readable reason for why a target was selected.
     */
    public static String getTargetSelectionReason(Entity entity, double distance) {
        String name = entity.getName().getString().toLowerCase();
        if (name.contains("creeper")) return "Explosive threat - highest priority";
        if (name.contains("warden")) return "Maximum danger mob";
        if (name.contains("skeleton") || name.contains("stray")) return "Ranged attacker";
        if (name.contains("witch")) return "Potion thrower - high threat";
        if (entity instanceof net.minecraft.world.entity.player.Player) return "Hostile player detected";
        if (distance < 3.0) return "Critical range - immediate threat";
        return "Closest/highest threat in range";
    }
}
