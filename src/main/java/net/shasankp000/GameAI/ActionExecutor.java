package net.shasankp000.GameAI;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.shasankp000.Entity.AutoFaceEntity;
import net.shasankp000.Entity.FaceClosestEntity;
import net.shasankp000.GameAI.autonomous.NearbyBedSleepController;
import net.shasankp000.PlayerUtils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * Executes RL-selected {@link StateActions.Action}s against the live world.
 *
 * <p>Extracted from {@link BotEventHandler} (god-object split, step 5): the
 * action-dispatch concern (enum→command dispatch, the string→command switch,
 * and reward shaping) is now a self-contained deep module. {@link BotEventHandler}
 * keeps only the RL loop (state sampling, policy selection, Q-update) and calls
 * into this class to act.
 *
 * <p>World access is injected via explicit {@code server}/{@code bot} parameters
 * rather than the former shared static fields, so the dispatch logic no longer
 * depends on {@link BotEventHandler}'s mutable statics. The only retained mutable
 * state is the action tracker and the per-action result flags, which are read back
 * by the RL loop to shape rewards.
 */
public final class ActionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("ai-player");

    // Action execution tracking - prevents action spam and ensures completion.
    private static final ActionTracker actionTracker = new ActionTracker();

    // Result of the most recent SLEEP/USE_ITEM action, read back by the RL loop.
    private static volatile boolean lastSleepActionSucceeded = false;
    private static volatile FoodConsumptionTool.ConsumptionResult lastFoodConsumption =
            FoodConsumptionTool.ConsumptionResult.notAttempted();

    // Surface-depth penalty: discourage aimless burrowing below ground level.
    // Only applies beyond this depth below the surface, so normal walking on
    // slightly-undulating terrain isn't punished. Deliberate mining/tasks that
    // bring the bot underground still work — this only shapes the *reward*, it
    // does not block any action.
    private static final int UNDERGROUND_THRESHOLD = 5;        // blocks below surface before penalty kicks in
    private static final double UNDERGROUND_PENALTY_PER_BLOCK = 1.5; // reward lost per block below threshold

    // Deterministic self-defense rate-limit. AutoFaceEntity re-invokes the
    // combat handler every tick (33ms), so without a cooldown the fallback
    // would spam an attack (and a log line) every tick. One attack per this
    // interval is plenty for a reactive self-defense response.
    private static volatile long lastCombatFallbackAt = 0L;
    private static final long COMBAT_FALLBACK_COOLDOWN_MS = 1000L; // 1 second

    private ActionExecutor() {
    }

    /**
     * Rate-limited deterministic self-defense attack.
     *
     * <p>AutoFaceEntity re-invokes the combat handler every tick, so an
     * unthrottled fallback would attack (and log) every ~33ms. This wrapper
     * enforces a 1s cooldown between fallback attacks while keeping the
     * "never passive" guarantee. It only logs when a real attack is performed;
     * the "nothing within reach" case is silent to avoid re-spamming the log.
     */
    public static void attackFallbackRateLimited(ServerPlayer bot, String reason) {
        long now = System.currentTimeMillis();
        long last = lastCombatFallbackAt;
        if (now - last < COMBAT_FALLBACK_COOLDOWN_MS) {
            return; // within cooldown — skip silently
        }
        lastCombatFallbackAt = now;

        String result = CombatTool.attackNearestHostileBlocking(bot, 8.0);
        // Only log a real attack; "No hostile mobs within N blocks" means the
        // detected hostile was beyond melee reach, which is not worth a log
        // line every second.
        if (result != null && result.startsWith("Attacked")) {
            LOGGER.info("[combat] {} — {}", reason, result);
        }
    }

    public static void executeAction(StateActions.Action chosenAction, CommandSourceStack botSource,
                                     MinecraftServer server, ServerPlayer bot) {
        lastSleepActionSucceeded = false;
        lastFoodConsumption = FoodConsumptionTool.ConsumptionResult.notAttempted();
        switch (chosenAction) {
            case MOVE_FORWARD -> performAction("moveForward", botSource, server, bot);
            case MOVE_BACKWARD -> performAction("moveBackward", botSource, server, bot);
            case TURN_LEFT -> performAction("turnLeft", botSource, server, bot);
            case TURN_RIGHT -> performAction("turnRight", botSource, server, bot);
            case JUMP -> performAction("jump", botSource, server, bot);
            case SNEAK -> performAction("sneak", botSource, server, bot);
            case SPRINT -> performAction("sprint", botSource, server, bot);
            case STOP_SNEAKING -> performAction("unsneak", botSource, server, bot);
            case STOP_SPRINTING -> performAction("unsprint", botSource, server, bot);
            case STOP_MOVING -> performAction("stopMoving", botSource, server, bot);
            case USE_ITEM -> {
                ServerPlayer actingBot = botSource.getPlayer();
                if (actingBot != null
                        && actingBot.getFoodData().getFoodLevel() <= 13
                        && FoodConsumptionTool.hasSafeFood(actingBot)) {
                    lastFoodConsumption = FoodConsumptionTool.consumeBestFood(actingBot);
                    LOGGER.info("RL food action for '{}': {}", botSource.getTextName(),
                            lastFoodConsumption.message());
                } else {
                    performAction("useItem", botSource, server, bot);
                }
            }
            case EQUIP_ARMOR -> armorUtils.autoEquipArmor(bot);
            case ATTACK -> performAction("attack", botSource, server, bot);
            case SHOOT_ARROW -> performAction("shootArrow", botSource, server, bot);
            case EVADE -> performAction("evade", botSource, server, bot);
            case SLEEP -> {
                lastSleepActionSucceeded = NearbyBedSleepController.attemptFromRl(botSource.getPlayer());
                LOGGER.info("RL sleep action for '{}' {}", botSource.getTextName(),
                        lastSleepActionSucceeded ? "succeeded" : "did not find a usable bed");
            }
            case HOTBAR_1 -> performAction("hotbar1", botSource, server, bot);
            case HOTBAR_2 -> performAction("hotbar2", botSource, server, bot);
            case HOTBAR_3 -> performAction("hotbar3", botSource, server, bot);
            case HOTBAR_4 -> performAction("hotbar4", botSource, server, bot);
            case HOTBAR_5 -> performAction("hotbar5", botSource, server, bot);
            case HOTBAR_6 -> performAction("hotbar6", botSource, server, bot);
            case HOTBAR_7 -> performAction("hotbar7", botSource, server, bot);
            case HOTBAR_8 -> performAction("hotbar8", botSource, server, bot);
            case HOTBAR_9 -> performAction("hotbar9", botSource, server, bot);
            case STAY -> System.out.println("Performing action: Stay and do nothing");
        }
    }

    public static double applySleepReward(StateActions.Action action, double reward) {
        if (action != StateActions.Action.SLEEP) return reward;
        return reward + (lastSleepActionSucceeded ? 50.0 : -20.0);
    }

    public static double applyFoodReward(StateActions.Action action, double reward) {
        if (action != StateActions.Action.USE_ITEM) return reward;
        if (!lastFoodConsumption.attempted()) return reward;
        if (!lastFoodConsumption.success()) return reward - 15.0;
        return reward + 20.0 + (lastFoodConsumption.hungerGained() * 4.0);
    }

    /**
     * Penalize the bot for going deep underground on its own.
     *
     * <p>Returns the reward unchanged when the bot is at/near the surface
     * (within {@value #UNDERGROUND_THRESHOLD} blocks below the highest solid
     * block at its XZ column), or when the depth is part of a legitimate
     * goal context. Otherwise subtracts a depth-proportional penalty, so the
     * bot learns that aimless burrowing is undesirable but deliberate
     * mining/tasks are not discouraged.
     *
     * <p>The surface reference uses {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES},
     * which tracks the top solid (non-leaf) block — a stable "ground level"
     * unaffected by tree canopies.
     */
    public static double applySurfaceDepthPenalty(ServerPlayer bot, double reward) {
        if (bot == null || bot.level() == null || bot.level().isClientSide()) {
            return reward;
        }
        int botY = bot.blockPosition().getY();
        int surfaceY = bot.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                bot.blockPosition().getX(),
                bot.blockPosition().getZ());

        int depth = surfaceY - botY;
        if (depth <= UNDERGROUND_THRESHOLD) {
            return reward; // at/near surface — no penalty
        }

        // Progressive penalty: shallow dips are cheap, deep burrows are not.
        double penalty = UNDERGROUND_PENALTY_PER_BLOCK * (depth - UNDERGROUND_THRESHOLD);
        LOGGER.debug("[reward] Surface-depth penalty: botY={}, surfaceY={}, depth={}, penalty={}",
                botY, surfaceY, depth, penalty);
        return reward - penalty;
    }

    /** Whether the most recent {@code SLEEP} action succeeded. */
    public static boolean wasLastSleepActionSuccessful() {
        return lastSleepActionSucceeded;
    }

    /** The result of the most recent {@code USE_ITEM} (food) action. */
    public static FoodConsumptionTool.ConsumptionResult getLastFoodConsumption() {
        return lastFoodConsumption;
    }

    // ── Action-tracking (in-flight action lifecycle) ─────────────────────────

    public static boolean isActionInProgress(String botName) {
        return actionTracker.isInProgress(botName);
    }

    public static void startAction(String botName, String actionName) {
        actionTracker.start(botName, actionName);
    }

    public static void completeAction(String botName) {
        actionTracker.complete(botName);
    }

    public static void waitForActionCompletion(String botName, long timeoutMs) {
        actionTracker.waitForCompletion(botName, timeoutMs);
    }

    public static String getCurrentAction(String botName) {
        return actionTracker.getCurrentAction(botName);
    }

    private static void performAction(String action, CommandSourceStack botSource,
                                      MinecraftServer server, ServerPlayer bot) {

        String botName = botSource.getTextName();


        switch (action) {
            case "moveForward":
                System.out.println("Performing action: move forward");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " move forward");
                AutoFaceEntity.isBotMoving = true;
                break;
            case "moveBackward":
                System.out.println("Performing action: move backward");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " move backward");
                AutoFaceEntity.isBotMoving = true;
                break;
            case "turnLeft":
                System.out.println("Performing action: turn left");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " turn left");
                break;
            case "turnRight":
                System.out.println("Performing action: turn right");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " turn right");
                break;
            case "jump":
                System.out.println("Performing action: jump");
                bot.jumpFromGround();
                break;
            case "sneak":
                System.out.println("Performing action: sneak");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " sneak");
                break;
            case "sprint":
                System.out.println("Performing action: sprint");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " sprint");
                break;
            case "unsneak":
                System.out.println("Performing action: unsneak");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " unsneak");
                break;
            case "unsprint":
                System.out.println("Performing action: unsprint");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " unsprint");
                break;
            case "stopMoving":
                System.out.println("Performing action: stop moving");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " stop");
                AutoFaceEntity.isBotMoving = false;
                break;
            case "useItem":
                System.out.println("Performing action: use currently selected item");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " use");
                break;
            case "attack":
                System.out.println("Performing action: ATTACK (intelligent combat)");

                // ⏸ BLOCK if action in progress
                if (isActionInProgress(botName)) {
                    System.out.println("❌ ATTACK blocked - another action in progress: " + actionTracker.getCurrentAction(botName));
                    break;
                }

                startAction(botName, "ATTACK");

                // Find highest threat hostile entity (not just closest!)
                if (AutoFaceEntity.hostileEntities == null || AutoFaceEntity.hostileEntities.isEmpty()) {
                    System.out.println("No hostile entities to attack");
                    completeAction(botName);
                    break;
                }

                // ✨ INTELLIGENT TARGETING: Prioritize high-threat entities (e.g., Creeper > Zombie)
                Entity attackTarget = ThreatEvaluator.selectHighestThreatTarget(bot, AutoFaceEntity.hostileEntities);

                if (attackTarget == null) {
                    System.out.println("Could not find attack target");
                    completeAction(botName);
                    break;
                }

                double distanceToTarget = Math.sqrt(attackTarget.distanceToSqr(bot));
                boolean hasRangedWeapon = RangedWeaponUtils.hasBowOrCrossbow(bot);
                boolean hasAmmo = RangedWeaponUtils.hasArrows(bot);

                System.out.println("Target: " + attackTarget.getName().getString() +
                                 " at " + String.format("%.1f", distanceToTarget) + "m");
                System.out.println("Ranged weapon: " + hasRangedWeapon + ", Ammo: " + hasAmmo);

                // Decision logic: Use ranged if available and target is far, otherwise melee
                if (hasRangedWeapon && hasAmmo && distanceToTarget > 4.0) {
                    // RANGED ATTACK STRATEGY
                    System.out.println("Using RANGED attack (distance > 4m)");

                    // Execute shooting command synchronously
                    server.getCommands().performPrefixedCommand(botSource, "/bot shoot_arrow " + botName + " false");

                    // Wait for shoot to complete (with timeout)
                    waitForActionCompletion(botName, 3000); // 3 second max wait
                } else {
                    // MELEE ATTACK STRATEGY
                    System.out.println("Using MELEE attack (close range or no ranged weapon)");

                    // ⚔ AUTO-EQUIP BEST MELEE WEAPON (if not already holding one)
                    boolean weaponEquipped = net.shasankp000.PlayerUtils.WeaponUtils.equipBestMeleeWeapon(bot);
                    if (weaponEquipped) {
                        System.out.println("✓ Best melee weapon equipped for combat");
                    } else {
                        System.out.println("⚠ No melee weapon found, attacking with current item");
                    }

                    FaceClosestEntity.faceClosestEntity(bot, AutoFaceEntity.hostileEntities);
                    server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " attack");

                    // Melee completes instantly
                    completeAction(botName);
                }
                break;
            case "shootArrow":
                System.out.println("Performing action: SHOOT_ARROW");

                // ⏸ BLOCK if action in progress
                if (isActionInProgress(botName)) {
                    System.out.println("❌ SHOOT_ARROW blocked - another action in progress: " + actionTracker.getCurrentAction(botName));
                    break;
                }

                startAction(botName, "SHOOT_ARROW");
                server.getCommands().performPrefixedCommand(botSource, "/bot shoot_arrow " + botName + " false");

                // Wait for action completion
                waitForActionCompletion(botName, 3000);
                break;

            case "hotbar1":
                System.out.println("Performing action: Select hotbar slot 1");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 1");
                break;
            case "hotbar2":
                System.out.println("Performing action: Select hotbar slot 2");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 2");
                break;
            case "hotbar3":
                System.out.println("Performing action: Select hotbar slot 3");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 3");
                break;
            case "hotbar4":
                System.out.println("Performing action: Select hotbar slot 4");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 4");
                break;
            case "hotbar5":
                System.out.println("Performing action: Select hotbar slot 5");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 5");
                break;
            case "hotbar6":
                System.out.println("Performing action: Select hotbar slot 6");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 6");
                break;
            case "hotbar7":
                System.out.println("Performing action: Select hotbar slot 7");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 7");
                break;
            case "hotbar8":
                System.out.println("Performing action: Select hotbar slot 8");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 8");
                break;
            case "hotbar9":
                System.out.println("Performing action: Select hotbar slot 9");
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " hotbar 9");
                break;

            case "evade":
                System.out.println("Performing action: EVADE");

                // ⏸ BLOCK if action in progress
                if (isActionInProgress(botName)) {
                    System.out.println("❌ EVADE blocked - another action in progress: " + actionTracker.getCurrentAction(botName));
                    break;
                }

                startAction(botName, "EVADE");

                // Find nearest hostile entity to evade from
                List<Entity> nearbyHostiles = AutoFaceEntity.detectNearbyEntities(bot, 20.0).stream()
                    .filter(e -> e instanceof Monster || e instanceof Slime)
                    .toList();

                if (!nearbyHostiles.isEmpty()) {
                    // PRIORITY 1: Check for dangerous creepers first (critical/ignited phase)
                    net.minecraft.world.entity.monster.Creeper dangerousCreeper =
                        net.shasankp000.PlayerUtils.MobThreatEvaluator.getMostDangerousCreeper(nearbyHostiles, bot);

                    Entity closestThreat;
                    if (dangerousCreeper != null) {
                        // Prioritize creeper threat (ignited or critical phase)
                        closestThreat = dangerousCreeper;
                        double distance = Math.sqrt(dangerousCreeper.distanceToSqr(bot));
                        LOGGER.warn("🧨 Prioritizing dangerous CREEPER for evasion at {}m",
                            String.format("%.1f", distance));
                    } else {
                        // No critical creeper - find closest threat normally
                        closestThreat = nearbyHostiles.stream()
                            .min(Comparator.comparingDouble(e -> e.distanceToSqr(bot)))
                            .orElse(null);
                    }

                    if (closestThreat != null) {
                        double distance = Math.sqrt(closestThreat.distanceToSqr(bot));
                        LOGGER.info("⚠ Evading from {} at {}m",
                            closestThreat.getName().getString(),
                            String.format("%.1f", distance));

                        // Check if threat is using ranged weapon and bot has shield
                        boolean isRangedThreat = false;

                        // Check for ranged mobs
                        if (closestThreat instanceof net.minecraft.world.entity.monster.skeleton.Skeleton ||
                            closestThreat instanceof net.minecraft.world.entity.monster.skeleton.WitherSkeleton ||
                            closestThreat instanceof net.minecraft.world.entity.monster.skeleton.Stray ||
                            closestThreat instanceof net.minecraft.world.entity.monster.illager.Pillager) {
                            isRangedThreat = true;
                        }

                        // Check for hostile players with ranged weapons
                        if (closestThreat instanceof net.minecraft.world.entity.player.Player player) {
                            net.minecraft.world.item.ItemStack mainHand = player.getMainHandItem();
                            net.minecraft.world.item.ItemStack activeItem = player.getUseItem();
                            String mainHandId = mainHand.isEmpty() ? "" :
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString();
                            String activeItemId = activeItem.isEmpty() ? "" :
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(activeItem.getItem()).toString();

                            if (mainHandId.contains("bow") || mainHandId.contains("crossbow") ||
                                activeItemId.contains("bow") || activeItemId.contains("crossbow")) {
                                isRangedThreat = true;
                                LOGGER.info("🎯 Hostile player {} has ranged weapon - shield defense available",
                                    player.getName().getString());
                            }
                        }

                        boolean hasShield = ProjectileDefenseUtils.hasShield(bot);
                        boolean isCloseRange = distance <= 8.0;

                        if (isRangedThreat && hasShield && isCloseRange) {
                          // Shield blocking strategy for ranged threats
                          LOGGER.info("🛡 Ranged threat detected - attempting shield block");

                          // Equip shield if not already equipped
                          if (!ProjectileDefenseUtils.hasShieldEquipped(bot)) {
                            LOGGER.info("Equipping shield from inventory...");
                            boolean equipped = ProjectileDefenseUtils.equipShieldToOffhand(bot);
                            if (!equipped) {
                              LOGGER.warn("Failed to equip shield - falling back to dodge");
                            } else {
                              LOGGER.info("✓ Shield equipped successfully");
                              // Start persistent blocking - will continue until threat changes weapon/dies/goes far
                              if (closestThreat instanceof net.minecraft.world.entity.LivingEntity) {
                                AutoFaceEntity.startPersistentBlocking(bot, (net.minecraft.world.entity.LivingEntity) closestThreat, server);
                                break; // Exit case - persistent blocking handles everything
                              }
                            }
                          } else {
                            // Shield already equipped - start persistent blocking
                            if (closestThreat instanceof net.minecraft.world.entity.LivingEntity) {
                              LOGGER.info("✓ Shield already equipped - starting persistent block");
                              AutoFaceEntity.startPersistentBlocking(bot, (net.minecraft.world.entity.LivingEntity) closestThreat, server);
                              break; // Exit case - persistent blocking handles everything
                            }
                          }
                        }

                        // Dodge/evasion strategy
                        // Calculate escape direction away from threat
                        Vec3 botPos = bot.position();
                        Vec3 threatPos = closestThreat.position();
                        Vec3 awayFromThreat = botPos.subtract(threatPos).normalize();

                        // Add randomness for unpredictability
                        double randomAngle = (Math.random() - 0.5) * Math.PI / 2.0; // ±90°
                        double cos = Math.cos(randomAngle);
                        double sin = Math.sin(randomAngle);
                        Vec3 scrambledDir = new Vec3(
                            awayFromThreat.x * cos - awayFromThreat.z * sin,
                            0,
                            awayFromThreat.x * sin + awayFromThreat.z * cos
                        ).normalize();

                        // Check obstacle clearance (20 blocks ahead)
                        double clearance = ProjectileDefenseUtils.checkObstacleClearance(bot, scrambledDir, 20.0);

                        if (clearance > 15.0) {
                          // Path is clear - use direct adaptive evasion (fast!)
                          LOGGER.info("✓ Path clear ({}m) - Direct sprint evasion", String.format("%.1f", clearance));

                          // Create threat object for evasion
                          if (closestThreat instanceof net.minecraft.world.entity.LivingEntity) {
                            PredictiveThreatDetector.DrawingBowThreat threat =
                              new PredictiveThreatDetector.DrawingBowThreat(
                                (net.minecraft.world.entity.LivingEntity) closestThreat, bot);
                            AutoFaceEntity.executeAdaptivePanicEvasion(bot, threat, server);
                          }
                        } else {
                          // Obstacles detected - use PathFinder for smart routing
                          LOGGER.warn("⚠ Obstacles at {}m - Using PathFinder navigation", String.format("%.1f", clearance));

                          // Calculate target position (10 blocks in escape direction)
                          Vec3 targetVec = botPos.add(scrambledDir.scale(10.0));
                          net.minecraft.core.BlockPos targetPos = new net.minecraft.core.BlockPos(
                            (int) Math.floor(targetVec.x),
                            (int) Math.floor(targetVec.y),
                            (int) Math.floor(targetVec.z)
                          );

                          // Find path around obstacles
                          net.minecraft.server.level.ServerLevel world = (net.minecraft.server.level.ServerLevel) bot.level();
                          List<net.shasankp000.PathFinding.PathFinder.PathNode> path =
                            net.shasankp000.PathFinding.PathFinder.calculatePath(bot.blockPosition(), targetPos, world);

                          if (!path.isEmpty()) {
                            LOGGER.info("✓ PathFinder found route with {} nodes - executing", path.size());

                            // Simplify and convert to segments
                            List<net.shasankp000.PathFinding.PathFinder.PathNode> simplified =
                              net.shasankp000.PathFinding.PathFinder.simplifyPath(path, world);
                            java.util.Queue<net.shasankp000.PathFinding.Segment> segments =
                              net.shasankp000.PathFinding.PathFinder.convertPathToSegments(simplified, true); // Sprint!

                            // Execute path with PathTracer
                            net.shasankp000.PathFinding.PathTracer.BotSegmentManager manager =
                              new net.shasankp000.PathFinding.PathTracer.BotSegmentManager(server, botSource, botName);
                            segments.forEach(manager::addSegmentJob);
                            manager.startProcessing();

                            LOGGER.info("✓ PathFinder evasion started - navigating around obstacles");
                          } else {
                            // No path found - use direct evasion as fallback
                            LOGGER.warn("⚠ PathFinder failed - using direct evasion fallback");
                            if (closestThreat instanceof net.minecraft.world.entity.LivingEntity) {
                              PredictiveThreatDetector.DrawingBowThreat threat =
                                new PredictiveThreatDetector.DrawingBowThreat(
                                  (net.minecraft.world.entity.LivingEntity) closestThreat, bot);
                              AutoFaceEntity.executeAdaptivePanicEvasion(bot, threat, server);
                            }
                          }
                        }
                      }
                    } else {
                      // No hostile entities nearby - evasion pointless
                      LOGGER.info("No threats detected - evasion unnecessary");
                      System.out.println("No threats to evade from");
                      completeAction(botName); // Complete immediately if no threat
                    }

                    // Note: EVADE completion is also handled in AutoFaceEntity.executeAdaptivePanicEvasion
                    // when evasion finishes or times out
                    break;

                default:
                    System.out.println("Invalid action");
                    break;
        }
    }
}
