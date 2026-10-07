/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lodekeeper.navigation.kernel.cache;

import dev.lodekeeper.navigation.kernel.Baritone;
import dev.lodekeeper.navigation.kernel.api.cache.ICachedWorld;
import dev.lodekeeper.navigation.kernel.api.utils.Helper;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import dev.lodekeeper.navigation.kernel.OwnedKernelRuntime;
import dev.lodekeeper.navigation.kernel.OwnedFinalFlush;
import dev.lodekeeper.navigation.kernel.snapshot.ChunkSnapshot;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author Brady
 * @since 8/4/2018
 */
public final class CachedWorld implements ICachedWorld, Helper {

    /**
     * The maximum number of regions in any direction from (0,0)
     */
    private static final int REGION_MAX = 30_000_000 / 512 + 1;

    /**
     * A map of all of the cached regions.
     */
    private Long2ObjectMap<CachedRegion> cachedRegions = new Long2ObjectOpenHashMap<>();

    /**
     * The directory that the cached region files are saved to
     */
    private final String directory;

    private static final int PACK_CAPACITY = 64;
    private static final int PACK_BATCH = 8;
    private final Map<ChunkPos, ChunkSnapshot> pending = new LinkedHashMap<>();
    private final Set<ChunkPos> pendingCapture = new LinkedHashSet<>();
    private volatile Map<Long, CachedChunk> publishedChunks = Map.of();
    private final OwnedKernelRuntime owner;
    private final OwnedFinalFlush finalFlush;
    private boolean drainScheduled;
    private boolean saveScheduled;
    private boolean saveRequested;
    private volatile boolean closed;
    private long nextSave = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    private volatile BlockPos pruneCenter = BlockPos.ZERO;

    private final DimensionType dimension;

    CachedWorld(Path directory, DimensionType dimension, OwnedKernelRuntime owner) {
        this.owner = owner;
        this.finalFlush = owner.finalFlush(this::save);
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException ignored) {
            }
        }
        this.directory = directory.toString();
        this.dimension = dimension;
        System.out.println("Cached world directory: " + directory);
    }

    @Override
    public final synchronized void queueForPacking(LevelChunk chunk) {
        owner.requireMainThread();
        if (closed || chunk == null) { return; }
        ChunkPos pos = chunk.getPos();
        int limit = Math.max(0, Math.min(PACK_CAPACITY, Baritone.settings().chunkPackerQueueMaxSize.value));
        if (!pendingCapture.contains(pos) && !pending.containsKey(pos) && pendingCapture.size() + pending.size() >= limit) { return; }
        if (!owner.snapshots().request(pos.x, pos.z)) { return; }
        pendingCapture.add(pos);
        captureReady();
    }

    private synchronized void captureReady() {
        var iterator = pendingCapture.iterator();
        while (iterator.hasNext()) {
            ChunkPos pos = iterator.next();
            ChunkSnapshot snapshot = owner.snapshots().ready(pos.x, pos.z);
            if (snapshot != null) {
                pending.put(pos, snapshot);
                iterator.remove();
            } else if (!owner.snapshots().request(pos.x, pos.z)) {
                iterator.remove();
            }
        }
        scheduleDrain();
    }

    private synchronized void scheduleDrain() {
        if (closed || drainScheduled || pending.isEmpty()) { return; }
        drainScheduled = true;
        if (!owner.maintain(this::drain)) { drainScheduled = false; }
    }

    private void drain() {
        try {
            for (int i = 0; i < PACK_BATCH && !Thread.currentThread().isInterrupted(); i++) {
                ChunkSnapshot chunk;
                synchronized (this) {
                    if (closed || pending.isEmpty()) { return; }
                    var iterator = pending.entrySet().iterator();
                    chunk = iterator.next().getValue();
                    iterator.remove();
                }
                CachedChunk packed = ChunkPacker.pack(chunk);
                if (!closed && owner.snapshots().current(chunk) && !Thread.currentThread().isInterrupted()) { updateCachedChunk(packed, chunk); }
            }
        } finally {
            synchronized (this) {
                drainScheduled = false;
                scheduleDrain();
            }
        }
    }

    public synchronized void tick(long now, BlockPos center) {
        owner.requireMainThread();
        if (closed) { return; }
        pruneCenter = center.immutable();
        captureReady();
        if (now - nextSave >= 0) {
            nextSave = now + TimeUnit.MINUTES.toNanos(10);
            saveRequested = true;
        }
        scheduleSave();
    }

    private synchronized boolean scheduleSave() {
        if (closed || saveScheduled || !saveRequested) { return true; }
        saveScheduled = true;
        if (!owner.maintain(() -> {
            synchronized (CachedWorld.this) { saveRequested = false; }
            try { save(); }
            finally {
                synchronized (CachedWorld.this) {
                    saveScheduled = false;
                    scheduleSave();
                }
            }
        })) {
            saveScheduled = false;
            return false;
        }
        return true;
    }

    public synchronized void close() {
        owner.requireMainThread();
        if (closed) { return; }
        closed = true;
        pending.clear();
        pendingCapture.clear();
        saveRequested = false;
        finalFlush.request();
    }

    public void retryFinalFlush() { finalFlush.request(); }
    public boolean finalFlushComplete() { return finalFlush.isComplete(); }
    public void finishFinalFlush() { finalFlush.finishAfterWorkersStop(); }

    @Override
    public final boolean isCached(int blockX, int blockZ) {
        CachedRegion region = getRegion(blockX >> 9, blockZ >> 9);
        if (region == null) {
            return false;
        }
        return region.isCached(blockX & 511, blockZ & 511);
    }

    public final boolean regionLoaded(int blockX, int blockZ) {
        return getRegion(blockX >> 9, blockZ >> 9) != null;
    }

    @Override
    public final ArrayList<BlockPos> getLocationsOf(String block, int maximum, int centerX, int centerZ, int maxRegionDistanceSq) {
        ArrayList<BlockPos> res = new ArrayList<>();
        int centerRegionX = centerX >> 9;
        int centerRegionZ = centerZ >> 9;

        int searchRadius = 0;
        while (searchRadius <= maxRegionDistanceSq) {
            for (int xoff = -searchRadius; xoff <= searchRadius; xoff++) {
                for (int zoff = -searchRadius; zoff <= searchRadius; zoff++) {
                    int distance = xoff * xoff + zoff * zoff;
                    if (distance != searchRadius) {
                        continue;
                    }
                    int regionX = xoff + centerRegionX;
                    int regionZ = zoff + centerRegionZ;
                    CachedRegion region = getOrCreateRegion(regionX, regionZ);
                    if (region != null) {
                        // TODO: 100% verify if this or addAll is faster.
                        res.addAll(region.getLocationsOf(block));
                    }
                }
            }
            if (res.size() >= maximum) {
                return res;
            }
            searchRadius++;
        }
        return res;
    }

    private void updateCachedChunk(CachedChunk chunk, ChunkSnapshot source) {
        CachedRegion region = getOrCreateRegion(chunk.x >> 5, chunk.z >> 5);
        if (region == null) { return; }
        // Disk reads finish before this bounded publication fence is acquired.
        synchronized (this) {
            if (closed) { return; }
            owner.snapshots().publishIfCurrent(source, () -> {
                region.updateCachedChunk(chunk.x & 31, chunk.z & 31, chunk);
                publishChunks(Map.of(net.minecraft.world.level.ChunkPos.asLong(chunk.x, chunk.z), chunk));
            });
        }
    }

    @Override
    public final void save() {
        if (!Baritone.settings().chunkCaching.value) {
            System.out.println("Not saving to disk; chunk caching is disabled.");
            allRegions().forEach(region -> {
                if (region != null) {
                    region.removeExpired();
                }
            }); // even if we aren't saving to disk, still delete expired old chunks from RAM
            prune();
            return;
        }
        long start = System.nanoTime() / 1000000L;
        allRegions().stream().forEach(region -> {
            if (region != null) {
                region.save(this.directory);
            }
        });
        long now = System.nanoTime() / 1000000L;
        System.out.println("World save took " + (now - start) + "ms");
        prune();
    }

    /**
     * Delete regions that are too far from the player
     */
    private synchronized void prune() {
        if (!Baritone.settings().pruneRegionsFromRAM.value) {
            return;
        }
        BlockPos pruneCenter = this.pruneCenter;
        for (CachedRegion region : allRegions()) {
            if (region == null) {
                continue;
            }
            int distX = ((region.getX() << 9) + 256) - pruneCenter.getX();
            int distZ = ((region.getZ() << 9) + 256) - pruneCenter.getZ();
            double dist = Math.sqrt(distX * distX + distZ * distZ);
            if (dist > 1024) {
                logDebug("Deleting cached region from ram");
                cachedRegions.remove(getRegionID(region.getX(), region.getZ()));
            }
        }
    }

    private synchronized List<CachedRegion> allRegions() {
        return new ArrayList<>(this.cachedRegions.values());
    }

    @Override
    public final void reloadAllFromDisk() {
        if (closed) { return; }
        long start = System.nanoTime() / 1000000L;
        allRegions().forEach(region -> {
            if (region != null) {
                region.load(this.directory);
            }
        });
        long now = System.nanoTime() / 1000000L;
        System.out.println("World load took " + (now - start) + "ms");
    }

    @Override
    public final synchronized CachedRegion getRegion(int regionX, int regionZ) {
        return cachedRegions.get(getRegionID(regionX, regionZ));
    }

    /**
     * Returns the region at the specified region coordinates. If a
     * region is not found, then a new one is created.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return The region located at the specified coordinates
     */
    private CachedRegion getOrCreateRegion(int regionX, int regionZ) {
        long id = getRegionID(regionX, regionZ);
        synchronized (this) {
            CachedRegion existing = cachedRegions.get(id);
            if (existing != null || closed) { return existing; }
        }
        CachedRegion loaded = new CachedRegion(regionX, regionZ, dimension);
        loaded.load(directory);
        synchronized (this) {
            if (closed) { return cachedRegions.get(id); }
            CachedRegion existing = cachedRegions.get(id);
            if (existing != null) { return existing; }
            cachedRegions.put(id, loaded);
        }
        publishChunks(loaded.snapshotChunks());
        return loaded;
    }

    private void publishChunks(Map<Long, CachedChunk> updates) {
        Map<Long, CachedChunk> next = new LinkedHashMap<>(publishedChunks);
        next.putAll(updates);
        while (next.size() > 512) { next.remove(next.keySet().iterator().next()); }
        publishedChunks = Map.copyOf(next);
    }

    public Map<Long, CachedChunk> publishedChunks() { return publishedChunks; }

    public void tryLoadFromDisk(int regionX, int regionZ) {
        getOrCreateRegion(regionX, regionZ);
    }

    /**
     * Returns the region ID based on the region coordinates. 0 will be
     * returned if the specified region coordinates are out of bounds.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return The region ID
     */
    private long getRegionID(int regionX, int regionZ) {
        if (!isRegionInWorld(regionX, regionZ)) {
            return 0;
        }

        return (long) regionX & 0xFFFFFFFFL | ((long) regionZ & 0xFFFFFFFFL) << 32;
    }

    /**
     * Returns whether or not the specified region coordinates is within the world bounds.
     *
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     * @return Whether or not the region is in world bounds
     */
    private boolean isRegionInWorld(int regionX, int regionZ) {
        return regionX <= REGION_MAX && regionX >= -REGION_MAX && regionZ <= REGION_MAX && regionZ >= -REGION_MAX;
    }

}
