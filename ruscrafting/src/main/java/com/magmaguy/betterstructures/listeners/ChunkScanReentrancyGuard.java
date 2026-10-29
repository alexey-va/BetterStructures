/*
 * Decompiled with CFR 0.152.
 */
package com.magmaguy.betterstructures.listeners;

final class ChunkScanReentrancyGuard {
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

    ChunkScanReentrancyGuard() {
    }

    boolean isActive() {
        return this.active.get();
    }

    boolean runIfIdle(Runnable scan) {
        if (this.active.get().booleanValue()) {
            return false;
        }
        this.active.set(true);
        try {
            scan.run();
            boolean bl = true;
            return bl;
        }
        finally {
            this.active.remove();
        }
    }
}
