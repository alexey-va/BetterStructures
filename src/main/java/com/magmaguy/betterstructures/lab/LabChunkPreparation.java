package com.magmaguy.betterstructures.lab;

import com.google.gson.Gson;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Lab-only readiness experiment; never changes the block, physics, NBT, or loot engines. Main thread only. */
public final class LabChunkPreparation {
    public static final String WORLD = "rc_native_generation_lab";
    private static final long LOAD_TIMEOUT = TimeUnit.SECONDS.toNanos(60);
    private static final long STEP_BUDGET = 1_500_000L;
    private static final int MAX_LOADED_STEPS = 64;
    // Remains registered until the ORIGINAL request completes, including across content reloads.
    private static final Map<Key, Integer> collateral = new HashMap<>();
    private static LabChunkPreparation active;
    private final Plugin plugin;
    private final int maxQueued, maxHeld;
    private final ArrayDeque<Lease> queued = new ArrayDeque<>();
    private final String runId = UUID.randomUUID().toString();
    private Lease current;
    private CompletableFuture<Chunk> pending;
    private BukkitTask task;
    private boolean closed, failed;
    private long epoch, waitingSince;
    private long requested, loaded, alreadyLoaded, acquired, released, suppressed, originBoundaries, fitBoundaries, pasteBoundaries, notReady;
    private long completed, aborted, failures, maxRequestNanos, maxPinNanos;
    private long haloRequests, ringRevalidations;
    private int peakQueued, peakHeld, peakActivation;

    public static void configure(Plugin plugin) throws Exception {
        if (active != null) shutdown();
        Path path = plugin.getDataFolder().toPath().resolve("assets/chunk-preparation-lab.yml");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;
        YamlConfiguration config = new YamlConfiguration(); config.load(path.toFile());
        if (!config.getBoolean("enabled", false)) return;
        validateConfig(plugin.getServer().getIp(), plugin.getServer().getPort(), config.getString("world"),
                config.getInt("maxQueued", 64), config.getInt("maxHeld", 256));
        active = new LabChunkPreparation(plugin, config.getInt("maxQueued", 64), config.getInt("maxHeld", 256));
        active.log("enabled");
    }

    static void validateConfig(String ip, int port, String world, int maxQueued, int maxHeld) {
        if (!"127.0.0.1".equals(ip) || port != 54295 || !WORLD.equals(world))
            throw new IllegalArgumentException("Chunk preparation experiment requires 127.0.0.1:54295 and " + WORLD);
        if (maxQueued < 1 || maxQueued > 64 || maxHeld < 1 || maxHeld > 256)
            throw new IllegalArgumentException("Lab limits require maxQueued 1..64 and maxHeld 1..256");
    }

    LabChunkPreparation(Plugin plugin, int maxQueued, int maxHeld) {
        this.plugin = plugin; this.maxQueued = maxQueued; this.maxHeld = maxHeld;
    }

    public static boolean enabled(World world) { return active != null && WORLD.equals(world.getName()); }

    /** Accepted origins queue as coordinates, so a pregenerator may unload them without losing their scan. */
    public static void prepareOrigin(Chunk origin, Consumer<Chunk> scan) {
        World world = origin.getWorld();
        var coordinate = new ChunkFootprint.Coordinate(origin.getX(), origin.getZ());
        if (!enabled(world)) throw new IllegalStateException("Lab chunk preparation is not enabled for this world");
        ChunkFootprint footprint = ChunkFootprint.rectangle(Math.multiplyExact(coordinate.x(), 16), Math.multiplyExact(coordinate.z(), 16), 1, 1, 0);
        active.enqueue(world, footprint, lease -> {
            try { scan.accept(lease.held.get(coordinate)); lease.complete(); }
            catch (Throwable failure) { lease.close(); throw failure; }
        }, true);
    }

    /** The caller retains its selected fitter/clipboard in the continuation; selection is never retried. */
    public static void prepare(World world, Supplier<ChunkFootprint> footprint, Consumer<Lease> continuation) {
        if (!enabled(world)) throw new IllegalStateException("Lab chunk preparation is not enabled for this world");
        try { active.enqueue(world, footprint.get(), continuation); }
        catch (Throwable failure) { active.fail(failure); throw failure; }
    }

    void enqueue(World world, ChunkFootprint footprint, Consumer<Lease> continuation) {
        enqueue(world, footprint, continuation, false);
    }

    private void enqueue(World world, ChunkFootprint footprint, Consumer<Lease> continuation, boolean origin) {
        requireMain();
        if (failed || closed) throw new IllegalStateException("Chunk preparation experiment is stopped/failed");
        if (queued.size() >= maxQueued || footprint.chunks().size() > maxHeld) {
            IllegalStateException error = new IllegalStateException("Lab chunk preparation queue/footprint limit exceeded");
            fail(error); throw error;
        }
        try { queued.addLast(new Lease(world, footprint, continuation, epoch, origin)); }
        catch (Throwable failure) { fail(failure); throw failure; }
        peakQueued = Math.max(peakQueued, queued.size());
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    /** Must be checked before the normal new-chunk/reload claim. Does not remove accepted origin jobs. */
    public static boolean suppresses(Chunk chunk) {
        if (!collateral.containsKey(new Key(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()))) return false;
        if (active != null) active.suppressed++;
        return true;
    }

    void tick() {
        if (closed || failed) return;
        long stop = System.nanoTime() + STEP_BUDGET;
        try {
            if (pending != null && System.nanoTime() - waitingSince > LOAD_TIMEOUT)
                throw new IllegalStateException("Lab chunk preparation FULL request exceeded 60 seconds");
            if (current == null) {
                current = queued.pollFirst();
                if (current != null) current.markCollateral();
            }
            if (current == null) return;
            Lease lease = current;
            lease.requireLive();
            if (lease.ready) return; // The paste queue owns this job until its finishing callback.
            if (System.nanoTime() - lease.progressAt > LOAD_TIMEOUT)
                throw new IllegalStateException("No FULL/pin progress for 60 seconds; activation halo may be unloading before it can be pinned");
            for (int step = 0; step < MAX_LOADED_STEPS && System.nanoTime() < stop; step++) {
                if (pending != null) return;
                var unprepared = lease.firstUnprepared();
                if (unprepared != null) {
                    if (lease.world.isChunkLoaded(unprepared.x(), unprepared.z())) { lease.prepared(unprepared); alreadyLoaded++; continue; }
                    request(lease, unprepared); return;
                }
                var target = lease.firstUnheld();
                if (target == null) { lease.becameReady(); return; }
                var missing = lease.firstMissingRing(target);
                if (missing != null) { request(lease, missing); return; }
                lease.hold(target, lease.world.getChunkAt(target.x(), target.z()));
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void request(Lease lease, ChunkFootprint.Coordinate coordinate) {
        Key key = lease.key(coordinate);
        collateral.merge(key, 1, Integer::sum);
        CompletableFuture<Chunk> original;
        long began = System.nanoTime();
        try { original = lease.world.getChunkAtAsync(coordinate.x(), coordinate.z(), true); }
        catch (Throwable error) { removeCollateral(key); throw error; }
        maxRequestNanos = Math.max(maxRequestNanos, System.nanoTime() - began);
        requested++; if (!lease.desired.contains(coordinate)) haloRequests++;
        waitingSince = System.nanoTime(); pending = original;
        original.whenComplete((chunk, error) -> {
            try {
                requireMain();
                if (pending == original) pending = null;
                if (!lease.live()) return;
                if (error != null) { fail(error); return; }
                if (chunk == null || !chunk.isLoaded() || chunk.getWorld() != lease.world)
                    throw new IllegalStateException("Invalid FULL completion for ticket activation footprint");
                lease.prepared(coordinate); loaded++;
                // Pin while the last request's load ticket still exists, but only if its entire
                // 5x5 FULL ring is resident. Never run the fitter or start another request here.
                lease.pinAvailable();
            } catch (Throwable failure) { fail(failure); }
            finally { removeCollateral(key); }
        });
    }

    private static void removeCollateral(Key key) { collateral.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1); }
    private static void requireMain() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Chunk readiness requires the server thread"); }

    private void fail(Throwable error) {
        if (failed || closed) return;
        failed = true; failures++;
        cancelJobs();
        plugin.getLogger().log(Level.SEVERE, "BS_CHUNK_PREPARATION status=failed runId=" + runId, error);
        log("failed");
    }

    private void cancelJobs() {
        epoch++;
        if (task != null) { task.cancel(); task = null; }
        // A running/queued paste must flush its local resources BEFORE releasing the shared lease.
        // Its next ready/completion boundary observes the epoch fence and onAborted releases it.
        if (current != null && !current.pasteOwned) current.close();
        while (!queued.isEmpty()) queued.removeFirst().close();
        // Do not cancel the native future: cancellation does not stop chunk generation/load events.
    }

    public static void prepareForReload() { if (active != null) { active.cancelJobs(); active.log("reload-cancelled"); } }
    public static void shutdown() {
        if (active == null) return;
        LabChunkPreparation previous = active; previous.closed = true; previous.cancelJobs(); previous.log("stopped"); active = null;
    }

    /** Probe-readable counters. Snapshot only on the main thread; no world loads or disk IO. */
    public static Map<String, Object> metrics() { requireMain(); return active == null ? Map.of("enabled", false) : active.snapshot(); }

    private Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", true); values.put("runId", runId); values.put("world", WORLD); values.put("failed", failed);
        values.put("requested", requested); values.put("loaded", loaded); values.put("alreadyLoaded", alreadyLoaded);
        values.put("holdsAcquired", acquired); values.put("holdsReleased", released); values.put("held", acquired - released);
        values.put("peakHeld", peakHeld); values.put("queued", queued.size()); values.put("peakQueued", peakQueued);
        values.put("activeJob", current != null); values.put("activationScope", current == null ? 0 : current.activation.size());
        values.put("peakActivation", peakActivation); values.put("haloRequests", haloRequests); values.put("ringRevalidations", ringRevalidations);
        values.put("requestPending", pending != null); values.put("collateralSuppressed", suppressed);
        values.put("originReadBoundaries", originBoundaries);
        values.put("fitReadBoundaries", fitBoundaries); values.put("pasteReadBoundaries", pasteBoundaries); values.put("notReady", notReady);
        values.put("completed", completed); values.put("aborted", aborted); values.put("failures", failures);
        values.put("maxRequestMs", maxRequestNanos / 1e6); values.put("maxPinMs", maxPinNanos / 1e6);
        return values;
    }

    private void log(String status) { plugin.getLogger().info("BS_CHUNK_PREPARATION status=" + status + " " + new Gson().toJson(snapshot())); }

    public final class Lease implements AutoCloseable {
        private final World world;
        private final long generation;
        private final boolean origin;
        private final Set<ChunkFootprint.Coordinate> desired = new LinkedHashSet<>();
        private final Set<ChunkFootprint.Coordinate> activation = new LinkedHashSet<>(), prepared = new HashSet<>();
        private final Map<ChunkFootprint.Coordinate, Chunk> held = new LinkedHashMap<>();
        private Consumer<Lease> continuation;
        private boolean ready, releasedLease, succeeded, marked, pasteOwned;
        private long progressAt;

        private Lease(World world, ChunkFootprint footprint, Consumer<Lease> continuation, long generation, boolean origin) {
            this.world = world; this.continuation = continuation; this.generation = generation; this.origin = origin; desired.addAll(footprint.chunks());
            activation.addAll(ChunkFootprint.activation(desired));
        }
        private boolean live() { return !closed && !failed && !releasedLease && generation == epoch && Bukkit.getWorld(world.getUID()) == world; }
        private void requireLive() { if (!live()) throw new IllegalStateException("Selected footprint lost its world/lifecycle owner"); }

        public void checkActive() { requireMain(); requireLive(); }
        public void transferToPaste() { checkActive(); pasteOwned = true; }

        public void include(ChunkFootprint footprint) {
            requireMain(); requireLive();
            Set<ChunkFootprint.Coordinate> merged = new LinkedHashSet<>(desired); merged.addAll(footprint.chunks());
            if (merged.size() > maxHeld) throw new IllegalStateException("Combined fit/paste footprint exceeds maxHeld");
            Set<ChunkFootprint.Coordinate> expanded = ChunkFootprint.activation(merged);
            if (marked) for (var coordinate : expanded)
                if (!activation.contains(coordinate)) collateral.merge(key(coordinate), 1, Integer::sum);
            activation.clear(); activation.addAll(expanded); peakActivation = Math.max(peakActivation, activation.size());
            desired.clear(); desired.addAll(merged); ready = held.keySet().containsAll(desired);
            progressAt = System.nanoTime();
        }

        private Key key(ChunkFootprint.Coordinate coordinate) { return new Key(world.getUID(), coordinate.x(), coordinate.z()); }

        private void markCollateral() {
            if (marked) return;
            marked = true; progressAt = System.nanoTime(); peakActivation = Math.max(peakActivation, activation.size());
            // FULL requests and level-31 plugin tickets can activate neighbours before our explicit request.
            for (var coordinate : activation) collateral.merge(key(coordinate), 1, Integer::sum);
        }

        private ChunkFootprint.Coordinate firstUnprepared() {
            for (var coordinate : activation) if (!prepared.contains(coordinate)) return coordinate;
            return null;
        }

        private ChunkFootprint.Coordinate firstUnheld() {
            for (var coordinate : desired) if (!held.containsKey(coordinate)) return coordinate;
            return null;
        }

        private void prepared(ChunkFootprint.Coordinate coordinate) {
            if (prepared.add(coordinate)) progressAt = System.nanoTime();
        }

        private ChunkFootprint.Coordinate firstMissingRing(ChunkFootprint.Coordinate center) {
            ringRevalidations++;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    var coordinate = new ChunkFootprint.Coordinate(center.x() + dx, center.z() + dz);
                    if (!world.isChunkLoaded(coordinate.x(), coordinate.z())) return coordinate;
                }
            return null;
        }

        private void pinAvailable() {
            if (prepared.size() != activation.size()) return;
            long stop = System.nanoTime() + STEP_BUDGET;
            for (int step = 0; step < MAX_LOADED_STEPS && System.nanoTime() < stop; step++) {
                var target = firstUnheld();
                if (target == null || firstMissingRing(target) != null) return;
                hold(target, world.getChunkAt(target.x(), target.z()));
            }
        }

        private void hold(ChunkFootprint.Coordinate coordinate, Chunk chunk) {
            requireLive();
            if (held.containsKey(coordinate)) return;
            long began = System.nanoTime(); PluginChunkHolds.hold(chunk);
            maxPinNanos = Math.max(maxPinNanos, System.nanoTime() - began);
            held.put(coordinate, chunk); acquired++; peakHeld = Math.max(peakHeld, held.size());
            progressAt = System.nanoTime();
        }

        private void becameReady() {
            for (Chunk chunk : held.values()) if (!chunk.isLoaded()) throw new IllegalStateException("Pinned fit chunk disappeared");
            ready = true;
            if (continuation != null) {
                Consumer<Lease> selected = continuation; continuation = null;
                if (origin) originBoundaries++; else fitBoundaries++;
                selected.accept(this);
            }
        }

        public boolean readyAt(Location location) {
            requireMain(); requireLive();
            if (!ready) { notReady++; return false; }
            var coordinate = new ChunkFootprint.Coordinate(Math.floorDiv(location.getBlockX(), 16), Math.floorDiv(location.getBlockZ(), 16));
            if (location.getWorld() != world || !held.containsKey(coordinate) || !world.isChunkLoaded(coordinate.x(), coordinate.z()))
                throw new IllegalStateException("World read outside the prepared/pinned footprint");
            pasteBoundaries++; return true;
        }

        public void complete() { succeeded = true; close(); }

        @Override public void close() {
            requireMain();
            if (releasedLease) return;
            releasedLease = true; continuation = null; ready = false;
            Throwable releaseFailure = null;
            for (Chunk chunk : held.values()) {
                try { PluginChunkHolds.release(chunk); released++; }
                catch (Throwable failure) { if (releaseFailure == null) releaseFailure = failure; }
            }
            held.clear();
            if (marked) { for (var coordinate : activation) removeCollateral(key(coordinate)); marked = false; }
            if (current == this) current = null;
            if (succeeded) completed++; else aborted++;
            if (releaseFailure != null) {
                plugin.getLogger().log(Level.SEVERE, "BS_CHUNK_PREPARATION ticket release failed runId=" + runId, releaseFailure);
                fail(releaseFailure);
            }
            log(succeeded ? "job-complete" : "job-closed");
        }
    }

    private record Key(UUID world, int x, int z) { }
}
