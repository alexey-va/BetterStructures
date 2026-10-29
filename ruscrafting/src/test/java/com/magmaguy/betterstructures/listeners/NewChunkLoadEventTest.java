package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.util.PluginChunkLease;
import com.magmaguy.betterstructures.worldedit.PasteChunkReadiness;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewChunkLoadEventTest {
    @Test
    void scanRunsOnlyAfterEventReturns() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.load(fixture.chunk, true);

            // Actual scanNewChunk calls actual ValidWorldsConfig.isValidWorld,
            // which reads World.getName. Empty config stops before fitters run.
            assertEquals(0, fixture.synchronousScanReads.get(),
                    "chunk scan must not execute within ChunkLoadEvent");
            assertEquals(0, fixture.scanReads.get());
            assertEquals(0, fixture.synchronousTerrainReads.get());
            assertEquals(1, fixture.pendingAt(1));
            verify(fixture.world, never()).getChunkAt(anyInt(), anyInt());

            fixture.tick();

            assertTrue(fixture.scanReads.get() > 0, "the queued real scan must run next tick");
            assertEquals(0, fixture.synchronousScanReads.get());
            verify(fixture.world).getChunkAt(12, 34);
        }
    }

    @Test
    void repeatedEventsShareOneQueuedScan() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.load(fixture.chunk, true);
            fixture.load(fixture.chunk, true);
            fixture.load(fixture.chunk, false);
            assertEquals(1, fixture.pendingAt(1));
            fixture.tick();
            verify(fixture.world).getChunkAt(12, 34);
            assertEquals(0, fixture.pendingAt(1));
            verify(fixture.chunk).addPluginChunkTicket(fixture.plugin);
            verify(fixture.world).removePluginChunkTicket(12, 34, fixture.plugin);
        }
    }

    @Test
    void pregeneratorUnloadWaitsForDeferredScan() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.unloadRequested.set(true);
            assertFalse(fixture.world.isChunkLoaded(12, 34), "the fixture unloads without a ticket");
            fixture.unloadRequested.set(false);

            fixture.load(fixture.chunk, true);
            fixture.unloadRequested.set(true);
            assertTrue(fixture.world.isChunkLoaded(12, 34), "event must pin the chunk before returning");
            assertEquals(0, fixture.scanReads.get());
            fixture.tick();

            assertTrue(fixture.scanReads.get() > 0, "pregenerated chunk must be scanned");
            assertFalse(fixture.ticketHeld.get());
            assertFalse(fixture.world.isChunkLoaded(12, 34), "unload can proceed after the scan");
            verify(fixture.chunk).addPluginChunkTicket(fixture.plugin);
            verify(fixture.world).removePluginChunkTicket(12, 34, fixture.plugin);
        }
    }

    @Test
    void successfulReloadKeepsQueuedChunkPinnedUntilReplay() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.load(fixture.chunk, true);
            fixture.reloading(true);
            NewChunkLoadEvent.prepareForContentReload();
            fixture.unloadRequested.set(true);
            fixture.tick();
            assertTrue(fixture.ticketHeld.get());
            assertEquals(0, fixture.scanReads.get());

            fixture.reloading(false);
            NewChunkLoadEvent.replayDeferredNewChunks();
            fixture.tick();
            assertTrue(fixture.scanReads.get() > 0);
            assertFalse(fixture.ticketHeld.get());
            verify(fixture.chunk).addPluginChunkTicket(fixture.plugin);
            verify(fixture.world).removePluginChunkTicket(12, 34, fixture.plugin);
        }
    }

    @Test
    void discardAndShutdownReleaseQueuedTicketsExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.load(fixture.chunk, true);
            NewChunkLoadEvent.discardDeferredNewChunks();
            NewChunkLoadEvent.discardDeferredNewChunks();
            fixture.tick();
            assertFalse(fixture.ticketHeld.get());
            assertEquals(0, fixture.scanReads.get());

            fixture.load(fixture.chunk, true);
            NewChunkLoadEvent.shutdown();
            NewChunkLoadEvent.shutdown();
            fixture.tick();
            assertFalse(fixture.ticketHeld.get());
            assertEquals(0, fixture.scanReads.get());
            verify(fixture.world, times(2)).removePluginChunkTicket(12, 34, fixture.plugin);
        }
    }

    @Test
    void scanFailureAndSchedulerRejectionReleaseTheirTickets() throws Exception {
        for (boolean failLoadedCheck : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.load(fixture.chunk, true);
                if (failLoadedCheck) {
                    when(fixture.world.isChunkLoaded(12, 34)).thenThrow(new IllegalStateException("loaded-check fixture failure"));
                } else {
                    when(fixture.world.getName()).thenThrow(new IllegalStateException("scan fixture failure"));
                }
                fixture.tick();
                assertFalse(fixture.ticketHeld.get());
                verify(fixture.world).removePluginChunkTicket(12, 34, fixture.plugin);
            }
        }
        try (Fixture fixture = new Fixture()) {
            when(fixture.scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong()))
                    .thenThrow(new IllegalStateException("scheduler fixture failure"));
            assertThrows(IllegalStateException.class, () -> fixture.load(fixture.chunk, true));
            assertFalse(fixture.ticketHeld.get());
            verify(fixture.world).removePluginChunkTicket(12, 34, fixture.plugin);
        }
    }

    @Test
    void scanCompletionDoesNotReleasePasteTicket() throws Exception {
        try (Fixture fixture = new Fixture(); PasteChunkReadiness paste = new PasteChunkReadiness(fixture.world)) {
            fixture.load(fixture.chunk, true);
            assertTrue(paste.ready(new Location(fixture.world, 12 * 16, 64, 34 * 16)));
            fixture.unloadRequested.set(true);
            fixture.tick();
            assertTrue(fixture.scanReads.get() > 0);
            assertTrue(fixture.ticketHeld.get(), "paste still owns a lease after scan completion");
            verify(fixture.world, never()).removePluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
            paste.close();
            paste.close();
            assertFalse(fixture.ticketHeld.get());
            verify(fixture.chunk).addPluginChunkTicket(fixture.plugin);
            verify(fixture.world).removePluginChunkTicket(12, 34, fixture.plugin);
        }
    }

    @Test
    void pasteCompletionDoesNotReleaseQueuedScanTicket() throws Exception {
        try (Fixture fixture = new Fixture(); PasteChunkReadiness paste = new PasteChunkReadiness(fixture.world)) {
            assertTrue(paste.ready(new Location(fixture.world, 12 * 16, 64, 34 * 16)));
            fixture.load(fixture.chunk, true);
            paste.close();
            paste.close();
            fixture.unloadRequested.set(true);
            assertTrue(fixture.ticketHeld.get());
            fixture.tick();
            assertTrue(fixture.scanReads.get() > 0);
            assertFalse(fixture.ticketHeld.get());
            verify(fixture.chunk).addPluginChunkTicket(fixture.plugin);
            verify(fixture.world).removePluginChunkTicket(12, 34, fixture.plugin);
        }
    }

    @Test
    void leasesRejectUnloadedChunksAndPreservePreexistingTicket() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.unloadRequested.set(true);
            assertThrows(IllegalStateException.class, () -> PluginChunkLease.acquire(fixture.chunk));
            verify(fixture.chunk, never()).addPluginChunkTicket(any(Plugin.class));
            fixture.ticketHeld.set(true); // A ticket owned by another existing BetterStructures path.
            PluginChunkLease lease = PluginChunkLease.acquire(fixture.chunk);
            lease.close();
            lease.close();
            assertTrue(fixture.ticketHeld.get());
            verify(fixture.world, never()).removePluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
        }
    }

    @Test
    void reloadRetainsNewChunkUntilOrdinaryExistingChunkLoad() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reloading(true);
            fixture.load(fixture.chunk, true);
            assertEquals(0, fixture.pendingAt(1));

            fixture.reloading(false);
            when(fixture.world.isChunkLoaded(12, 34)).thenReturn(false);
            NewChunkLoadEvent.replayDeferredNewChunks();
            fixture.tick();
            assertEquals(0, fixture.scanReads.get());
            assertEquals(0, fixture.pendingAt(1), "unloaded coordinates must not spin");
            verify(fixture.world, never()).getChunkAt(anyInt(), anyInt());

            when(fixture.world.isChunkLoaded(12, 34)).thenReturn(true);
            fixture.load(fixture.chunk, false);
            assertEquals(0, fixture.scanReads.get());
            fixture.tick();
            assertTrue(fixture.scanReads.get() > 0);
        }
    }

    @Test
    void nestedScanSideEffectsAreNotEnqueued() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Field field = NewChunkLoadEvent.class.getDeclaredField("chunkScanReentrancyGuard");
            field.setAccessible(true);
            ChunkScanReentrancyGuard guard = (ChunkScanReentrancyGuard) field.get(null);
            assertTrue(guard.runIfIdle(() -> fixture.load(fixture.chunk, true)));
            assertFalse(guard.isActive());
            NewChunkLoadEvent.replayDeferredNewChunks();
            assertEquals(0, fixture.pendingAt(1));
            assertEquals(0, fixture.scanReads.get());
            verify(fixture.chunk, never()).addPluginChunkTicket(any(Plugin.class));
        }
    }

    @Test
    void reloadRecoversPendingDungeonAndFencesItsOldRunnable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Class<?> keyClass = Class.forName(NewChunkLoadEvent.class.getName() + "$LoadingChunkKey");
            var constructor = keyClass.getDeclaredConstructor(UUID.class, int.class, int.class);
            constructor.setAccessible(true);
            Object key = constructor.newInstance(fixture.worldId, 12, 34);
            var schedule = NewChunkLoadEvent.class.getDeclaredMethod("scheduleDungeonScanner", keyClass);
            schedule.setAccessible(true);
            schedule.invoke(null, key);
            assertEquals(1, fixture.pendingAt(1));

            fixture.reloading(true);
            NewChunkLoadEvent.prepareForContentReload();
            fixture.tick();
            assertEquals(0, fixture.worldLookups.get(), "old dungeon task lost its pending key");

            fixture.reloading(false);
            NewChunkLoadEvent.replayDeferredNewChunks();
            fixture.tick();
            assertEquals(1, fixture.pendingAt(1), "replay must reschedule the deferred dungeon");
            fixture.tick();
            assertEquals(2, fixture.worldLookups.get());
            assertEquals(0, fixture.pendingAt(1));
        }
    }

    @Test
    void packagedListenerAndItsPrivateEntrypointsLink() throws Exception {
        Path loaded = Path.of(NewChunkLoadEvent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(Path.of(System.getProperty("candidateJar")).toRealPath(), loaded.toRealPath());
        assertDoesNotThrow(() -> {
            NewChunkLoadEvent.class.getDeclaredMethods();
            NewChunkLoadEvent.class.getDeclaredConstructors();
            for (Class<?> member : NewChunkLoadEvent.class.getDeclaredClasses()) {
                member.getDeclaredMethods();
                member.getDeclaredConstructors();
            }
        });
    }

    private static final class Fixture implements AutoCloseable {
        final UUID worldId = UUID.fromString("0aa62684-8060-4873-aa76-dad5ecb9ba55");
        final AtomicBoolean inCallback = new AtomicBoolean();
        final AtomicInteger scanReads = new AtomicInteger();
        final AtomicInteger synchronousScanReads = new AtomicInteger();
        final AtomicInteger synchronousTerrainReads = new AtomicInteger();
        final AtomicInteger worldLookups = new AtomicInteger();
        final AtomicBoolean unloadRequested = new AtomicBoolean();
        final AtomicBoolean ticketHeld = new AtomicBoolean();
        final BetterStructures plugin = mock(BetterStructures.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final World world = mock(World.class, invocation -> {
            String name = invocation.getMethod().getName();
            if (inCallback.get() && (name.equals("getBlockAt") || name.equals("getHighestBlockYAt") || name.equals("getChunkAt"))) {
                synchronousTerrainReads.incrementAndGet();
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        final Chunk chunk = mock(Chunk.class);
        final List<Scheduled> scheduled = new ArrayList<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        long currentTick;

        Fixture() throws Exception {
            when(plugin.isEnabled()).thenReturn(true);
            Logger logger = Logger.getAnonymousLogger();
            logger.setLevel(Level.OFF);
            when(plugin.getLogger()).thenReturn(logger);
            MetadataHandler.PLUGIN = plugin;
            when(world.getUID()).thenReturn(worldId);
            when(world.getName()).thenAnswer(invocation -> {
                scanReads.incrementAndGet();
                if (inCallback.get()) synchronousScanReads.incrementAndGet();
                return "regression-fixture";
            });
            when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(invocation -> !unloadRequested.get() || ticketHeld.get());
            when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
            when(chunk.getWorld()).thenReturn(world);
            when(chunk.getX()).thenReturn(12);
            when(chunk.getZ()).thenReturn(34);
            when(chunk.isLoaded()).thenAnswer(invocation -> !unloadRequested.get() || ticketHeld.get());
            when(chunk.addPluginChunkTicket(plugin)).thenAnswer(invocation -> !ticketHeld.getAndSet(true));
            when(world.removePluginChunkTicket(12, 34, plugin)).thenAnswer(invocation -> ticketHeld.getAndSet(false));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenAnswer(invocation -> {
                worldLookups.incrementAndGet();
                return world;
            });
            when(scheduler.runTaskLater(any(Plugin.class), any(Runnable.class), anyLong())).thenAnswer(invocation -> {
                BukkitTask task = mock(BukkitTask.class);
                AtomicBoolean cancelled = new AtomicBoolean();
                doAnswer(ignored -> { cancelled.set(true); return null; }).when(task).cancel();
                scheduled.add(new Scheduled(currentTick + invocation.<Long>getArgument(2), invocation.getArgument(1), task, cancelled));
                return task;
            });
            NewChunkLoadEvent.shutdown();
        }

        void load(Chunk target, boolean isNew) {
            inCallback.set(true);
            try {
                new NewChunkLoadEvent().onChunkLoad(new ChunkLoadEvent(target, isNew));
            } finally {
                inCallback.set(false);
            }
        }

        void reloading(boolean value) throws Exception {
            Field field = BetterStructures.class.getDeclaredField("contentReloadInProgress");
            field.setAccessible(true);
            field.setBoolean(plugin, value);
        }

        long pendingAt(long ticksFromNow) {
            return scheduled.stream().filter(task -> !task.cancelled.get() && task.at == currentTick + ticksFromNow).count();
        }

        void tick() {
            currentTick++;
            List<Scheduled> due = scheduled.stream().filter(task -> task.at <= currentTick).toList();
            scheduled.removeAll(due);
            due.stream().filter(task -> !task.cancelled.get()).forEach(task -> task.action.run());
        }

        @Override
        public void close() {
            NewChunkLoadEvent.shutdown();
            MetadataHandler.PLUGIN = null;
            bukkit.close();
        }
    }

    private record Scheduled(long at, Runnable action, BukkitTask task, AtomicBoolean cancelled) {}
}
