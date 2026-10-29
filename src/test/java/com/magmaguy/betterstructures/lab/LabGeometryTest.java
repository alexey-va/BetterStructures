package com.magmaguy.betterstructures.lab;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class LabGeometryTest {
    @Test void negativeUnalignedCrossChunkVolumeIsVisitedExactlyOnceInAnyChunkOrder() {
        var geometry = new LabGeometry(-19, 70, -3, 37, 9, 23);
        var slices = new ArrayList<>(geometry.slices());
        Collections.shuffle(slices, new Random(42));
        Set<String> actual = new HashSet<>();
        for (var slice : slices)
            for (int y = 0; y < geometry.height(); y++)
                for (int z = slice.fromZ(); z < slice.toZ(); z++)
                    for (int x = slice.fromX(); x < slice.toX(); x++) {
                        int wx = geometry.minX() + x, wz = geometry.minZ() + z;
                        assertEquals(slice.chunkX(), Math.floorDiv(wx, 16));
                        assertEquals(slice.chunkZ(), Math.floorDiv(wz, 16));
                        assertTrue(actual.add(wx + ":" + (geometry.minY() + y) + ":" + wz), "Duplicate cell");
                    }
        Set<String> expected = new HashSet<>();
        for (int x = -19; x < 18; x++) for (int y = 70; y < 79; y++) for (int z = -3; z < 20; z++)
            expected.add(x + ":" + y + ":" + z);
        assertEquals(expected, actual);
    }
    @Test void exactNegativeChunkEdgesDoNotAddANeighbour() {
        var slices = new LabGeometry(-32, -64, -16, 16, 1, 16).slices();
        assertEquals(1, slices.size());
        assertEquals(-2, slices.getFirst().chunkX()); assertEquals(-1, slices.getFirst().chunkZ());
    }
    @Test void linearIndicesHaveTheSpongeXThenZThenYOrdering() {
        var geometry = new LabGeometry(0, 0, 0, 3, 2, 4);
        assertEquals(0, geometry.index(0, 0, 0)); assertEquals(2, geometry.index(2, 0, 0));
        assertEquals(3, geometry.index(0, 0, 1)); assertEquals(12, geometry.index(0, 1, 0));
        assertEquals(23, geometry.index(2, 1, 3));
    }
    @Test void rejectsOutOfWorldAndExcessiveVolumesBeforeGeneration() {
        assertThrows(IllegalArgumentException.class, () -> new LabGeometry(0, 319, 0, 1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new LabGeometry(0, 0, 0, 128, 128, 128));
        assertThrows(IllegalArgumentException.class, () -> new LabGeometry(0, 0, 0, 0, 1, 1));
        assertThrows(ArithmeticException.class, () -> new LabGeometry(Integer.MAX_VALUE, 0, 0, 2, 1, 1));
    }
}
