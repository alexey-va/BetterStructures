package ru.ruscrafting.nativestructureslab;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.google.gson.GsonBuilder;
import org.bukkit.*;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/** Disposable lab fixture. All live world access and ticket ownership stays on Paper's main thread. */
public final class NativeStructuresLab extends JavaPlugin implements Listener, CommandExecutor {
    private static final long SLICE_NANOS = 1_500_000L;
    private static final int MAX_POSITIONS = 512, MAX_PREPARING = 2;
    private static final long DEADLINE_NANOS = TimeUnit.MINUTES.toNanos(10);
    private enum Stage { BOOT, BASELINE, DECODE, PALETTE, PREPARE, RESET, APPLY, SETTLE, REPORT, IDLE, STOPPED }
    private Stage stage = Stage.STOPPED;
    private final RunFence fence = new RunFence();
    private final Map<String, Samples> tickSamples = new LinkedHashMap<>();
    private final Map<String, Samples> sliceSamples = new LinkedHashMap<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private Samples overallTicks = new Samples();
    private ExecutorService worker;
    private BukkitTask ticker;
    private Future<List<Fixtures.Fixture>> fixtureLoad;
    private Future<SchematicCodec.Schematic> manualLoad;
    private Future<Path> reportWrite;
    private World world;
    private Path dataFolder, schematicFolder;
    private List<Fixtures.Fixture> fixtures = List.of();
    private Job job;
    private BlockData air;
    private int stageTicks, fixtureIndex, serverDataVersion;
    private long token, runStarted, phaseStarted, manualDecodeStarted;
    private boolean benchmark, collecting, observedActive;
    private String observedPhase = "boot", terminalStatus = "complete", terminalMessage = "";
    private String serverVersion, startedAt, worldGenerator;
    private String manualName;
    private int manualX, manualY, manualZ, benchmarkBaseX;

    @Override public void onEnable() {
        try {
            benchmark = true;
            collecting = false; observedActive = false; fixtureIndex = 0;
            results.clear(); tickSamples.clear(); sliceSamples.clear(); overallTicks = new Samples();
            manualLoad = null; reportWrite = null;
            startedAt = Instant.now().toString();
            LabGuard.server(getServer().getIp(), getServer().getPort());
            dataFolder = getDataFolder().toPath();
            schematicFolder = dataFolder.resolve("schematics");
            worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(4), runnable -> {
                        Thread thread = new Thread(runnable, "ArcNativeStructuresLab-io"); thread.setDaemon(true); return thread;
                    }, new ThreadPoolExecutor.AbortPolicy());
            // World creation is startup maintenance, never a placement or chunk-event callback.
            world = Bukkit.getWorld(LabGuard.WORLD);
            if (world == null) world = new WorldCreator(LabGuard.WORLD).generateStructures(false).createWorld();
            if (world == null) throw new IllegalStateException("Could not create rc_native_lab");
            LabGuard.world(world.getName());
            worldGenerator = world.getGenerator() == null ? "native-default" : world.getGenerator().getClass().getName();
            air = Bukkit.createBlockData(Material.AIR);
            serverDataVersion = Bukkit.getUnsafe().getDataVersion();
            serverVersion = Bukkit.getVersion();
            int fixtureDataVersion = serverDataVersion;
            Path fixtureFolder = schematicFolder;
            fixtureLoad = worker.submit(() -> Fixtures.prepare(fixtureFolder, fixtureDataVersion));
            Objects.requireNonNull(getCommand("nativestructures")).setExecutor(this);
            Bukkit.getPluginManager().registerEvents(this, this);
            stage = Stage.BOOT; stageTicks = 0;
            runStarted = System.nanoTime();
            ticker = Bukkit.getScheduler().runTaskTimer(this, this::tickSafely, 1L, 1L);
            getLogger().info("NATIVE_STRUCTURES_LAB status=ready world=" + LabGuard.WORLD + " bind=loopback port=54295");
        } catch (Throwable failure) {
            getLogger().log(Level.SEVERE, "NATIVE_STRUCTURES_BENCH status=failed phase=startup", failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void tickSafely() {
        if (stage == Stage.STOPPED || stage == Stage.IDLE) return;
        try {
            LabGuard.server(getServer().getIp(), getServer().getPort());
            LabGuard.world(world.getName());
            if (Bukkit.getWorld(world.getUID()) != world) throw new IllegalStateException("Lab world was unloaded/replaced");
            if (stage != Stage.REPORT && System.nanoTime() - runStarted > DEADLINE_NANOS)
                throw new TimeoutException("Lab operation exceeded its explicit ten-minute deadline");
            observedPhase = phaseName();
            observedActive = stage == Stage.DECODE || stage == Stage.PALETTE || stage == Stage.PREPARE
                    || stage == Stage.RESET || stage == Stage.APPLY || stage == Stage.SETTLE;
            SliceBudget budget = new SliceBudget(System::nanoTime, SLICE_NANOS, MAX_POSITIONS);
            switch (stage) {
                case BOOT -> {
                    if (++stageTicks >= 100 && fixtureLoad.isDone()) {
                        fixtures = fixtureLoad.get(); benchmark = true; token = fence.begin();
                        startedAt = Instant.now().toString(); runStarted = System.nanoTime();
                        benchmarkBaseX = 4096 + (int) (System.currentTimeMillis() % 40_000L) * 16;
                        collecting = true; transition(Stage.BASELINE);
                    }
                }
                case BASELINE -> { if (++stageTicks >= 200) startFixture(); }
                case DECODE -> {
                    if (manualLoad.isDone()) {
                        SchematicCodec.Schematic schematic = manualLoad.get();
                        beginJob(manualName, schematic, manualX, manualY, manualZ, -1, System.nanoTime() - manualDecodeStarted);
                    }
                }
                case PALETTE -> preparePalette(budget);
                case PREPARE -> prepareChunks(budget);
                case RESET -> apply(budget, true);
                case APPLY -> apply(budget, false);
                case SETTLE -> { if (++stageTicks >= 40) finishJob(); }
                case REPORT -> finishReportIfReady();
                default -> { }
            }
            if (budget.used() > 0) sliceSamples.computeIfAbsent(observedPhase, ignored -> new Samples())
                    .add(budget.elapsed() / 1_000_000D);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void startFixture() {
        if (fixtureIndex >= fixtures.size()) { completeRun("complete", "All fixtures completed"); return; }
        Fixtures.Fixture fixture = fixtures.get(fixtureIndex);
        beginJob(fixture.name(), fixture.schematic(), benchmarkBaseX + fixtureIndex * 256,
                200, -8192, fixture.expectedWrites(), fixture.decodeNanos());
    }
    private void beginJob(String name, SchematicCodec.Schematic schematic, int x, int y, int z,
                          int expectedWrites, long decodeNanos) {
        int minX = Math.addExact(x, schematic.offsetX), minY = Math.addExact(y, schematic.offsetY);
        int minZ = Math.addExact(z, schematic.offsetZ);
        int maxX = Math.addExact(minX, schematic.width - 1), maxY = Math.addExact(minY, schematic.height - 1);
        int maxZ = Math.addExact(minZ, schematic.length - 1);
        if (minY < world.getMinHeight() || maxY >= world.getMaxHeight() ||
                Math.abs((long) minX) > 29_999_000 || Math.abs((long) maxX) > 29_999_000 ||
                Math.abs((long) minZ) > 29_999_000 || Math.abs((long) maxZ) > 29_999_000)
            throw new IllegalArgumentException("Schematic footprint exceeds lab world height/coordinate bounds");
        if (schematic.dataVersion > serverDataVersion)
            throw new IllegalArgumentException("Schematic DataVersion is newer than the server; data conversion is unsupported");
        job = new Job(name, schematic, x, y, z, expectedWrites, decodeNanos);
        transition(Stage.PALETTE);
        getLogger().info("NATIVE_STRUCTURES_JOB status=started name=" + name + " volume=" + schematic.volume());
    }
    private void preparePalette(SliceBudget budget) {
        while (job.paletteCursor < job.palette.length && budget.next()) {
            int index = job.paletteCursor++;
            String state = job.schematic.palette.get(index);
            if (state == null) continue; // Sparse palette IDs are legal; codec validates every actual reference.
            BlockData data = Bukkit.createBlockData(state);
            if (data.createBlockState() instanceof TileState)
                throw new IllegalArgumentException("Block-entity material unsupported: " + state);
            job.palette[index] = data;
        }
        if (job.paletteCursor == job.palette.length) {
            job.paletteNanos = System.nanoTime() - phaseStarted; transition(Stage.PREPARE);
        }
    }

    private void prepareChunks(SliceBudget budget) {
        if (job.failure != null) throw new IllegalStateException("Asynchronous chunk preparation failed", job.failure);
        while (job.requestCursor < job.footprint.size() && job.pending.size() < MAX_PREPARING && budget.next()) {
            SchematicCodec.ChunkPos position = job.footprint.get(job.requestCursor++);
            if (world.isChunkLoaded(position.x(), position.z())) job.alreadyLoaded++;
            Job requestedJob = job;
            long requestedToken = token;
            CompletableFuture<Chunk> future = world.getChunkAtAsync(position.x(), position.z(), true);
            requestedJob.pending.put(position.key(), future);
            future.whenComplete((chunk, failure) -> {
                // Paper 1.21.11 completes this future on the main server thread.
                if (!fence.accepts(requestedToken) || job != requestedJob || stage == Stage.STOPPED) return;
                requestedJob.pending.remove(position.key());
                if (failure != null) { requestedJob.failure = failure; return; }
                long started = System.nanoTime();
                try {
                    if (!Bukkit.isPrimaryThread() || chunk == null || chunk.getWorld() != world || !chunk.isLoaded())
                        throw new IllegalStateException("Chunk completed outside the loaded lab-world/main-thread boundary");
                    // Pin immediately in the completion callback; delaying this allows pregeneration to unload it.
                    boolean owned = chunk.addPluginChunkTicket(this);
                    requestedJob.held.put(position.key(), new HeldChunk(chunk, owned));
                } catch (Throwable error) { requestedJob.failure = error; }
                finally { requestedJob.maxPinNanos = Math.max(requestedJob.maxPinNanos, System.nanoTime() - started); }
            });
        }
        if (job.failure != null) throw new IllegalStateException("Asynchronous chunk preparation failed", job.failure);
        if (job.held.size() == job.footprint.size() && job.pending.isEmpty()) {
            job.preparationNanos = System.nanoTime() - phaseStarted;
            transition(benchmark ? Stage.RESET : Stage.APPLY);
        }
    }

    private void apply(SliceBudget budget, boolean reset) {
        SchematicCodec.Schematic schematic = job.schematic;
        while (job.cursor < schematic.volume() && budget.next()) {
            int index = job.cursor++;
            BlockData target = reset ? air : job.palette[schematic.block(index)];
            // Sparse structures preserve existing air-space; reset is explicit for the synthetic benchmark only.
            if (!reset && target.getMaterial().isAir()) { job.skippedAir++; continue; }
            int x = job.x + schematic.offsetX + schematic.x(index);
            int y = job.y + schematic.offsetY + schematic.y(index);
            int z = job.z + schematic.offsetZ + schematic.z(index);
            if (!world.isChunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16)))
                throw new IllegalStateException("Prepared chunk disappeared; synchronous reacquisition is forbidden");
            var block = world.getBlockAt(x, y, z);
            if (block.getBlockData().equals(target)) { if (!reset) job.noOps++; continue; }
            block.setBlockData(target, false);
            if (reset) job.resetWrites++; else job.actualWrites++;
        }
        if (job.cursor == schematic.volume()) {
            if (reset) {
                job.resetNanos = System.nanoTime() - phaseStarted; job.cursor = 0; transition(Stage.APPLY);
            } else {
                job.applyNanos = System.nanoTime() - phaseStarted;
                if (job.expectedWrites >= 0 && job.actualWrites != job.expectedWrites)
                    throw new IllegalStateException("Fixture actual writes differ: expected=" + job.expectedWrites + " actual=" + job.actualWrites);
                transition(Stage.SETTLE);
            }
        }
    }

    private void finishJob() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", job.name); result.put("volume", job.schematic.volume());
        result.put("origin", List.of(job.x, job.y, job.z)); result.put("chunks", job.footprint.size());
        result.put("chunksAlreadyLoadedAtRequest", job.alreadyLoaded);
        result.put("decodeMs", job.decodeNanos / 1_000_000D); result.put("paletteMs", job.paletteNanos / 1_000_000D);
        result.put("chunkPreparationMs", job.preparationNanos / 1_000_000D);
        result.put("resetMs", job.resetNanos / 1_000_000D); result.put("resetActualWrites", job.resetWrites);
        result.put("placementMs", job.applyNanos / 1_000_000D); result.put("actualWrites", job.actualWrites);
        result.put("skippedAir", job.skippedAir); result.put("noOps", job.noOps);
        result.put("actualBlocksPerSecond", job.applyNanos == 0 ? 0D : job.actualWrites * 1_000_000_000D / job.applyNanos);
        result.put("maxTicketPinCallbackMs", job.maxPinNanos / 1_000_000D);
        result.put("totalJobMs", (System.nanoTime() - job.started) / 1_000_000D);
        results.add(result);
        getLogger().info("NATIVE_STRUCTURES_JOB status=complete name=" + job.name + " actualWrites=" + job.actualWrites);
        releaseJob();
        if (benchmark) { fixtureIndex++; startFixture(); } else completeRun("complete", "Placement completed");
    }

    private void transition(Stage next) { stage = next; stageTicks = 0; phaseStarted = System.nanoTime(); }
    private String phaseName() {
        String label = switch (stage) {
            case PREPARE -> "chunk_prepare";
            case APPLY -> "place";
            default -> stage.name().toLowerCase(Locale.ROOT);
        };
        return job == null ? label : job.name + "." + label;
    }
    @EventHandler public void tickEnded(ServerTickEndEvent event) {
        if (collecting) {
            tickSamples.computeIfAbsent(observedPhase, ignored -> new Samples()).add(event.getTickDuration());
            if (observedActive) overallTicks.add(event.getTickDuration());
        }
    }

    private void fail(Throwable failure) {
        if (stage == Stage.REPORT) {
            getLogger().log(Level.SEVERE, "NATIVE_STRUCTURES_BENCH status=failed phase=report", failure);
            stage = Stage.IDLE; collecting = false; return;
        }
        getLogger().log(Level.WARNING, "NATIVE_STRUCTURES_" + (benchmark ? "BENCH" : "PLACE")
                + " status=failed phase=" + phaseName(), failure);
        completeRun("failed", failure.toString());
    }
    private void completeRun(String status, String message) {
        collecting = false; fence.cancel(); releaseJob();
        if (fixtureLoad != null && !fixtureLoad.isDone()) fixtureLoad.cancel(true);
        if (manualLoad != null && !manualLoad.isDone()) manualLoad.cancel(true);
        terminalStatus = status; terminalMessage = message;
        long totalNanos = System.nanoTime() - runStarted;
        List<Map<String, Object>> completedResults = new ArrayList<>(results);
        Map<String, Samples> ticks = new LinkedHashMap<>(tickSamples), slices = new LinkedHashMap<>(sliceSamples);
        Samples overall = overallTicks;
        boolean benchReport = benchmark;
        String startTimestamp = startedAt, version = serverVersion, generator = worldGenerator;
        Path reportFolder = dataFolder;
        String reportId = UUID.randomUUID().toString();
        long reportingToken = fence.begin();
        transition(Stage.REPORT);
        reportWrite = worker.submit(() -> {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", status); report.put("message", message); report.put("startedAt", startTimestamp);
            report.put("runId", reportId);
            report.put("server", version); report.put("java", System.getProperty("java.version"));
            report.put("world", LabGuard.WORLD); report.put("totalMs", totalNanos / 1_000_000D);
            report.put("worldGenerator", generator);
            report.put("sliceBudgetMs", SLICE_NANOS / 1_000_000D); report.put("maxPositionsPerTick", MAX_POSITIONS);
            report.put("maxChunkRequestsInFlight", MAX_PREPARING); report.put("cases", completedResults);
            report.put("tickMs", summarize(ticks)); report.put("ownSliceMs", summarize(slices));
            report.put("overallActiveTickMs", overall.summary());
            report.put("overallWindow", "Palette preparation, chunk preparation, reset, placement and settle; excludes boot, idle baseline and report IO");
            report.put("limits", List.of("Native calls cannot be preempted; time budget is checked between calls",
                    "Ticket pinning occurs immediately in at most two in-flight chunk completions and is measured separately",
                    "Chunk preparation reports unloaded-vs-loaded, not an assumed on-disk generation state",
                    "Block entities, entities, biome changes and automatic data-version conversion are unsupported",
                    "Physics=false does not eliminate native lighting or later neighbour work; settle ticks are included",
                    "Sparse placements skip air; only benchmark fixtures receive an explicit reset"));
            Files.createDirectories(reportFolder);
            // A retired writer can only finish its own immutable run receipt.
            Path target = reportFolder.resolve((benchReport ? "benchmark-report-" : "placement-report-") + reportId + ".json");
            Path temporary = target.resolveSibling(target.getFileName() + "." + reportingToken + ".tmp");
            try {
                if (!fence.accepts(reportingToken)) throw new CancellationException("Report generation retired");
                Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(report));
                if (!fence.accepts(reportingToken)) throw new CancellationException("Report generation retired");
                Files.move(temporary, target);
            } finally { Files.deleteIfExists(temporary); }
            return target;
        });
    }
    private static Map<String, Object> summarize(Map<String, Samples> values) {
        Map<String, Object> summaries = new LinkedHashMap<>();
        values.forEach((name, samples) -> summaries.put(name, samples.summary())); return summaries;
    }
    private void finishReportIfReady() throws Exception {
        if (reportWrite != null && reportWrite.isDone()) {
            Path path = reportWrite.get();
            getLogger().info("NATIVE_STRUCTURES_" + (benchmark ? "BENCH" : "PLACE")
                    + " status=" + terminalStatus + " " + new GsonBuilder().create().toJson(
                    Map.of("report", path.toString(), "cases", results.size(), "message", terminalMessage)));
            stage = Stage.IDLE;
        }
    }
    private void releaseJob() {
        Job old = job; job = null;
        if (old == null) return;
        for (CompletableFuture<Chunk> pending : new ArrayList<>(old.pending.values())) pending.cancel(false);
        old.pending.clear();
        for (HeldChunk held : old.held.values()) {
            if (held.owned && Bukkit.getWorld(world.getUID()) == world)
                world.removePluginChunkTicket(held.chunk.getX(), held.chunk.getZ(), this);
        }
        old.held.clear();
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) { sender.sendMessage("This lab command is console-only."); return true; }
        if (args.length == 1 && args[0].equals("status")) {
            sender.sendMessage("Native lab: " + stage + ", job=" + (job == null ? "none" : job.name)
                    + ", pinnedChunks=" + (job == null ? 0 : job.held.size())); return true;
        }
        if (args.length == 1 && args[0].equals("cancel")) {
            if (stage != Stage.IDLE && stage != Stage.STOPPED && stage != Stage.REPORT) completeRun("cancelled", "Cancelled by console");
            sender.sendMessage("Native lab: cancellation applied."); return true;
        }
        if (args.length != 5 || !args[0].equals("place")) return false;
        if (stage != Stage.IDLE) { sender.sendMessage("Native lab is busy; use status or cancel first."); return true; }
        try {
            LabGuard.server(getServer().getIp(), getServer().getPort()); LabGuard.world(world.getName());
            manualName = args[1]; manualX = Integer.parseInt(args[2]); manualY = Integer.parseInt(args[3]); manualZ = Integer.parseInt(args[4]);
            String filename = manualName;
            Future<SchematicCodec.Schematic> accepted = worker.submit(() -> SchematicCodec.read(LabGuard.schematic(schematicFolder, filename)));
            benchmark = false; token = fence.begin(); results.clear(); tickSamples.clear(); sliceSamples.clear(); overallTicks = new Samples();
            startedAt = Instant.now().toString(); runStarted = manualDecodeStarted = System.nanoTime(); collecting = true;
            manualLoad = accepted;
            transition(Stage.DECODE); sender.sendMessage("Accepted native placement in rc_native_lab; completion is reported separately.");
        } catch (RuntimeException failure) { sender.sendMessage("Placement rejected: " + failure.getMessage()); }
        return true;
    }

    @Override public void onDisable() {
        collecting = false; fence.cancel(); stage = Stage.STOPPED;
        if (ticker != null) { ticker.cancel(); ticker = null; }
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        if (fixtureLoad != null) fixtureLoad.cancel(true);
        if (manualLoad != null) manualLoad.cancel(true);
        if (reportWrite != null) reportWrite.cancel(true);
        releaseJob();
        if (worker != null) { worker.shutdownNow(); worker = null; }
        getLogger().info("NATIVE_STRUCTURES_LAB status=stopped nativeTicketsReleased=true");
    }

    private record HeldChunk(Chunk chunk, boolean owned) {}
    private static final class Job {
        final String name;
        final SchematicCodec.Schematic schematic;
        final int x, y, z, expectedWrites;
        final long decodeNanos, started = System.nanoTime();
        final List<SchematicCodec.ChunkPos> footprint;
        final BlockData[] palette;
        final Map<Long, CompletableFuture<Chunk>> pending = new LinkedHashMap<>();
        final Map<Long, HeldChunk> held = new LinkedHashMap<>();
        int paletteCursor, requestCursor, cursor, alreadyLoaded, actualWrites, resetWrites, skippedAir, noOps;
        long paletteNanos, preparationNanos, resetNanos, applyNanos, maxPinNanos;
        Throwable failure;
        Job(String name, SchematicCodec.Schematic schematic, int x, int y, int z, int expectedWrites, long decodeNanos) {
            this.name = name; this.schematic = schematic; this.x = x; this.y = y; this.z = z;
            this.expectedWrites = expectedWrites; this.decodeNanos = decodeNanos;
            footprint = schematic.footprint(x, z); palette = new BlockData[schematic.palette.size()];
        }
    }
}
