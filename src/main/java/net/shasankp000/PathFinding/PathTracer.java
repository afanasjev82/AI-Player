package net.shasankp000.PathFinding;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.shasankp000.Commands.modCommandRegistry;
import net.shasankp000.PlayerUtils.FoodConsumptionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class PathTracer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final double WALKING_SPEED = 4.317; // blocks per second
    private static final double SPRINTING_SPEED = 5.612; // blocks per second
    private static final int MAX_RETRIES = 5; // Reduced from 10

    /**
     * Position-driven movement timeout. The bot's actual travel speed is game-
     * tick driven (and slower under server load), so a fixed wall-clock stop
     * makes it undershoot the segment and re-path forever. Instead the bot is
     * kept moving and polled until it arrives, bounded by a distance-scaled
     * timeout: {@code base + perBlock * blocks}.
     */
    private static final long SEGMENT_TIMEOUT_BASE_MS = 5_000L;
    private static final long SEGMENT_TIMEOUT_PER_BLOCK_MS = 2_500L;

    public static class BotSegmentManager {
        /** Most-recently created manager; the static movement-status API reads this. */
        private static volatile BotSegmentManager ACTIVE = null;

        private final Queue<Segment> jobQueue = new LinkedList<>();
        private final MinecraftServer server;
        private final CommandSourceStack botSource;
        private final String botName;
        private final boolean sprint;
        private int retries = 0;
        private boolean isMoving = false;
        private Segment currentSegment = null; // Track current segment
        private BlockPos finalDestination = null;

        // ✅ Add completion tracking
        private CompletableFuture<String> pathCompletionFuture = null;
        private final AtomicReference<String> finalResult = new AtomicReference<>("");

        public static boolean getBotMovementStatus() {
            BotSegmentManager m = ACTIVE;
            return m != null && m.isMoving;
        }

        /** Abandon the currently-active path (if any) and reset its state. */
        public static void clearActive() {
            BotSegmentManager m = ACTIVE;
            if (m != null) m.clearJobs();
        }

        public BotSegmentManager(MinecraftServer server, CommandSourceStack botSource, String botName) {
            this(server, botSource, botName, true);
        }

        public BotSegmentManager(MinecraftServer server, CommandSourceStack botSource, String botName, boolean sprint) {
            this.server = server;
            this.botSource = botSource;
            this.botName = botName;
            this.sprint = sprint;
            ACTIVE = this;
        }

        public void clearJobs() {
            jobQueue.clear();
            isMoving = false;
            currentSegment = null;

            // ✅ Reset completion tracking
            if (pathCompletionFuture != null && !pathCompletionFuture.isDone()) {
                pathCompletionFuture.complete("Path cleared");
            }
            pathCompletionFuture = null;
            finalResult.set("");

            LOGGER.info("Job queue flushed.");
        }

        // ✅ Add method to get completion future
        public CompletableFuture<String> getPathCompletionFuture() {
            if (pathCompletionFuture == null) {
                pathCompletionFuture = new CompletableFuture<>();
            }
            return pathCompletionFuture;
        }

        public void addSegmentJob(Segment segment) {
            jobQueue.add(segment);
            finalDestination = segment.end(); // track the overall target
        }

        public void startProcessing() {
            // ✅ Initialize completion future if not already done
            if (pathCompletionFuture == null) {
                pathCompletionFuture = new CompletableFuture<>();
            }

            if (!jobQueue.isEmpty()) {
                currentSegment = jobQueue.poll();
                executeSegment(currentSegment);
            } else {
                isMoving = false;
                currentSegment = null;

                // If this path was sprinting, stop sprinting now that it's done.
                if (sprint) {
                    server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " unsprint");
                }

                // ✅ Complete the path and set final result
                String result = tracePathOutput(botSource);
                finalResult.set(result);
                if (pathCompletionFuture != null && !pathCompletionFuture.isDone()) {
                    pathCompletionFuture.complete(result);
                }

                LOGGER.info("No more segments to process. Final result: {}", result);
            }
        }

        public Queue<Segment> getJobQueue() {
            return jobQueue;
        }

        private void executeSegment(Segment segment) {
            ServerPlayer player = botSource.getPlayer();
            if (player != null && FoodConsumptionTool.isConsumptionInProgress(player.getUUID())) {
                scheduler.schedule(() -> executeSegment(segment), 50L, TimeUnit.MILLISECONDS);
                return;
            }

            LOGGER.info("START segment: " + segment);
            updateFacing(segment);

            int distance = calculateAxisAlignedDistance(segment.start(), segment.end());

            if (distance == 0) {
                LOGGER.info("Skipping zero-length segment: {}", segment);
                waitForSegmentCompletion(segment); // instantly mark as complete
                return;
            }

            // Mark movement active BEFORE issuing the movement command so the
            // combat/autoface tick (33ms) sees it and skips its own /player
            // attack+look commands, which would otherwise cancel move-forward.
            isMoving = true;

            // Start continuous forward movement (plus sprint) and keep it
            // active until the bot physically reaches the segment end — the
            // actual speed is game-tick driven, so a fixed wall-clock stop
            // under load makes the bot stop short and re-path forever.
            modCommandRegistry.moveForward(server, botSource, botName);

            if (segment.sprint()) {
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " sprint");
            } else {
                // if was set to sprint before, stop sprinting anyways.
                server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " unsprint");
            }

            // Best-effort jump partway through the segment.
            if (segment.jump()) {
                double speed = segment.sprint() ? SPRINTING_SPEED : WALKING_SPEED;
                long half = Math.max(100L, (long) ((distance / speed) * 1000) / 2);
                scheduleAfterActiveDelay(player, half, () -> {
                    server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " jump");
                    LOGGER.info(botName + " performed a jump!");
                });
            }

            // Poll until the bot arrives (or a distance-scaled timeout), then
            // stop and delegate to waitForSegmentCompletion for advance/retry.
            pollSegmentArrival(segment);
        }

        /**
         * Polls the bot's position until it reaches the segment end (or a
         * distance-scaled timeout elapses), keeping {@code /player move forward}
         * active the whole time. This replaces the old fixed-duration stop,
         * which undershot on game-tick-driven movement and triggered the
         * "Segment not reached" re-path loop.
         */
        private void pollSegmentArrival(Segment segment) {
            int distance = Math.abs(segment.end().getX() - segment.start().getX())
                    + Math.abs(segment.end().getY() - segment.start().getY())
                    + Math.abs(segment.end().getZ() - segment.start().getZ());
            long timeoutMs = SEGMENT_TIMEOUT_BASE_MS + (long) distance * SEGMENT_TIMEOUT_PER_BLOCK_MS;
            long deadline = System.currentTimeMillis() + timeoutMs;

            Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                ServerPlayer bot = botSource.getPlayer();
                if (bot == null) {
                    modCommandRegistry.stopMoving(server, botSource, botName);
                    isMoving = false;
                    waitForSegmentCompletion(segment);
                    return;
                }
                if (FoodConsumptionTool.isConsumptionInProgress(bot.getUUID())) {
                    // Eating pauses movement; wait without advancing the deadline.
                    scheduler.schedule(poll[0], 100, TimeUnit.MILLISECONDS);
                    return;
                }
                if (hasReachedTarget(bot.blockPosition(), segment.end(), segment)) {
                    modCommandRegistry.stopMoving(server, botSource, botName);
                    isMoving = false;
                    LOGGER.info("{} reached segment target {}", botName, segment.end());
                    waitForSegmentCompletion(segment);
                    return;
                }
                if (System.currentTimeMillis() >= deadline) {
                    modCommandRegistry.stopMoving(server, botSource, botName);
                    isMoving = false;
                    LOGGER.warn("{} did not reach {} within {}ms — re-pathing",
                            botName, segment.end(), timeoutMs);
                    waitForSegmentCompletion(segment);
                    return;
                }
                scheduler.schedule(poll[0], 100, TimeUnit.MILLISECONDS);
            };
            scheduler.schedule(poll[0], 100, TimeUnit.MILLISECONDS);
        }

        /** Schedules path work by active movement time, excluding eating pauses. */
        private void scheduleAfterActiveDelay(ServerPlayer player, long delayMillis, Runnable task) {
            if (player == null) {
                scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
                return;
            }

            UUID botId = player.getUUID();
            long wallStart = System.currentTimeMillis();
            long pausedAtStart = FoodConsumptionTool.getTotalPausedMillis(botId);

            Runnable[] check = new Runnable[1];
            check[0] = () -> {
                long wallElapsed = System.currentTimeMillis() - wallStart;
                long pausedElapsed = FoodConsumptionTool.getTotalPausedMillis(botId) - pausedAtStart;
                long activeElapsed = Math.max(0L, wallElapsed - pausedElapsed);
                long remaining = delayMillis - activeElapsed;

                if (remaining <= 0L) {
                    task.run();
                } else {
                    scheduler.schedule(check[0], Math.min(remaining, 50L), TimeUnit.MILLISECONDS);
                }
            };

            scheduler.schedule(check[0], delayMillis, TimeUnit.MILLISECONDS);
        }

        private void waitForSegmentCompletion(Segment completedSegment) {
            ServerPlayer player = botSource.getPlayer();
            if (player == null) {
                LOGGER.error("Player is null, cannot continue pathfinding");
                // ✅ Complete with error
                String errorResult = "Player not found";
                finalResult.set(errorResult);
                if (pathCompletionFuture != null && !pathCompletionFuture.isDone()) {
                    pathCompletionFuture.complete(errorResult);
                }
                return;
            }

            BlockPos currentPos = player.blockPosition();

            // Get the final destination for distance checking
            BlockPos finalDestination = getFinalDestination();

            LOGGER.info("Bot at: {}, Target: {}, Final: {}", currentPos, completedSegment.end(), finalDestination);

            // Check if we've reached the segment target with improved tolerance
            if (hasReachedTarget(currentPos, completedSegment.end(), completedSegment)) {
                LOGGER.info("✅ Reached segment target: {}", completedSegment.end());
                retries = 0;
                isMoving = false;
                startProcessing(); // This will either start next segment or complete the path
                return;
            }

            // Check if we're close to the final destination and can stop
            if (isCloseToFinalDestination(currentPos, finalDestination)) {
                LOGGER.info("✅ Bot is close enough to final destination: {}", finalDestination);
                flushAllMovementTasks();
                isMoving = false;

                // ✅ Complete with success
                String result = tracePathOutput(botSource);
                finalResult.set(result);
                if (pathCompletionFuture != null && !pathCompletionFuture.isDone()) {
                    pathCompletionFuture.complete(result);
                }
                return;
            }

            // Try to find if we're already at a future segment position
            if (tryAdvancedSegmentSkip(currentPos)) {
                return;
            }

            retries++;
            LOGGER.warn("Segment not reached. Retry {}/{}", retries, MAX_RETRIES);

            // If we haven't exceeded retries, try to re-path
            if (retries < MAX_RETRIES) {
                LOGGER.info("Attempting re-pathfinding from {} to {}", currentPos, finalDestination);

                ServerLevel world = botSource.getServer().overworld();
                List<PathFinder.PathNode> newPath = PathFinder.calculatePath(currentPos, finalDestination, world);

                if (newPath.isEmpty()) {
                    LOGGER.error("Re-pathfinding failed! Stopping bot.");
                    flushAllMovementTasks();
                    isMoving = false;

                    // ✅ Complete with failure
                    String failureResult = "Re-pathfinding failed";
                    finalResult.set(failureResult);
                    if (pathCompletionFuture != null && !pathCompletionFuture.isDone()) {
                        pathCompletionFuture.complete(failureResult);
                    }
                    return;
                }

                // Clear old segments and re-add the freshly computed ones.
                // Do NOT call clearJobs() here: that completes the path future
                // with "Path cleared", making GoTo return success prematurely
                // while the re-path is still running. Also keep finalDestination
                // intact — the re-path targets the same overall destination.
                jobQueue.clear();
                currentSegment = null;

                List<PathFinder.PathNode> simplified = PathFinder.simplifyPath(newPath, world);
                Queue<Segment> newSegments = PathFinder.convertPathToSegments(simplified, sprint);

                LOGGER.info("New path generated with {} segments", newSegments.size());
                newSegments.forEach(this::addSegmentJob);

                retries = 0; // Reset retries for new path
                startProcessing();
            } else {
                LOGGER.warn("Max retries exceeded. Stopping pathfinding.");
                flushAllMovementTasks();
                isMoving = false;

                // ✅ Complete with retry failure
                String retryFailureResult = "Max retries exceeded";
                finalResult.set(retryFailureResult);
                if (pathCompletionFuture != null && !pathCompletionFuture.isDone()) {
                    pathCompletionFuture.complete(retryFailureResult);
                }
            }
        }

        private BlockPos getFinalDestination() {
            return finalDestination != null ? finalDestination
                    : (currentSegment != null ? currentSegment.end() : null);
        }

        private boolean isCloseToFinalDestination(BlockPos currentPos, BlockPos finalDestination) {
            if (finalDestination == null) return false;

            double distance = Math.sqrt(currentPos.distSqr(finalDestination));
            return distance <= 2.0; // Within 2 blocks is considered "close enough"
        }

        private boolean tryAdvancedSegmentSkip(BlockPos currentPos) {
            // Check if current position matches any upcoming segment start/end
            List<Segment> remainingSegments = new ArrayList<>(jobQueue);

            for (int i = 0; i < remainingSegments.size(); i++) {
                Segment segment = remainingSegments.get(i);

                // Check if we're at this segment's start or end
                if (isPositionMatch(currentPos, segment.start()) || isPositionMatch(currentPos, segment.end())) {
                    LOGGER.info("✅ Bot advanced to segment {}: {}", i, segment);

                    // Clear old segments up to this point (keep the completion
                    // future pending — we're advancing, not aborting).
                    jobQueue.clear();
                    currentSegment = null;

                    // Add remaining segments starting from this one
                    for (int j = i; j < remainingSegments.size(); j++) {
                        addSegmentJob(remainingSegments.get(j));
                    }

                    retries = 0;
                    startProcessing();
                    return true;
                }
            }
            return false;
        }

        private boolean isPositionMatch(BlockPos pos1, BlockPos pos2) {
            return Math.abs(pos1.getX() - pos2.getX()) <= 1 &&
                    Math.abs(pos1.getY() - pos2.getY()) <= 1 &&
                    Math.abs(pos1.getZ() - pos2.getZ()) <= 1;
        }

        // ✅ Updated to return proper format for parsing
        public static String tracePathOutput(CommandSourceStack botSource) {
            if (botSource == null || botSource.getPlayer() == null) {
                return "Bot not found";
            }

            ServerPlayer bot = botSource.getPlayer();
            BlockPos currentPos = bot.blockPosition();

            // Return in the format expected by parseOutputValues
            return String.format("Bot moved to position - x: %d y: %d z: %d",
                    currentPos.getX(), currentPos.getY(), currentPos.getZ());
        }

        // Improved target reaching detection
        private boolean hasReachedTarget(BlockPos current, BlockPos target, Segment segment) {
            ServerPlayer player = botSource.getPlayer();
            if (player == null) return false;

            // Use entity position for more accurate checking
            double playerX = player.getX();
            double playerY = player.getY();
            double playerZ = player.getZ();

            // Target center coordinates
            double targetX = target.getX() + 0.5;
            double targetY = target.getY();
            double targetZ = target.getZ() + 0.5;

            double dx = Math.abs(playerX - targetX);
            double dy = Math.abs(playerY - targetY);
            double dz = Math.abs(playerZ - targetZ);

            // Dynamic tolerance based on segment type
            double horizontalTolerance = segment.jump() ? 1.0 : 0.8;
            double verticalTolerance = segment.jump() ? 1.2 : 0.8;

            boolean reached = dx <= horizontalTolerance && dz <= horizontalTolerance && dy <= verticalTolerance;

            if (reached) {
                // SLF4J uses `{}` placeholders, not Python-style `{:.2f}` — the
                // latter produced the "found 2 placeholders, provided 5" warning.
                LOGGER.info("Target reached! dx={}, dy={}, dz={} (tolerance: h={}, v={})",
                        String.format("%.2f", dx), String.format("%.2f", dy),
                        String.format("%.2f", dz),
                        horizontalTolerance, verticalTolerance);
            }

            return reached;
        }

        // Update calculateAxisAlignedDistance for precision
        private int calculateAxisAlignedDistance(BlockPos current, BlockPos target) {
            ServerPlayer player = botSource.getPlayer();
            if (player != null) {
                double dx = Math.abs(player.getX() - (target.getX() + 0.5));
                double dy = Math.abs(player.getY() - target.getY());
                double dz = Math.abs(player.getZ() - (target.getZ() + 0.5));
                return (int) Math.max(1, Math.round(dx + dy + dz));
            }
            return Math.abs(current.getX() - target.getX()) + Math.abs(current.getY() - target.getY()) + Math.abs(current.getZ() - target.getZ());
        }

        private String lastDirection = "north"; // initialize with something reasonable

        private void updateFacing(Segment segment) {
            BlockPos start = segment.start();
            BlockPos end = segment.end();

            int dx = end.getX() - start.getX();
            int dz = end.getZ() - start.getZ();
            int dy = end.getY() - start.getY();

            String direction = null;

            if (Math.abs(dx) > 0 && dz == 0) {
                direction = dx > 0 ? "east" : "west";
            } else if (Math.abs(dz) > 0 && dx == 0) {
                direction = dz > 0 ? "south" : "north";
            } else if (Math.abs(dy) > 0 && dx == 0 && dz == 0) {
                direction = dy > 0 ? "up" : "down";
            }

            if (direction == null) {
                direction = lastDirection;
            } else {
                lastDirection = direction;
            }

            server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " look " + direction);
            LOGGER.info("{} is now facing {} (dx: {}, dy: {}, dz: {})", botName, direction, dx, dy, dz);
        }
    }

    // ✅ Updated to return CompletableFuture for proper async handling
    public static CompletableFuture<String> tracePath(MinecraftServer server, CommandSourceStack botSource, String botName, Queue<Segment> segments, boolean sprint) {
        // Abandon any in-flight path from a previous call so per-execution state
        // (job queue, movement flag, completion future) can't cross-contaminate
        // when autonomous navigation and player commands interleave.
        BotSegmentManager.clearActive();

        // Create the manager and initialize the completion future FIRST
        BotSegmentManager manager = new BotSegmentManager(server, botSource, botName, sprint);
        CompletableFuture<String> completionFuture = manager.getPathCompletionFuture();

        // Start the path execution in a separate thread
        new Thread(() -> {
            try {
                segments.forEach(manager::addSegmentJob);
                manager.startProcessing();
            } catch (Exception e) {
                LOGGER.error("Error starting path processing: ", e);
                if (!completionFuture.isDone()) {
                    completionFuture.complete("Path processing failed: " + e.getMessage());
                }
            }
        }).start();

        return completionFuture;
    }


    public static void flushAllMovementTasks() {
        BotSegmentManager.clearActive();
        LOGGER.info("All movement tasks flushed");
    }
}
