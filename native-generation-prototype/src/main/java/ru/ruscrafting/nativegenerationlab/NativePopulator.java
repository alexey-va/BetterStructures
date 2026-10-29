package ru.ruscrafting.nativegenerationlab;

import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** No World, Chunk, scheduler or Bukkit access in a population callback. */
final class NativePopulator extends BlockPopulator {
    static final class Prepared {
        final GenerationPlan plan;
        private final BlockData[] palette;
        private final String[] canonical;
        Prepared(GenerationPlan plan, BlockData[] palette, String[] canonical) {
            this.plan = plan; this.palette = palette.clone(); this.canonical = canonical.clone();
        }
        BlockData target(int index) { return palette[plan.schematic.block(index)]; }
        String canonical(int index) { return canonical[plan.schematic.block(index)]; }
    }
    record Receipt(String caseName, int chunkX, int chunkZ, int actualWrites, int skippedAir, int noOps,
                   long nanos, String threadName, boolean mainThread, boolean cancelled) {}
    private record Work(Prepared prepared, GenerationPlan.Slice slice) {}
    private final Map<Long, Work> work;
    private final Set<Long> claimed = ConcurrentHashMap.newKeySet();
    private final Map<Long, Receipt> receipts = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final long mainThreadId;

    NativePopulator(List<Prepared> prepared, long mainThreadId) {
        this.mainThreadId = mainThreadId;
        Map<Long, Work> planned = new HashMap<>();
        for (Prepared plan : prepared) for (var slice : plan.plan.slices)
            if (planned.put(slice.key(), new Work(plan, slice)) != null)
                throw new IllegalArgumentException("Benchmark plans overlap a chunk");
        work = Map.copyOf(planned);
    }

    @Override public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        if (!active.get()) return;
        long key = GenerationPlan.key(chunkX, chunkZ);
        Work current = work.get(key);
        if (current == null) return;
        if (!claimed.add(key)) { failure.compareAndSet(null, "Duplicate population callback for " + chunkX + "," + chunkZ); return; }
        long started = System.nanoTime();
        int writes = 0, noOps = 0;
        boolean cancelled = false;
        try {
            if (Thread.currentThread().threadId() == mainThreadId)
                throw new IllegalStateException("Lab requires off-main population; refusing writes on server main thread");
            GenerationPlan plan = current.prepared.plan;
            for (int cursor = 0; cursor < current.slice.size(); cursor++) {
                if ((cursor & 255) == 0 && (!active.get() || Thread.currentThread().isInterrupted())) { cancelled = true; break; }
                int position = current.slice.position(cursor);
                int x = plan.worldX(position), y = plan.worldY(position), z = plan.worldZ(position);
                if (Math.floorDiv(x, 16) != chunkX || Math.floorDiv(z, 16) != chunkZ || !region.isInRegion(x, y, z))
                    throw new IllegalStateException("Placement escaped its central chunk or LimitedRegion");
                BlockData target = current.prepared.target(position);
                if (region.getBlockData(x, y, z).equals(target)) { noOps++; continue; }
                region.setBlockData(x, y, z, target);
                writes++;
            }
        } catch (RuntimeException error) { failure.compareAndSet(null, error.toString()); }
        finally {
            Thread thread = Thread.currentThread();
            receipts.put(key, new Receipt(current.prepared.plan.name, chunkX, chunkZ, writes, current.slice.skippedAir,
                    noOps, System.nanoTime() - started, thread.getName(), thread.threadId() == mainThreadId, cancelled));
        }
    }
    Receipt receipt(long key) { return receipts.get(key); }
    String failure() { return failure.get(); }
    void deactivate() { active.set(false); }
}
