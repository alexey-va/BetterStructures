package ru.ruscrafting.nativestructureslab;

import java.util.function.LongSupplier;

/** One aggregate allowance, including skipped/no-op positions. Native calls are indivisible. */
final class SliceBudget {
    private final LongSupplier clock;
    private final long started, allowance;
    private final int maximum;
    private int used;
    SliceBudget(LongSupplier clock, long allowance, int maximum) {
        this.clock = clock; this.started = clock.getAsLong(); this.allowance = allowance; this.maximum = maximum;
    }
    boolean next() {
        if (used >= maximum || clock.getAsLong() - started >= allowance) return false;
        used++; return true;
    }
    int used() { return used; }
    long elapsed() { return clock.getAsLong() - started; }
}
