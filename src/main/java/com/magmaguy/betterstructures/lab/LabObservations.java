package com.magmaguy.betterstructures.lab;

import org.bukkit.block.data.BlockData;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Disjoint chunk-owned slots; a published completion receipt exposes all writes to main-thread readback. */
final class LabObservations {
    record Receipt(long started, long nanos, int setBlockCalls, String thread, boolean mainThread) {}
    final String[] before, expected;
    final Map<Long, Receipt> receipts = new ConcurrentHashMap<>();
    private final Map<String, String> canonicalBefore = new ConcurrentHashMap<>();
    LabObservations(int size) { before = new String[size]; expected = new String[size]; }
    BlockData capture(LabPlan plan, int index, BlockData existing) {
        String value = canonicalBefore.computeIfAbsent(existing.getAsString(), s -> s);
        before[index] = value;
        boolean preserve = plan.policy(index).preserves(existing.getMaterial().isAir(), existing.getMaterial().isSolid());
        expected[index] = preserve ? value : plan.canonicalTarget(index);
        return preserve ? existing : plan.target(index);
    }
}
