package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic site search for structure building.
 *
 * <p>A "buildable site" is an origin (the bot's standing block) such that the
 * structure footprint — laid out offset +1 on X/Z from the origin, matching
 * {@link StructureBuilder} — sits on a continuous band of solid ground with
 * clear air above it. The bot's own column is excluded: it is where the bot
 * stands, not part of the structure.
 *
 * <p>This is the "search for a better place nearby" step that precedes
 * terraforming and building, so a complex goal like "build a small house here"
 * does not blindly build into a cliff or a lake.
 */
public final class SiteSurveyor {
    private static final Logger LOGGER = LoggerFactory.getLogger("site-surveyor");

    private SiteSurveyor() {}

    /** How far above the bot's feet to start the per-column surface scan. */
    private static final int SCAN_UP = 4;
    /** How far below the bot's feet to scan before giving up on a column. */
    private static final int SCAN_DOWN = 32;

    /**
     * Find a flat, clear origin near the bot, scanning concentric rings
     * outward from the bot's current block and, for each ring cell, locating the
     * actual ground surface (which may be several blocks above or below the bot
     * on uneven terrain) before checking flatness.
     *
     * @param bot       the bot
     * @param footprintX width of the structure footprint (X)
     * @param footprintZ depth of the structure footprint (Z)
     * @param height    structure height above the floor
     * @param radius    max ring distance (in blocks) to search
     * @return a buildable origin, or {@code null} if none found within radius
     */
    public static BlockPos findFlatSite(ServerPlayer bot, int footprintX, int footprintZ,
                                        int height, int radius) {
        BlockPos start = bot.blockPosition();
        Level level = bot.level();
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // Only the ring boundary, not the whole square (dedupe).
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos column = new BlockPos(start.getX() + dx, start.getY(), start.getZ() + dz);
                    // Find the surface of this column and use it as the origin's Y.
                    int surfaceY = surfaceY(level, column);
                    if (surfaceY == Integer.MIN_VALUE) continue;
                    BlockPos origin = new BlockPos(column.getX(), surfaceY + 1, column.getZ());
                    if (isBuildableSite(level, origin, footprintX, footprintZ, height)) {
                        LOGGER.info("Found buildable site at {} (ring {})", origin, r);
                        return origin;
                    }
                }
            }
        }
        LOGGER.warn("No buildable site found within radius {}", radius);
        return null;
    }

    /** The topmost solid block in {@code column}, scanning down from above the bot. */
    private static int surfaceY(Level level, BlockPos column) {
        for (int y = column.getY() + SCAN_UP; y >= column.getY() - SCAN_DOWN; y--) {
            BlockPos p = new BlockPos(column.getX(), y, column.getZ());
            if (!level.getBlockState(p).isAir()) {
                return y;
            }
        }
        return Integer.MIN_VALUE; // no solid block found in this column
    }

    /**
     * Whether the structure anchored at {@code origin} has flat solid ground
     * underneath and clear air inside its volume.
     *
     * <p>Ground: every footprint column must have a solid block at
     * {@code origin.y - 1} (the level the bot stands on). Air: every cell from
     * {@code origin.y} to {@code origin.y + height} in the footprint must be
     * empty. The footprint is {@code origin + 1 .. origin + footprint} on X/Z.
     */
    public static boolean isBuildableSite(Level level, BlockPos origin, int footprintX,
                                          int footprintZ, int height) {
        for (int x = origin.getX() + 1; x <= origin.getX() + footprintX; x++) {
            for (int z = origin.getZ() + 1; z <= origin.getZ() + footprintZ; z++) {
                BlockPos ground = new BlockPos(x, origin.getY() - 1, z);
                if (level.getBlockState(ground).isAir()) {
                    return false; // no floor to build on (void / drop-off)
                }
                for (int y = origin.getY(); y <= origin.getY() + height; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        return false; // obstruction in the build volume
                    }
                }
            }
        }
        return true;
    }
}
