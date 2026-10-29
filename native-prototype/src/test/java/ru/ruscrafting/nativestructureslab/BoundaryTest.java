package ru.ruscrafting.nativestructureslab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryTest {
    @TempDir Path temporary;
    @Test void budgetAppliesToSkippedPositionsAndStopsByTimeOrCount() {
        SliceBudget count = new SliceBudget(() -> 0L, 1_500_000, 3);
        int skipped = 0; while (count.next()) skipped++;
        assertEquals(3, skipped);
        AtomicLong clock = new AtomicLong();
        SliceBudget time = new SliceBudget(() -> clock.getAndAdd(5), 10, 512);
        assertTrue(time.next()); assertFalse(time.next()); assertEquals(1, time.used());
    }
    @Test void cancellationRejectsLateCompletionsAndFreshGenerationIsIndependent() {
        RunFence fence = new RunFence(); long old = fence.begin(); assertTrue(fence.accepts(old));
        fence.cancel(); fence.cancel(); assertFalse(fence.accepts(old));
        long fresh = fence.begin(); assertTrue(fence.accepts(fresh)); assertFalse(fence.accepts(old));
    }
    @Test void labRefusesWildcardBindingWrongPortAndAnyOtherWorld() {
        assertDoesNotThrow(() -> LabGuard.server("127.0.0.1", 54295));
        assertThrows(IllegalStateException.class, () -> LabGuard.server("0.0.0.0", 54295));
        assertThrows(IllegalStateException.class, () -> LabGuard.server("127.0.0.1", 25565));
        assertThrows(IllegalArgumentException.class, () -> LabGuard.world("survival"));
    }
    @Test void filesCannotTraverseOrEscapeViaSymlink() throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("schematics"));
        Path inside = Files.writeString(folder.resolve("valid.schem"), "fixture");
        Path outside = Files.writeString(temporary.resolve("outside.schem"), "outside");
        assertEquals(inside.toRealPath(), LabGuard.schematic(folder, "valid.schem"));
        assertThrows(IOException.class, () -> LabGuard.schematic(folder, "../outside.schem"));
        assertThrows(IOException.class, () -> LabGuard.schematic(folder, outside.toString()));
        Files.createSymbolicLink(folder.resolve("link.schem"), outside);
        assertThrows(IOException.class, () -> LabGuard.schematic(folder, "link.schem"));
    }
    @Test void metricsUseObservedSamplesAndNearestRankPercentiles() {
        Samples samples = new Samples(); for (int i = 1; i <= 100; i++) samples.add(i);
        var summary = samples.summary();
        assertEquals(50D, summary.get("p50Ms")); assertEquals(95D, summary.get("p95Ms"));
        assertEquals(99D, summary.get("p99Ms")); assertEquals(100D, summary.get("maxMs"));
    }
}
