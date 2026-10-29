package com.magmaguy.betterstructures.lab;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;

/** Worker-only Spark command execution, with identity checks under Spark's own command lock. */
final class SparkCapture {
    interface SamplerAccess {
        Lock lock();
        Object active() throws Exception;
        boolean background(Object sampler) throws Exception;
        long started(Object sampler) throws Exception;
        void command(String... args) throws Exception;
        Receipt receipt(byte[] bytes) throws Exception;
    }
    @FunctionalInterface interface AccessFactory { SamplerAccess open() throws Exception; }
    record Receipt(String comment, long started) {
        boolean belongsTo(String runId, long start) { return comment.equals("native-lab-" + runId) && started == start; }
    }
    private final Path directory;
    private final Set<Path> previous;
    private final ExecutorService worker;
    private final AccessFactory factory;
    private SamplerAccess access;
    private Future<?> starting, stopping;
    private volatile boolean stopRequested;
    private Object owned;
    private long ownedStart;
    private String runId;

    SparkCapture(Path directory, ExecutorService worker, SamplerAccess access) throws IOException {
        this(directory, worker, () -> access);
    }
    SparkCapture(Path directory, ExecutorService worker, AccessFactory factory) throws IOException {
        this.directory = directory; this.worker = worker; this.factory = factory; previous = profiles();
    }
    // Main thread polls readiness; it never waits for the native profiler or its execution lock.
    boolean start(String runId) throws Exception {
        if (starting == null) {
            this.runId = runId;
            starting = worker.submit(() -> {
                if (stopRequested) return null;
                access = factory.open();
                locked(() -> {
                    if (stopRequested) return;
                    Object before = access.active();
                    if (before != null && !access.background(before)) throw new IllegalStateException("A foreign foreground Spark sampler is active");
                    access.command("profiler", "start", "--only-ticks-over", "50", "--interval", "1", "--timeout", "600", "--save-to-file", "--comment", "native-lab-" + runId);
                    Object created = access.active();
                    if (created == null || created == before || access.background(created)) throw new IllegalStateException("Spark did not create an owned foreground sampler");
                    owned = created; ownedStart = access.started(created);
                    if (stopRequested) stopOwned();
                });
                return null;
            });
        }
        if (!starting.isDone()) return false;
        starting.get();
        if (stopRequested || owned == null) throw new CancellationException("Spark capture cancelled before readiness");
        return true;
    }
    void stop(String runId) {
        stopRequested = true;
        if (starting == null || stopping != null) return;
        stopping = worker.submit(() -> { if (access != null) locked(this::stopOwned); return null; });
    }
    private void stopOwned() throws Exception {
        if (owned == null) return;
        // Another actor may have stopped/replaced this sampler. Never stop that replacement.
        if (access.active() != owned) return;
        access.command("profiler", "stop", "--save-to-file", "--comment", "native-lab-" + runId);
        if (access.active() == owned) throw new IllegalStateException("Spark did not stop the owned sampler");
    }
    private void locked(CheckedAction action) throws Exception {
        Lock lock = access.lock();
        if (!lock.tryLock(5, TimeUnit.SECONDS)) throw new TimeoutException("Spark command lock was busy for five seconds");
        try { action.run(); } finally { lock.unlock(); }
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    // Called on the same single worker after the queued stop, never on the server thread.
    Map<String, Object> awaitReceipt() throws Exception {
        if (stopping != null) stopping.get();
        if (owned == null) return Map.of("ownershipVerified", false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Map<Path, Long> sizes = new HashMap<>(); Map<Path, Integer> stable = new HashMap<>(); Set<Path> foreign = new HashSet<>();
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Spark receipt wait cancelled");
            Set<Path> added = profiles(); added.removeAll(previous); added.removeAll(foreign);
            for (Path file : added) {
                long size = Files.size(file);
                if (size > 64L * 1024 * 1024) throw new IOException("Spark profile exceeds diagnostic bound");
                Long previousSize = sizes.put(file, size);
                int count = previousSize != null && previousSize == size && size > 0 ? stable.getOrDefault(file, 0) + 1 : 0;
                stable.put(file, count); if (count < 3) continue;
                byte[] bytes = Files.readAllBytes(file);
                Receipt receipt = access.receipt(bytes);
                if (!receipt.belongsTo(runId, ownedStart)) { foreign.add(file); continue; }
                return Map.of("ownershipVerified", true, "sparkVersion", "1.10.152", "onlyTicksOverMs", 50, "intervalMs", 1,
                        "samplerStartedAtEpochMs", ownedStart, "comment", receipt.comment(), "path", file.toString(), "bytes", size,
                        "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
            Thread.sleep(200);
        }
        throw new IOException("No stable Spark receipt with the owned sampler start time and run UUID within 30 seconds");
    }
    private Set<Path> profiles() throws IOException {
        Set<Path> result = new HashSet<>();
        if (!Files.exists(directory)) return result;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "profile-*.sparkprofile")) {
            for (Path path : entries) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) result.add(path);
                if (result.size() > 4096) throw new IOException("Spark receipt inventory exceeds diagnostic bound");
            }
        }
        return result;
    }
}
