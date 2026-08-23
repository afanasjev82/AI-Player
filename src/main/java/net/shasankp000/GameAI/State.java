package net.shasankp000.GameAI;

import net.minecraft.world.item.ItemStack;
import net.shasankp000.Entity.EntityDetails;
import net.shasankp000.PlayerUtils.SelectedItemDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class State implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Recommended for Serializable classes

    private static final double DISTANCE_TOLERANCE = 8.0; // Maximum allowable difference
    private static final double ENTITY_SIMILARITY_THRESHOLD = 0.5; // 50% overlap
    private static final double BLOCK_SIMILARITY_THRESHOLD = 0.5; // 50% overlap

    // Immutable world observation — owned by WorldSnapshot (god-object split).
    private final WorldSnapshot snapshot;

    // Mutable RL learning state — owned by LearningState (god-object split).
    private final LearningState learning;



    // Full constructor for custom action
    public State(int botX, int botY, int botZ, List<EntityDetails> nearbyEntities, List<String> nearbyBlocks, double distanceToHostileEntity, int botHealth, double distanceToDangerZone,
                 List<ItemStack> hotBarItems, SelectedItemDetails selectedItem, String timeOfDay, String dimensionType,
                 int botHungerLevel, int botOxygenLevel, int frostLevel ,ItemStack offhandItem, Map<String, ItemStack> armorItems,
                 StateActions.Action actionTaken, Map<StateActions.Action, Double> riskMap , double riskAppetite, Map<StateActions.Action, Double> podMap) {

        // Convert ItemStack to Strings for serialization, then build the
        // immutable observation snapshot.
        this.snapshot = new WorldSnapshot(
                botX, botY, botZ,
                nearbyEntities,
                nearbyBlocks,
                distanceToHostileEntity,
                botHealth,
                distanceToDangerZone,
                serializeItemStackList(hotBarItems),
                selectedItem,
                timeOfDay,
                dimensionType,
                botHungerLevel,
                botOxygenLevel,
                frostLevel,
                serializeItemStack(offhandItem),
                serializeArmorItems(armorItems)
        );

        this.learning = new LearningState(actionTaken, riskAppetite, riskMap, podMap);
    }

    // Getters for state variables (delegated to the immutable snapshot)
    public int getBotX() { return snapshot.getBotX(); }
    public int getBotY() { return snapshot.getBotY(); }
    public int getBotZ() { return snapshot.getBotZ(); }
    public double getDistanceToHostileEntity() { return snapshot.getDistanceToHostileEntity(); }
    public int getBotHealth() { return snapshot.getBotHealth(); }
    public double getDistanceToDangerZone() { return snapshot.getDistanceToDangerZone(); }
    public List<String> getHotBarItems() { return snapshot.getHotBarItems(); }
    public String getSelectedItem() { return snapshot.getSelectedItem(); }
    public SelectedItemDetails getSelectedItemStack() { return snapshot.getSelectedItemStack(); }
    public String getTimeOfDay() { return snapshot.getTimeOfDay(); }
    public String getDimensionType() { return snapshot.getDimensionType(); }
    public int getBotHungerLevel() { return snapshot.getBotHungerLevel(); }
    public int getBotOxygenLevel() { return snapshot.getBotOxygenLevel(); }
    public String getOffhandItem() { return snapshot.getOffhandItem(); }
    public Map<String, String> getArmorItems() { return snapshot.getArmorItems(); }
    public StateActions.Action getActionTaken() { return learning.getActionTaken(); }
    public List<EntityDetails> getNearbyEntities() { return snapshot.getNearbyEntities();}
    public List<String> getNearbyBlocks() { return snapshot.getNearbyBlocks(); }
    public int getFrostLevel() { return snapshot.getFrostLevel(); }
    public Map<StateActions.Action, Double> getRiskMap() { return learning.getRiskMap();}
    public double getRiskAppetite() {return learning.getRiskAppetite();}
    public Map<StateActions.Action, Double> getPodMap() {return learning.getPodMap();}
    public boolean isInDangerousStructure() { return snapshot.isInDangerousStructure(); }


    public void setPodMap(Map<StateActions.Action, Double> podMap) {
        this.learning.setPodMap(podMap);
    }

    public void setRiskMap(Map<StateActions.Action, Double> riskMap) {
        this.learning.setRiskMap(riskMap);
    }


    // Serialization helpers
    public static String serializeItemStack(ItemStack stack) {
        return stack != null ? stack.getItem().toString() : "empty";
    }

    public static List<String> serializeItemStackList(List<ItemStack> stacks) {
        return stacks.stream().map(State::serializeItemStack).collect(Collectors.toList());
    }

    public static Map<String, String> serializeArmorItems(Map<String, ItemStack> armorItems) {
        return armorItems.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> serializeItemStack(e.getValue())));
    }

    public boolean isOptimal() {

        // Set optimal values
        int optimalBotHealth = 20; // Full health
        int optimalBotHunger = 20; // No hunger
        int optimalBotOxygenLevel = 300; // Full oxygen
        int optimalFrostLevel = 0; // Not freezing

        // Get current values from the input state
        int botHealth = getBotHealth();
        int botHunger = getBotHungerLevel();
        int botOxygen = getBotOxygenLevel();
        double distanceToDangerZone = getDistanceToDangerZone();
        int frostLevel = getFrostLevel();

        // Check for minimum requirements for optimality
        if (botHealth >= optimalBotHealth / 2 && botHunger >= optimalBotHunger / 2 && botOxygen >= optimalBotOxygenLevel / 2 && frostLevel <= optimalFrostLevel) {

            List<EntityDetails> hostileEntities = getNearbyEntities().stream()
                    .filter(EntityDetails::isHostile)
                    .toList();

            // If no hostile entities and not in a danger zone, the state is optimal
            return hostileEntities.isEmpty() && distanceToDangerZone == 0;
        }

        return false;
    }



    // Readable toString for debugging purposes
    @Override
    public String toString() {
        return "State{" +
                "bucketX =" + getBotX() +
                ", bucketY =" + getBotY() +
                ", bucketZ =" + getBotZ() +
                ", nearbyEntities = " + getNearbyEntities() +
                ", nearbyBlocks = " + getNearbyBlocks() +
                ", inDangerousStructure = " + isInDangerousStructure() +
                ", distanceToHostileEntity = " + getDistanceToHostileEntity() +
                ", distanceToDangerZone = " + getDistanceToDangerZone() +
                ", botHealth = " + getBotHealth() +
                ", hotBarItems = " + getHotBarItems() +
                ", selectedItem = '" + getSelectedItem() + '\'' +
                ", timeOfDay = '" + getTimeOfDay() + '\'' +
                ", dimensionType = '" + getDimensionType() + '\'' +
                ", botHungerLevel = " + getBotHungerLevel() +
                ", botOxygenLevel = " + getBotOxygenLevel() +
                ", botFrostLevel = " + getFrostLevel() +
                ", offhandItem ='" + getOffhandItem() + '\'' +
                ", armorItems =" + getArmorItems() +
                ", actionTaken =" + learning.getActionTaken() +
                ", riskMap = " + learning.getRiskMap() +
                ", riskAppetite = " + learning.getRiskAppetite() +
                ", podMap = " + learning.getPodMap() +
                '}';
    }

    public static boolean isStateConsistent(State lastState, State currentState) {
        if (lastState == null) return false;

        // OPTIMIZATION: Check fastest comparisons first (early termination)
        // Exact match for categorical parameters (very fast)
        if (!lastState.getTimeOfDay().equals(currentState.getTimeOfDay())) return false;
        if (!lastState.getDimensionType().equals(currentState.getDimensionType())) return false;

        // Numeric comparison with tolerance (fast)
        boolean distanceToHostileEntitySimilar = Math.abs(lastState.getDistanceToHostileEntity() - currentState.getDistanceToHostileEntity()) <= DISTANCE_TOLERANCE;
        boolean distanceToDangerZoneSimilar = Math.abs(lastState.getDistanceToDangerZone() - currentState.getDistanceToDangerZone()) <= DISTANCE_TOLERANCE;

        // Overlap check for collections (EXPENSIVE - do last)
        boolean nearbyEntitiesSimilar = calculateEntityOverlap(lastState.getNearbyEntities(), currentState.getNearbyEntities()) >= ENTITY_SIMILARITY_THRESHOLD;
        boolean nearbyBlocksSimilar = calculateBlockOverlap(lastState.getNearbyBlocks(), currentState.getNearbyBlocks()) >= BLOCK_SIMILARITY_THRESHOLD;


        // Combine all checks
        return distanceToHostileEntitySimilar &&
                distanceToDangerZoneSimilar &&
                nearbyEntitiesSimilar ||
                nearbyBlocksSimilar;
    }

    private static double calculateBlockOverlap(List<String> lastBlocks, List<String> currentBlocks) {
        if (lastBlocks.isEmpty() || currentBlocks.isEmpty()) {
            return 0.0; // No overlap if either list is empty
        }

        // Convert last blocks to a set for O(1) lookup instead of nested streams
        var lastBlockSet = new java.util.HashSet<>(lastBlocks);

        // Count similar blocks with optimized lookup
        long similarBlocksCount = currentBlocks.stream()
                .filter(lastBlockSet::contains)
                .count();

        // Calculate overlap ratio
        return (double) similarBlocksCount / Math.max(lastBlocks.size(), currentBlocks.size());
    }


    private static double calculateEntityOverlap(List<EntityDetails> lastEntities, List<EntityDetails> currentEntities) {
        if (lastEntities.isEmpty() || currentEntities.isEmpty()) {
            return 0.0; // No overlap if either list is empty
        }

        // Convert entity names to a set for O(1) lookup instead of nested streams
        var lastEntityNameSet = lastEntities.stream()
                .map(EntityDetails::getName)
                .collect(java.util.stream.Collectors.toSet());

        // Count exact name matches with optimized lookup
        long exactNameMatches = currentEntities.stream()
                .map(EntityDetails::getName)
                .filter(lastEntityNameSet::contains)
                .count();

        // Calculate overlap ratio (based on exact matches)
        return (double) exactNameMatches / Math.max(lastEntities.size(), currentEntities.size());
    }
}
