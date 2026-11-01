package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the "stand cell at or near the target" resolution used to repair home
 * anchors. The pure cell-picking overload is tested directly (no world mock is
 * needed, matching the repo's pure-logic test style): the standability check is
 * supplied as a predicate over the candidate foot cell.
 */
class StandPositionTest {

    @Test
    void alreadyStandableTargetIsReturnedAsIs() {
        BlockPos target = new BlockPos(10, 64, 10);
        BlockPos result = StandPosition.findStandAtOrNear(target, p -> p.equals(target));
        assertEquals(target, result, "a standable target cell must be accepted unchanged");
    }

    @Test
    void occupiedTargetFallsBackToStandableNeighbor() {
        BlockPos target = new BlockPos(10, 64, 10);
        BlockPos neighbor = new BlockPos(11, 64, 10); // +X neighbour, same level
        BlockPos result = StandPosition.findStandAtOrNear(target, p -> p.equals(neighbor));
        assertEquals(neighbor, result,
                "an occupied target must resolve to the standable neighbour");
    }

    @Test
    void noStandableCellReturnsNull() {
        BlockPos target = new BlockPos(10, 64, 10);
        assertNull(StandPosition.findStandAtOrNear(target, p -> false),
                "with nothing standable the helper must return null so callers can fall back");
    }

    @Test
    void neighborScanSkipsTheTargetCellItself() {
        // The neighbour scan must never accept the target cell — that is the one
        // cell findStandNear's 3x3x3 scan excludes.
        BlockPos target = new BlockPos(10, 64, 10);
        BlockPos neighbor = new BlockPos(10, 65, 10);
        BlockPos result = StandPosition.findStandNear(target,
                (Predicate<BlockPos>) p -> p.equals(target) || p.equals(neighbor));
        assertEquals(neighbor, result,
                "the neighbour scan must ignore the target cell and pick the neighbour");
    }
}
