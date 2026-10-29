package ru.ruscrafting.nativegenerationlab;

import java.util.*;

/** Immutable worker-side geometry. Every non-air position belongs to exactly one central chunk. */
final class GenerationPlan {
    final String name;
    final SchematicCodec.Schematic schematic; // null only for the zero-write vanilla control
    final int x, y, z;
    final List<Slice> slices;

    static final class Slice {
        final int chunkX, chunkZ, skippedAir;
        private final int[] positions;
        Slice(int chunkX, int chunkZ, int skippedAir, int[] positions) {
            this.chunkX = chunkX; this.chunkZ = chunkZ; this.skippedAir = skippedAir;
            this.positions = positions.clone();
        }
        long key() { return GenerationPlan.key(chunkX, chunkZ); }
        int size() { return positions.length; }
        int position(int cursor) { return positions[cursor]; }
    }

    GenerationPlan(String name, SchematicCodec.Schematic schematic, int x, int y, int z) {
        this.name = name; this.schematic = Objects.requireNonNull(schematic);
        this.x = x; this.y = y; this.z = z;
        int minX = Math.addExact(x, schematic.offsetX), minY = Math.addExact(y, schematic.offsetY);
        int minZ = Math.addExact(z, schematic.offsetZ);
        Math.addExact(minY, schematic.height - 1);
        int endX = Math.addExact(minX, schematic.width), endZ = Math.addExact(minZ, schematic.length);
        if (Math.abs((long) minX) > 29_999_000 || Math.abs((long) endX) > 29_999_000
                || Math.abs((long) minZ) > 29_999_000 || Math.abs((long) endZ) > 29_999_000)
            throw new IllegalArgumentException("Plan exceeds world coordinate bounds");
        List<SchematicCodec.ChunkPos> footprint = schematic.footprint(x, z);
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (var pos : footprint) counts.put(key(pos.x(), pos.z()), 0);
        for (int c = 0; c < schematic.placementCount(); c++) {
            int index = schematic.placementPosition(c);
            long key = key(Math.floorDiv(worldX(index), 16), Math.floorDiv(worldZ(index), 16));
            counts.compute(key, (ignored, count) -> count + 1);
        }
        Map<Long, int[]> groups = new LinkedHashMap<>();
        counts.forEach((key, count) -> groups.put(key, new int[count]));
        Map<Long, Integer> cursors = new HashMap<>();
        for (int c = 0; c < schematic.placementCount(); c++) {
            int index = schematic.placementPosition(c);
            long key = key(Math.floorDiv(worldX(index), 16), Math.floorDiv(worldZ(index), 16));
            int cursor = cursors.getOrDefault(key, 0);
            groups.get(key)[cursor] = index; cursors.put(key, cursor + 1);
        }
        List<Slice> result = new ArrayList<>();
        for (var pos : footprint) {
            int cellsX = Math.min(endX, pos.x() * 16 + 16) - Math.max(minX, pos.x() * 16);
            int cellsZ = Math.min(endZ, pos.z() * 16 + 16) - Math.max(minZ, pos.z() * 16);
            int[] indices = groups.get(key(pos.x(), pos.z()));
            result.add(new Slice(pos.x(), pos.z(), cellsX * cellsZ * schematic.height - indices.length, indices));
        }
        slices = List.copyOf(result);
    }

    private GenerationPlan(String name, int x, int z, boolean centralGridFirst) {
        this.name = name; this.schematic = null; this.x = x; this.y = 200; this.z = z;
        if (Math.floorMod(x, 16) != 0 || Math.floorMod(z, 16) != 0)
            throw new IllegalArgumentException("Control origin must be chunk-aligned");
        List<Slice> result = new ArrayList<>();
        for (int cx = Math.floorDiv(x, 16); cx < Math.floorDiv(x, 16) + 4; cx++)
            for (int cz = Math.floorDiv(z, 16); cz < Math.floorDiv(z, 16) + 4; cz++)
                result.add(new Slice(cx, cz, 0, new int[0]));
        // Probe requests its exact grid chunk before neighboring chunks can trigger terrain reads.
        if (centralGridFirst) Collections.swap(result, 0, 5);
        slices = List.copyOf(result);
    }

    static GenerationPlan control(int x, int z) { return new GenerationPlan("vanilla_control16", x, z, false); }
    static GenerationPlan control(String name, int x, int z) { return new GenerationPlan(name, x, z, true); }
    boolean control() { return schematic == null; }
    int expectedWrites() { return control() ? 0 : schematic.placementCount(); }
    int expectedSkippedAir() { return control() ? 0 : schematic.airCount(); }
    int worldX(int index) { return Math.addExact(Math.addExact(x, schematic.offsetX), schematic.x(index)); }
    int worldY(int index) { return Math.addExact(Math.addExact(y, schematic.offsetY), schematic.y(index)); }
    int worldZ(int index) { return Math.addExact(Math.addExact(z, schematic.offsetZ), schematic.z(index)); }
    static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
}
