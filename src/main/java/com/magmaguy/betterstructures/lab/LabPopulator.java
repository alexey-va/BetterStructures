package com.magmaguy.betterstructures.lab;

import org.bukkit.block.data.BlockData;
import org.bukkit.generator.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class LabPopulator extends BlockPopulator {
    private record Work(LabPlan plan, LabGeometry.ChunkSlice slice, LabObservations observed) {}
    private final Map<Long, Work> work;
    private final Set<Long> claimed = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final long mainThread;
    private final boolean nativeEngine;
    LabPopulator(List<LabPlan> plans, Map<String, LabObservations> observations, boolean nativeEngine, long mainThread) {
        Map<Long, Work> map = new HashMap<>();
        for (LabPlan plan : plans) for (var slice : plan.slices)
            if (map.put(slice.key(), new Work(plan, slice, observations.get(plan.asset.id()))) != null)
                throw new IllegalArgumentException("Manifest case footprints overlap a chunk");
        work = Map.copyOf(map); this.mainThread = mainThread; this.nativeEngine = nativeEngine;
    }
    @Override public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        if (!active.get()) return;
        long key = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
        Work item = work.get(key); if (item == null) return;
        if (!claimed.add(key)) { failure.compareAndSet(null, "Duplicate planned chunk population"); return; }
        long started = System.nanoTime(); int writes = 0;
        try {
            if (Thread.currentThread().threadId() == mainThread) throw new IllegalStateException("Lab refuses population on the server main thread");
            if (nativeEngine) {
                var g = item.plan.geometry; var s = item.slice;
                for (int y = 0; y < g.height(); y++) for (int z = s.fromZ(); z < s.toZ(); z++) for (int x = s.fromX(); x < s.toX(); x++) {
                    if (!active.get() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Population cancelled");
                    int index = g.index(x, y, z), wx = g.minX() + x, wy = g.minY() + y, wz = g.minZ() + z;
                    if (Math.floorDiv(wx, 16) != chunkX || Math.floorDiv(wz, 16) != chunkZ || !region.isInRegion(wx, wy, wz))
                        throw new IllegalStateException("Placement escaped current chunk");
                    BlockData before = region.getBlockData(wx, wy, wz);
                    BlockData target = item.observed.capture(item.plan, index, before);
                    if (!target.equals(before)) { region.setBlockData(wx, wy, wz, target); writes++; }
                }
            }
        } catch (RuntimeException error) { failure.compareAndSet(null, error.toString()); }
        finally {
            item.observed.receipts.put(key, new LabObservations.Receipt(started, System.nanoTime() - started, writes,
                    Thread.currentThread().getName(), Thread.currentThread().threadId() == mainThread));
        }
    }
    String failure() { return failure.get(); }
    void deactivate() { active.set(false); }
}
