package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

final class SchematicLabCancellationTest {
    @Test void disabledOwnerCancelsLabWithoutSchedulingOrClosingAnotherWorldsQueuedPaste() throws Exception {
        Field serverField = field(Bukkit.class, "server"); Object originalServer = serverField.get(null); Plugin originalPlugin = MetadataHandler.PLUGIN;
        var scheduled = new AtomicInteger(); var cancelled = new AtomicInteger(); var foreignClosed = new AtomicInteger();
        BukkitTask task = proxy(BukkitTask.class, (p, m, args) -> { if (m.getName().equals("cancel")) cancelled.incrementAndGet(); return null; });
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p, m, args) -> { scheduled.incrementAndGet(); throw new IllegalStateException("Disabled plugin cannot schedule"); });
        Server server = proxy(Server.class, (p, m, args) -> switch (m.getName()) {
            case "isPrimaryThread" -> true; case "getScheduler" -> scheduler; default -> null;
        });
        MetadataHandler.PLUGIN = proxy(Plugin.class, (p, m, args) -> m.getName().equals("isEnabled") ? false : null);
        serverField.set(null, server);
        try {
            World world = proxy(World.class, (p, m, args) -> null);
            Clipboard clipboard = proxy(Clipboard.class, (p, m, args) -> m.getName().equals("getDimensions") ? BlockVector3.at(1, 1, 1) : null);
            Class<?> type = Class.forName(Schematic.class.getName() + "$ClipboardPasteOperation");
            Constructor<?> ctor = type.getDeclaredConstructor(Clipboard.class, Location.class, Function.class, Runnable.class); ctor.setAccessible(true);
            Object paste = ctor.newInstance(clipboard, new Location(world, 0, 100, 0), (Function<Boolean, Material>) ignored -> Material.STONE, (Runnable) () -> fail("Cancelled paste must not complete"));
            field(Schematic.class, "activePasteOperation").set(null, paste); field(Schematic.class, "activePasteTask").set(null, task);
            field(Schematic.class, "isDistributedPasting").set(null, true);
            @SuppressWarnings("unchecked") Queue<Schematic.PasteOperation> queue = (Queue<Schematic.PasteOperation>) field(Schematic.class, "pasteQueue").get(null);
            Schematic.PasteOperation foreign = new Schematic.PasteOperation() {
                public boolean hasNext() { return true; } public void pasteNext() { fail(); } public void onComplete() { fail(); }
                public void close() { foreignClosed.incrementAndGet(); }
            };
            queue.add(foreign);
            assertDoesNotThrow(() -> Schematic.cancelClipboardPastesInWorld(world));
            assertEquals(1, cancelled.get()); assertEquals(0, scheduled.get()); assertEquals(0, foreignClosed.get());
            assertSame(foreign, queue.peek()); assertNull(field(Schematic.class, "activePasteOperation").get(null));
            Schematic.shutdown(); assertEquals(1, foreignClosed.get()); assertTrue(queue.isEmpty());
        } finally {
            Schematic.shutdown(); MetadataHandler.PLUGIN = originalPlugin; serverField.set(null, originalServer);
        }
    }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
}
