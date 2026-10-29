package ru.ruscrafting.nativegenerationlab;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.google.gson.GsonBuilder;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/** Generation-only lab: main thread coordinates native FULL loads and independent bounded readback. */
public final class NativeGenerationLab extends JavaPlugin implements Listener, CommandExecutor {
    private enum Stage { PREPARE, PALETTE, WORLD, BASELINE, GENERATE, VERIFY, SETTLE, TAIL, REPORT, IDLE, STOPPED }
    private static final long BUDGET_NANOS = 1_500_000L, DEADLINE_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final int MAX_POSITIONS = 4096;
    private final RunFence fence = new RunFence();
    private final List<NativePopulator.Prepared> prepared = new ArrayList<>();
    private final Map<String, Samples> tickSamples = new LinkedHashMap<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private Samples activeTicks = new Samples();
    private ExecutorService worker;
    private Future<List<GenerationPlan>> decode;
    private Future<Path> reportWrite;
    private BukkitTask ticker;
    private NativePopulator populator;
    private World world;
    private Stage stage = Stage.STOPPED;
    private List<GenerationPlan> plans;
    private BlockData[] buildingPalette;
    private String[] buildingCanonical;
    private Case current;
    private Path folder;
    private String runId, startedAt, serverVersion, observedPhase = "startup", terminalStatus, terminalMessage;
    private int planCursor, paletteCursor, caseCursor, stageTicks, dataVersion, remainingTickets = -1;
    private long startedNanos, token, phaseStarted, startupNanos;
    private boolean attached, observedActive;

    @Override public void onEnable() {
        try {
            LabGuard.server(getServer().getIp(), getServer().getPort());
            if (Bukkit.getWorld(LabGuard.WORLD) != null)
                throw new IllegalStateException("Generation lab world is already loaded; this variant requires a fresh server boot");
            runId = UUID.randomUUID().toString(); startedAt = Instant.now().toString(); startedNanos = System.nanoTime();
            folder = getDataFolder().toPath(); dataVersion = Bukkit.getUnsafe().getDataVersion(); serverVersion = Bukkit.getVersion();
            worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "ArcNativeGenerationLab-io"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            int baseX = 1_048_576 + (int) Math.floorMod(UUID.fromString(runId).getLeastSignificantBits(), 24_000L) * 512;
            int fixtureDataVersion = dataVersion; Path fixtureFolder = folder.resolve("generation-fixtures");
            decode = worker.submit(() -> {
                List<GenerationPlan> list = new ArrayList<>();
                list.add(GenerationPlan.control(baseX, -8192));
                int index = 1;
                for (var fixture : Fixtures.prepare(fixtureFolder, fixtureDataVersion))
                    list.add(new GenerationPlan(fixture.name(), fixture.schematic(), baseX + index++ * 4096, 200, -8192));
                return List.copyOf(list);
            });
            getServer().getPluginManager().registerEvents(this, this);
            Objects.requireNonNull(getCommand("nativegeneration")).setExecutor(this);
            token = fence.begin(); transition(Stage.PREPARE);
            ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
            getLogger().info("NATIVE_GENERATION_LAB status=preparing runId=" + runId);
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "NATIVE_STRUCTURES_BENCH status=failed phase=startup", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @EventHandler public void worldInit(WorldInitEvent event) {
        if (!LabGuard.WORLD.equals(event.getWorld().getName())) return;
        if (stage != Stage.WORLD || populator == null || attached)
            throw new IllegalStateException("Unexpected generation-lab WorldInitEvent");
        event.getWorld().getPopulators().add(populator); attached = true;
    }

    private void tick() {
        if (stage == Stage.STOPPED || stage == Stage.IDLE) return;
        try {
            LabGuard.server(getServer().getIp(), getServer().getPort());
            if (stage != Stage.REPORT && System.nanoTime() - startedNanos > DEADLINE_NANOS)
                throw new TimeoutException("Generation lab exceeded ten minutes");
            if (populator != null && populator.failure() != null) throw new IllegalStateException(populator.failure());
            observedPhase = phaseName();
            observedActive = stage == Stage.GENERATE || stage == Stage.VERIFY || stage == Stage.SETTLE || stage == Stage.TAIL;
            SliceBudget budget = new SliceBudget(System::nanoTime, BUDGET_NANOS, MAX_POSITIONS);
            switch (stage) {
                case PREPARE -> { if (decode.isDone()) { plans = decode.get(); transition(Stage.PALETTE); } }
                case PALETTE -> preparePalette(budget);
                case WORLD -> createWorld();
                case BASELINE -> { if (++stageTicks >= 200) startCase(); }
                case GENERATE -> requestNextChunk();
                case VERIFY -> verify(budget);
                case SETTLE -> { if (++stageTicks >= 40) finishCase(); }
                // Finish on the following tick so all 200 completed tail ticks enter the report snapshot.
                case TAIL -> { if (++stageTicks > 200) complete("complete", "All generation plans verified"); }
                case REPORT -> {
                    if (reportWrite.isDone()) {
                        Path report = reportWrite.get();
                        getLogger().info("NATIVE_STRUCTURES_BENCH status=" + terminalStatus + " " + new GsonBuilder().create().toJson(
                                Map.of("runId", runId, "report", report.toString(), "message", terminalMessage)));
                        stage = Stage.IDLE;
                    }
                }
                default -> { }
            }
        } catch (Throwable error) {
            if (stage == Stage.REPORT) {
                getLogger().log(Level.SEVERE, "NATIVE_STRUCTURES_BENCH status=failed phase=report runId=" + runId, error);
                stage = Stage.IDLE;
            } else {
                getLogger().log(Level.WARNING, "NATIVE_GENERATION_LAB status=failed phase=" + phaseName(), error);
                complete("failed", error.toString());
            }
        }
    }

    private void preparePalette(SliceBudget budget) {
        while (planCursor < plans.size() && budget.next()) {
            GenerationPlan plan = plans.get(planCursor);
            if (plan.control()) {
                prepared.add(new NativePopulator.Prepared(plan, new BlockData[0], new String[0])); planCursor++; continue;
            }
            if (buildingPalette == null) {
                if (plan.schematic.dataVersion > dataVersion) throw new IllegalArgumentException("Newer DataVersion unsupported");
                buildingPalette = new BlockData[plan.schematic.palette.size()]; buildingCanonical = new String[buildingPalette.length];
            }
            if (paletteCursor < buildingPalette.length) {
                int index = paletteCursor++; String state = plan.schematic.palette.get(index);
                if (state == null) continue;
                BlockData data = Bukkit.createBlockData(state);
                if (data.createBlockState() instanceof TileState) throw new IllegalArgumentException("Block-entity material unsupported: " + state);
                buildingPalette[index] = data; buildingCanonical[index] = data.getAsString();
            }
            if (paletteCursor == buildingPalette.length) {
                prepared.add(new NativePopulator.Prepared(plan, buildingPalette, buildingCanonical));
                buildingPalette = null; buildingCanonical = null; paletteCursor = 0; planCursor++;
            }
        }
        if (planCursor == plans.size()) { populator = new NativePopulator(List.copyOf(prepared), Thread.currentThread().threadId()); transition(Stage.WORLD); }
    }
    private void createWorld() {
        world = new WorldCreator(LabGuard.WORLD).createWorld();
        if (world == null || !attached || !world.getPopulators().contains(populator))
            throw new IllegalStateException("Populator was not attached through WorldInitEvent");
        LabGuard.world(world.getName());
        if (world.getGenerator() != null) throw new IllegalStateException("Benchmark requires vanilla default generation");
        for (GenerationPlan plan : plans) if (!plan.control()) {
            int minY = plan.y + plan.schematic.offsetY;
            if (minY < world.getMinHeight() || minY + plan.schematic.height > world.getMaxHeight())
                throw new IllegalStateException("Plan exceeds world height");
        }
        startupNanos = System.nanoTime() - startedNanos;
        transition(Stage.BASELINE);
    }
    private void startCase() {
        if (caseCursor == prepared.size()) { transition(Stage.TAIL); return; }
        current = new Case(prepared.get(caseCursor)); transition(Stage.GENERATE);
        getLogger().info("NATIVE_GENERATION_JOB status=started name=" + current.prepared.plan.name);
    }
    private void requestNextChunk() {
        if (current.failure != null) throw new IllegalStateException("FULL preparation failed", current.failure);
        if (current.pending != null) return;
        GenerationPlan plan = current.prepared.plan;
        if (current.requestCursor == plan.slices.size()) {
            for (var slice : plan.slices) {
                var receipt = populator.receipt(slice.key());
                if (receipt == null || receipt.cancelled())
                    throw new IllegalStateException("Missing fresh population receipt after FULL: " + slice.chunkX + "," + slice.chunkZ);
            }
            current.generationNanos = System.nanoTime() - phaseStarted;
            transition(plan.control() ? Stage.SETTLE : Stage.VERIFY); return;
        }
        var slice = plan.slices.get(current.requestCursor++);
        if (world.isChunkLoaded(slice.chunkX, slice.chunkZ)) current.alreadyLoaded++;
        Case requested = current; long requestedToken = token;
        long start = System.nanoTime();
        CompletableFuture<Chunk> future = world.getChunkAtAsync(slice.chunkX, slice.chunkZ, true);
        current.maxRequestNanos = Math.max(current.maxRequestNanos, System.nanoTime() - start);
        current.pending = future;
        future.whenComplete((chunk, error) -> {
            if (!fence.accepts(requestedToken) || current != requested) return;
            long callbackStart = System.nanoTime();
            requested.pending = null;
            try {
                if (error != null) throw new CompletionException(error);
                if (!Bukkit.isPrimaryThread() || chunk == null || chunk.getWorld() != world || !chunk.isLoaded())
                    throw new IllegalStateException("FULL callback outside main-thread loaded lab chunk");
                boolean owned = chunk.addPluginChunkTicket(this);
                requested.held.put(slice.key(), new Held(chunk.getX(), chunk.getZ(), owned));
            } catch (Throwable failure) { requested.failure = failure; }
            finally { requested.maxCallbackNanos = Math.max(requested.maxCallbackNanos, System.nanoTime() - callbackStart); }
        });
    }

    private void verify(SliceBudget budget) {
        GenerationPlan plan = current.prepared.plan;
        var schematic = plan.schematic;
        long start = System.nanoTime();
        // Independent nested coordinates: never traverse the placement partition or compacted index list.
        while (current.verifyY < schematic.height && budget.next()) {
            int rx = current.verifyX, ry = current.verifyY, rz = current.verifyZ;
            if (++current.verifyX == schematic.width) {
                current.verifyX = 0;
                if (++current.verifyZ == schematic.length) { current.verifyZ = 0; current.verifyY++; }
            }
            int index = rx + rz * schematic.width + ry * schematic.width * schematic.length;
            BlockData target = current.prepared.target(index);
            if (target.getMaterial().isAir()) continue;
            int x = plan.x + schematic.offsetX + rx, y = plan.y + schematic.offsetY + ry, z = plan.z + schematic.offsetZ + rz;
            if (!world.isChunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16)))
                throw new IllegalStateException("Pinned verification chunk disappeared; sync reacquisition forbidden");
            BlockData actual = world.getBlockAt(x, y, z).getBlockData();
            current.expectedHash.add(x, y, z, current.prepared.canonical(index));
            current.actualHash.add(x, y, z, actual.getAsString());
            current.verified++;
            if (!target.equals(actual)) current.mismatches++;
        }
        current.maxVerifySliceNanos = Math.max(current.maxVerifySliceNanos, System.nanoTime() - start);
        if (current.verifyY == schematic.height) {
            current.verificationNanos = System.nanoTime() - phaseStarted;
            if (current.mismatches != 0 || current.verified != plan.expectedWrites()
                    || !current.expectedHash.hex().equals(current.actualHash.hex()))
                throw new IllegalStateException("Independent final-world verification failed: mismatches=" + current.mismatches);
            transition(Stage.SETTLE);
        }
    }

    private void finishCase() {
        Case done = current; GenerationPlan plan = done.prepared.plan;
        int writes = 0, air = 0, noOps = 0, mainCallbacks = 0;
        long populationNanos = 0, maximumNanos = 0;
        Map<String, Integer> threadNames = new TreeMap<>();
        for (var slice : plan.slices) {
            var receipt = populator.receipt(slice.key());
            if (receipt == null || receipt.cancelled()) throw new IllegalStateException("Incomplete population receipt");
            writes += receipt.actualWrites(); air += receipt.skippedAir(); noOps += receipt.noOps();
            populationNanos += receipt.nanos(); maximumNanos = Math.max(maximumNanos, receipt.nanos());
            threadNames.merge(receipt.threadName(), 1, Integer::sum); if (receipt.mainThread()) mainCallbacks++;
        }
        if (writes + noOps != plan.expectedWrites() || air != plan.expectedSkippedAir())
            throw new IllegalStateException("Population counters differ from fixture: writes=" + writes + ", skippedAir=" + air + ", noOps=" + noOps);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", plan.name); result.put("control", plan.control()); result.put("origin", List.of(plan.x, plan.y, plan.z));
        result.put("chunks", plan.slices.size()); result.put("populationReceipts", plan.slices.size());
        result.put("actualWrites", writes); result.put("plannedNonAir", plan.expectedWrites()); result.put("skippedAir", air); result.put("noOps", noOps);
        result.put("populationWorkerTotalMs", populationNanos / 1_000_000D); result.put("populationMaxChunkMs", maximumNanos / 1_000_000D);
        result.put("populationThreadCallbacks", threadNames); result.put("mainThreadPopulationCallbacks", mainCallbacks);
        result.put("generationToFullMs", done.generationNanos / 1_000_000D); result.put("maxFullRequestMs", done.maxRequestNanos / 1_000_000D);
        result.put("maxChunkCallbackMs", done.maxCallbackNanos / 1_000_000D); result.put("chunksAlreadyLoadedAtRequest", done.alreadyLoaded);
        result.put("verificationMs", done.verificationNanos / 1_000_000D); result.put("maxVerificationSliceMs", done.maxVerifySliceNanos / 1_000_000D);
        result.put("verifiedNonAirPositions", done.verified); result.put("mismatches", done.mismatches);
        result.put("expectedHash", done.expectedHash.hex()); result.put("actualHash", done.actualHash.hex());
        result.put("totalCaseMs", (System.nanoTime() - done.started) / 1_000_000D);
        releaseCase(); result.put("remainingNativeChunkTickets", remainingTickets); results.add(result);
        if (remainingTickets != 0) throw new IllegalStateException("Native plugin chunk tickets remain after release: " + remainingTickets);
        getLogger().info("NATIVE_GENERATION_JOB status=complete name=" + plan.name + " actualWrites=" + writes);
        caseCursor++; startCase();
    }

    private void transition(Stage next) { stage = next; stageTicks = 0; phaseStarted = System.nanoTime(); }
    private String phaseName() { return current == null ? stage.name().toLowerCase(Locale.ROOT) : current.prepared.plan.name + "." + stage.name().toLowerCase(Locale.ROOT); }
    @EventHandler public void tickEnd(ServerTickEndEvent event) {
        if (stage != Stage.STOPPED && stage != Stage.IDLE && stage != Stage.REPORT) {
            tickSamples.computeIfAbsent(observedPhase, ignored -> new Samples()).add(event.getTickDuration());
            if (observedActive) activeTicks.add(event.getTickDuration());
        }
    }
    private void releaseCase() {
        Case old = current; current = null;
        if (old != null) {
            if (old.pending != null) old.pending.cancel(false);
            for (Held held : old.held.values()) if (held.owned && world != null && Bukkit.getWorld(world.getUID()) == world)
                world.removePluginChunkTicket(held.x, held.z, this);
            old.held.clear();
        }
        remainingTickets = world != null && Bukkit.getWorld(world.getUID()) == world
                ? world.getPluginChunkTickets().getOrDefault(this, List.of()).size() : -1;
    }
    private void complete(String status, String message) {
        fence.cancel(); if (populator != null) populator.deactivate(); releaseCase();
        if (decode != null && !decode.isDone()) decode.cancel(true);
        terminalStatus = status; terminalMessage = message;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status); report.put("message", message); report.put("runId", runId); report.put("startedAt", startedAt);
        report.put("server", serverVersion); report.put("java", System.getProperty("java.version")); report.put("world", LabGuard.WORLD);
        report.put("mode", "generation-native-limited-region"); report.put("worldGenerator", "native-default");
        report.put("startupPreparationMs", startupNanos / 1_000_000D); report.put("totalMs", (System.nanoTime() - startedNanos) / 1_000_000D);
        report.put("verifyBudgetMs", BUDGET_NANOS / 1_000_000D); report.put("maxChunkRequestsInFlight", 1);
        report.put("remainingNativeChunkTickets", remainingTickets); report.put("cases", new ArrayList<>(results));
        Map<String, Object> phases = new LinkedHashMap<>(); tickSamples.forEach((phase, samples) -> phases.put(phase, samples.summary()));
        report.put("tickMs", phases); report.put("overallActiveTickMs", activeTicks.summary());
        report.put("overallWindow", "Control/fixture FULL generation, readback, per-case 40-tick settle and 200-tick final post-release tail; excludes startup and idle baseline");
        report.put("limits", List.of("FULL request, native ticket propagation, vanilla generation, unload/save and GC still affect server ticks",
                "One 16-chunk vanilla control matches 64x64 footprint; 32x32 cases have only four chunks",
                "Population callback timings are concurrent worker durations, not main-thread MSPT or elapsed time",
                "Independent main-thread readback and hashing are benchmark validation only; their VERIFY phase is separate from generation but included in overall ticks",
                "Native calls are indivisible; 1.5 ms readback budget is checked between calls",
                "Only non-air targets are written and verified; surrounding terrain/air is preserved",
                "Entities, block entities, biomes and automatic DataVersion conversion are unsupported",
                "Every planned chunk, including control, must populate this run; missing receipts fail instead of accepting old chunks",
                "This fresh-boot-only lab retains one deactivated populator until world/server shutdown; it performs no further writes"));
        Path target = folder.resolve("generation-report-" + runId + ".json"); long reportToken = fence.begin();
        transition(Stage.REPORT);
        reportWrite = worker.submit(() -> {
            Files.createDirectories(target.getParent()); Path temporary = Files.createTempFile(target.getParent(), ".generation-report-", ".tmp");
            try {
                if (!fence.accepts(reportToken)) throw new CancellationException("Retired report");
                Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(report));
                if (!fence.accepts(reportToken)) throw new CancellationException("Retired report");
                Files.move(temporary, target); return target;
            } finally { Files.deleteIfExists(temporary); }
        });
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) { sender.sendMessage("Console only."); return true; }
        if (args.length == 1 && args[0].equals("status")) { sender.sendMessage("Generation lab: " + stage + ", run=" + runId); return true; }
        if (args.length == 1 && args[0].equals("cancel")) {
            if (stage != Stage.IDLE && stage != Stage.STOPPED && stage != Stage.REPORT) complete("cancelled", "Cancelled by console");
            sender.sendMessage("Generation cancellation applied; partial lab terrain is retained."); return true;
        }
        return false;
    }
    @Override public void onDisable() {
        fence.cancel(); stage = Stage.STOPPED;
        if (populator != null) populator.deactivate();
        if (ticker != null) ticker.cancel();
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        if (decode != null) decode.cancel(true); if (reportWrite != null) reportWrite.cancel(true);
        releaseCase();
        // CraftWorld's populator list is an ArrayList iterated by generation workers.
        // Keep this single inert instance until world shutdown instead of racing an active iterator.
        if (worker != null) worker.shutdownNow();
    }
    private record Held(int x, int z, boolean owned) {}
    private static final class Case {
        final NativePopulator.Prepared prepared;
        final Map<Long, Held> held = new LinkedHashMap<>();
        final BlockHash expectedHash = new BlockHash(), actualHash = new BlockHash();
        final long started = System.nanoTime();
        CompletableFuture<Chunk> pending;
        Throwable failure;
        int requestCursor, alreadyLoaded, verifyX, verifyY, verifyZ, verified, mismatches;
        long generationNanos, verificationNanos, maxRequestNanos, maxCallbackNanos, maxVerifySliceNanos;
        Case(NativePopulator.Prepared prepared) { this.prepared = prepared; }
    }
}
