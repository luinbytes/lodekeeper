package dev.lodekeeper.navigation.kernel.snapshot;

import dev.lodekeeper.navigation.kernel.OwnedKernelRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.Iterator;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OwnedWorldSnapshots {
    private static final int CAPACITY = 64;
    private static final int INTEREST_RADIUS = 3;
    private static final int MAX_SECTIONS = 256;
    private static final long TICK_NANOS = 2_000_000;
    private final OwnedKernelRuntime owner;
    private final Map<Long, Builder> pending = new LinkedHashMap<>();
    private final Map<Long, ChunkSnapshot> ready = new LinkedHashMap<>();
    private OwnedKernelRuntime.Session session;
    private long revision;
    private boolean hasInterestCenter;
    private int interestX;
    private int interestZ;
    private final Object revisionLock = new Object();
    private final java.util.concurrent.ConcurrentMap<Long, Long> revisions = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Builder {
        final int x;
        final int z;
        final int minY;
        final PalettedContainer<BlockState>[] sections;
        final BitSet dirty = new BitSet();
        long revision;
        int next;
        @SuppressWarnings("unchecked")
        Builder(LevelChunk chunk) {
            x = chunk.getPos().x;
            z = chunk.getPos().z;
            minY = chunk.getMinBuildHeight();
            sections = new PalettedContainer[chunk.getSections().length];
            dirty.set(0, sections.length);
        }
    }

    public OwnedWorldSnapshots(OwnedKernelRuntime owner) { this.owner = owner; }

    public void reset(OwnedKernelRuntime.Session next) {
        owner.requireMainThread();
        pending.clear();
        ready.clear();
        synchronized (revisionLock) { revisions.clear(); }
        session = next;
        hasInterestCenter = false;
    }

    public void dirty(ChunkPos pos) {
        owner.requireMainThread();
        long key = ChunkPos.asLong(pos.x, pos.z);
        ready.remove(key);
        pending.remove(key);
        invalidate(key);
        request(pos.x, pos.z);
    }

    public void dirtyBlock(BlockPos pos) {
        owner.requireMainThread();
        if (!owner.isCurrent(session)) { return; }
        refreshInterest();
        int x = pos.getX() >> 4;
        int z = pos.getZ() >> 4;
        long key = ChunkPos.asLong(x, z);
        if (!interested(x, z)) { ready.remove(key); pending.remove(key); invalidate(key); return; }
        Builder builder = pending.get(key);
        ChunkSnapshot old = ready.remove(key);
        if (builder == null) {
            if (pending.size() >= CAPACITY) { invalidate(key); return; }
            var loaded = session.world().getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (!(loaded instanceof LevelChunk chunk) || chunk.getSections().length > MAX_SECTIONS) { invalidate(key); return; }
            builder = new Builder(chunk);
            if (old != null && old.height() == chunk.getSections().length * 16) {
                System.arraycopy(old.sections(), 0, builder.sections, 0, builder.sections.length);
                builder.dirty.clear();
            }
            pending.put(key, builder);
        }
        builder.revision = ++revision;
        synchronized (revisionLock) { revisions.put(key, builder.revision); }
        int index = (pos.getY() - builder.minY) >> 4;
        if (index >= 0 && index < builder.sections.length) {
            builder.dirty.set(index);
            builder.next = Math.min(builder.next, index);
        }
    }

    public void unload(int x, int z) {
        owner.requireMainThread();
        long key = ChunkPos.asLong(x, z);
        ready.remove(key);
        pending.remove(key);
        invalidate(key);
    }

    public boolean request(int x, int z) {
        owner.requireMainThread();
        if (!owner.isCurrent(session)) { return false; }
        refreshInterest();
        if (!interested(x, z)) { return false; }
        long key = ChunkPos.asLong(x, z);
        if (ready.containsKey(key) || pending.containsKey(key)) { return true; }
        if (pending.size() >= CAPACITY) { return false; }
        var loaded = session.world().getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (!(loaded instanceof LevelChunk chunk) || chunk.getSections().length > MAX_SECTIONS) { return false; }
        Builder builder = new Builder(chunk);
        builder.revision = ++revision;
        synchronized (revisionLock) { revisions.put(key, builder.revision); }
        pending.put(key, builder);
        return true;
    }

    private boolean interested(int x, int z) {
        return hasInterestCenter && Math.abs((long) x - interestX) <= INTEREST_RADIUS
                && Math.abs((long) z - interestZ) <= INTEREST_RADIUS;
    }

    private void refreshInterest() {
        BlockPos center = owner.getPrimaryBaritone().getPlayerContext().playerFeet();
        int x = center.getX() >> 4;
        int z = center.getZ() >> 4;
        if (hasInterestCenter && x == interestX && z == interestZ) { return; }
        interestX = x;
        interestZ = z;
        hasInterestCenter = true;
        ready.entrySet().removeIf(entry -> {
            boolean remove = !interested(entry.getValue().x(), entry.getValue().z());
            if (remove) { invalidate(entry.getKey()); }
            return remove;
        });
        pending.entrySet().removeIf(entry -> {
            boolean remove = !interested(entry.getValue().x, entry.getValue().z);
            if (remove) { invalidate(entry.getKey()); }
            return remove;
        });
    }

    public void requestAround(BlockPos center) {
        owner.requireMainThread();
        if (!owner.isCurrent(session)) { return; }
        refreshInterest();
        int x = center.getX() >> 4;
        int z = center.getZ() >> 4;
        for (int radius = 0; radius <= INTEREST_RADIUS && pending.size() < CAPACITY; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == radius) { request(x + dx, z + dz); }
                }
            }
        }
    }

    public void tick() {
        owner.requireMainThread();
        if (!owner.isCurrent(session)) { return; }
        long deadline = System.nanoTime() + TICK_NANOS;
        int copied = 0;
        Iterator<Map.Entry<Long, Builder>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext() && copied < MAX_SECTIONS && System.nanoTime() - deadline < 0) {
            var entry = iterator.next();
            Builder builder = entry.getValue();
            var loaded = session.world().getChunkSource().getChunk(builder.x, builder.z, ChunkStatus.FULL, false);
            if (!(loaded instanceof LevelChunk chunk) || chunk.getSections().length != builder.sections.length) { invalidate(entry.getKey()); iterator.remove(); continue; }
            while (builder.next < builder.sections.length && copied < MAX_SECTIONS && System.nanoTime() - deadline < 0) {
                if (!builder.dirty.get(builder.next)) { builder.next++; continue; }
                var section = chunk.getSections()[builder.next];
                builder.sections[builder.next] = section == null || section.hasOnlyAir() ? null : section.getStates().copy();
                builder.dirty.clear(builder.next++);
                copied++;
            }
            if (builder.next == builder.sections.length) {
                if (ready.size() == CAPACITY) {
                    long evicted = ready.keySet().iterator().next();
                    ready.remove(evicted);
                    if (!pending.containsKey(evicted)) { invalidate(evicted); }
                }
                ready.put(entry.getKey(), new ChunkSnapshot(session, builder.revision, builder.x, builder.z, builder.minY, builder.sections));
                iterator.remove();
            }
        }
    }

    private void invalidate(long key) {
        synchronized (revisionLock) { revisions.remove(key); }
    }

    /** The callback must perform only a finite in-memory publication, with no IO. */
    public boolean publishIfCurrent(ChunkSnapshot snapshot, Runnable publication) {
        synchronized (revisionLock) {
            if (!current(snapshot)) { return false; }
            publication.run();
            return true;
        }
    }

    public boolean current(ChunkSnapshot snapshot) {
        return owner.isCurrent(snapshot.session()) && revisions.getOrDefault(ChunkPos.asLong(snapshot.x(), snapshot.z()), -1L) == snapshot.revision();
    }

    public ChunkSnapshot ready(int x, int z) {
        owner.requireMainThread();
        return ready.get(ChunkPos.asLong(x, z));
    }

    public Map<Long, ChunkSnapshot> view() {
        owner.requireMainThread();
        return Map.copyOf(ready);
    }
}
