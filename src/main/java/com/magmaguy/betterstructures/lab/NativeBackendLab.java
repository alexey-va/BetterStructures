package com.magmaguy.betterstructures.lab;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.google.gson.GsonBuilder;
import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import com.magmaguy.betterstructures.worldedit.Schematic;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.event.*;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/** Explicit opt-in clipboard-block A/B experiment. Never replaces automatic terrain fitting. */
public final class NativeBackendLab implements Listener, AutoCloseable {
    private enum Stage { WORLD, WAIT_CONTENT, BASELINE, FULL, BEFORE, PASTE, SETTLE, VERIFY, TAIL, REPORT, IDLE, STOPPED }
    private static volatile NativeBackendLab active;
    private final BetterStructures plugin;
    private final LabManifest manifest;
    private final String runId = UUID.randomUUID().toString();
    private final String startedAt = Instant.now().toString();
    private final long started = System.nanoTime();
    private final RunFence fence = new RunFence();
    private final List<LabPlan> plans = new ArrayList<>();
    private final List<Map<String, Object>> preflight = new ArrayList<>(), results = new ArrayList<>();
    private final Map<String, LabObservations> observations = new HashMap<>();
    private final Map<String, Samples> tickSamples = new LinkedHashMap<>();
    private final Samples activeTicks = new Samples();
    private final List<Map<String, String>> providers = new ArrayList<>();
    private final ExecutorService worker;
    private final Path folder;
    private final String worldName;
    private final Material pedestalMaterial;
    private LabPopulator populator;
    private SparkCapture spark;
    private World world;
    private BukkitTask ticker;
    private Future<Path> reportWrite;
    private Stage stage = Stage.WORLD;
    private Case current;
    private int caseCursor, stageTicks, remainingTickets = -1;
    private long token, phaseStarted, startupNanos, entityAdapterWarmupNanos;
    private boolean attached, contentReady, permitDefaultPaste, allMatched = true, observedActive;
    private String observedPhase = "startup", terminalStatus;

    public static NativeBackendLab startIfConfigured(BetterStructures plugin) throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("assets/native-lab.yml");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        LabManifest manifest = LabManifest.load(file);
        if (manifest == null) return null;
        if (!"127.0.0.1".equals(plugin.getServer().getIp()) || plugin.getServer().getPort() != 54295)
            throw new IllegalStateException("native-lab.yml is permitted only on 127.0.0.1:54295");
        return new NativeBackendLab(plugin, manifest);
    }
    private NativeBackendLab(BetterStructures plugin, LabManifest manifest) throws Exception {
        this.plugin = plugin; this.manifest = manifest; this.folder = plugin.getDataFolder().toPath();
        worldName = "rc_bs_ab_" + manifest.engine().name().toLowerCase(Locale.ROOT) + "_" + runId.substring(0, 8);
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "BetterStructures-native-lab-io"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        try {
            Material material = Material.matchMaterial(manifest.pedestalMaterial());
            if (material == null || !material.isBlock() || !material.isSolid() || material.createBlockData().createBlockState() instanceof TileState)
                throw new IllegalArgumentException("pedestalMaterial must be a solid ordinary block material");
            pedestalMaterial = material;
            Future<List<LabPlan.Raw>> loading = worker.submit(() -> {
                List<LabPlan.Raw> values = new ArrayList<>();
                for (var asset : manifest.assets()) values.add(LabPlan.read(folder, asset));
                return values;
            });
            List<LabPlan.Raw> raw;
            try { raw = loading.get(30, TimeUnit.SECONDS); }
            catch (Exception failure) { loading.cancel(true); throw failure; }
            boolean selectedUnsupported = false;
            for (LabPlan.Raw input : raw) {
                LabPlan plan = LabPlan.prepare(input, material.createBlockData());
                preflight.add(Map.copyOf(input.metadata()));
                if (input.asset().benchmark()) {
                    if (plan == null) selectedUnsupported = true;
                    else { plans.add(plan); observations.put(plan.asset.id(), new LabObservations(plan.geometry.volume())); }
                }
            }
            Path preflightPath = folder.resolve("native-lab-preflight-" + runId + ".json");
            worker.submit(() -> { Files.writeString(preflightPath, json(Map.of("runId", runId, "assets", preflight))); return null; }).get(5, TimeUnit.SECONDS);
            if (selectedUnsupported) throw new IllegalArgumentException("Selected assets failed preflight; see " + preflightPath);
            populator = new LabPopulator(plans, observations, manifest.engine() == LabManifest.Engine.NATIVE_GENERATION, Thread.currentThread().threadId());
            for (var provider : Bukkit.getPluginManager().getPlugins())
                if (provider.getName().equals("WorldEdit") || provider.getName().equals("FastAsyncWorldEdit"))
                    providers.add(Map.of("name", provider.getName(), "version", provider.getDescription().getVersion(), "enabled", Boolean.toString(provider.isEnabled())));
            if (providers.isEmpty()) throw new IllegalStateException("No effective WorldEdit/FAWE provider is installed");
            if (manifest.profileSpark()) {
                var loader = Bukkit.getServer().getClass().getClassLoader();
                var console = Bukkit.getConsoleSender();
                spark = worker.submit(() -> new SparkCapture(folder.resolveSibling("spark"), worker,
                        () -> PinnedSparkAccess.open(loader, console))).get(5, TimeUnit.SECONDS);
            }
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            active = this; token = fence.begin();
            if (Files.exists(plugin.getServer().getWorldContainer().toPath().resolve(worldName))) throw new IllegalStateException("A/B world already exists");
            world = new WorldCreator(worldName).seed(manifest.seed()).createWorld();
            if (world == null || !attached || world.getGenerator() != null) throw new IllegalStateException("Fresh native-default lab world/populator initialization failed");
            if (manifest.warmEntityAdapters()) {
                long began = System.nanoTime();
                var detached = world.createEntity(world.getSpawnLocation(), org.bukkit.entity.ArmorStand.class);
                if (Bukkit.getEntity(detached.getUniqueId()) != null) throw new IllegalStateException("Detached warmup unexpectedly registered an entity");
                entityAdapterWarmupNanos = System.nanoTime() - began;
            }
            startupNanos = System.nanoTime() - started;
            transition(Stage.WAIT_CONTENT);
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
            plugin.getLogger().info("BS_NATIVE_LAB status=ready engine=" + manifest.engine() + " world=" + worldName + " runId=" + runId);
        } catch (Exception | Error failure) {
            fence.cancel();
            if (populator != null) populator.deactivate();
            if (active == this) active = null;
            HandlerList.unregisterAll(this); worker.shutdownNow();
            throw failure;
        }
    }
    public void contentReady() { contentReady = true; }
    public static boolean manages(World world) { NativeBackendLab lab = active; return lab != null && lab.worldName.equals(world.getName()); }
    public static void requireLatePasteAllowed(World world) {
        NativeBackendLab lab = active;
        if (lab != null && lab.worldName.equals(world.getName()) && !lab.permitDefaultPaste)
            throw new IllegalStateException("Manifest-owned A/B world refuses ordinary late paste; NATIVE_GENERATION requires pre-generation plans and never falls back");
    }
    public static boolean rejectLateCommand(World world, CommandSender sender) {
        if (!manages(world)) return false;
        sender.sendMessage("This experimental manifest world does not accept ordinary /bs place or automatic fitting. Restart the lab with a manifest."); return true;
    }
    @EventHandler public void worldInit(WorldInitEvent event) {
        if (!event.getWorld().getName().equals(worldName)) return;
        if (stage != Stage.WORLD || attached) throw new IllegalStateException("Unexpected A/B WorldInitEvent");
        event.getWorld().getPopulators().add(populator); attached = true;
    }
    private void tick() {
        if (stage == Stage.IDLE || stage == Stage.STOPPED) return;
        try {
            observedPhase = phaseName(); observedActive = stage == Stage.FULL || stage == Stage.BEFORE || stage == Stage.PASTE
                    || stage == Stage.SETTLE || stage == Stage.VERIFY || stage == Stage.TAIL;
            if (stage != Stage.REPORT && System.nanoTime() - started > TimeUnit.MINUTES.toNanos(10)) throw new TimeoutException("A/B lab ten-minute deadline");
            if (populator.failure() != null) throw new IllegalStateException(populator.failure());
            switch (stage) {
                case WAIT_CONTENT -> { if (contentReady && (spark == null || spark.start(runId))) transition(Stage.BASELINE); }
                case BASELINE -> { if (++stageTicks >= 200) startCase(); }
                case FULL -> full();
                case BEFORE -> readback(false);
                case PASTE -> { if (current.pasteCompleted) { current.pasteNanos = System.nanoTime() - phaseStarted; transition(Stage.SETTLE); } }
                case SETTLE -> { if (++stageTicks >= 40) transition(Stage.VERIFY); }
                case VERIFY -> readback(true);
                case TAIL -> { if (++stageTicks > 200) complete(allMatched ? "complete" : "failed", allMatched ? "All selected real assets verified" : "Settled block states differ; see case mismatches"); }
                case REPORT -> {
                    if (reportWrite.isDone()) {
                        Path path = reportWrite.get();
                        plugin.getLogger().info("NATIVE_STRUCTURES_BENCH status=" + terminalStatus + " " + new GsonBuilder().create().toJson(Map.of("runId", runId, "report", path.toString(), "engine", manifest.engine().name())));
                        stage = Stage.IDLE;
                    }
                }
                default -> { }
            }
        } catch (Throwable error) {
            plugin.getLogger().log(Level.WARNING, "BS_NATIVE_LAB failure phase=" + phaseName(), error);
            if (stage == Stage.REPORT) { plugin.getLogger().severe("NATIVE_STRUCTURES_BENCH status=failed phase=report runId=" + runId); stage = Stage.IDLE; }
            else complete("failed", error.toString());
        }
    }
    private void startCase() {
        if (caseCursor == plans.size()) { transition(Stage.TAIL); return; }
        current = new Case(plans.get(caseCursor), observations.get(plans.get(caseCursor).asset.id())); transition(Stage.FULL);
        plugin.getLogger().info("BS_NATIVE_LAB_CASE status=started id=" + current.plan.asset.id());
    }
    private void full() {
        if (current.failure != null) throw new IllegalStateException("FULL request failed", current.failure);
        if (current.pending != null) return;
        if (current.requestCursor == current.plan.slices.size()) {
            for (var slice : current.plan.slices) {
                var receipt = current.observed.receipts.get(slice.key());
                if (receipt == null || receipt.started() < current.started || receipt.mainThread())
                    throw new IllegalStateException("Missing fresh off-main population receipt for this case");
            }
            current.fullNanos = System.nanoTime() - phaseStarted;
            transition(manifest.engine() == LabManifest.Engine.DEFAULT ? Stage.BEFORE : Stage.SETTLE); return;
        }
        var slice = current.plan.slices.get(current.requestCursor++);
        Case requested = current; long requestedToken = token, start = System.nanoTime();
        CompletableFuture<Chunk> future = world.getChunkAtAsync(slice.chunkX(), slice.chunkZ(), true);
        current.maxRequestNanos = Math.max(current.maxRequestNanos, System.nanoTime() - start); current.pending = future;
        future.whenComplete((chunk, error) -> {
            if (!fence.accepts(requestedToken) || current != requested) return;
            long began = System.nanoTime(); requested.pending = null;
            try {
                if (error != null) throw new CompletionException(error);
                if (!Bukkit.isPrimaryThread() || chunk == null || !chunk.isLoaded() || chunk.getWorld() != world) throw new IllegalStateException("Invalid FULL completion boundary");
                PluginChunkHolds.hold(chunk);
                requested.held.put(slice.key(), chunk);
            } catch (Throwable failure) { requested.failure = failure; }
            finally { requested.maxPinNanos = Math.max(requested.maxPinNanos, System.nanoTime() - began); }
        });
    }
    private void readback(boolean verify) {
        var g = current.plan.geometry; SliceBudget budget = new SliceBudget(System::nanoTime, 1_500_000L, 4096);
        while (current.y < g.height() && budget.next()) {
            int x = current.x, y = current.y, z = current.z;
            if (++current.x == g.width()) { current.x = 0; if (++current.z == g.length()) { current.z = 0; current.y++; } }
            // Deliberately independent of the chunk partition used during population.
            int index = x + z * g.width() + y * g.width() * g.length();
            int wx = g.minX() + x, wy = g.minY() + y, wz = g.minZ() + z;
            if (!world.isChunkLoaded(Math.floorDiv(wx, 16), Math.floorDiv(wz, 16))) throw new IllegalStateException("Pinned readback chunk vanished");
            BlockData block = world.getBlockAt(wx, wy, wz).getBlockData();
            if (!verify) { current.observed.capture(current.plan, index, block); continue; }
            String actual = block.getAsString(), expected = current.observed.expected[index], before = current.observed.before[index];
            if (expected == null || before == null) throw new IllegalStateException("Missing cell observation");
            current.actualHash.add(wx, wy, wz, actual); current.expectedHash.add(wx, wy, wz, expected);
            current.beforeHash.add(wx, wy, wz, before);
            if (!before.equals(actual)) current.changed++;
            current.verified++;
            if (!expected.equals(actual)) {
                current.mismatches++;
                if (current.examples.size() < 12) current.examples.add(Map.of("xyz", List.of(wx, wy, wz), "expected", expected, "actual", actual, "policy", current.plan.policy(index).name()));
            }
        }
        current.maxReadbackSlice = Math.max(current.maxReadbackSlice, budget.elapsed());
        if (current.y != g.height()) return;
        current.x = current.y = current.z = 0;
        if (!verify) {
            current.beforeNanos = System.nanoTime() - phaseStarted; transition(Stage.PASTE);
            Case submitted = current; long submittedToken = token;
            permitDefaultPaste = true;
            try {
                Schematic.pasteSchematic(current.plan.clipboard, new Location(world, current.plan.asset.x(), current.plan.asset.y(), current.plan.asset.z()),
                        WorldEditUtils.getSchematicOffset(current.plan.clipboard), ground -> pedestalMaterial,
                        () -> { if (fence.accepts(submittedToken) && current == submitted) submitted.pasteCompleted = true; });
            } finally { permitDefaultPaste = false; }
        } else { current.verifyNanos = System.nanoTime() - phaseStarted; finishCase(); }
    }
    private void finishCase() {
        Case done = current; Map<String, Object> result = new LinkedHashMap<>(done.plan.metadata);
        long workerNanos = 0, maxChunk = 0; int calls = 0;
        Map<String, Integer> threads = new TreeMap<>();
        for (var receipt : done.observed.receipts.values()) {
            workerNanos += receipt.nanos(); maxChunk = Math.max(maxChunk, receipt.nanos()); calls += receipt.setBlockCalls(); threads.merge(receipt.thread(), 1, Integer::sum);
        }
        boolean matched = done.mismatches == 0 && done.verified == done.plan.geometry.volume() && done.expectedHash.hex().equals(done.actualHash.hex());
        allMatched &= matched;
        result.put("status", matched ? "matched" : "mismatch"); result.put("anchor", List.of(done.plan.asset.x(), done.plan.asset.y(), done.plan.asset.z()));
        result.put("populationReceipts", done.observed.receipts.size()); result.put("populationThreadCallbacks", threads);
        result.put("populationWorkerTotalMs", workerNanos / 1_000_000D); result.put("populationMaxChunkMs", maxChunk / 1_000_000D);
        result.put("nativeSetBlockDataCalls", calls); result.put("observedChangedCells", done.changed);
        result.put("fullPreparationMs", done.fullNanos / 1_000_000D); result.put("defaultPasteMs", done.pasteNanos / 1_000_000D);
        result.put("beforeSnapshotMs", done.beforeNanos / 1_000_000D); result.put("verificationMs", done.verifyNanos / 1_000_000D);
        result.put("maxFullRequestMs", done.maxRequestNanos / 1_000_000D); result.put("maxPinCallbackMs", done.maxPinNanos / 1_000_000D);
        result.put("maxReadbackSliceMs", done.maxReadbackSlice / 1_000_000D); result.put("verifiedCells", done.verified);
        result.put("expectedHash", done.expectedHash.hex()); result.put("actualHash", done.actualHash.hex());
        result.put("beforeHash", done.beforeHash.hex());
        result.put("mismatches", done.mismatches); result.put("mismatchExamples", done.examples);
        result.put("totalCaseMs", (System.nanoTime() - done.started) / 1_000_000D);
        releaseCase(); result.put("remainingNativeChunkTickets", remainingTickets); results.add(result);
        if (remainingTickets != 0) throw new IllegalStateException("Native tickets remain after case release");
        plugin.getLogger().info("BS_NATIVE_LAB_CASE status=" + (matched ? "matched" : "mismatch") + " id=" + done.plan.asset.id() + " mismatches=" + done.mismatches);
        caseCursor++; startCase();
    }
    private void transition(Stage next) { stage = next; stageTicks = 0; phaseStarted = System.nanoTime(); }
    private String phaseName() { return (current == null ? "" : current.plan.asset.id() + ".") + stage.name().toLowerCase(Locale.ROOT); }
    @EventHandler public void tickEnd(ServerTickEndEvent event) {
        if (stage == Stage.STOPPED || stage == Stage.IDLE || stage == Stage.REPORT) return;
        tickSamples.computeIfAbsent(observedPhase, ignored -> new Samples()).add(event.getTickDuration());
        if (observedActive) activeTicks.add(event.getTickDuration());
    }
    private void releaseCase() {
        Case old = current; current = null;
        if (old != null) {
            if (old.pending != null) old.pending.cancel(false);
            for (Chunk held : old.held.values()) PluginChunkHolds.release(held);
            old.held.clear();
        }
        remainingTickets = world != null && Bukkit.getWorld(world.getUID()) == world ? world.getPluginChunkTickets().getOrDefault(plugin, List.of()).size() : -1;
    }
    private void complete(String status, String message) {
        fence.cancel(); populator.deactivate();
        if (world != null) Schematic.cancelClipboardPastesInWorld(world);
        releaseCase(); if (spark != null) spark.stop(runId);
        terminalStatus = status;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status); report.put("message", message); report.put("runId", runId); report.put("startedAt", startedAt);
        report.put("engine", manifest.engine().name()); report.put("scope", "CLIPBOARD_BLOCK_PHASE"); report.put("world", worldName); report.put("seed", manifest.seed());
        report.put("server", Bukkit.getVersion()); report.put("java", System.getProperty("java.version")); report.put("worldEditProviders", providers);
        report.put("pedestalMaterial", pedestalMaterial.name()); report.put("percentageOfTickUsedForPasting", DefaultConfig.getPercentageOfTickUsedForPasting());
        report.put("preflight", new ArrayList<>(preflight)); report.put("cases", new ArrayList<>(results));
        report.put("startupPreparationMs", startupNanos / 1_000_000D); report.put("totalMs", (System.nanoTime() - started) / 1_000_000D);
        report.put("warmEntityAdapters", manifest.warmEntityAdapters()); report.put("entityAdapterWarmupMs", entityAdapterWarmupNanos / 1_000_000D);
        report.put("entityAdapterWarmupRegisteredEntity", false);
        report.put("remainingNativeChunkTickets", remainingTickets); report.put("overallActiveTickMs", activeTicks.summary());
        Map<String, Object> phases = new LinkedHashMap<>(); tickSamples.forEach((name, values) -> phases.put(name, values.summary())); report.put("tickMs", phases);
        report.put("overallWindow", "FULL, before-snapshot, actual DEFAULT paste or native population, settle, verification, final 200-tick post-release tail; excludes startup/content wait/idle baseline/report IO");
        report.put("limits", List.of("This opt-in manifest tests the actual clipboard block phase, not automatic fitting, Preparation/Finishing, loot, markers or entity placement",
                "WorldEdit/FAWE is retained as reader and DEFAULT implementation; native population makes no WE write calls",
                "AIR clears occupied terrain but preserves existing air variants; BARRIER preserves terrain; BEDROCK uses the same explicit constant provider and solid-destination condition",
                "Only identity transforms are accepted; unsupported selected assets fail preflight before either world is generated",
                "DEFAULT retains its normal physics and special-material WE path; LimitedRegion has no physics, and settled state differences fail acceptance",
                "Before snapshots and final hashing are benchmark validation overhead, separated by phase; native population timing includes observation capture",
                "FULL ticket propagation, vanilla generation, unload/save and GC may still stall main ticks; native calls are indivisible",
                "The bootstrap runs in onEnable; one inert populator remains until world shutdown to avoid racing CraftWorld's ArrayList"));
        Path target = folder.resolve("native-ab-report-" + runId + ".json"); long reporting = fence.begin(); SparkCapture completedSpark = spark;
        transition(Stage.REPORT);
        reportWrite = worker.submit(() -> {
            if (completedSpark != null) report.put("spark", completedSpark.awaitReceipt());
            Path tmp = Files.createTempFile(folder, ".native-ab-", ".tmp");
            try {
                if (!fence.accepts(reporting)) throw new CancellationException("Retired A/B report");
                Files.writeString(tmp, json(report));
                if (!fence.accepts(reporting)) throw new CancellationException("Retired A/B report");
                Files.move(tmp, target); return target;
            } finally { Files.deleteIfExists(tmp); }
        });
    }
    @Override public void close() {
        fence.cancel(); stage = Stage.STOPPED; populator.deactivate(); if (active == this) active = null;
        cleanup("ticker", () -> { if (ticker != null) ticker.cancel(); });
        cleanup("listeners", () -> HandlerList.unregisterAll(this));
        cleanup("paste", () -> { if (world != null) Schematic.cancelClipboardPastesInWorld(world); });
        cleanup("tickets", this::releaseCase);
        if (reportWrite != null) reportWrite.cancel(true);
        cleanup("owned spark capture", () -> { if (spark != null) spark.stop(runId); });
        // Permit the already-queued, identity-checked Spark stop to finish off-thread.
        worker.shutdown();
    }
    private void cleanup(String name, Runnable action) {
        try { action.run(); }
        catch (RuntimeException | LinkageError failure) { plugin.getLogger().log(Level.WARNING, "Could not close lab " + name + "; continuing cleanup", failure); }
    }
    private static String json(Object value) { return new GsonBuilder().setPrettyPrinting().create().toJson(value); }
    private static final class Case {
        final LabPlan plan; final LabObservations observed; final long started = System.nanoTime();
        final Map<Long, Chunk> held = new LinkedHashMap<>(); final List<Map<String, Object>> examples = new ArrayList<>();
        final BlockHash expectedHash = new BlockHash(), actualHash = new BlockHash(), beforeHash = new BlockHash();
        CompletableFuture<Chunk> pending; Throwable failure;
        boolean pasteCompleted; int requestCursor, x, y, z, verified, mismatches, changed;
        long fullNanos, beforeNanos, pasteNanos, verifyNanos, maxRequestNanos, maxPinNanos, maxReadbackSlice;
        Case(LabPlan plan, LabObservations observed) { this.plan = plan; this.observed = observed; }
    }
}
