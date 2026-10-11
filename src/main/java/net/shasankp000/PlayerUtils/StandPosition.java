package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks a cell for the bot to stand on while it interacts with a nearby block
 * (mine / place / use).
 *
 * <p>The old approach was a blind "one block east" offset, which is wrong on any
 * uneven terrain: on a cliff the offset cell is open air, so navigation
 * normalises the goal onto the cliff top (a cell it <em>can</em> reach), and the
 * bot ends up stranded above the target instead of beside it. That is exactly
 * why gather failed at Paul's spawn — see
 * {@code convertStepToParams} in FunctionCallerV2.
 *
 * <p>This computes a standable cell adjacent to the target from real world data,
 * preferring the target's own level so the interaction is in range and the bot
 * does not have to look up or down.
 */
public final class StandPosition {
    private static final Logger LOGGER = LoggerFactory.getLogger("stand-position");

    private StandPosition() {}

    /**
     * Find a standable foot position next to {@code target}, or {@code null} if
     * none of the immediate neighbours is standable.
     *
     * <p>Reads the world synchronously on the caller's thread (the gather plan
     * runs on a worker thread), matching how {@code SearchBlocks} already reads
     * block state off-thread. The scan is bounded to the 3×3×3 box around the
     * target, so it is cheap.
     */
    public static BlockPos findStandNear(ServerPlayer bot, BlockPos target) {
        if (bot == null || target == null) return null;
        ServerLevel world = bot.level();

        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    candidates.add(target.offset(dx, dy, dz));
                }
            }
        }

        // Prefer the target's own level (so mining is in range and the block is
        // face-on), then the closest cell, so a cliff-edge target still picks the
        // standable ground side rather than a distant ledge.
        candidates.sort(Comparator
                .comparingInt((BlockPos p) -> Math.abs(p.getY() - target.getY()))
                .thenComparingDouble(p -> p.distSqr(target)));

        for (BlockPos feet : candidates) {
            if (isStandable(world, feet)) {
                return feet;
            }
        }
        return null;
    }

    /**
     * A foot cell is standable when there is solid ground beneath it and free
     * space for the body and head. Liquids and air beneath do not count as
     * ground; a block with a solid collision shape at the feet or head does not
     * count as free space. Mirrors the hazard set used by the navigator's
     * {@code NavigationWorldView.live()} so we never suggest a cell the
     * navigator would reject.
     */
    static boolean isStandable(ServerLevel world, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState ground = world.getBlockState(below);
        if (ground.isAir()) return false;
        if (!ground.getFluidState().isEmpty()) return false;
        if (ground.getCollisionShape(world, below).isEmpty()) return false;
        if (isHazard(world, below)) return false;

        BlockState at = world.getBlockState(feet);
        if (!at.getCollisionShape(world, feet).isEmpty()) return false;
        if (isHazard(world, feet)) return false;

        BlockState above = world.getBlockState(feet.above());
        return above.getCollisionShape(world, feet.above()).isEmpty();
    }

    /** Same hazard set the navigator uses, so stand-cell and path decisions agree. */
    private static boolean isHazard(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || level.getFluidState(pos).is(FluidTags.LAVA);
    }
}
