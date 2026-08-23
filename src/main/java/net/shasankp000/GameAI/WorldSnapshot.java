package net.shasankp000.GameAI;

import net.shasankp000.Entity.EntityDetails;
import net.shasankp000.PlayerUtils.SelectedItemDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the bot's <em>world observation</em> — the 18 fields
 * that describe "what the bot sees right now" (position, health, hunger,
 * surroundings, held items, time, dimension, structure context).
 *
 * <p>Extracted from {@link State} (Phase: god-object split, step 1). {@link State}
 * previously mixed two responsibilities: (a) an immutable world observation and
 * (b) mutable RL learning state ({@code riskMap}/{@code podMap}). This class owns
 * (a) only. {@link State} now <em>composes</em> a {@code WorldSnapshot} and
 * delegates its observation getters to it, keeping every existing caller and
 * constructor signature unchanged.
 *
 * <p>This class is a pure value holder: all fields are {@code final}, and there
 * are no setters. It is {@link Serializable} to match {@link State}'s existing
 * persistence contract.
 */
public final class WorldSnapshot implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int botX, botY, botZ;
    private final int frostLevel;
    private final double distanceToHostileEntity;
    private final double distanceToDangerZone;
    private final int botHealth;
    private final List<String> hotBarItems;         // already-serialized item names
    private final SelectedItemDetails selectedItem;
    private final String timeOfDay;
    private final String dimensionType;
    private final int botHungerLevel;
    private final int botOxygenLevel;
    private final String offhandItem;               // already-serialized item name
    private final Map<String, String> armorItems;   // already-serialized item names
    private final List<EntityDetails> nearbyEntities;
    private final List<String> nearbyBlocks;
    private final boolean inDangerousStructure;

    public WorldSnapshot(
            int botX, int botY, int botZ,
            List<EntityDetails> nearbyEntities,
            List<String> nearbyBlocks,
            double distanceToHostileEntity,
            int botHealth,
            double distanceToDangerZone,
            List<String> hotBarItems,
            SelectedItemDetails selectedItem,
            String timeOfDay,
            String dimensionType,
            int botHungerLevel,
            int botOxygenLevel,
            int frostLevel,
            String offhandItem,
            Map<String, String> armorItems) {
        this.botX = botX;
        this.botY = botY;
        this.botZ = botZ;
        this.frostLevel = frostLevel;
        this.distanceToHostileEntity = distanceToHostileEntity;
        this.distanceToDangerZone = distanceToDangerZone;
        this.botHealth = botHealth;
        this.hotBarItems = hotBarItems;
        this.selectedItem = selectedItem;
        this.timeOfDay = timeOfDay;
        this.dimensionType = dimensionType;
        this.botHungerLevel = botHungerLevel;
        this.botOxygenLevel = botOxygenLevel;
        this.offhandItem = offhandItem;
        this.armorItems = armorItems;
        this.nearbyEntities = nearbyEntities;
        this.nearbyBlocks = nearbyBlocks;
        this.inDangerousStructure = detectDangerousStructure(nearbyBlocks, dimensionType);
    }

    public int getBotX() { return botX; }
    public int getBotY() { return botY; }
    public int getBotZ() { return botZ; }
    public int getFrostLevel() { return frostLevel; }
    public double getDistanceToHostileEntity() { return distanceToHostileEntity; }
    public double getDistanceToDangerZone() { return distanceToDangerZone; }
    public int getBotHealth() { return botHealth; }
    public List<String> getHotBarItems() { return hotBarItems; }
    public SelectedItemDetails getSelectedItemStack() { return selectedItem; }
    public String getSelectedItem() { return selectedItem.getName(); }
    public String getTimeOfDay() { return timeOfDay; }
    public String getDimensionType() { return dimensionType; }
    public int getBotHungerLevel() { return botHungerLevel; }
    public int getBotOxygenLevel() { return botOxygenLevel; }
    public String getOffhandItem() { return offhandItem; }
    public Map<String, String> getArmorItems() { return armorItems; }
    public List<EntityDetails> getNearbyEntities() { return nearbyEntities; }
    public List<String> getNearbyBlocks() { return nearbyBlocks; }
    public boolean isInDangerousStructure() { return inDangerousStructure; }

    /**
     * Structure detection: checks dimension and requires a cluster of
     * structure-unique blocks to reduce false positives from player builds.
     */
    static boolean detectDangerousStructure(List<String> nearbyBlocks, String dimensionType) {
        String dim = dimensionType != null ? dimensionType : "minecraft:overworld";
        int fortressBlocks = 0, bastionBlocks = 0, trialBlocks = 0, dungeonBlocks = 0;

        for (String block : nearbyBlocks) {
            if (dim.contains("nether") && (block.contains("nether_bricks") || block.contains("nether_brick_fence") || block.contains("nether_brick_stairs"))) {
                fortressBlocks++;
            }
            if (dim.contains("nether") && (block.contains("gilded_blackstone") || block.contains("polished_blackstone_bricks") || block.contains("chiseled_polished_blackstone"))) {
                bastionBlocks++;
            }
            if (dim.contains("overworld") && (block.contains("trial_spawner") || block.contains("copper_bulb") || block.contains("tuff_bricks") || block.contains("chiseled_tuff_bricks"))) {
                trialBlocks++;
            }
            if (dim.contains("overworld") && (block.contains("mossy_cobblestone") || block.contains("spawner"))) {
                dungeonBlocks++;
            }
        }
        return fortressBlocks >= 3 || bastionBlocks >= 3 || trialBlocks >= 2 || dungeonBlocks >= 2;
    }
}
