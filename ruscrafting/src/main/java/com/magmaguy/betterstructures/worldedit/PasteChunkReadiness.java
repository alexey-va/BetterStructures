/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Chunk
 *  org.bukkit.Location
 *  org.bukkit.World
 */
package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.util.PluginChunkLease;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

public final class PasteChunkReadiness
implements AutoCloseable {
    private static final long LOAD_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private final World world;
    private final Method asyncLoad;
    private CompletableFuture<Void> pending;
    private Chunk held;
    private PluginChunkLease lease;
    private long waitingSince = -1L;
    private boolean closed;

    public PasteChunkReadiness(World world) {
        Method method;
        this.world = world;
        try {
            method = world.getClass().getMethod("getChunkAtAsync", Integer.TYPE, Integer.TYPE, Boolean.TYPE);
        }
        catch (NoSuchMethodException spigot) {
            method = null;
        }
        this.asyncLoad = method;
    }

    public boolean ready(Location location) {
        CompletableFuture<Chunk> request;
        int z;
        if (this.closed) {
            return false;
        }
        int x = location.getBlockX() >> 4;
        if (this.isHeld(x, z = location.getBlockZ() >> 4)) {
            return true;
        }
        if (Bukkit.getWorld((UUID)this.world.getUID()) != this.world) {
            throw new IllegalStateException("Paste world was unloaded while loading a chunk");
        }
        long now = System.nanoTime();
        if (this.waitingSince < 0L) {
            this.waitingSince = now;
        } else if (now - this.waitingSince > LOAD_TIMEOUT_NANOS) {
            throw new IllegalStateException("Timed out waiting for chunk " + x + ", " + z + " in " + this.world.getName() + " to load for pasting");
        }
        if (this.pending != null) {
            if (!this.pending.isDone()) {
                return false;
            }
            CompletableFuture<Void> completed = this.pending;
            this.pending = null;
            completed.join();
            if (this.isHeld(x, z)) {
                return true;
            }
        }
        if (this.world.isChunkLoaded(x, z)) {
            this.hold(this.world.getChunkAt(x, z));
            return this.isHeld(x, z);
        }
        this.release();
        if (this.asyncLoad != null) {
            try {
                CompletableFuture<Chunk> paperRequest;
                request = paperRequest = (CompletableFuture<Chunk>)this.asyncLoad.invoke((Object)this.world, x, z, true);
            }
            catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not request a Paper chunk for pasting", failure);
            }
        } else {
            CompletableFuture<Chunk> spigotRequest;
            request = spigotRequest = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                if (this.closed || Bukkit.getWorld((UUID)this.world.getUID()) != this.world) {
                    spigotRequest.cancel(false);
                    return;
                }
                try {
                    spigotRequest.complete(this.world.getChunkAt(x, z));
                }
                catch (Throwable failure) {
                    spigotRequest.completeExceptionally(failure);
                }
            });
        }
        this.pending = request.thenAccept(chunk -> {
            if (!this.closed && chunk != null && chunk.isLoaded() && Bukkit.getWorld((UUID)this.world.getUID()) == this.world) {
                this.hold((Chunk)chunk);
            }
        });
        return false;
    }

    private boolean isHeld(int x, int z) {
        if (this.held == null || this.held.getX() != x || this.held.getZ() != z) {
            return false;
        }
        this.waitingSince = -1L;
        return true;
    }

    private void hold(Chunk chunk) {
        this.release();
        this.lease = PluginChunkLease.acquire(chunk);
        this.held = chunk;
    }

    private void release() {
        if (this.held == null) {
            return;
        }
        PluginChunkLease lease = this.lease;
        this.held = null;
        this.lease = null;
        if (lease != null) lease.close();
    }

    @Override
    public void close() {
        this.closed = true;
        this.release();
        this.pending = null;
    }
}
