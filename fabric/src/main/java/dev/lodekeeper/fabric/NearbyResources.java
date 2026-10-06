package dev.lodekeeper.fabric;

import dev.lodekeeper.core.GatherSource;
import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.PlanningPreferences;
import dev.lodekeeper.core.TagId;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Small, loaded-world observations used only to order plans, never to prove absence or reachability. */
final class NearbyResources {
    private static final TagId LOGS_TAG = TagId.parse("minecraft:logs");
    private static final int RADIUS = 8;
    private static final int LOG_SEARCH_RADIUS = 32;
    private static final int WIDTH = RADIUS * 2 + 1;
    private static final int MAX_INDEXED_SOURCES = 4096;
    private static final int MAX_INDEXED_BLOCKS = 8192;
    private static final int MAX_SOURCES_PER_BLOCK = 32;
    private static final int MAX_OBSERVED_SOURCES = 256;
    private static final int MAX_DISCOVERED_SOURCE_OBSERVATIONS = 64;
    private static final int MAX_DISCOVERED_LOG_OBSERVATIONS = 64;
    private static final int MAX_PENDING_LOG_OBSERVATIONS = 1024;
    private static final long TICK_BUDGET_NANOS = 1_000_000;
    private static final long LOCAL_SCAN_BUDGET_NANOS = 200_000;
    private static final long LOG_SCAN_PROCESS_RESERVE_NANOS = 250_000;
    private static final int LOG_RESCAN_INTERVAL_TICKS = 200;
    private static final int[] OFFSETS = java.util.stream.IntStream.range(0, WIDTH * WIDTH * WIDTH).boxed()
            .sorted(Comparator.comparingInt(NearbyResources::distance).thenComparingInt(Integer::intValue))
            .mapToInt(Integer::intValue).toArray();

    private final MinecraftClient client;
    private final Map<Block, Set<String>> sourcesByBlock = new HashMap<>();
    private final Map<String, BlockPos> observations = new HashMap<>();
    private final Map<String, DiscoveredObservation> discoveredObservations = new LinkedHashMap<>();
    private final Map<Block, List<GatherSource>> logSourcesByBlock = new HashMap<>();
    private final Map<Block, BlockPos> processedLogRepresentatives = new HashMap<>();
    private final Map<Block, BlockPos> queuedLogRepresentatives = new HashMap<>();
    private final Deque<PendingLogObservation> pendingLogObservations = new ArrayDeque<>();
    private final Map<String, GatherSource> logSourcesById = new HashMap<>();
    private final Map<String, DiscoveredObservation> logObservations = new LinkedHashMap<>();
    private final Map<String, Integer> fallbackEffort = new HashMap<>();
    private BlockPos origin;
    private long generation = -1;
    private long version;
    private int cursor, sourceCursor, blockCursor, indexedSources, indexedBlocks;
    private int tickCounter;
    private GatherSource indexingGather;
    private boolean indexComplete;
    private Set<ItemId> logItems = Set.of();
    private BlockSearch logSearch;
    private long logSearchProgress = Long.MIN_VALUE;
    private final Map<Block, Float> hardnessCache = new HashMap<>();

    private record DiscoveredObservation(BlockPos position, Block block) {}
    private static final class PendingLogObservation {
        private final BlockPos position;
        private final Block block;
        private final java.util.List<GatherSource> sources;
        private int sourceCursor;
        private PendingLogObservation(BlockPos position, Block block, java.util.List<GatherSource> sources) {
            this.position = position;
            this.block = block;
            this.sources = sources;
        }
    }

    NearbyResources(MinecraftClient client) { this.client = client; }

    void reset() {
        origin = null;
        generation = -1;
        cursor = 0;
        sourceCursor = blockCursor = indexedSources = indexedBlocks = 0;
        tickCounter = 0;
        indexingGather = null;
        indexComplete = false;
        logItems = Set.of();
        resetLogSearch();
        hardnessCache.clear();
        sourcesByBlock.clear();
        logSourcesByBlock.clear();
        logSourcesById.clear();
        fallbackEffort.clear();
        observations.clear();
        discoveredObservations.clear();
        logObservations.clear();
        version++;
    }

    void tick(GameCatalog catalog, int configuredProbeBudget) {
        if (client.world == null || client.player == null || catalog == null || !catalog.ready()) return;
        if (generation != catalog.generation()) {
            reset();
            generation = catalog.generation();
            logItems = Set.copyOf(catalog.tags.getOrDefault(LOGS_TAG, java.util.List.of()));
        }
        tickCounter++;
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        advanceIndex(catalog, deadline);
        if (!indexComplete) return;
        BlockPos feet = client.player.getBlockPos();
        if (origin == null || feet.getSquaredDistance(origin) > 16) {
            origin = feet.toImmutable();
            observations.clear();
            cursor = 0;
            resetLogSearch();
            version++;
        }
        if (logSearch != null && logSearch.complete() && tickCounter % LOG_RESCAN_INTERVAL_TICKS == 0) {
            resetLogSearch();
        }
        long localDeadline = Math.min(deadline, System.nanoTime() + LOCAL_SCAN_BUDGET_NANOS);
        int probes = 0, budget = Math.min(128, configuredProbeBudget);
        while (cursor < OFFSETS.length && probes < budget && System.nanoTime() < localDeadline) {
            int offset = OFFSETS[cursor++];
            BlockPos position = origin.add(dx(offset), dy(offset), dz(offset));
            probes++;
            if (!chunkPresent(position)) continue;
            Block block = client.world.getBlockState(position).getBlock();
            Set<String> sources = sourcesByBlock.get(block);
            if (sources == null) continue;
            for (String source : sources) {
                if (System.nanoTime() >= localDeadline) break;
                BlockPos previous = observations.get(source);
                if (previous == null) {
                    if (observations.size() >= MAX_OBSERVED_SOURCES) continue;
                    observations.put(source, position);
                    version++;
                } else if (position.getSquaredDistance(origin) < previous.getSquaredDistance(origin)) {
                    observations.put(source, position);
                }
            }
        }
        advanceLogSearch(configuredProbeBudget, deadline);
    }

    private void advanceIndex(GameCatalog catalog, long deadline) {
        int units = 0;
        while (!indexComplete && units++ < 512 && System.nanoTime() < deadline) {
            if (indexedBlocks >= MAX_INDEXED_BLOCKS) { indexComplete = true; break; }
            if (indexingGather == null) {
                if (sourceCursor >= catalog.sources.size() || indexedSources >= MAX_INDEXED_SOURCES
                        || indexedBlocks >= MAX_INDEXED_BLOCKS) {
                    indexComplete = true;
                    break;
                }
                var source = catalog.sources.get(sourceCursor++);
                if (!(source instanceof GatherSource gather)) continue;
                indexedSources++;
                indexingGather = gather;
                blockCursor = 0;
            }
            if (blockCursor >= indexingGather.blocks().size()) {
                indexingGather = null;
                continue;
            }
            var blockId = indexingGather.blocks().get(blockCursor++);
            indexedBlocks++;
            Block block = Registries.BLOCK.get(GameApi.identifier(blockId.toString()));
            if (block == null || block == Blocks.AIR) continue;
            Set<String> fanOut = sourcesByBlock.computeIfAbsent(block, ignored -> new LinkedHashSet<>());
            if (fanOut.size() < MAX_SOURCES_PER_BLOCK) fanOut.add(indexingGather.sourceId());
            if (logItems.contains(indexingGather.output())) {
                logSourcesById.putIfAbsent(indexingGather.sourceId(), indexingGather);
                List<GatherSource> logFanOut = logSourcesByBlock.computeIfAbsent(block, ignored -> new java.util.ArrayList<>());
                if (logFanOut.size() < MAX_SOURCES_PER_BLOCK
                        && logFanOut.stream().noneMatch(source -> source.sourceId().equals(indexingGather.sourceId()))) {
                    logFanOut.add(indexingGather);
                }
            }
            // Only native vanilla blocks get an unobserved effort estimate. Custom sources stay unknown.
            if (!blockId.namespace().equals("minecraft") || !block.getClass().getName().startsWith("net.minecraft.")) continue;
            Float hardness = hardnessCache.get(block);
            if (hardness == null) {
                try { hardness = block.getDefaultState().getHardness(client.world, client.player.getBlockPos()); }
                catch (RuntimeException ignored) { hardness = Float.NaN; }
                hardnessCache.put(block, hardness);
            }
            if (Float.isFinite(hardness) && hardness >= 0) {
                int effort = 1_000_000 + (int) Math.min(100_000_000,
                        Math.ceil(hardness * 1000.0 / indexingGather.outputCount()));
                fallbackEffort.merge(indexingGather.sourceId(), effort, Math::min);
            }
        }
    }

    boolean ready() {
        return indexComplete && (logSourcesByBlock.isEmpty() || bestLogObservation() != null
                || (logSearch != null && logSearch.complete() && pendingLogObservations.isEmpty()));
    }

    record LogObservation(BlockPos position, GatherSource source) {}

    LogObservation bestLogObservation() {
        if (client.world == null || client.player == null || logObservations.isEmpty()) return null;
        BlockPos playerPosition = client.player.getBlockPos();
        LogObservation best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (var entry : logObservations.entrySet()) {
            GatherSource source = logSourcesById.get(entry.getKey());
            DiscoveredObservation observation = entry.getValue();
            if (source == null || !isLiveLogObservation(entry.getKey(), observation)) continue;
            double distance = observation.position().getSquaredDistance(playerPosition);
            if (distance < bestDistance || (distance == bestDistance
                    && (best == null || source.sourceId().compareTo(best.source().sourceId()) < 0))) {
                best = new LogObservation(observation.position(), source);
                bestDistance = distance;
            }
        }
        return best;
    }

    private void advanceLogSearch(int configuredProbeBudget, long deadline) {
        if (logSourcesByBlock.isEmpty()) return;
        if (System.nanoTime() < deadline - LOG_SCAN_PROCESS_RESERVE_NANOS) {
            if (logSearch == null) {
                logSearch = new BlockSearch(client, logSourcesByBlock.keySet(), LOG_SEARCH_RADIUS, Set.of(), true);
                logSearchProgress = Long.MIN_VALUE;
            }
            long remaining = Math.max(0, deadline - System.nanoTime() - LOG_SCAN_PROCESS_RESERVE_NANOS);
            logSearch.advance(Math.min(1024, configuredProbeBudget), remaining);
            long progress = logSearch.progressToken();
            if (progress != logSearchProgress) {
                logSearchProgress = progress;
                enqueueLogRepresentatives();
            }
        }
        drainLogObservations(deadline);
    }

    private void enqueueLogRepresentatives() {
        for (BlockPos position : logSearch.representativeResults()) {
            if (!chunkPresent(position)) { resetLogSearch(); return; }
            Block block = client.world.getBlockState(position).getBlock();
            List<GatherSource> sources = logSourcesByBlock.get(block);
            if (sources == null) { resetLogSearch(); return; }
            BlockPos previous = processedLogRepresentatives.get(block);
            BlockPos queued = queuedLogRepresentatives.get(block);
            if (position.equals(previous) || position.equals(queued)) continue;
            if (pendingLogObservations.size() >= MAX_PENDING_LOG_OBSERVATIONS) {
                resetLogSearch();
                return;
            }
            BlockPos immutable = position.toImmutable();
            queuedLogRepresentatives.put(block, immutable);
            pendingLogObservations.addLast(new PendingLogObservation(immutable, block, sources));
        }
    }

    private void drainLogObservations(long deadline) {
        while (!pendingLogObservations.isEmpty() && System.nanoTime() < deadline) {
            PendingLogObservation pending = pendingLogObservations.peekFirst();
            BlockPos queued = queuedLogRepresentatives.get(pending.block);
            if (!pending.position.equals(queued)) {
                pendingLogObservations.removeFirst();
                continue;
            }
            if (!chunkPresent(pending.position)
                    || client.world.getBlockState(pending.position).getBlock() != pending.block) {
                resetLogSearch();
                return;
            }
            while (pending.sourceCursor < pending.sources.size() && System.nanoTime() < deadline) {
                GatherSource source = pending.sources.get(pending.sourceCursor++);
                observeDiscoveredSource(source.sourceId(), pending.position, pending.block);
            }
            if (pending.sourceCursor < pending.sources.size()) return;
            pendingLogObservations.removeFirst();
            processedLogRepresentatives.put(pending.block, pending.position);
            queuedLogRepresentatives.remove(pending.block, pending.position);
        }
    }

    private void resetLogSearch() {
        logSearch = null;
        logSearchProgress = Long.MIN_VALUE;
        processedLogRepresentatives.clear();
        queuedLogRepresentatives.clear();
        pendingLogObservations.clear();
    }

    private boolean isLogSourceBlock(String sourceId, Block block) {
        List<GatherSource> sources = logSourcesByBlock.get(block);
        return sources != null && sources.stream().anyMatch(source -> source.sourceId().equals(sourceId));
    }

    private boolean isLiveLogObservation(String sourceId, DiscoveredObservation observation) {
        BlockPos position = observation.position();
        if (!chunkPresent(position) || client.world.getBlockState(position).getBlock() != observation.block()) return false;
        return isLogSourceBlock(sourceId, observation.block());
    }

    PlanningPreferences snapshot() {
        if (client.world == null || origin == null) return PlanningPreferences.NONE;
        Map<String, Integer> ranks = new HashMap<>(fallbackEffort);
        boolean invalidated = false;
        var entries = observations.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            BlockPos position = entry.getValue();
            Set<String> sources = chunkPresent(position)
                    ? sourcesByBlock.get(client.world.getBlockState(position).getBlock()) : null;
            if (sources == null || !sources.contains(entry.getKey())) {
                entries.remove();
                invalidated = true;
                continue;
            }
            ranks.put(entry.getKey(), 1 + (int) position.getSquaredDistance(origin));
        }
        BlockPos playerPosition = client.player == null ? origin : client.player.getBlockPos();
        var discovered = discoveredObservations.entrySet().iterator();
        while (discovered.hasNext()) {
            var entry = discovered.next();
            DiscoveredObservation observation = entry.getValue();
            BlockPos position = observation.position();
            if (!chunkPresent(position) || client.world.getBlockState(position).getBlock() != observation.block()) {
                discovered.remove();
                invalidated = true;
                continue;
            }
            long distance = (long) Math.min(Integer.MAX_VALUE - 1L, position.getSquaredDistance(playerPosition));
            ranks.merge(entry.getKey(), 1 + (int) distance, Math::min);
        }
        var discoveredLogs = logObservations.entrySet().iterator();
        while (discoveredLogs.hasNext()) {
            var entry = discoveredLogs.next();
            DiscoveredObservation observation = entry.getValue();
            if (!isLiveLogObservation(entry.getKey(), observation)) {
                discoveredLogs.remove();
                invalidated = true;
                continue;
            }
            long distance = (long) Math.min(Integer.MAX_VALUE - 1L,
                    observation.position().getSquaredDistance(playerPosition));
            ranks.merge(entry.getKey(), 1 + (int) distance, Math::min);
        }
        if (invalidated) {
            // A mined nearest block must not hide farther live candidates in the same window.
            cursor = 0;
            resetLogSearch();
            version++;
        }
        return new PlanningPreferences(ranks);
    }

    void observeDiscoveredSource(String sourceId, BlockPos position, Block block) {
        if (client.world == null || client.player == null || sourceId == null || position == null || block == null
                || !chunkPresent(position) || client.world.getBlockState(position).getBlock() != block) return;
        BlockPos immutable = position.toImmutable();
        boolean logChanged = rememberLogObservation(sourceId, immutable, block);
        DiscoveredObservation previous = discoveredObservations.get(sourceId);
        if (previous != null && chunkPresent(previous.position())
                && client.world.getBlockState(previous.position()).getBlock() == previous.block()
                && previous.position().getSquaredDistance(client.player.getBlockPos())
                <= immutable.getSquaredDistance(client.player.getBlockPos())) {
            if (logChanged) version++;
            return;
        }
        if (previous == null && discoveredObservations.size() >= MAX_DISCOVERED_SOURCE_OBSERVATIONS) {
            String farthestSource = null;
            long farthestDistance = -1;
            BlockPos playerPosition = client.player.getBlockPos();
            for (var entry : discoveredObservations.entrySet()) {
                long distance = (long) entry.getValue().position().getSquaredDistance(playerPosition);
                if (distance > farthestDistance) {
                    farthestDistance = distance;
                    farthestSource = entry.getKey();
                }
            }
            long newDistance = (long) immutable.getSquaredDistance(playerPosition);
            if (farthestSource == null || newDistance >= farthestDistance) {
                if (logChanged) version++;
                return;
            }
            discoveredObservations.remove(farthestSource);
        }
        discoveredObservations.put(sourceId, new DiscoveredObservation(immutable, block));
        version++;
    }

    private boolean rememberLogObservation(String sourceId, BlockPos position, Block block) {
        if (!isLogSourceBlock(sourceId, block)) return false;
        DiscoveredObservation known = discoveredObservations.get(sourceId);
        if (known != null && chunkPresent(known.position())
                && client.world.getBlockState(known.position()).getBlock() == known.block()
                && known.position().getSquaredDistance(client.player.getBlockPos())
                <= position.getSquaredDistance(client.player.getBlockPos())) {
            position = known.position();
            block = known.block();
        }
        DiscoveredObservation previous = logObservations.get(sourceId);
        if (previous != null && isLiveLogObservation(sourceId, previous)
                && previous.position().getSquaredDistance(client.player.getBlockPos())
                <= position.getSquaredDistance(client.player.getBlockPos())) return false;
        if (previous == null && logObservations.size() >= MAX_DISCOVERED_LOG_OBSERVATIONS) {
            String farthestSource = null;
            double farthestDistance = -1;
            BlockPos playerPosition = client.player.getBlockPos();
            for (var entry : logObservations.entrySet()) {
                double distance = entry.getValue().position().getSquaredDistance(playerPosition);
                if (distance > farthestDistance || (distance == farthestDistance
                        && (farthestSource == null || entry.getKey().compareTo(farthestSource) > 0))) {
                    farthestDistance = distance;
                    farthestSource = entry.getKey();
                }
            }
            double newDistance = position.getSquaredDistance(playerPosition);
            if (farthestSource == null || newDistance > farthestDistance
                    || (newDistance == farthestDistance && sourceId.compareTo(farthestSource) >= 0)) return false;
            logObservations.remove(farthestSource);
        }
        logObservations.put(sourceId, new DiscoveredObservation(position, block));
        return true;
    }

    private boolean chunkPresent(BlockPos position) {
        return client.world.getChunkManager().getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) != null;
    }

    long version() { return version; }
    private static int dx(int offset) { return offset % WIDTH - RADIUS; }
    private static int dy(int offset) { return offset / WIDTH % WIDTH - RADIUS; }
    private static int dz(int offset) { return offset / (WIDTH * WIDTH) - RADIUS; }
    private static int distance(int offset) {
        int x = dx(offset), y = dy(offset), z = dz(offset);
        return x * x + y * y + z * z;
    }
}
