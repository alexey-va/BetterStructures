package com.magmaguy.betterstructures.lab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;

final class SparkCaptureTest {
    @TempDir Path directory;
    @Test void foreignForegroundPreventsStartAndIsNeverStopped() throws Exception {
        var worker = new ManualExecutor(); var access = new FakeAccess(); access.current = new Sample(false);
        Object foreign = access.current; var capture = new SparkCapture(directory, worker, access);
        assertFalse(capture.start("run")); worker.drain();
        assertThrows(ExecutionException.class, () -> capture.start("run"));
        capture.stop("run"); worker.drain();
        assertSame(foreign, access.current); assertEquals(0, access.starts); assertEquals(0, access.stops);
    }
    @Test void normalBackgroundTransitionCreatesAnOwnedForegroundAndStopsItOnce() throws Exception {
        var worker = new ManualExecutor(); var access = new FakeAccess(); access.current = new Sample(true);
        Object background = access.current; var capture = new SparkCapture(directory, worker, access);
        assertFalse(capture.start("run")); worker.drain(); assertTrue(capture.start("run"));
        assertNotSame(background, access.current); assertFalse(access.current.background);
        capture.stop("run"); capture.stop("run"); worker.drain();
        assertEquals(1, access.starts); assertEquals(1, access.stops); assertTrue(access.current.background);
    }
    @Test void commandReturningWithoutCreatingSamplerIsNotOwnership() throws Exception {
        var worker = new ManualExecutor(); var access = new FakeAccess(); access.createSampler = false;
        var capture = new SparkCapture(directory, worker, access);
        assertFalse(capture.start("run")); worker.drain();
        assertThrows(ExecutionException.class, () -> capture.start("run"));
        capture.stop("run"); worker.drain(); assertEquals(0, access.stops);
    }
    @Test void samplerReplacedBeforeStopIsLeftUntouched() throws Exception {
        var worker = new ManualExecutor(); var access = new FakeAccess(); var capture = new SparkCapture(directory, worker, access);
        capture.start("run"); worker.drain(); assertTrue(capture.start("run"));
        Sample foreign = new Sample(false); access.current = foreign;
        capture.stop("run"); worker.drain(); assertSame(foreign, access.current); assertEquals(0, access.stops);
    }
    @Test void disableBeforeStartWorkerRunsDoesNotCreateAnOrphanCapture() throws Exception {
        var worker = new ManualExecutor(); var access = new FakeAccess(); var capture = new SparkCapture(directory, worker, access);
        assertFalse(capture.start("run")); capture.stop("run"); worker.drain();
        assertEquals(0, access.starts); assertEquals(0, access.stops);
        assertThrows(CancellationException.class, () -> capture.start("run"));
    }
    @Test void receiptRequiresBothOwnedStartTimeAndRunId() {
        var receipt = new SparkCapture.Receipt("native-lab-owned", 1234);
        assertTrue(receipt.belongsTo("owned", 1234));
        assertFalse(receipt.belongsTo("foreign", 1234)); assertFalse(receipt.belongsTo("owned", 1235));
    }
    @Test void unknownSparkPayloadFailsClosedBeforeAnyCommand() {
        assertThrows(IllegalStateException.class, () -> PinnedSparkAccess.open(new ClassLoader(null) {}, null));
    }
    private record Sample(boolean background) {}
    private static final class FakeAccess implements SparkCapture.SamplerAccess {
        final ReentrantLock lock = new ReentrantLock(); Sample current; int starts, stops; boolean createSampler = true;
        @Override public ReentrantLock lock() { return lock; }
        @Override public Object active() { assertTrue(lock.isHeldByCurrentThread()); return current; }
        @Override public boolean background(Object sampler) { assertTrue(lock.isHeldByCurrentThread()); return ((Sample) sampler).background; }
        @Override public long started(Object sampler) { return 1234; }
        @Override public void command(String... args) {
            assertTrue(lock.isHeldByCurrentThread(), "check and command must share Spark's lock");
            if (args[1].equals("start")) { starts++; if (createSampler) current = new Sample(false); }
            else { stops++; current = new Sample(true); }
        }
        @Override public SparkCapture.Receipt receipt(byte[] bytes) { throw new AssertionError("No file IO in ownership tests"); }
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        final Deque<Runnable> tasks = new ArrayDeque<>(); boolean shutdown;
        void drain() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; var result = List.copyOf(tasks); tasks.clear(); return result; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
