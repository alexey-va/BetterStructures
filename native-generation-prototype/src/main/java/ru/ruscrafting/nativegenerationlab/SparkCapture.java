package ru.ruscrafting.nativegenerationlab;

import org.bukkit.Bukkit;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** This lab-only benchmark owns one foreground capture, with an automatic deadline. */
final class SparkCapture {
    private final Path directory;
    private final Set<Path> previous;
    private boolean dispatched, stopped;

    // Worker-only filesystem inventory, before the main thread dispatches a capture.
    SparkCapture(Path directory) throws IOException {
        this.directory = directory;
        this.previous = profiles();
    }
    boolean dispatched() { return dispatched; }
    void start(String runId) {
        if (dispatched) return;
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "spark profiler start --only-ticks-over 50 --interval 1 --timeout 600 --save-to-file --comment native-lab-" + runId))
            throw new IllegalStateException("Bundled spark command is unavailable");
        dispatched = true;
    }
    void stop(String runId) {
        if (!dispatched || stopped) return;
        stopped = true;
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spark profiler stop --save-to-file --comment native-lab-" + runId))
            throw new IllegalStateException("Unable to dispatch owned spark capture stop");
    }
    Map<String, Object> awaitReceipt() throws Exception {
        if (!dispatched) return Map.of("commandDispatched", false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Path candidate = null; long lastSize = -1; int stable = 0;
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Spark receipt wait cancelled");
            Set<Path> added = profiles(); added.removeAll(previous);
            if (added.size() > 1) throw new IOException("Ambiguous spark profile receipts: " + added.size());
            if (!added.isEmpty()) {
                Path found = added.iterator().next(); long size = Files.size(found);
                if (size > 64L * 1024 * 1024) throw new IOException("Spark profile exceeds diagnostic bound");
                stable = found.equals(candidate) && size == lastSize && size > 0 ? stable + 1 : 0;
                candidate = found; lastSize = size;
                if (stable >= 3) return Map.of("commandDispatched", true, "onlyTicksOverMs", 50, "intervalMs", 1,
                        "path", found.toString(), "bytes", size, "sha256",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(found))));
            }
            Thread.sleep(200);
        }
        throw new IOException("No completed spark file within 30 seconds; command dispatch is not capture proof");
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
