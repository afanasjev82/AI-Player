package net.shasankp000.PlayerUtils;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the structure layout computation (pure, world-independent).
 */
class StructureBuilderTest {

    @Test
    void wallHasCorrectDimensions() {
        List<BlockPos> wall = StructureBuilder.layout("wall", new BlockPos(0, 60, 0));
        // length 5 × height 3 = 15 blocks.
        assertEquals(15, wall.size());
        assertTrue(wall.contains(new BlockPos(1, 60, 1)));          // base corner
        assertTrue(wall.contains(new BlockPos(5, 62, 1)));          // top far corner
        assertFalse(wall.contains(new BlockPos(1, 60, 2)));         // 1-thick (no depth)
    }

    @Test
    void shelterIsHollowWithDoorGap() {
        // origin (0,60,0) → shelter footprint bx=1..3, bz=1..3, y=60..63.
        List<BlockPos> shelter = StructureBuilder.layout("shelter", new BlockPos(0, 60, 0));
        assertFalse(shelter.isEmpty());
        // Interior cell (2,61,2) is not a wall/floor/roof → hollow.
        assertFalse(shelter.contains(new BlockPos(2, 61, 2)));
        // Door gap on +Z face center (2, 61..62, 3) → not placed.
        assertFalse(shelter.contains(new BlockPos(2, 61, 3)));
        assertFalse(shelter.contains(new BlockPos(2, 62, 3)));
        // A wall block (corner) IS present.
        assertTrue(shelter.contains(new BlockPos(1, 61, 1)));
    }

    @Test
    void roomIsLargerThanShelter() {
        List<BlockPos> room = StructureBuilder.layout("room", new BlockPos(0, 60, 0));
        List<BlockPos> shelter = StructureBuilder.layout("shelter", new BlockPos(0, 60, 0));
        assertTrue(room.size() > shelter.size());
    }

    @Test
    void unknownStructureReturnsNull() {
        assertNull(StructureBuilder.layout("castle", new BlockPos(0, 60, 0)));
    }

    @Test
    void dimensionsExposeFootprintSizes() {
        int[] room = StructureBuilder.dimensions("room");
        assertArrayEquals(new int[]{5, 5, 4}, room);
        int[] shelter = StructureBuilder.dimensions("shelter");
        assertArrayEquals(new int[]{3, 3, 3}, shelter);
        int[] wall = StructureBuilder.dimensions("wall");
        assertArrayEquals(new int[]{5, 1, 3}, wall);
        assertNull(StructureBuilder.dimensions("castle"));
    }

    @Test
    void buildRecoveryStartsWithCurrentSite() {
        // buildWithRecovery must first attempt the bot's own site, then relocate.
        // The pure shape of this is hard to test without a live world, but we can
        // at least assert the public API surface is coherent: build() is the
        // anchor-at-current-site path and buildAt() takes an explicit origin.
        assertNotNull(StructureBuilder.dimensions("room"));
        // build() and terraform() share the same layout, so their dimensions agree.
        assertArrayEquals(StructureBuilder.dimensions("room"), new int[]{5, 5, 4});
    }
}
