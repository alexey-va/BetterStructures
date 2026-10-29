package ru.ruscrafting.nativestructureslab;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded lab diagnostics; dumping owns the recording independently of plugin shutdown. */
final class LabRecording implements AutoCloseable {
    private final AtomicReference<Recording> recording;

    private LabRecording(Recording recording) {
        this.recording = new AtomicReference<>(recording);
    }

    static LabRecording start() {
        Recording recording = new Recording();
        try {
            recording.setName("RusCrafting native structures lab");
            recording.setMaxSize(32L << 20);
            recording.setMaxAge(Duration.ofMinutes(10));
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
            for (String event : List.of("jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.SafepointBegin",
                    "jdk.SafepointStateSynchronization", "jdk.SafepointEnd", "jdk.ExecuteVMOperation")) {
                recording.enable(event).withThreshold(Duration.ZERO).withoutStackTrace();
            }
            recording.enable(NativeSlice.class).withThreshold(Duration.ZERO).withoutStackTrace();
            recording.start();
            return new LabRecording(recording);
        } catch (RuntimeException | Error failure) {
            recording.close();
            throw failure;
        }
    }

    /** Worker-only I/O. The caller supplies a unique final path; an existing receipt is never replaced. */
    Path stopAndDump(Path target) throws IOException {
        Recording owned = recording.getAndSet(null);
        if (owned == null) throw new IllegalStateException("Lab recording is already closed or transferred");
        Path temporary = null;
        try (owned) {
            owned.stop();
            Path destination = target.toAbsolutePath().normalize();
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), ".native-recording-", ".tmp");
            owned.dump(temporary);
            Files.move(temporary, destination);
            return destination;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    @Override public void close() {
        Recording owned = recording.getAndSet(null);
        if (owned != null) owned.close();
    }

    @Name("ru.ruscrafting.NativeSlice")
    @Label("Native structure slice")
    @Category({"RusCrafting", "Native structures lab"})
    @StackTrace(false)
    public static final class NativeSlice extends Event {
        @Label("Phase") public String phase;
        @Label("Operations") public int operations;
        @Label("Server tick milliseconds") public double serverTickMs;
    }
}
