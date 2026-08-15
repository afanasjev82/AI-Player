package net.shasankp000.Commands;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the spawn-position selection logic extracted from
 * {@code modCommandRegistry.findSafeSpawn(int,int,int,Predicate)}.
 *
 * <p>Regression target: the bot previously spawned at a blind {@code player + 5}
 * offset with no solid-ground/air check, so it could appear inside a block and
 * suffocate. This tests the pure ring-scan search without needing a live world.
 */
class SafeSpawnSearchTest {

    @Test
    void returnsOriginWhenItIsAlreadySafe() {
        Set<BlockPos> safe = Set.of(new BlockPos(100, 64, 200));

        BlockPos result = modCommandRegistry.findSafeSpawn(
                100, 64, 200, safe::contains);

        assertEquals(new BlockPos(100, 64, 200), result);
    }

    @Test
    void stepsUpWhenOriginHeadOrFeetBlocked() {
        // Only (100, 67, 200) is valid; the search must move up from y=64.
        Set<BlockPos> safe = Set.of(new BlockPos(100, 67, 200));

        BlockPos result = modCommandRegistry.findSafeSpawn(
                100, 64, 200, safe::contains);

        assertEquals(new BlockPos(100, 67, 200), result);
    }

    @Test
    void prefersVerticalSearchOverHorizontalRingExpansion() {
        // Safe block is 2 up on the same column; a horizontal neighbor exists
        // but is on ring r=1, which must not be reached before vertical scan.
        Set<BlockPos> safe = Set.of(
                new BlockPos(100, 66, 200),   // vertical (dy=2), same column
                new BlockPos(101, 64, 200)    // horizontal ring r=1
        );

        BlockPos result = modCommandRegistry.findSafeSpawn(
                100, 64, 200, safe::contains);

        assertEquals(new BlockPos(100, 66, 200), result,
                "vertical candidate must be found before expanding to ring r=1");
    }

    @Test
    void expandsToNearestHorizontalRingWhenVerticalFails() {
        // Nothing safe in the origin column; nearest safe is at ring r=1.
        Set<BlockPos> safe = Set.of(new BlockPos(101, 64, 200));

        BlockPos result = modCommandRegistry.findSafeSpawn(
                100, 64, 200, safe::contains);

        assertEquals(new BlockPos(101, 64, 200), result);
    }

    @Test
    void fallsBackToOriginWhenNothingIsSafe() {
        BlockPos origin = new BlockPos(50, 70, -30);

        BlockPos result = modCommandRegistry.findSafeSpawn(
                origin.getX(), origin.getY(), origin.getZ(), p -> false);

        assertEquals(origin, result);
    }

    @Test
    void neverReturnsNull() {
        BlockPos result = modCommandRegistry.findSafeSpawn(0, 0, 0, p -> false);
        assertNotNull(result);
        // The fallback returns the origin itself.
        assertEquals(new BlockPos(0, 0, 0), result);
    }

    // Keep HashSet imported for potential future use; avoid unused warning noise.
    @SuppressWarnings("unused")
    private static Set<BlockPos> emptySet() {
        return new HashSet<>();
    }
}
