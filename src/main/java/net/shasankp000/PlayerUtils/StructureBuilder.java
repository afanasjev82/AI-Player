package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
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
        // Anchor the structure on solid ground below the bot (if the bot is
        // flying/creative and has no floor beneath it, placeBlock cannot find
        // a surface to place against).
        BlockPos ground = findGroundBelow(bot);
        List<BlockPos> layout = layout(structureName, ground);
        if (layout == null) {
            return "❌ Unknown structure: " + structureName
                    + " (supported: wall, shelter, room).";
        }

        int placed = 0;
        for (BlockPos pos : layout) {
            // This method runs on the common ForkJoinPool (via build()'s
            // supplyAsync), the same thread context the single-block placeBlock
            // tool already uses successfully. Each placement is awaited
            // sequentially — no server-thread blocking, so no deadlock.
            String result = BlockPlacementTool.placeBlock(bot, pos, blockType).join();
            if (!result.startsWith("✅")) {
                return "❌ Build stopped after " + placed + "/" + layout.size()
                        + " blocks: " + result;
            }
            placed++;
        }
        return "✅ Built " + structureName + " (" + placed + " blocks).";
    }

    /**
     * Find the anchor Y for the structure: the highest solid block in the
     * footprint area, plus one (so the structure's floor sits on a clear plane
     * above the terrain instead of colliding with it). If the bot is standing
     * on flat ground, this is the ground-top; if floating, it scans downward.
     */
    private static BlockPos findGroundBelow(ServerPlayer bot) {
        BlockPos feet = bot.blockPosition();
        int highest = feet.getY() - 30; // scan up to 30 blocks down
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 30; dy++) {
                    BlockPos p = feet.offset(dx, -dy, dz);
                    if (!bot.level().getBlockState(p).isAir()) {
                        highest = Math.max(highest, p.getY());
                        break; // found the surface in this column
                    }
                }
            }
        }
        return new BlockPos(feet.getX(), highest + 1, feet.getZ());
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
