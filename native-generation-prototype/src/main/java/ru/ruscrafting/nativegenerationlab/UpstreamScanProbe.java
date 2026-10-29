package ru.ruscrafting.nativegenerationlab;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.api.BuildPasteCompleteEvent;
import com.magmaguy.betterstructures.api.BuildPlaceEvent;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.ValidWorldsConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.*;

/** Loaded only for the opt-in mode after the BetterStructures plugin is present. */
final class UpstreamScanProbe {
    private static final String SCAN_LISTENER = "com.magmaguy.betterstructures.listeners.NewChunkLoadEvent";
    private final Plugin provider;
    private final World world;
    private final java.lang.reflect.Method chunkPreparationMetrics;
    private final int distance, offset, spawnRadius, surfaceSchematics;
    private final List<String> loadedSchematics;
    private final List<SurfaceGridPlanner.Region> regions;
    private final Set<Long> targets = new HashSet<>(), freshTargetEvents = new HashSet<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final Listener listener = new Listener() { };
    private long placeEvents, acceptedPlaceEvents, cancelledPlaceEvents, completeEvents, lastEventNanos;
    private String eventFailure;

    static boolean ready(Plugin provider) {
        return provider.isEnabled() && !BetterStructures.isReloading()
                && HandlerList.getRegisteredListeners(provider).stream()
                .anyMatch(registered -> registered.getListener().getClass().getName().equals(SCAN_LISTENER));
    }

    UpstreamScanProbe(JavaPlugin owner, Plugin provider, World world, int baseX, boolean requireChunkPreparation) {
        if (!Bukkit.isPrimaryThread() || !ready(provider)) throw new IllegalStateException("Upstream listener is not ready");
        this.provider = provider; this.world = world;
        try {
            // Optional public diagnostics of our lab fork; the stable upstream API lacks this class.
            chunkPreparationMetrics = requireChunkPreparation ? Class.forName(
                    "com.magmaguy.betterstructures.lab.LabChunkPreparation", true,
                    provider.getClass().getClassLoader()).getMethod("metrics") : null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The requested chunk-preparation lab diagnostics are unavailable", failure);
        }
        if (!ValidWorldsConfig.isValidWorld(world))
            throw new IllegalStateException("BetterStructures ValidWorlds.yml must enable " + world.getName());
        distance = DefaultConfig.getDistanceSurface(); offset = DefaultConfig.getMaxOffsetSurface();
        spawnRadius = DefaultConfig.getSpawnProtectionRadius();
        loadedSchematics = SchematicContainer.getSchematics().values().stream()
                .map(SchematicContainer::getClipboardFilename).distinct().sorted().toList();
        surfaceSchematics = SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SURFACE).size();
        if (loadedSchematics.size() != 103 || surfaceSchematics == 0)
            throw new IllegalStateException("Probe requires 103 loaded real schematics and a non-empty SURFACE registry; observed "
                    + loadedSchematics.size() + " total, " + surfaceSchematics + " surface");
        regions = SurfaceGridPlanner.regions(world.getSeed(), distance, offset, spawnRadius, baseX);
        for (var region : regions) targets.add(GenerationPlan.key(region.point().chunkX(), region.point().chunkZ()));
        var manager = owner.getServer().getPluginManager();
        manager.registerEvent(ChunkLoadEvent.class, listener, EventPriority.MONITOR, (ignored, event) -> {
            ChunkLoadEvent loaded = (ChunkLoadEvent) event;
            if (loaded.isNewChunk() && loaded.getWorld() == world) {
                long key = GenerationPlan.key(loaded.getChunk().getX(), loaded.getChunk().getZ());
                if (targets.contains(key)) freshTargetEvents.add(key);
            }
        }, owner);
        manager.registerEvent(BuildPlaceEvent.class, listener, EventPriority.MONITOR, (ignored, event) -> {
            BuildPlaceEvent placed = (BuildPlaceEvent) event;
            if (placed.getFitAnything() == null || !inWorld(placed.getFitAnything().getLocation())) return;
            placeEvents++;
            if (placed.isCancelled()) cancelledPlaceEvents++; else acceptedPlaceEvents++;
            record("BuildPlaceEvent", placed.getFitAnything().getLocation(),
                    placed.getFitAnything().getSchematicContainer().getClipboardFilename(), placed.isCancelled());
        }, owner);
        manager.registerEvent(BuildPasteCompleteEvent.class, listener, EventPriority.MONITOR, (ignored, event) -> {
            BuildPasteCompleteEvent completed = (BuildPasteCompleteEvent) event;
            if (!inWorld(completed.getLowestCorner())) return;
            completeEvents++;
            record("BuildPasteCompleteEvent", completed.getLowestCorner(),
                    completed.getFitAnything().getSchematicContainer().getClipboardFilename(), false);
        }, owner);
        lastEventNanos = System.nanoTime();
    }

    List<GenerationPlan> plans() { return regions.stream().map(SurfaceGridPlanner.Region::plan).toList(); }
    void check() {
        if (eventFailure != null) throw new IllegalStateException(eventFailure);
        if (Bukkit.getPluginManager().getPlugin("BetterStructures") != provider || !ready(provider))
            throw new IllegalStateException("BetterStructures was disabled, replaced or reloaded during the probe");
        if (chunkPreparationMetrics != null) PreparationState.settled(preparationSnapshot());
    }
    void verifyCase(GenerationPlan plan) {
        var region = regions.stream().filter(candidate -> candidate.plan() == plan).findFirst().orElseThrow();
        if (!freshTargetEvents.contains(GenerationPlan.key(region.point().chunkX(), region.point().chunkZ())))
            throw new IllegalStateException("Target surface grid chunk did not emit a fresh ChunkLoadEvent: " + plan.name);
    }
    boolean settled() {
        return tickets() == 0 && (chunkPreparationMetrics == null || PreparationState.settled(preparationSnapshot()))
                && System.nanoTime() - lastEventNanos >= 2_000_000_000L;
    }
    void stop() { HandlerList.unregisterAll(listener); }

    Map<String, Object> snapshot() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("providerVersion", provider.getDescription().getVersion()); report.put("listenerReady", ready(provider));
        report.put("worldSeed", Long.toString(world.getSeed())); report.put("distanceSurfaceChunks", distance);
        if (chunkPreparationMetrics != null) report.put("chunkPreparation", preparationSnapshot());
        report.put("maxOffsetSurfaceChunks", offset); report.put("spawnProtectionRadiusBlocks", spawnRadius);
        report.put("loadedRealSchematicCount", loadedSchematics.size()); report.put("loadedSchematics", loadedSchematics);
        report.put("surfaceSchematicRegistryEntries", surfaceSchematics); report.put("harnessFixtureWrites", 0);
        report.put("plannedSurfaceGridChunks", targets.size()); report.put("freshSurfaceGridChunkEvents", freshTargetEvents.size());
        report.put("buildPlaceEvents", placeEvents); report.put("acceptedBuildPlaceEvents", acceptedPlaceEvents);
        report.put("cancelledBuildPlaceEvents", cancelledPlaceEvents); report.put("buildPasteCompleteEvents", completeEvents);
        report.put("remainingBetterStructuresChunkTickets", tickets()); report.put("naturalStructureEvents", List.copyOf(events));
        report.put("surfaceGridRegions", regions.stream().map(region -> Map.of("case", region.plan().name,
                "cellX", region.point().cellX(), "cellZ", region.point().cellZ(),
                "targetChunkX", region.point().chunkX(), "targetChunkZ", region.point().chunkZ())).toList());
        report.put("observation", "Only normal BetterStructures listeners choose and place real content. Grid selection guarantees eligible coordinates, not a successful terrain fit or any minimum structure event count. Events include ancillary natural structures in this lab world.");
        return report;
    }
    private boolean inWorld(Location location) { return location != null && location.getWorld() == world; }
    private Map<String, Object> preparationSnapshot() {
        try {
            Object value = chunkPreparationMetrics.invoke(null);
            if (!(value instanceof Map<?, ?> raw)) throw new IllegalStateException("Invalid chunk-preparation diagnostics");
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalStateException("Invalid diagnostic key");
                result.put(key, entry.getValue());
            }
            return Map.copyOf(result);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read chunk-preparation diagnostics", failure);
        }
    }
    private int tickets() { return world.getPluginChunkTickets().getOrDefault(provider, List.of()).size(); }
    private void record(String type, Location location, String schematic, boolean cancelled) {
        if (events.size() >= 4096) { eventFailure = "Probe event receipt bound exceeded"; return; }
        lastEventNanos = System.nanoTime();
        events.add(Map.of("type", type, "at", Instant.now().toString(), "schematic", schematic,
                "position", List.of(location.getBlockX(), location.getBlockY(), location.getBlockZ()), "cancelled", cancelled));
    }
}

/** Pure completion oracle: tested without loading Bukkit or the optional provider API. */
final class PreparationState {
    static boolean settled(Map<String, Object> state) {
        if (!Boolean.TRUE.equals(state.get("enabled")) || !Boolean.FALSE.equals(state.get("failed"))
                || count(state, "failures") != 0)
            throw new IllegalStateException("Chunk-preparation experiment is disabled or failed: " + state);
        if (!(state.get("requestPending") instanceof Boolean pending))
            throw new IllegalStateException("Missing pending-request diagnostics");
        if (!(state.get("activeJob") instanceof Boolean active))
            throw new IllegalStateException("Missing active-job diagnostics");
        return !active && !pending && count(state, "queued") == 0 && count(state, "held") == 0;
    }
    private static long count(Map<String, Object> state, String key) {
        if (!(state.get(key) instanceof Number n) || n.longValue() < 0 || n.longValue() != n.doubleValue())
            throw new IllegalStateException("Invalid chunk-preparation counter: " + key);
        return n.longValue();
    }
}
