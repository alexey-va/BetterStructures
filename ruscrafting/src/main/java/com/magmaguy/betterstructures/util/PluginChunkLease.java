package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared scan/paste ownership of Bukkit's single ticket per plugin and chunk. */
public final class PluginChunkLease implements AutoCloseable {
    private static final Map<Key, Held> tickets = new HashMap<>();
    private final Key key;
    private final Held held;
    private boolean closed;

    private PluginChunkLease(Key key, Held held) {
        this.key = key;
        this.held = held;
    }

    // Called on the chunk's owning thread, as required by the native ticket API.
    public static PluginChunkLease acquire(Chunk chunk) {
        Plugin plugin = Objects.requireNonNull(MetadataHandler.PLUGIN, "BetterStructures plugin");
        World world = chunk.getWorld();
        UUID worldId = world.getUID();
        if (Bukkit.getWorld(worldId) != world || !chunk.isLoaded()) {
            throw new IllegalStateException("Cannot retain an unloaded chunk or world");
        }
        Key key = new Key(worldId, chunk.getX(), chunk.getZ(), world, plugin);
        Held held = tickets.get(key);
        if (held == null) {
            held = new Held(chunk.addPluginChunkTicket(plugin));
            tickets.put(key, held);
        }
        held.references++;
        return new PluginChunkLease(key, held);
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        if (--this.held.references != 0) return;
        tickets.remove(this.key, this.held);
        if (this.held.ownsTicket && Bukkit.getWorld(this.key.worldId()) == this.key.world()) {
            this.key.world().removePluginChunkTicket(this.key.x(), this.key.z(), this.key.plugin());
        }
    }

    private record Key(UUID worldId, int x, int z, World world, Plugin plugin) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && this.x == key.x && this.z == key.z
                    && this.worldId.equals(key.worldId) && this.world == key.world && this.plugin == key.plugin;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.worldId, this.x, this.z,
                    System.identityHashCode(this.world), System.identityHashCode(this.plugin));
        }
    }

    private static final class Held {
        private final boolean ownsTicket;
        private int references;

        private Held(boolean ownsTicket) {
            this.ownsTicket = ownsTicket;
        }
    }
}
