package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Structure building (Phase C): lays out a block pattern relative to the bot
 * and places each block via {@link BlockPlacementTool}.
 *
 * <p>Supports a small catalog of simple structures the bot can build
 * deterministically:
 * <ul>
 *   <li>{@code wall}  — a 1-thick wall, {@code length} wide × {@code height} tall.</li>
 *   <li>{@code shelter}— a 3×3×3 hollow box (roof + walls, door gap on one side).</li>
 *   <li>{@code room}  — a larger 5×4×5 hollow box (roof + walls, door gap).</li>
 * </ul>
 *
 * <p>The bot must have the building material in inventory. Each block is placed
 * sequentially; if any placement fails (missing material), the build stops and
 * reports how many blocks were placed.
 */
public final class StructureBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("structure-builder");

    /**
     * Reach allowance for laying out a structure from a single bot position.
     *
     * <p>A 5-wide footprint spreads its far cells ~7 blocks diagonally from a
     * bot standing at the structure's origin, which exceeds a player-like
     * 5-block reach. The builder places a known layout rather than mimicking a
     * player's arm, so it uses a larger limit instead of failing mid-build.
     */
    private static final double BUILD_REACH = 16.0;

    private StructureBuilder() {}

    /** Build {@code structureName} (wall/shelter/room) near the bot using {@code blockType}. */
    public static CompletableFuture<String> build(ServerPlayer bot, String structureName, String blockType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (bot == null || !bot.isAlive() || bot.hasDisconnected()) {
                    return "❌ Bot is unavailable.";
                }
                return doBuild(bot, structureName, blockType);
            } catch (Exception e) {
                LOGGER.error("Build failed: {}", e.getMessage(), e);
                return "❌ Build failed: " + e.getMessage();
            }
        });
    }

    private static String doBuild(ServerPlayer bot, String structureName, String blockType) {
        List<BlockPos> layout = layout(structureName, bot.blockPosition());
        if (layout == null) {
            return "❌ Unknown structure: " + structureName
                    + " (supported: wall, shelter, room).";
        }

        // Level the site first. There is no terrain shape small enough for the
        // catalog structures to fit on unchanged, so instead of refusing to
        // build on uneven ground we clear the structure's own bounding volume:
        // every remaining block in there would otherwise fail placement with
        // "target position is already occupied". (An earlier version instead
        // lifted the layout above the highest block in the footprint, which on a
        // sloped cliff pushed the build out of reach and left it floating.)
        clearVolume(bot, layout);

        int placed = 0;
        for (BlockPos pos : layout) {
            // This method runs on the common ForkJoinPool (via build()'s
            // supplyAsync), the same thread context the single-block placeBlock
            // tool already uses successfully. Each placement is awaited
            // sequentially — no server-thread blocking, so no deadlock.
            String result = BlockPlacementTool.placeBlock(bot, pos, blockType, BUILD_REACH).join();
            if (!result.startsWith("✅")) {
                return "❌ Build stopped after " + placed + "/" + layout.size()
                        + " blocks: " + result;
            }
            placed++;
        }
        return "✅ Built " + structureName + " (" + placed + " blocks).";
    }

    /**
     * Clear every cell inside the bounding volume of {@code layout} (including
     * the hollow interior and the door gap, so the result is a clean box rather
     * than a box filled with whatever terrain was there).
     *
     * <p>Block edits are marshalled onto the server thread: this runs on the
     * common ForkJoinPool, and mutating the world off-thread is unsafe.
     */
    private static void clearVolume(ServerPlayer bot, List<BlockPos> layout) {
        if (layout.isEmpty()) return;
        Level level = bot.level();
        MinecraftServer server = bot.level().getServer();

        int minX = layout.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = layout.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minY = layout.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int maxY = layout.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int minZ = layout.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = layout.stream().mapToInt(BlockPos::getZ).max().orElseThrow();

        // Never clear the bot's own two body blocks, or it would fall.
        BlockPos botFeet = bot.blockPosition();
        BlockPos botHead = botFeet.above();

        runOnServer(server, () -> {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (p.equals(botFeet) || p.equals(botHead)) continue;
                        if (!level.getBlockState(p).isAir()) {
                            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        });
        LOGGER.info("Cleared build site {}..{} ({}x{}x{})",
                new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ),
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    }

    /** Run {@code task} on the server thread and wait for it to finish. */
    private static void runOnServer(MinecraftServer server, Runnable task) {
        if (server == null || server.isSameThread()) {
            task.run();
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> {
            try {
                task.run();
            } finally {
                done.complete(null);
            }
        });
        done.join();
    }

    /** Compute the block positions for a structure anchored near {@code origin}. */
    static List<BlockPos> layout(String name, BlockPos origin) {
        switch (name.toLowerCase()) {
            case "wall":
                return wall(origin, 5, 3);
            case "shelter":
                return shelter(origin, 3, 3);
            case "room":
                return shelter(origin, 5, 4);
            default:
                return null;
        }
    }

    /** A 1-thick wall {@code length} long × {@code height} tall, along +X. */
    private static List<BlockPos> wall(BlockPos origin, int length, int height) {
        List<BlockPos> blocks = new ArrayList<>();
        int baseY = origin.getY();
        for (int x = 0; x < length; x++) {
            for (int y = 0; y < height; y++) {
                blocks.add(new BlockPos(origin.getX() + x + 1, baseY + y, origin.getZ() + 1));
            }
        }
        return blocks;
    }

    /**
     * A hollow box {@code size}×{@code size} (footprint) × {@code height} tall,
     * with a 1-block door gap at the front (+Z face).
     */
    private static List<BlockPos> shelter(BlockPos origin, int size, int height) {
        List<BlockPos> blocks = new ArrayList<>();
        int bx = origin.getX() + 1;
        int by = origin.getY();
        int bz = origin.getZ() + 1;

        // Floor perimeter (leave interior empty), walls, and roof.
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean isFloor = (y == 0);
                    boolean isRoof = (y == height);
                    boolean isWall = (x == 0 || x == size - 1 || z == 0 || z == size - 1);

                    if (isFloor || isRoof || isWall) {
                        // Door gap: skip the bottom two blocks on the +Z face center.
                        boolean door = (z == size - 1 && x == size / 2 && y >= 1 && y <= 2);
                        if (door) continue;
                        blocks.add(new BlockPos(bx + x, by + y, bz + z));
                    }
                }
            }
        }
        return blocks;
    }
}
