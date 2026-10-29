package com.magmaguy.betterstructures.lab;

import java.util.*;

/** Pure, bounded placement geometry; all coordinates are absolute after applying clipboard origin. */
record LabGeometry(int minX, int minY, int minZ, int width, int height, int length) {
    LabGeometry {
        if (width < 1 || height < 1 || length < 1 || width > 128 || height > 128 || length > 128
                || (long) width * height * length > 1_048_576) throw new IllegalArgumentException("Clipboard dimensions exceed lab bounds");
        int endX = Math.addExact(minX, width), endY = Math.addExact(minY, height), endZ = Math.addExact(minZ, length);
        if (Math.abs((long) minX) > 29_999_000 || Math.abs((long) endX) > 29_999_000
                || Math.abs((long) minZ) > 29_999_000 || Math.abs((long) endZ) > 29_999_000
                || minY < -64 || endY > 320) throw new IllegalArgumentException("Clipboard exceeds lab world bounds");
    }
    int volume() { return width * height * length; }
    int index(int x, int y, int z) { return x + z * width + y * width * length; }
    List<ChunkSlice> slices() {
        List<ChunkSlice> result = new ArrayList<>();
        for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(minX + width - 1, 16); cx++)
            for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(minZ + length - 1, 16); cz++)
                result.add(new ChunkSlice(cx, cz, Math.max(0, cx * 16 - minX), Math.min(width, cx * 16 + 16 - minX),
                        Math.max(0, cz * 16 - minZ), Math.min(length, cz * 16 + 16 - minZ)));
        return List.copyOf(result);
    }
    record ChunkSlice(int chunkX, int chunkZ, int fromX, int toX, int fromZ, int toZ) {
        long key() { return ((long) chunkX << 32) | (chunkZ & 0xffffffffL); }
    }
}
