package ru.ruscrafting.nativestructureslab;

/** A cancelled asynchronous completion cannot enter the next operation. Main-thread owned. */
final class RunFence {
    private volatile long generation;
    private volatile boolean active;
    long begin() { active = true; return ++generation; }
    boolean accepts(long token) { return active && token == generation; }
    void cancel() { active = false; generation++; }
}
