package com.magmaguy.betterstructures.lab;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

final class LabBudgetLifecycleTest {
    @Test void cancelledCallbacksCannotEnterANewRun() {
        var fence = new RunFence(); long first = fence.begin(); assertTrue(fence.accepts(first));
        fence.cancel(); assertFalse(fence.accepts(first)); long next = fence.begin();
        assertFalse(fence.accepts(first)); assertTrue(fence.accepts(next));
    }
    @Test void deadlineIsCheckedEvenForPreservedOrSkippedCells() {
        var now = new AtomicLong(); var budget = new SliceBudget(now::get, 1_500_000, 4096);
        assertTrue(budget.next()); now.set(1_500_000);
        assertFalse(budget.next()); assertEquals(1, budget.used());
    }
    @Test void countCapBoundsWorkWhenClockDoesNotAdvance() {
        var budget = new SliceBudget(() -> 0, 1_500_000, 4096);
        for (int i = 0; i < 4096; i++) assertTrue(budget.next());
        assertFalse(budget.next());
    }
}
