package ru.ruscrafting.nativegenerationlab;

import java.util.*;

/** Exact SURFACE cell arithmetic from BetterStructures 15808b1; no world or server access. */
final class SurfaceGridPlanner {
    record Point(int chunkX, int chunkZ, long cellX, long cellZ) { }
    record Region(Point point, GenerationPlan plan) { }

    static Point point(long seed, int distance, int maxOffset, long cellX, long cellZ) {
        if (distance <= 0 || maxOffset < 0 || maxOffset > (Integer.MAX_VALUE - 1) / 2)
            throw new IllegalArgumentException("Invalid upstream surface grid settings");
        long baseX = Math.multiplyExact(cellX, distance), baseZ = Math.multiplyExact(cellZ, distance);
        if (cellZ % 2L != 0) baseX = Math.addExact(baseX, distance / 2);
        // Preserve upstream's int multiplication overflow before promotion to long.
        long typeSeed = seed + "SURFACE".hashCode() * 7919;
        Random random = new Random(typeSeed ^ ((baseX << 32) | (baseZ & 0xffffffffL)));
        int bound = (int) (2L * maxOffset + 1L);
        long x = Math.addExact(baseX, maxOffset > 0 ? random.nextInt(bound) - maxOffset : 0);
        long z = Math.addExact(baseZ, maxOffset > 0 ? random.nextInt(bound) - maxOffset : 0);
        if (Math.abs(x) > 1_874_000L || Math.abs(z) > 1_874_000L)
            throw new IllegalArgumentException("Surface grid exceeds the bounded lab coordinates");
        return new Point(Math.toIntExact(x), Math.toIntExact(z), cellX, cellZ);
    }

    static List<Region> regions(long seed, int distance, int maxOffset, int spawnRadius, int baseBlockX) {
        if (distance <= 0 || maxOffset < 0 || maxOffset > (Integer.MAX_VALUE - 1) / 2 || spawnRadius < 0)
            throw new IllegalArgumentException("Invalid upstream surface grid settings");
        // Even opposing random offsets leave at least 128 chunks between target cells.
        long step = Math.max(1, Math.ceilDiv(128L + 2L * maxOffset, distance));
        long startX = Math.floorDiv(Math.floorDiv(baseBlockX, 16), distance);
        long startZ = Math.floorDiv(-512, distance);
        List<Region> regions = new ArrayList<>();
        Set<Long> occupied = new HashSet<>();
        for (int index = 0; index < 16; index++) {
            Point point = point(seed, distance, maxOffset, startX + index % 4 * step, startZ + index / 4 * step);
            long centerX = point.chunkX * 16L + 8, centerZ = point.chunkZ * 16L + 8;
            if (centerX * centerX + centerZ * centerZ < (long) spawnRadius * spawnRadius)
                throw new IllegalArgumentException("Probe grid lies within upstream spawn protection");
            GenerationPlan plan = GenerationPlan.control("upstream_surface_control16_" + (index + 1),
                    Math.multiplyExact(point.chunkX - 1, 16), Math.multiplyExact(point.chunkZ - 1, 16));
            for (var slice : plan.slices)
                if (!occupied.add(slice.key())) throw new IllegalArgumentException("Probe regions overlap");
            regions.add(new Region(point, plan));
        }
        return List.copyOf(regions);
    }
}
