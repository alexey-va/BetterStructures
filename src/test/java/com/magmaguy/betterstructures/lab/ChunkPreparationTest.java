package com.magmaguy.betterstructures.lab;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.buildingfitter.FitAnything;
import com.magmaguy.betterstructures.listeners.NewChunkLoadEvent;
import com.magmaguy.betterstructures.util.PluginChunkHolds;
import com.magmaguy.betterstructures.worldedit.Schematic;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import org.bukkit.util.Vector;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual readiness coordinator and paste lifecycle using a controlled Paper future boundary. */
final class ChunkPreparationTest {
    @Test void negativeClipboardCornerAndCrossSearchHaveExactChunkCoverage() {
        var surface = ChunkFootprint.fit(-16, -16, 16, 16, true).chunks();
        assertEquals(Set.of(c(-1, -1), c(-2, -1), c(-1, -2), c(-1, 0), c(0, -1)), surface);
        assertEquals(9, ChunkFootprint.fit(-16, -16, 16, 16, false).chunks().size());
        assertEquals(Set.of(c(-2, 0), c(-1, 0)), ChunkFootprint.rectangle(-17, 0, 2, 1, 0).chunks());
    }

    @Test void pasteHaloIncludesBothFarEdgesAndRejectsUnboundedDimensions() {
        var chunks = ChunkFootprint.rectangle(15, -1, 2, 2, 1).chunks();
        assertEquals(16, chunks.size()); assertTrue(chunks.contains(c(-1, -2))); assertTrue(chunks.contains(c(2, 1)));
        assertThrows(IllegalArgumentException.class, () -> ChunkFootprint.rectangle(0, 0, 1024, 1024, 0));
        assertThrows(ArithmeticException.class, () -> ChunkFootprint.rectangle(Integer.MAX_VALUE, 0, 2, 1, 0));
    }

    @Test void guardRefusesEveryNonLabTarget() {
        assertDoesNotThrow(() -> LabChunkPreparation.validateConfig("127.0.0.1", 54295, LabChunkPreparation.WORLD, 64, 256));
        assertThrows(IllegalArgumentException.class, () -> LabChunkPreparation.validateConfig("0.0.0.0", 54295, LabChunkPreparation.WORLD, 64, 256));
        assertThrows(IllegalArgumentException.class, () -> LabChunkPreparation.validateConfig("127.0.0.1", 25565, LabChunkPreparation.WORLD, 64, 256));
        assertThrows(IllegalArgumentException.class, () -> LabChunkPreparation.validateConfig("127.0.0.1", 54295, "survival", 64, 256));
        assertThrows(IllegalArgumentException.class, () -> LabChunkPreparation.validateConfig("127.0.0.1", 54295, LabChunkPreparation.WORLD, 65, 256));
    }

    @Test void ticketActivationHasItsOwnCeilingAndNeverChangesHeldGeometry() {
        var desired = ChunkFootprint.rectangle(0, 0, 256, 256, 0).chunks();
        assertEquals(256, desired.size()); assertEquals(400, ChunkFootprint.activation(desired).size());
        Set<ChunkFootprint.Coordinate> sparse = new HashSet<>();
        for (int i = 0; i < 42; i++) sparse.add(c(i * 10, 0));
        assertThrows(IllegalArgumentException.class, () -> ChunkFootprint.activation(sparse));
    }

    @Test void waitsForFullFootprintAndMarksCollateralBeforeItsOwnRequest() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<LabChunkPreparation.Lease> entered = new AtomicReference<>();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 32, 1, 0), entered::set);
            f.coordinator.tick();
            assertEquals(1, f.requests.size()); assertNull(entered.get());
            assertTrue(LabChunkPreparation.suppresses(f.chunk(c(1, 0))));
            assertFalse(LabChunkPreparation.suppresses(f.chunk(c(50, 50))));
            for (int i = 0; i < 5; i++) f.coordinator.tick();
            assertEquals(1, f.requests.size()); assertNull(entered.get());
            f.complete(c(0, 0)); f.coordinator.tick();
            assertEquals(2, f.requests.size()); assertNull(entered.get());
            f.complete(c(1, 0)); f.coordinator.tick();
            assertNull(entered.get()); assertEquals(0, f.added.get(), "No plugin ticket before the FULL activation halo");
            f.drainRequests();
            assertNotNull(entered.get()); assertEquals(2, f.added.get());
            assertTrue(entered.get().readyAt(new Location(f.world, 31, 100, 0)));
            assertEquals(1L, LabChunkPreparation.metrics().get("fitReadBoundaries"));
            entered.get().complete(); entered.get().close();
            assertEquals(2, f.removed.get()); assertEquals(0L, LabChunkPreparation.metrics().get("held"));
            assertFalse(LabChunkPreparation.suppresses(f.chunk(c(1, 0))));
        }
    }

    @Test void reloadKeepsOriginalRequestSuppressedAndFencesItsCompletion() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger oldScans = new AtomicInteger(), newScans = new AtomicInteger();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), lease -> oldScans.incrementAndGet());
            f.coordinator.tick();
            CompletableFuture<Chunk> original = f.requests.get(c(0, 0));
            LabChunkPreparation.prepareForReload();
            assertFalse(original.isCancelled()); assertTrue(LabChunkPreparation.suppresses(f.chunk(c(0, 0))));
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(80, 0, 1, 1, 0), lease -> { newScans.incrementAndGet(); lease.complete(); });
            f.coordinator.tick(); assertEquals(1, f.requests.size());
            f.complete(c(0, 0)); assertEquals(0, oldScans.get()); assertEquals(0, f.added.get());
            assertFalse(LabChunkPreparation.suppresses(f.chunk(c(0, 0))));
            f.drainRequests();
            assertEquals(1, newScans.get()); assertEquals(1, f.added.get()); assertEquals(1, f.removed.get());
        }
    }

    @Test void existingSamePluginTicketOutlivesTheLease() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loaded.add(c(0, 0)); Chunk chunk = f.chunk(c(0, 0)); PluginChunkHolds.hold(chunk);
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), LabChunkPreparation.Lease::complete);
            f.drainRequests();
            assertEquals(1, f.added.get()); assertEquals(0, f.removed.get());
            PluginChunkHolds.release(chunk); assertEquals(1, f.removed.get());
        }
    }

    @Test void acceptedOriginSurvivesPregeneratorUnloadWithoutRetainingAQueuedTicket() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger scans = new AtomicInteger();
            LabChunkPreparation.prepareOrigin(f.chunk(c(3, -2)), chunk -> {
                assertEquals(3, chunk.getX()); assertEquals(-2, chunk.getZ()); assertTrue(chunk.isLoaded()); scans.incrementAndGet();
            });
            assertEquals(0, f.added.get()); assertEquals(0, scans.get());
            f.coordinator.tick(); assertEquals(0, scans.get());
            f.complete(c(3, -2)); f.coordinator.tick();
            assertEquals(0, scans.get()); assertEquals(0, f.added.get());
            f.drainRequests();
            assertEquals(1, scans.get()); assertEquals(1, f.added.get()); assertEquals(1, f.removed.get());
            assertEquals(1L, LabChunkPreparation.metrics().get("originReadBoundaries"));
        }
    }

    @Test void retiredRequestCannotRemoveOverlappingNewLeaseSuppression() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<LabChunkPreparation.Lease> entered = new AtomicReference<>();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), lease -> fail()); f.coordinator.tick();
            LabChunkPreparation.prepareForReload();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), entered::set); f.coordinator.tick();
            f.complete(c(0, 0));
            assertTrue(LabChunkPreparation.suppresses(f.chunk(c(0, 0))));
            f.drainRequests();
            assertNotNull(entered.get()); assertEquals(25, f.requests.size());
            entered.get().complete(); assertFalse(LabChunkPreparation.suppresses(f.chunk(c(0, 0))));
        }
    }

    @Test void fitterContinuationKeepsTheSelectedClipboardAndRunsOnlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            Clipboard chosen = proxy(Clipboard.class, (p, method, args) -> method.getName().equals("getDimensions") ? BlockVector3.at(1, 1, 1) : null);
            AtomicInteger scanned = new AtomicInteger();
            TestFit fit = new TestFit(chosen);
            fit.run(new Location(f.world, 8, 100, 8), () -> { assertSame(chosen, fit.getSchematicClipboard()); scanned.incrementAndGet(); });
            assertEquals(0, scanned.get());
            f.drainRequests();
            assertEquals(1, scanned.get()); assertEquals(0, fit.picks);
            assertEquals(f.added.get(), f.removed.get());
        }
    }

    @Test void successfulCloseBeforeCallbackDoesNotReleaseTransactionLease() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<LabChunkPreparation.Lease> entered = new AtomicReference<>();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), entered::set); f.drainRequests();
            List<String> order = new ArrayList<>();
            Schematic.PasteOperation stage = new Operation() {
                @Override public void close() { order.add("close"); }
                @Override public void onComplete() {
                    order.add("onComplete"); assertEquals(0, f.removed.get());
                    assertTrue(entered.get().readyAt(new Location(f.world, 0, 100, 0)));
                }
                @Override public void onAborted() { fail("Successful phase must retain its lease"); }
            };
            invokeSchematic("completeActivePaste", stage);
            assertEquals(List.of("close", "onComplete"), order); assertEquals(0, f.removed.get());
            entered.get().complete(); assertEquals(1, f.removed.get());
        }
    }

    @Test void abortNotifiesOwnerEvenWhenStageCloseThrowsAndNeverCompletes() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<LabChunkPreparation.Lease> entered = new AtomicReference<>();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), entered::set); f.drainRequests();
            List<String> order = new ArrayList<>();
            Schematic.PasteOperation stage = new Operation() {
                @Override public void close() { order.add("close"); throw new IllegalStateException("flush failed"); }
                @Override public void onComplete() { fail("Aborted operation cannot complete"); }
                @Override public void onAborted() { order.add("abort"); entered.get().close(); }
            };
            InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> invokeSchematic("closeAborted", stage));
            assertEquals("flush failed", error.getCause().getMessage());
            assertEquals(List.of("close", "abort"), order); assertEquals(1, f.removed.get());
        }
    }

    @Test void queueOverflowFailsExplicitlyAndReleasesActiveHolds() throws Exception {
        try (Fixture f = new Fixture(1)) {
            AtomicReference<LabChunkPreparation.Lease> entered = new AtomicReference<>();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), entered::set); f.drainRequests();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(16, 0, 1, 1, 0), lease -> fail("queued overflow work must not run"));
            assertThrows(IllegalStateException.class, () -> f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(32, 0, 1, 1, 0), lease -> fail()));
            assertEquals(true, LabChunkPreparation.metrics().get("failed"));
            assertEquals(1, f.removed.get()); assertEquals(0L, LabChunkPreparation.metrics().get("held"));
        }
    }

    @Test void failedExperimentRetainsPasteTicketsUntilItsAbortFlush() throws Exception {
        try (Fixture f = new Fixture(1)) {
            AtomicReference<LabChunkPreparation.Lease> entered = new AtomicReference<>();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), lease -> { entered.set(lease); lease.transferToPaste(); });
            f.drainRequests();
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(16, 0, 1, 1, 0), lease -> fail());
            assertThrows(IllegalStateException.class, () -> f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(32, 0, 1, 1, 0), lease -> fail()));
            assertEquals(0, f.removed.get()); assertThrows(IllegalStateException.class, entered.get()::checkActive);
            invokeSchematic("closeAborted", new Operation() {
                @Override public void close() { assertEquals(0, f.removed.get(), "Finisher flush must run while pinned"); }
                @Override public void onAborted() { entered.get().close(); }
            });
            assertEquals(1, f.removed.get()); assertEquals(0L, LabChunkPreparation.metrics().get("held"));
        }
    }

    @Test void denseGridTicketHaloLoadsAreSuppressedAndHaloNeverReceivesPluginTickets() throws Exception {
        try (Fixture f = new Fixture()) {
            var desired = ChunkFootprint.rectangle(0, 0, 32, 32, 0).chunks();
            var activation = ChunkFootprint.activation(desired);
            AtomicInteger pins = new AtomicInteger();
            f.onPin = center -> {
                assertTrue(desired.contains(center), "Halo-only coordinates must not create their own activation halo");
                assertTrue(f.loaded.containsAll(activation), "Initial FULL preflight must finish before the first ticket");
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        var at = c(center.x() + dx, center.z() + dz);
                        assertTrue(f.loaded.contains(at));
                        // Every coordinate is a possible origin with distance=1. The real listener must
                        // return at suppression, before touching normal world/config/claim registries.
                        new NewChunkLoadEvent().onChunkLoad(new ChunkLoadEvent(f.chunk(at), true));
                        assertTrue(LabChunkPreparation.suppresses(f.chunk(at)));
                    }
                pins.incrementAndGet();
            };
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 32, 32, 0), lease -> {
                assertFalse(LabChunkPreparation.suppresses(f.chunk(c(4, 0))), "Unrelated origins remain eligible");
                lease.complete();
            });
            f.drainRequests();
            assertEquals(4, pins.get()); assertEquals(4, f.added.get()); assertEquals(4, f.removed.get());
            assertEquals(36, f.requests.size()); assertEquals(false, LabChunkPreparation.metrics().get("failed"));
        }
    }

    @Test void previouslyPreparedButUnloadedHaloIsRequestedAgainBeforePinning() throws Exception {
        try (Fixture f = new Fixture()) {
            var activation = ChunkFootprint.activation(ChunkFootprint.rectangle(0, 0, 1, 1, 0).chunks());
            f.loaded.addAll(activation); f.loaded.remove(c(2, 2));
            AtomicReference<LabChunkPreparation.Lease> entered = new AtomicReference<>();
            f.onPin = center -> assertTrue(f.loaded.containsAll(activation), "All 5x5 neighbours must still be FULL at pin time");
            f.coordinator.enqueue(f.world, ChunkFootprint.rectangle(0, 0, 1, 1, 0), entered::set);
            f.coordinator.tick(); assertTrue(f.requests.containsKey(c(2, 2))); assertEquals(0, f.added.get());
            f.loaded.remove(c(-2, -2)); f.complete(c(2, 2));
            assertEquals(0, f.added.get()); assertEquals(true, LabChunkPreparation.metrics().get("activeJob"));
            f.coordinator.tick(); assertTrue(f.requests.containsKey(c(-2, -2))); assertEquals(0, f.added.get());
            f.complete(c(-2, -2)); f.coordinator.tick();
            assertNotNull(entered.get()); assertEquals(1, f.added.get()); entered.get().complete();
        }
    }

    private static ChunkFootprint.Coordinate c(int x, int z) { return new ChunkFootprint.Coordinate(x, z); }
    private static void invokeSchematic(String name, Schematic.PasteOperation operation) throws Exception {
        Method method = Schematic.class.getDeclaredMethod(name, Schematic.PasteOperation.class); method.setAccessible(true); method.invoke(null, operation);
    }
    private static class Operation implements Schematic.PasteOperation {
        public boolean hasNext() { return false; } public void pasteNext() { fail(); } public void onComplete() { }
    }
    private static class TestFit extends FitAnything {
        int picks;
        TestFit(Clipboard chosen) { schematicClipboard = chosen; schematicOffset = new Vector(0, 0, 0); }
        void run(Location at, Runnable selected) { scanPrepared(at, selected); }
        @Override protected void randomizeSchematicContainer(Location at, com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType type) { picks++; fail("Selection cannot be repeated"); }
    }

    private static final class Fixture implements AutoCloseable {
        final Field serverField = field(Bukkit.class, "server");
        final Object oldServer = serverField.get(null);
        final Plugin oldPlugin = MetadataHandler.PLUGIN;
        final UUID id = UUID.randomUUID();
        final Set<ChunkFootprint.Coordinate> loaded = new HashSet<>();
        final Map<ChunkFootprint.Coordinate, CompletableFuture<Chunk>> requests = new LinkedHashMap<>();
        final Map<ChunkFootprint.Coordinate, Chunk> chunks = new HashMap<>();
        final AtomicInteger added = new AtomicInteger(), removed = new AtomicInteger();
        Consumer<ChunkFootprint.Coordinate> onPin = ignored -> { };
        final World world;
        final LabChunkPreparation coordinator;

        Fixture() throws Exception { this(64); }
        Fixture(int maxQueued) throws Exception {
            world = proxy(World.class, (p, method, args) -> switch (method.getName()) {
                case "getUID" -> id; case "getName" -> LabChunkPreparation.WORLD;
                case "isChunkLoaded" -> loaded.contains(c((int) args[0], (int) args[1]));
                case "getChunkAt" -> { var coordinate = c((int) args[0], (int) args[1]); assertTrue(loaded.contains(coordinate), "Synchronous load forbidden"); yield chunk(coordinate); }
                case "getChunkAtAsync" -> {
                    var coordinate = c((int) args[0], (int) args[1]);
                    assertTrue(requests.values().stream().allMatch(CompletableFuture::isDone), "Only one original FULL request may be outstanding");
                    var request = new CompletableFuture<Chunk>(); requests.put(coordinate, request); yield request;
                }
                default -> primitiveDefault(method.getReturnType());
            });
            BukkitTask task = proxy(BukkitTask.class, (p, method, args) -> primitiveDefault(method.getReturnType()));
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p, method, args) -> method.getName().equals("runTaskTimer") ? task : primitiveDefault(method.getReturnType()));
            Logger logger = Logger.getLogger("ChunkPreparationTest"); logger.setUseParentHandlers(false);
            Plugin plugin = proxy(Plugin.class, (p, method, args) -> switch (method.getName()) {
                case "getLogger" -> logger; case "isEnabled" -> true; default -> primitiveDefault(method.getReturnType());
            });
            Server server = proxy(Server.class, (p, method, args) -> switch (method.getName()) {
                case "isPrimaryThread" -> true; case "getScheduler" -> scheduler; case "getWorld" -> world;
                default -> primitiveDefault(method.getReturnType());
            });
            serverField.set(null, server); MetadataHandler.PLUGIN = plugin;
            coordinator = new LabChunkPreparation(plugin, maxQueued, 256);
            field(LabChunkPreparation.class, "active").set(null, coordinator);
        }
        Chunk chunk(ChunkFootprint.Coordinate coordinate) {
            return chunks.computeIfAbsent(coordinate, ignored -> proxy(Chunk.class, (p, method, args) -> switch (method.getName()) {
                case "getWorld" -> world; case "getX" -> coordinate.x(); case "getZ" -> coordinate.z();
                case "isLoaded" -> loaded.contains(coordinate);
                case "addPluginChunkTicket" -> { onPin.accept(coordinate); added.incrementAndGet(); yield true; }
                case "removePluginChunkTicket" -> { removed.incrementAndGet(); yield true; }
                default -> primitiveDefault(method.getReturnType());
            }));
        }
        void complete(ChunkFootprint.Coordinate coordinate) { loaded.add(coordinate); requests.get(coordinate).complete(chunk(coordinate)); }
        void drainRequests() {
            for (int i = 0; i < 512; i++) {
                coordinator.tick();
                for (var entry : new ArrayList<>(requests.entrySet())) if (!entry.getValue().isDone()) complete(entry.getKey());
            }
        }
        @Override public void close() throws Exception {
            LabChunkPreparation.shutdown();
            for (var entry : requests.entrySet()) if (!entry.getValue().isDone()) complete(entry.getKey());
            PluginChunkHolds.shutdown(); MetadataHandler.PLUGIN = oldPlugin; serverField.set(null, oldServer);
        }
    }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L;
        if (type == double.class) return 0D; if (type == float.class) return 0F; return null;
    }
}
