/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Chunk
 *  org.bukkit.Location
 *  org.bukkit.World
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.Listener
 *  org.bukkit.event.world.ChunkLoadEvent
 *  org.bukkit.scheduler.BukkitRunnable
 *  org.bukkit.scheduler.BukkitTask
 */
package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.buildingfitter.FitAirBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitLiquidBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitSurfaceBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitUndergroundShallowBuilding;
import com.magmaguy.betterstructures.buildingfitter.util.FitUndergroundDeepBuilding;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.ValidWorldsConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.listeners.ChunkScanReentrancyGuard;
import com.magmaguy.betterstructures.listeners.DelayedDungeonScanTracker;
import com.magmaguy.betterstructures.magmacore.util.Logger;
import com.magmaguy.betterstructures.modules.NaturalDungeonReservation;
import com.magmaguy.betterstructures.modules.WFCGenerator;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.thirdparty.WorldGuard;
import com.magmaguy.betterstructures.util.PluginChunkLease;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class NewChunkLoadEvent
implements Listener {
    private static final Set<LoadingChunkKey> loadingChunks = new HashSet<LoadingChunkKey>();
    private static final Set<LoadingChunkKey> deferredNewChunks = new LinkedHashSet<LoadingChunkKey>();
    private static final Map<LoadingChunkKey, PluginChunkLease> deferredChunkLeases = new HashMap<>();
    private static final DelayedDungeonScanTracker<LoadingChunkKey> delayedDungeonScans = new DelayedDungeonScanTracker();
    private static final ChunkScanReentrancyGuard chunkScanReentrancyGuard = new ChunkScanReentrancyGuard();
    private static final int MAX_DEFERRED_SCANS_PER_DRAIN = 32;
    private static final long MAX_DEFERRED_SCAN_NANOS = 5000000L;
    private static BukkitTask deferredDrainTask;
    private static int remainingNewInspections;
    private static int remainingDungeonInspections;

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        LoadingChunkKey loadingChunkKey = LoadingChunkKey.from(chunk);
        boolean deferred = deferredNewChunks.contains(loadingChunkKey);
        boolean deferredDungeon = delayedDungeonScans.isDeferred(loadingChunkKey);
        if (BetterStructures.isReloading()) {
            if (event.isNewChunk()) {
                NewChunkLoadEvent.deferChunk(chunk, loadingChunkKey);
            }
            return;
        }
        if (!event.isNewChunk() && !deferred) {
            if (deferredDungeon) {
                delayedDungeonScans.removeDeferred(loadingChunkKey);
                NewChunkLoadEvent.scheduleDungeonScanner(loadingChunkKey);
            }
            return;
        }
        if (chunkScanReentrancyGuard.isActive()) {
            // Nested loads are side effects of our terrain scan, not fresh work.
            if (deferred) NewChunkLoadEvent.scheduleDeferredDrain();
            return;
        }
        NewChunkLoadEvent.deferChunk(chunk, loadingChunkKey);
        NewChunkLoadEvent.scheduleDeferredDrain();
    }

    private static void deferChunk(Chunk chunk, LoadingChunkKey key) {
        // Pregenerators may request unload before the next tick's scan.
        if (!deferredChunkLeases.containsKey(key)) {
            deferredChunkLeases.put(key, PluginChunkLease.acquire(chunk));
        }
        deferredNewChunks.add(key);
    }

    private static void scanNewChunk(Chunk chunk, final LoadingChunkKey loadingChunkKey) {
        if (!ValidWorldsConfig.isValidWorld(chunk.getWorld())) {
            return;
        }
        if (loadingChunks.contains(loadingChunkKey)) {
            return;
        }
        loadingChunks.add(loadingChunkKey);
        new BukkitRunnable(){

            public void run() {
                loadingChunks.remove(loadingChunkKey);
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 20L);
        NewChunkLoadEvent.surfaceScanner(chunk);
        NewChunkLoadEvent.shallowUndergroundScanner(chunk);
        NewChunkLoadEvent.deepUndergroundScanner(chunk);
        NewChunkLoadEvent.skyScanner(chunk);
        NewChunkLoadEvent.liquidSurfaceScanner(chunk);
        NewChunkLoadEvent.scheduleDungeonScanner(loadingChunkKey);
    }

    public static void prepareForContentReload() {
        NewChunkLoadEvent.cancelDeferredDrain();
        delayedDungeonScans.deferAllPending();
        loadingChunks.clear();
    }

    public static void replayDeferredNewChunks() {
        NewChunkLoadEvent.scheduleDeferredDrain();
    }

    private static void scheduleDeferredDrain() {
        NewChunkLoadEvent.scheduleDeferredDrain(true);
    }

    private static void scheduleDeferredDrain(boolean wakeup) {
        if (wakeup) {
            remainingNewInspections = deferredNewChunks.size();
            remainingDungeonInspections = delayedDungeonScans.deferredSize();
        }
        if (BetterStructures.isReloading() || deferredNewChunks.isEmpty() && !delayedDungeonScans.hasDeferred() || deferredDrainTask != null || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) {
            return;
        }
        try {
            deferredDrainTask = Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN, () -> {
                deferredDrainTask = null;
                NewChunkLoadEvent.drainDeferredNewChunks();
            }, 1L);
        } catch (RuntimeException failure) {
            NewChunkLoadEvent.releaseDeferredLeases();
            throw failure;
        }
    }

    private static void drainDeferredNewChunks() {
        if (BetterStructures.isReloading() || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) {
            return;
        }
        long started = System.nanoTime();
        int scanned = 0;
        while (remainingNewInspections > 0 && !deferredNewChunks.isEmpty() && scanned < 32 && System.nanoTime() - started < 5000000L) {
            Iterator<LoadingChunkKey> iterator = deferredNewChunks.iterator();
            LoadingChunkKey key = iterator.next();
            iterator.remove();
            --remainingNewInspections;
            try {
                World world = Bukkit.getWorld((UUID)key.worldId());
                if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
                    deferredNewChunks.add(key);
                    continue;
                }
                ++scanned;
                Chunk chunk = world.getChunkAt(key.x(), key.z());
                if (chunkScanReentrancyGuard.runIfIdle(() -> NewChunkLoadEvent.scanNewChunk(chunk, key))) {
                    delayedDungeonScans.removeDeferred(key);
                    continue;
                }
                deferredNewChunks.add(key);
            }
            catch (Throwable failure) {
                deferredNewChunks.add(key);
                MetadataHandler.PLUGIN.getLogger().warning("Failed deferred chunk scan at " + String.valueOf(key) + ": " + failure.getMessage());
            }
            finally {
                NewChunkLoadEvent.releaseDeferredLease(key);
            }
        }
        while (remainingDungeonInspections > 0 && delayedDungeonScans.hasDeferred() && scanned < 32 && System.nanoTime() - started < 5000000L) {
            LoadingChunkKey key = delayedDungeonScans.pollDeferred();
            --remainingDungeonInspections;
            if (deferredNewChunks.contains(key) || Bukkit.getWorld((UUID)key.worldId()) == null) {
                delayedDungeonScans.defer(key);
                continue;
            }
            ++scanned;
            NewChunkLoadEvent.scheduleDungeonScanner(key);
        }
        if (deferredNewChunks.isEmpty()) {
            remainingNewInspections = 0;
        }
        if (!delayedDungeonScans.hasDeferred()) {
            remainingDungeonInspections = 0;
        }
        if (remainingNewInspections > 0 || remainingDungeonInspections > 0) {
            NewChunkLoadEvent.scheduleDeferredDrain(false);
        }
    }

    private static void cancelDeferredDrain() {
        if (deferredDrainTask == null) {
            return;
        }
        deferredDrainTask.cancel();
        deferredDrainTask = null;
    }

    public static void discardDeferredNewChunks() {
        NewChunkLoadEvent.cancelDeferredDrain();
        NewChunkLoadEvent.releaseDeferredLeases();
        deferredNewChunks.clear();
        delayedDungeonScans.clear();
    }

    public static void shutdown() {
        NewChunkLoadEvent.cancelDeferredDrain();
        NewChunkLoadEvent.releaseDeferredLeases();
        loadingChunks.clear();
        deferredNewChunks.clear();
        delayedDungeonScans.clear();
    }

    private static void releaseDeferredLease(LoadingChunkKey key) {
        PluginChunkLease lease = deferredChunkLeases.remove(key);
        if (lease == null) return;
        try {
            lease.close();
        } catch (RuntimeException failure) {
            MetadataHandler.PLUGIN.getLogger().log(Level.WARNING, "Failed to release deferred chunk ticket at " + key, failure);
        }
    }

    private static void releaseDeferredLeases() {
        for (LoadingChunkKey key : new ArrayList<>(deferredChunkLeases.keySet())) {
            NewChunkLoadEvent.releaseDeferredLease(key);
        }
    }

    private static void scheduleDungeonScanner(LoadingChunkKey loadingChunkKey) {
        if (MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) {
            return;
        }
        if (!delayedDungeonScans.markPending(loadingChunkKey)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN, () -> {
            if (!delayedDungeonScans.takePending(loadingChunkKey)) {
                return;
            }
            if (BetterStructures.isReloading()) {
                delayedDungeonScans.defer(loadingChunkKey);
                return;
            }
            World world = Bukkit.getWorld((UUID)loadingChunkKey.worldId());
            if (world == null) {
                delayedDungeonScans.defer(loadingChunkKey);
                return;
            }
            NewChunkLoadEvent.dungeonScanner(world, loadingChunkKey.x(), loadingChunkKey.z());
        }, 1L);
    }

    private static boolean isValidStructurePosition(Chunk chunk, GeneratorConfigFields.StructureType structureType, int gridDistance, int maxOffset) {
        return NewChunkLoadEvent.isValidStructurePosition(chunk.getWorld(), chunk.getX(), chunk.getZ(), structureType, gridDistance, maxOffset);
    }

    private static boolean isValidStructurePosition(World world, int x, int z, GeneratorConfigFields.StructureType structureType, int gridDistance, int maxOffset) {
        int blockZ;
        int blockX;
        int spawnProtectionRadius = DefaultConfig.getSpawnProtectionRadius();
        if (spawnProtectionRadius > 0 && (long)(blockX = x * 16 + 8) * (long)blockX + (long)(blockZ = z * 16 + 8) * (long)blockZ < (long)spawnProtectionRadius * (long)spawnProtectionRadius) {
            return false;
        }
        long worldSeed = world.getSeed();
        long typeSeed = worldSeed + (long)(structureType.name().hashCode() * 7919);
        long minimumGridX = ((long)x - (long)maxOffset) / (long)gridDistance - 1L;
        long maximumGridX = ((long)x + (long)maxOffset) / (long)gridDistance + 1L;
        long minimumGridZ = ((long)z - (long)maxOffset) / (long)gridDistance - 1L;
        long maximumGridZ = ((long)z + (long)maxOffset) / (long)gridDistance + 1L;
        int offsetBound = (int)(2L * (long)maxOffset + 1L);
        for (long gridX = minimumGridX; gridX <= maximumGridX; ++gridX) {
            for (long gridZ = minimumGridZ; gridZ <= maximumGridZ; ++gridZ) {
                long baseX = gridX * (long)gridDistance;
                long baseZ = gridZ * (long)gridDistance;
                if (gridZ % 2L != 0L) {
                    baseX += (long)(gridDistance / 2);
                }
                Random cellRandom = new Random(typeSeed ^ (baseX << 32 | baseZ & 0xFFFFFFFFL));
                int offsetX = maxOffset > 0 ? cellRandom.nextInt(offsetBound) - maxOffset : 0;
                int offsetZ = maxOffset > 0 ? cellRandom.nextInt(offsetBound) - maxOffset : 0;
                long structureX = baseX + (long)offsetX;
                long structureZ = baseZ + (long)offsetZ;
                if ((long)x != structureX || (long)z != structureZ) continue;
                return true;
            }
        }
        return false;
    }

    private static void surfaceScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SURFACE).isEmpty()) {
            return;
        }
        if (!NewChunkLoadEvent.isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.SURFACE, DefaultConfig.getDistanceSurface(), DefaultConfig.getMaxOffsetSurface())) {
            return;
        }
        new FitSurfaceBuilding(chunk);
    }

    private static void shallowUndergroundScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW).isEmpty()) {
            return;
        }
        if (!NewChunkLoadEvent.isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW, DefaultConfig.getDistanceShallow(), DefaultConfig.getMaxOffsetShallow())) {
            return;
        }
        FitUndergroundShallowBuilding.fit(chunk);
    }

    private static void deepUndergroundScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.UNDERGROUND_DEEP).isEmpty()) {
            return;
        }
        if (!NewChunkLoadEvent.isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.UNDERGROUND_DEEP, DefaultConfig.getDistanceDeep(), DefaultConfig.getMaxOffsetDeep())) {
            return;
        }
        FitUndergroundDeepBuilding.fit(chunk);
    }

    private static void skyScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SKY).isEmpty()) {
            return;
        }
        if (!NewChunkLoadEvent.isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.SKY, DefaultConfig.getDistanceSky(), DefaultConfig.getMaxOffsetSky())) {
            return;
        }
        new FitAirBuilding(chunk);
    }

    private static void liquidSurfaceScanner(Chunk chunk) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.LIQUID_SURFACE).isEmpty()) {
            return;
        }
        if (!NewChunkLoadEvent.isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.LIQUID_SURFACE, DefaultConfig.getDistanceLiquid(), DefaultConfig.getMaxOffsetLiquid())) {
            return;
        }
        new FitLiquidBuilding(chunk);
    }

    private static void dungeonScanner(World world, int chunkX, int chunkZ) {
        if (ModuleGeneratorsConfig.getModuleGenerators().isEmpty()) {
            return;
        }
        if (!NewChunkLoadEvent.isValidStructurePosition(world, chunkX, chunkZ, GeneratorConfigFields.StructureType.DUNGEON, DefaultConfig.getDistanceDungeon(), DefaultConfig.getMaxOffsetDungeon())) {
            return;
        }
        ArrayList<ModuleGeneratorsConfigFields> validatedGenerators = new ArrayList<ModuleGeneratorsConfigFields>();
        for (ModuleGeneratorsConfigFields moduleGeneratorsConfigFields : ModuleGeneratorsConfig.getModuleGenerators().values()) {
            if (!moduleGeneratorsConfigFields.isEnabled() || moduleGeneratorsConfigFields.getValidWorlds() != null && !moduleGeneratorsConfigFields.getValidWorlds().isEmpty() && !moduleGeneratorsConfigFields.getValidWorlds().contains(world.getName()) || moduleGeneratorsConfigFields.getValidWorldEnvironments() != null && !moduleGeneratorsConfigFields.getValidWorldEnvironments().isEmpty() && !moduleGeneratorsConfigFields.getValidWorldEnvironments().contains(world.getEnvironment())) continue;
            validatedGenerators.add(moduleGeneratorsConfigFields);
        }
        if (validatedGenerators.isEmpty()) {
            return;
        }
        ModuleGeneratorsConfigFields moduleGeneratorsConfigFields = (ModuleGeneratorsConfigFields)validatedGenerators.get(ThreadLocalRandom.current().nextInt(0, validatedGenerators.size()));
        int altitude = moduleGeneratorsConfigFields.getCenterModuleAltitude();
        if (altitude < world.getMinHeight() || altitude >= world.getMaxHeight()) {
            Logger.warn("Skipped natural modular generation because centerModuleAltitude " + altitude + " of " + moduleGeneratorsConfigFields.getFilename() + " is outside the height of world " + world.getName() + ".");
            return;
        }
        Location startLocation = new Location(world, (double)((chunkX << 4) + 8), (double)altitude, (double)((chunkZ << 4) + 8));
        Optional<NaturalDungeonReservation> reservation = NaturalDungeonReservation.tryCreate(startLocation.getBlockX(), startLocation.getBlockZ(), moduleGeneratorsConfigFields.getRadius(), moduleGeneratorsConfigFields.getModuleSizeXZ(), DefaultConfig.getSpawnProtectionRadius());
        if (reservation.isEmpty()) {
            Logger.info("Skipped natural modular generation because its footprint overlaps spawn protection.");
            return;
        }
        NaturalDungeonReservation.BlockBounds bounds = reservation.get().blockBounds();
        if (NewChunkLoadEvent.hasWorldGuardOverlap(world, bounds)) {
            Logger.info("Skipped natural modular generation because its footprint overlaps a WorldGuard region.");
            return;
        }
        WFCGenerator.generateNaturally(moduleGeneratorsConfigFields, startLocation, () -> !NewChunkLoadEvent.hasWorldGuardOverlap(world, bounds));
    }

    private static boolean hasWorldGuardOverlap(World world, NaturalDungeonReservation.BlockBounds bounds) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            return false;
        }
        try {
            return WorldGuard.hasRegionOverlap(world, bounds);
        }
        catch (Throwable throwable) {
            Logger.warn("Could not verify WorldGuard regions for natural modular generation; the paste was cancelled: " + throwable.getMessage());
            return true;
        }
    }

    private record LoadingChunkKey(UUID worldId, int x, int z) {
        private static LoadingChunkKey from(Chunk chunk) {
            return new LoadingChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }
}
