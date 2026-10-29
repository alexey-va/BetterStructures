package ru.ruscrafting.nativegenerationlab;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceGridPlannerTest {
    @Test void pinnedUpstream15808b1CellsPreserveSignedCoordinatesAndIntSaltOverflow() {
        assertPoint(0, 25, 8, 10, 20, 252, 498);
        assertPoint(0, 25, 8, -10, -21, -240, -523);
        assertPoint(1_234_567_891_011L, 30, 12, 1234, -777, 37034, -23305);
        assertPoint(-778899, 31, 0, -333, 444, -10323, 13764);
        assertPoint(-778899, 31, 15, -333, -445, -10315, -13782);
    }

    @Test void sixteenIndependentZeroWriteRegionsContainTheirExactTargetChunk() {
        var first = SurfaceGridPlanner.regions(1729, 30, 15, 2000, 1_048_576);
        var repeat = SurfaceGridPlanner.regions(1729, 30, 15, 2000, 1_048_576);
        assertEquals(16, first.size());
        Set<Long> allChunks = new HashSet<>();
        for (int index = 0; index < first.size(); index++) {
            var region = first.get(index); var plan = region.plan();
            assertEquals(repeat.get(index).point(), region.point());
            assertEquals("upstream_surface_control16_" + (index + 1), plan.name);
            assertTrue(plan.control()); assertEquals(0, plan.expectedWrites());
            assertEquals(16, plan.slices.size());
            assertEquals(region.point().chunkX(), plan.slices.getFirst().chunkX);
            assertEquals(region.point().chunkZ(), plan.slices.getFirst().chunkZ);
            assertTrue(plan.slices.stream().anyMatch(slice -> slice.chunkX == region.point().chunkX()
                    && slice.chunkZ == region.point().chunkZ()));
            for (var slice : plan.slices) {
                assertTrue(allChunks.add(slice.key()), "Control regions must not share a chunk");
                assertEquals(0, slice.size()); assertEquals(0, slice.skippedAir);
            }
        }
        assertEquals(256, allChunks.size());
    }

    @Test void invalidSettingsSpawnProtectionAndOutOfWorldCellsFailBeforeRequests() {
        assertThrows(IllegalArgumentException.class, () -> SurfaceGridPlanner.regions(0, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SurfaceGridPlanner.point(0, 30, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SurfaceGridPlanner.point(0, 30, Integer.MAX_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SurfaceGridPlanner.point(0, 30, 0, 100_000, 0));
        assertThrows(IllegalArgumentException.class, () -> SurfaceGridPlanner.regions(0, 30, 0, Integer.MAX_VALUE, 0));
    }

    private static void assertPoint(long seed, int distance, int offset, long cellX, long cellZ, int x, int z) {
        var point = SurfaceGridPlanner.point(seed, distance, offset, cellX, cellZ);
        assertEquals(x, point.chunkX()); assertEquals(z, point.chunkZ());
        assertEquals(cellX, point.cellX()); assertEquals(cellZ, point.cellZ());
    }
}
