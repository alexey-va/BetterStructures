package com.magmaguy.betterstructures.lab;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Set;

/** Horizontal chunk coordinates only; no world access and no assumptions about clipboard origin. */
public final class ChunkFootprint {
    private final Set<Coordinate> chunks;

    private ChunkFootprint(Set<Coordinate> chunks) { this.chunks = Collections.unmodifiableSet(new LinkedHashSet<>(chunks)); }

    public static ChunkFootprint rectangle(int minX, int minZ, int width, int length, int haloChunks) {
        if (width < 1 || length < 1 || haloChunks < 0 || haloChunks > 1)
            throw new IllegalArgumentException("Invalid footprint dimensions/halo");
        int firstX = Math.subtractExact(Math.floorDiv(minX, 16), haloChunks);
        int firstZ = Math.subtractExact(Math.floorDiv(minZ, 16), haloChunks);
        int lastX = Math.addExact(Math.floorDiv(Math.addExact(minX, width - 1), 16), haloChunks);
        int lastZ = Math.addExact(Math.floorDiv(Math.addExact(minZ, length - 1), 16), haloChunks);
        if ((long) (lastX - firstX + 1) * (lastZ - firstZ + 1) > 256)
            throw new IllegalArgumentException("Selected footprint exceeds the lab's 256-chunk ceiling");
        Set<Coordinate> chunks = new LinkedHashSet<>();
        for (int x = firstX; x <= lastX; x++)
            for (int z = firstZ; z <= lastZ; z++) chunks.add(new Coordinate(x, z));
        return new ChunkFootprint(chunks);
    }

    /** Includes exactly the fitter's possible candidates, including the surface '+' search. */
    public static ChunkFootprint fit(int minX, int minZ, int width, int length, boolean surface) {
        ChunkFootprint result = rectangle(minX, minZ, width, length, 0);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (surface && dx != 0 && dz != 0) continue;
                result = result.union(rectangle(Math.addExact(minX, dx * 16), Math.addExact(minZ, dz * 16), width, length, 0));
            }
        return result;
    }

    public ChunkFootprint union(ChunkFootprint other) {
        Set<Coordinate> merged = new LinkedHashSet<>(chunks); merged.addAll(other.chunks);
        if (merged.size() > 256) throw new IllegalArgumentException("Selected footprint exceeds the lab's 256-chunk ceiling");
        return new ChunkFootprint(merged);
    }

    Set<Coordinate> chunks() { return chunks; }

    /** Paper plugin tickets are ENTITY_TICKING (31): their FULL (33) activation reaches two chunks out. */
    static Set<Coordinate> activation(Set<Coordinate> desired) {
        Set<Coordinate> scope = new LinkedHashSet<>(desired);
        for (Coordinate at : desired)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    scope.add(new Coordinate(Math.addExact(at.x(), dx), Math.addExact(at.z(), dz)));
                    if (scope.size() > 1024) throw new IllegalArgumentException("Ticket activation exceeds the lab's 1024-chunk ceiling");
                }
        return scope;
    }

    public record Coordinate(int x, int z) { }
}
