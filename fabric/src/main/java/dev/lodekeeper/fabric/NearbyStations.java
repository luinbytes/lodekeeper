package dev.lodekeeper.fabric;

import dev.lodekeeper.core.AcquisitionSource;
import dev.lodekeeper.core.ClaimBox;
import dev.lodekeeper.core.StationId;
import dev.lodekeeper.core.StationRequirement;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NearbyStations {
    private static final long ORDINARY_BUDGET_NANOS = 500_000L;
    private static final long PREFERRED_BUDGET_NANOS = 1_000_000L;
    private static final int MAX_RENDER_DISTANCE_CHUNKS = 32;
    private static final int MAX_STATION_KINDS = 256;
    private static final int MAX_STATION_POSITIONS = 512;
    private static final Map<Block, StationId> KINDS = Map.of(
            Blocks.CRAFTING_TABLE, StationId.parse("minecraft:crafting_table"),
            Blocks.FURNACE, StationId.parse("minecraft:furnace"),
            Blocks.SMOKER, StationId.parse("minecraft:smoker"),
            Blocks.BLAST_FURNACE, StationId.parse("minecraft:blast_furnace"),
            Blocks.STONECUTTER, StationId.parse("minecraft:stonecutter"));
    private static final List<BlockPos> OFFSETS = offsets();
    private final MinecraftClient client;
    private final Map<BlockPos, StationId> observations = new HashMap<>();
    private final Map<Long, Boolean> loadedChunks = new HashMap<>();
    private Map<Block, StationId> stationKinds = KINDS;
    private Map<Block, StationId> buildingStationKinds;
    private List<AcquisitionSource> stationSources = List.of();
    private int stationSourceCursor, stationRequirementCursor;
    private long stationCatalogGeneration = Long.MIN_VALUE;
    private boolean stationCatalogReady;
    private boolean stationKindsComplete;
    private long stationKindsVersion;
    private Object indexedWorld;
    private BlockPos origin;
    private int cursor, completedTicks;
    private WorldProtection.PolicySnapshot preferredPolicy;
    private List<ClaimBox> preferredClaims = List.of();
    private final List<ChunkPos> preferredChunks = new ArrayList<>();
    private int preferredChunkCenterX, preferredChunkCenterZ;
    private int preferredRenderDistance, preferredRing, preferredOffsetX, preferredOffsetZ;
    private int preferredOriginChunkX, preferredOriginChunkZ;
    private long preferredCatalogGeneration = Long.MIN_VALUE;
    private long preferredStationKindsVersion = Long.MIN_VALUE;
    private long preferredRevision = Long.MIN_VALUE;
    private boolean preferredChunksComplete;
    private BlockSearch preferredSearch;

    NearbyStations(MinecraftClient client) { this.client = client; }

    void reset() {
        resetLocalScan();
        stationKinds = KINDS;
        buildingStationKinds = null;
        stationSources = List.of();
        stationSourceCursor = 0;
        stationRequirementCursor = 0;
        stationCatalogGeneration = Long.MIN_VALUE;
        stationCatalogReady = false;
        stationKindsComplete = false;
        stationKindsVersion++;
        indexedWorld = null;
        resetPreferredScan(null, 0, 0, 0, 2);
    }

    boolean advance(WorldProtection.PolicySnapshot policy, GameCatalog catalog) {
        if (client.player == null || client.world == null || catalog == null) {
            if (indexedWorld != null) reset();
            return false;
        }
        if (indexedWorld != client.world) {
            reset();
            indexedWorld = client.world;
        }
        BlockPos feet = client.player.getBlockPos();
        long preferredDeadline = System.nanoTime() + PREFERRED_BUDGET_NANOS;
        advanceStationKinds(catalog, preferredDeadline);
        syncPreferredContext(policy, feet, catalog.generation(), renderDistanceChunks());
        advanceOrdinary(feet);
        advancePreferred(preferredDeadline);
        return cursor == OFFSETS.size();
    }

    boolean ready() { return indexedWorld == client.world && client.player != null && cursor == OFFSETS.size(); }

    Map<BlockPos, StationId> observations() {
        if (client.player == null || client.world == null) return Map.of();
        Map<BlockPos, StationId> valid = new HashMap<>();
        observations.forEach((position, kind) -> {
            if (loaded(position) && kind.equals(stationKinds.get(client.world.getBlockState(position).getBlock())))
                valid.put(position, kind);
        });
        return Map.copyOf(valid);
    }

    Map<BlockPos, StationId> preferredObservations() {
        if (client.player == null || client.world == null || preferredSearch == null
                || preferredRevision != WorldRevision.preferredStationRevision()) return Map.of();
        Map<BlockPos, StationId> valid = new HashMap<>();
        Set<BlockPos> candidates = new java.util.LinkedHashSet<>(preferredSearch.representativeResults());
        candidates.addAll(preferredSearch.results());
        for (BlockPos position : candidates) {
            if (valid.size() == MAX_STATION_POSITIONS) break;
            if (!loaded(position) || !preferredPolicy.preferredStation(position.getX(), position.getY(), position.getZ())) continue;
            StationId kind = stationKinds.get(client.world.getBlockState(position).getBlock());
            if (kind != null) valid.put(position.toImmutable(), kind);
        }
        return Map.copyOf(valid);
    }

    boolean isPreferred(BlockPos position) {
        return position != null && preferredPolicy != null && withinRenderDistance(position)
                && preferredPolicy.preferredStation(position.getX(), position.getY(), position.getZ()) && loaded(position);
    }

    BlockPos closestPreferred(StationId station, Set<BlockPos> unreachable, Set<BlockPos> rejected) {
        if (client.player == null) return null;
        BlockPos player = client.player.getBlockPos();
        BlockPos closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (var observation : preferredObservations().entrySet()) {
            BlockPos position = observation.getKey();
            if (!station.equals(observation.getValue()) || unreachable.contains(position) || rejected.contains(position)) continue;
            double distance = position.getSquaredDistance(player);
            if (distance < closestDistance) { closest = position; closestDistance = distance; }
        }
        return closest;
    }

    private void advanceOrdinary(BlockPos feet) {
        if (origin == null) origin = feet.toImmutable();
        else if (cursor == OFFSETS.size()
                && (feet.getSquaredDistance(origin) > 16 || ++completedTicks >= 100)) {
            resetLocalScan();
            origin = feet.toImmutable();
        }
        long deadline = System.nanoTime() + ORDINARY_BUDGET_NANOS;
        for (int probes = 0; cursor < OFFSETS.size() && probes < 256 && System.nanoTime() < deadline; probes++) {
            BlockPos position = origin.add(OFFSETS.get(cursor++));
            if (client.world.isOutOfHeightLimit(position.getY()) || !loaded(position)) continue;
            StationId kind = stationKinds.get(client.world.getBlockState(position).getBlock());
            if (kind != null) observations.put(position.toImmutable(), kind);
        }
    }

    private void advanceStationKinds(GameCatalog catalog, long deadline) {
        long generation = catalog.generation();
        boolean ready = catalog.ready();
        if (generation != stationCatalogGeneration || ready != stationCatalogReady) {
            stationCatalogGeneration = generation;
            stationCatalogReady = ready;
            stationSources = ready ? catalog.sources : List.of();
            stationSourceCursor = 0;
            stationRequirementCursor = 0;
            buildingStationKinds = ready ? new HashMap<>(KINDS) : null;
            stationKinds = KINDS;
            stationKindsComplete = false;
            stationKindsVersion++;
            resetLocalScan();
        }
        if (!ready || stationKindsComplete) return;
        while (stationSourceCursor < stationSources.size() && System.nanoTime() < deadline) {
            var requirements = stationSources.get(stationSourceCursor).requirements();
            if (stationRequirementCursor == requirements.size()) {
                stationSourceCursor++;
                stationRequirementCursor = 0;
                continue;
            }
            var requirement = requirements.get(stationRequirementCursor++);
            if (!(requirement instanceof StationRequirement station) || buildingStationKinds.size() >= MAX_STATION_KINDS) continue;
            Block block = Registries.BLOCK.get(GameApi.identifier(station.station().toString()));
            if (block != Blocks.AIR) buildingStationKinds.putIfAbsent(block, station.station());
        }
        if (stationSourceCursor == stationSources.size()) {
            stationKinds = Map.copyOf(buildingStationKinds);
            buildingStationKinds = null;
            stationKindsComplete = true;
            stationKindsVersion++;
            resetLocalScan();
        }
    }

    private void syncPreferredContext(WorldProtection.PolicySnapshot policy, BlockPos feet, long catalogGeneration,
                                      int renderDistance) {
        int chunkX = feet.getX() >> 4, chunkZ = feet.getZ() >> 4;
        if (preferredPolicy == null || preferredPolicy.epoch() != policy.epoch()
                || preferredCatalogGeneration != catalogGeneration || preferredStationKindsVersion != stationKindsVersion
                || preferredOriginChunkX != chunkX || preferredOriginChunkZ != chunkZ
                || preferredRenderDistance != renderDistance) {
            resetLocalScan();
            resetPreferredScan(policy, catalogGeneration, chunkX, chunkZ, renderDistance);
        } else if (preferredRevision != WorldRevision.preferredStationRevision()) {
            resetLocalScan();
            resetPreferredScan(policy, catalogGeneration, chunkX, chunkZ, renderDistance);
        }
    }

    private void advancePreferred(long deadline) {
        if (preferredClaims.isEmpty() || System.nanoTime() >= deadline) return;
        while (!preferredChunksComplete && System.nanoTime() < deadline) {
            ChunkPos chunk = nextRenderChunk();
            if (chunk == null) { preferredChunksComplete = true; break; }
            if (intersectsPreferredClaim(chunk)) preferredChunks.add(chunk);
        }
        if (!preferredChunksComplete || System.nanoTime() >= deadline) return;
        if (preferredSearch == null) {
            WorldProtection.PolicySnapshot scanPolicy = preferredPolicy;
            preferredSearch = new BlockSearch(client, stationKinds.keySet(), preferredRenderDistance * 32,
                    Set.of(), preferredChunks, position -> scanPolicy.preferredStation(
                            position.getX(), position.getY(), position.getZ()),
                    chunk -> WorldRevision.watchPreferredStationChunk(chunk.x, chunk.z));
        }
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) preferredSearch.advance(4_096, remaining);
    }

    private void resetPreferredScan(WorldProtection.PolicySnapshot policy, long catalogGeneration,
                                    int chunkX, int chunkZ, int renderDistance) {
        preferredPolicy = policy;
        preferredClaims = policy == null || policy.locked() || policy.scope() == null ? List.of()
                : policy.claims().forScope(policy.scope()).stream().filter(ClaimBox::preferredStations).toList();
        preferredCatalogGeneration = catalogGeneration;
        preferredStationKindsVersion = stationKindsVersion;
        preferredOriginChunkX = chunkX;
        preferredOriginChunkZ = chunkZ;
        preferredRenderDistance = renderDistance;
        preferredChunkCenterX = chunkX;
        preferredChunkCenterZ = chunkZ;
        preferredRing = 0;
        preferredOffsetX = preferredOffsetZ = 0;
        preferredChunks.clear();
        preferredChunksComplete = false;
        preferredSearch = null;
        WorldRevision.beginPreferredStationScan();
        preferredRevision = WorldRevision.preferredStationRevision();
    }

    private ChunkPos nextRenderChunk() {
        if (preferredRing == 0) {
            preferredRing = 1;
            preferredOffsetX = preferredOffsetZ = -1;
            return new ChunkPos(preferredChunkCenterX, preferredChunkCenterZ);
        }
        while (preferredRing <= preferredRenderDistance) {
            if (preferredOffsetX > preferredRing) {
                preferredRing++;
                preferredOffsetX = -preferredRing;
                preferredOffsetZ = -preferredRing;
                continue;
            }
            if (preferredOffsetZ > preferredRing) {
                preferredOffsetX++;
                preferredOffsetZ = -preferredRing;
                continue;
            }
            int x = preferredOffsetX, z = preferredOffsetZ++;
            if (Math.max(Math.abs(x), Math.abs(z)) == preferredRing)
                return new ChunkPos(preferredChunkCenterX + x, preferredChunkCenterZ + z);
        }
        return null;
    }

    private boolean intersectsPreferredClaim(ChunkPos chunk) {
        int minX = chunk.getStartX(), minZ = chunk.getStartZ();
        int maxX = minX + 15, maxZ = minZ + 15;
        for (ClaimBox claim : preferredClaims) {
            if (claim.minX() <= maxX && claim.maxX() >= minX
                    && claim.minZ() <= maxZ && claim.maxZ() >= minZ) return true;
        }
        return false;
    }

    private boolean loaded(BlockPos position) {
        int x = position.getX() >> 4, z = position.getZ() >> 4;
        WorldRevision.watchPreferredStationChunk(x, z);
        long key = ((long) x << 32) | (z & 0xffffffffL);
        return loadedChunks.computeIfAbsent(key,
                ignored -> client.world.getChunk(x, z, ChunkStatus.FULL, false) != null);
    }

    private boolean withinRenderDistance(BlockPos position) {
        int x = (position.getX() >> 4) - preferredOriginChunkX;
        int z = (position.getZ() >> 4) - preferredOriginChunkZ;
        return Math.abs(x) <= preferredRenderDistance && Math.abs(z) <= preferredRenderDistance;
    }

    private int renderDistanceChunks() {
        return Math.max(2, Math.min(MAX_RENDER_DISTANCE_CHUNKS, client.options.getViewDistance().getValue()));
    }

    private void resetLocalScan() {
        observations.clear();
        loadedChunks.clear();
        origin = null;
        cursor = completedTicks = 0;
    }

    private static List<BlockPos> offsets() {
        List<BlockPos> result = new ArrayList<>();
        for (int x = -6; x <= 6; x++) for (int y = -2; y <= 2; y++) for (int z = -6; z <= 6; z++)
            result.add(new BlockPos(x, y, z));
        result.sort(Comparator.comparingInt(position -> position.getX() * position.getX()
                + position.getY() * position.getY() + position.getZ() * position.getZ()));
        return List.copyOf(result);
    }
}
