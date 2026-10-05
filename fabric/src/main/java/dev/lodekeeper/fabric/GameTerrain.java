package dev.lodekeeper.fabric;

import dev.lodekeeper.nav.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityPose;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.Arrays;

/** Native voxel-shape adapter for exact grounded player stances and bounded sweeps. */
final class GameTerrain implements Terrain {
    static final int INVALID_FEET_Y16 = Integer.MIN_VALUE;
    private static final double PLAYER_WIDTH_FALLBACK = 0.6;
    private static final double PLAYER_HEIGHT_FALLBACK = 1.8;
    private static final int MAX_SHAPE_BOXES = 256;
    private static final int MAX_WALK_EVENTS = 256;
    private static final int MAX_WALK_PROOFS = 256;
    // The trailing part of the footprint can cover a stair's lower half while the
    // leading part climbs the next half-step. Support depth is separate from the
    // live rise limit: every height change still obeys liveStepLimit16() (at most 9).
    private static final int SUPPORT_DEPTH16 = 16;
    private static final double MAX_STANDING_WIDTH = 1.25;
    private static final double MAX_STANDING_HEIGHT = 3.0;
    private static final double HEIGHT_EPSILON = 1.0e-6;
    private static final double GEOMETRY_EPSILON = 1.0e-7;
    private static final int VOXEL_READ_CACHE_LIMIT = 8_192;
    private static final int VOXEL_READ_CACHE_SIZE = 16_384;
    private static final int CHUNK_READ_CACHE_LIMIT = 256;
    private static final int CHUNK_READ_CACHE_SIZE = 512;

    private static final int MODE_NONE = 0;
    private static final int MODE_BODY = 1;
    private static final int MODE_SUPPORT_CANDIDATES = 2;
    private static final int MODE_SUPPORT_PROFILE = 3;
    private static final int MODE_WALK_EVENTS = 4;

    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final BlockPos.Mutable position = new BlockPos.Mutable();
    private final FootprintCoverage exactCoverage = new FootprintCoverage();
    private final FootprintCoverage allCoverage = new FootprintCoverage();
    private final GroundedStanceBuffer stanceBuffer = new GroundedStanceBuffer();
    private final double[] trajectoryPoint = new double[3];
    private final MotionEventBuffer walkEvents = new MotionEventBuffer(MAX_WALK_EVENTS);
    private final VoxelShapes.BoxConsumer shapeConsumer = this::visitShapeBox;

    private long revision;
    private double standingWidth = PLAYER_WIDTH_FALLBACK;
    private double standingHeight = PLAYER_HEIGHT_FALLBACK;
    private boolean standingDimensionsValid = true;
    private double observedStandingWidth = PLAYER_WIDTH_FALLBACK;
    private double observedStandingHeight = PLAYER_HEIGHT_FALLBACK;
    private long observedPlayerContext;
    private boolean hasObservedPlayerContext;
    private ShapeContext shapeContext;
    private int shapeMode;
    private int shapeBoxCount;
    private boolean shapeIncomplete;
    private int shapeBlockX, shapeBlockY, shapeBlockZ;
    private double bodyMinX, bodyMinY, bodyMinZ, bodyMaxX, bodyMaxY, bodyMaxZ;
    private boolean bodyCollision;
    private boolean shapeBlockHazard;
    private boolean supportHazardSeen;
    private double footprintMinX, footprintMinZ, footprintMaxX, footprintMaxZ;
    private int candidateFeetY16, candidateMinimumFeetY16, candidateMaximumFeetY16;
    private GroundedStanceBuffer candidateBuffer;
    private int supportDepth16;
    private double walkFromX, walkFromZ, walkDeltaX, walkDeltaZ;

    // Query-only cache: every proof still visits the exact native boxes. Entries survive only
    // within one client tick and one world/context revision, and saturation falls back live.
    private final int[] voxelCacheGeneration = new int[VOXEL_READ_CACHE_SIZE];
    private final int[] voxelCacheX = new int[VOXEL_READ_CACHE_SIZE];
    private final int[] voxelCacheY = new int[VOXEL_READ_CACHE_SIZE];
    private final int[] voxelCacheZ = new int[VOXEL_READ_CACHE_SIZE];
    private final BlockState[] voxelCacheState = new BlockState[VOXEL_READ_CACHE_SIZE];
    private final VoxelShape[] voxelCacheShape = new VoxelShape[VOXEL_READ_CACHE_SIZE];
    private final byte[] voxelCacheShapeReady = new byte[VOXEL_READ_CACHE_SIZE];
    private final int[] chunkCacheGeneration = new int[CHUNK_READ_CACHE_SIZE];
    private final int[] chunkCacheX = new int[CHUNK_READ_CACHE_SIZE];
    private final int[] chunkCacheZ = new int[CHUNK_READ_CACHE_SIZE];
    private final byte[] chunkCacheLoaded = new byte[CHUNK_READ_CACHE_SIZE];
    private int readCacheGeneration = 1;
    private int voxelCacheEntries, chunkCacheEntries;
    private boolean readCacheEpochValid;
    private Object readCacheWorld;
    private long readCacheRevision, readCacheContext;

    // Package-visible for bounded native benchmarks; reset between measured searches if needed.
    long voxelQueries, readMisses, shapeMisses, chunkQueries, chunkMisses;

    GameTerrain(MinecraftClient client, LodekeeperConfig config) {
        this.client = client;
        this.config = config;
        refreshStandingDimensions();
    }

    void changed() { revision++; }
    void beginSearch() { WorldRevision.beginSearch(); refreshStandingDimensions(); }
    void refreshStandingDimensions() {
        invalidateReadCache();
        if (client.player == null) return;
        Box box = client.player.getDimensions(EntityPose.STANDING).getBoxAt(0.0, 0.0, 0.0);
        double width = box.maxX - box.minX;
        double height = box.maxY - box.minY;
        if (!Double.isFinite(width) || !Double.isFinite(height)) {
            if (standingDimensionsValid) revision++;
            standingDimensionsValid = false;
            return;
        }
        boolean valid = Double.isFinite(width) && Double.isFinite(height)
                && width >= 0.1 && width <= MAX_STANDING_WIDTH
                && height >= 0.5 && height <= MAX_STANDING_HEIGHT;
        long contextSignature = playerContextSignature();
        if (Math.abs(width - observedStandingWidth) > HEIGHT_EPSILON
                || Math.abs(height - observedStandingHeight) > HEIGHT_EPSILON
                || valid != standingDimensionsValid
                || !hasObservedPlayerContext || contextSignature != observedPlayerContext) {
            revision++;
            shapeContext = ShapeContext.of(client.player);
        }
        observedStandingWidth = width;
        observedStandingHeight = height;
        observedPlayerContext = contextSignature;
        hasObservedPlayerContext = true;
        standingDimensionsValid = valid;
        if (valid) { standingWidth = width; standingHeight = height; }
    }

    private long playerContextSignature() {
        var player = client.player;
        ItemStack main = player.getMainHandStack();
        ItemStack off = player.getOffHandStack();
        long signature = System.identityHashCode(player);
        signature = signature * 31 + player.getPose().ordinal();
        signature = signature * 31 + (player.isSneaking() ? 1 : 0);
        signature = signature * 31 + (player.isOnGround() ? 1 : 0);
        signature = signature * 31 + System.identityHashCode(main.getItem());
        signature = signature * 31 + main.getCount();
        signature = signature * 31 + (main.isDamageable() ? main.getDamage() : 0);
        signature = signature * 31 + System.identityHashCode(off.getItem());
        signature = signature * 31 + off.getCount();
        return signature * 31 + (off.isDamageable() ? off.getDamage() : 0);
    }
    static int quantizedFeetY16(double feetY) {
        if (!Double.isFinite(feetY)) return INVALID_FEET_Y16;
        double scaled = feetY * 16.0;
        double nearest = Math.rint(scaled);
        if (!Double.isFinite(nearest) || nearest <= Integer.MIN_VALUE || nearest > Integer.MAX_VALUE
                || Math.abs(feetY - nearest / 16.0) > 1.0e-4) return INVALID_FEET_Y16;
        return (int) nearest;
    }
    void changedChunk(int chunkX, int chunkZ) { WorldRevision.changedChunk(chunkX, chunkZ); }
    @Override public long revision() { return revision + WorldRevision.value(); }

    private boolean loaded(int x, int y, int z) {
        chunkQueries++;
        if (client.world == null || client.world.isOutOfHeightLimit(y)) return false;
        WorldRevision.watch(x >> 4, z >> 4);
        int chunkX = x >> 4, chunkZ = z >> 4;
        int slot = findChunkCacheSlot(chunkX, chunkZ);
        if (slot >= 0 && chunkCacheGeneration[slot] == readCacheGeneration) {
            return chunkCacheLoaded[slot] != 0;
        }
        chunkMisses++;
        boolean available = client.world.getChunkManager().getChunk(
                chunkX, chunkZ, ChunkStatus.FULL, false) != null;
        if (slot >= 0 && chunkCacheEntries < CHUNK_READ_CACHE_LIMIT) {
            chunkCacheGeneration[slot] = readCacheGeneration;
            chunkCacheX[slot] = chunkX;
            chunkCacheZ[slot] = chunkZ;
            chunkCacheLoaded[slot] = (byte) (available ? 1 : 0);
            chunkCacheEntries++;
        }
        return available;
    }

    private BlockState blockState(int x, int y, int z) {
        voxelQueries++;
        int slot = findVoxelCacheSlot(x, y, z);
        if (slot >= 0 && voxelCacheGeneration[slot] == readCacheGeneration) {
            position.set(x, y, z);
            return voxelCacheState[slot];
        }
        readMisses++;
        BlockState state = client.world.getBlockState(position.set(x, y, z));
        if (slot >= 0 && voxelCacheEntries < VOXEL_READ_CACHE_LIMIT) {
            voxelCacheGeneration[slot] = readCacheGeneration;
            voxelCacheX[slot] = x;
            voxelCacheY[slot] = y;
            voxelCacheZ[slot] = z;
            voxelCacheState[slot] = state;
            voxelCacheShape[slot] = null;
            voxelCacheShapeReady[slot] = 0;
            voxelCacheEntries++;
        }
        return state;
    }

    private int findVoxelCacheSlot(int x, int y, int z) {
        int slot = voxelHash(x, y, z) & (VOXEL_READ_CACHE_SIZE - 1);
        int start = slot;
        while (voxelCacheGeneration[slot] == readCacheGeneration) {
            if (voxelCacheX[slot] == x && voxelCacheY[slot] == y && voxelCacheZ[slot] == z) return slot;
            slot = (slot + 1) & (VOXEL_READ_CACHE_SIZE - 1);
            if (slot == start) return -1;
        }
        return slot;
    }

    private int findChunkCacheSlot(int x, int z) {
        int slot = voxelHash(x, 0, z) & (CHUNK_READ_CACHE_SIZE - 1);
        int start = slot;
        while (chunkCacheGeneration[slot] == readCacheGeneration) {
            if (chunkCacheX[slot] == x && chunkCacheZ[slot] == z) return slot;
            slot = (slot + 1) & (CHUNK_READ_CACHE_SIZE - 1);
            if (slot == start) return -1;
        }
        return slot;
    }

    private static int voxelHash(int x, int y, int z) {
        int hash = x * 0x9e3779b9;
        hash = Integer.rotateLeft(hash ^ y * 0x85ebca6b, 13);
        hash = Integer.rotateLeft(hash ^ z * 0xc2b2ae35, 15);
        hash ^= hash >>> 16;
        return hash;
    }

    private void invalidateReadCache() {
        if (readCacheGeneration == Integer.MAX_VALUE) {
            Arrays.fill(voxelCacheGeneration, 0);
            Arrays.fill(chunkCacheGeneration, 0);
            readCacheGeneration = 1;
        } else {
            readCacheGeneration++;
        }
        voxelCacheEntries = 0;
        chunkCacheEntries = 0;
        readCacheEpochValid = false;
    }

    private void syncReadCacheEpoch() {
        long context = client.player == null ? 0L : playerContextSignature();
        if (client.player != null && (!hasObservedPlayerContext || context != observedPlayerContext)) {
            refreshStandingDimensions();
            context = observedPlayerContext;
        }
        Object world = client.world;
        long currentRevision = revision();
        if (!readCacheEpochValid || readCacheWorld != world
                || readCacheRevision != currentRevision || readCacheContext != context) {
            invalidateReadCache();
            readCacheWorld = world;
            readCacheRevision = currentRevision;
            readCacheContext = context;
            readCacheEpochValid = true;
        }
    }

    private boolean hazardous(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA) || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.WITHER_ROSE);
    }

    @Override public void probeStance(int x, int y, int z, StanceProbe out) {
        syncReadCacheEpoch();
        if (y < -2_048 || y > 2_047) { out.clear(); return; }
        probeAt(x + 0.5, Math.multiplyExact(y, 16), z + 0.5, true, out);
    }

    @Override public boolean probeStance16(int x, int feetY16, int z, StanceProbe out) {
        syncReadCacheEpoch();
        probeAt(x + 0.5, feetY16, z + 0.5, true, out);
        return out.loaded;
    }

    @Override public boolean probeCurrentStance(double feetX, int feetY16, double feetZ, StanceProbe out) {
        syncReadCacheEpoch();
        if (!Double.isFinite(feetX) || !Double.isFinite(feetZ)) { out.clear(); return false; }
        probeAt(feetX, feetY16, feetZ, false, out);
        return out.loaded;
    }

    private void probeAt(double feetX, int feetY16, double feetZ, boolean centered, StanceProbe out) {
        out.clear();
        if (client.player == null || client.world == null || !standingDimensionsValid
                || !Double.isFinite(feetX) || !Double.isFinite(feetZ)) return;
        if (Math.abs(feetX) > 33_554_432.0 || Math.abs(feetZ) > 33_554_432.0) return;
        double feetY = feetY16 / 16.0;
        setFootprint(feetX, feetZ);
        setBodyBounds(feetX, feetY, feetZ);
        out.hazard = false;
        out.bodyClear = true;

        int tightMinX = blockMin(bodyMinX), tightMaxX = blockMax(bodyMaxX);
        int tightMinY = blockMin(bodyMinY), tightMaxY = blockMax(bodyMaxY);
        int tightMinZ = blockMin(bodyMinZ), tightMaxZ = blockMax(bodyMaxZ);
        int minX = tightMinX - 1, maxX = tightMaxX + 1;
        int minZ = tightMinZ - 1, maxZ = tightMaxZ + 1;
        int minY = tightMinY - 1, maxY = tightMaxY + 1;
        shapeBoxCount = 0;
        shapeIncomplete = false;
        shapeMode = MODE_BODY;
        for (int bx = minX; bx <= maxX; bx++) for (int by = minY; by <= maxY; by++) for (int bz = minZ; bz <= maxZ; bz++) {
            if (!loaded(bx, by, bz)) { out.clear(); shapeMode = MODE_NONE; return; }
            BlockState state = blockState(bx, by, bz);
            if (unsupportedContextShape(state)) { out.clear(); shapeMode = MODE_NONE; return; }
            boolean cellTouchesBody = bx >= tightMinX && bx <= tightMaxX
                    && by >= tightMinY && by <= tightMaxY && bz >= tightMinZ && bz <= tightMaxZ;
            if (cellTouchesBody) {
                out.hazard |= hazardous(state);
                out.water |= state.getFluidState().isIn(FluidTags.WATER);
                out.climbable |= state.isIn(BlockTags.CLIMBABLE);
            }
            shapeBlockX = bx; shapeBlockY = by; shapeBlockZ = bz;
            bodyCollision = false;
            VoxelShape shape = collisionShape(state);
            shape.forEachBox(shapeConsumer);
            if (shapeIncomplete) { out.clear(); shapeMode = MODE_NONE; return; }
            if (!bodyCollision) continue;
            out.bodyClear = false;
            addBreakTarget(out, state, bx, by, bz);
        }
        shapeMode = MODE_NONE;

        supportDepth16 = SUPPORT_DEPTH16;
        if (!supportProfile(feetX, feetZ, feetY16, exactCoverage, allCoverage, out)) {
            out.clear();
            return;
        }
        out.surfaceSupport = !out.hazard && exactCoverage.coversFraction(centered ? 0.5 : 0.0)
                && (centered || exactCoverage.coveredArea() > GEOMETRY_EPSILON)
                && allCoverage.coversAll();
        if (Math.floorMod(feetY16, 16) == 0) {
            int floorY = Math.floorDiv(feetY16, 16) - 1;
            int floorX = (int) Math.floor(feetX), floorZ = (int) Math.floor(feetZ);
            if (!loaded(floorX, floorY, floorZ)) { out.clear(); return; }
            BlockState floor = blockState(floorX, floorY, floorZ);
            out.fullSupport = !out.hazard && exactCoverage.coversAll() && allCoverage.coversAll()
                    && Block.isShapeFullCube(collisionShape(floor));
        }
        if (out.breakCount > 0) verifyBreakNeighborhood(out);
        out.loaded = true;
    }

    @Override public boolean collectGroundedStances(int x, int referenceFeetY16, int z,
                                                    GroundedStanceBuffer out) {
        syncReadCacheEpoch();
        out.clear();
        return collectSupportHeights(x + 0.5, z + 0.5, referenceFeetY16, 16, out);
    }

    private boolean collectSupportHeights(double feetX, double feetZ, int referenceFeetY16,
                                          int range16, GroundedStanceBuffer out) {
        out.clear();
        if (client.world == null || client.player == null || !standingDimensionsValid
                || range16 < 0 || !Double.isFinite(feetX) || !Double.isFinite(feetZ)) return false;
        setFootprint(feetX, feetZ);
        candidateBuffer = out;
        long minimum = (long) referenceFeetY16 - range16;
        long maximum = (long) referenceFeetY16 + range16;
        if (minimum < Integer.MIN_VALUE + 32L || maximum > Integer.MAX_VALUE - 32L) return false;
        candidateMinimumFeetY16 = (int) minimum;
        candidateMaximumFeetY16 = (int) maximum;
        long lowBlock = Math.floorDiv(minimum, 16L) - 2L;
        long highBlock = Math.floorDiv(maximum, 16L) + 1L;
        if (lowBlock < Integer.MIN_VALUE || highBlock > Integer.MAX_VALUE) return false;
        int minBlockY = (int) lowBlock, maxBlockY = (int) highBlock;
        int minX = blockMin(footprintMinX) - 1, maxX = blockMax(footprintMaxX) + 1;
        int minZ = blockMin(footprintMinZ) - 1, maxZ = blockMax(footprintMaxZ) + 1;
        shapeBoxCount = 0;
        shapeIncomplete = false;
        shapeMode = MODE_SUPPORT_CANDIDATES;
        for (int bx = minX; bx <= maxX; bx++) for (int by = minBlockY; by <= maxBlockY; by++) for (int bz = minZ; bz <= maxZ; bz++) {
            if (!loaded(bx, by, bz)) { shapeMode = MODE_NONE; return false; }
            BlockState state = blockState(bx, by, bz);
            if (unsupportedContextShape(state)) { shapeMode = MODE_NONE; return false; }
            shapeBlockX = bx; shapeBlockY = by; shapeBlockZ = bz;
            collisionShape(state).forEachBox(shapeConsumer);
            if (shapeIncomplete || !out.isComplete()) { shapeMode = MODE_NONE; return false; }
        }
        shapeMode = MODE_NONE;
        return out.isComplete();
    }

    private boolean supportProfile(double feetX, double feetZ, int feetY16,
                                   FootprintCoverage exact, FootprintCoverage all, StanceProbe out) {
        exact.clear(footprintMinX, footprintMinZ, footprintMaxX, footprintMaxZ);
        all.clear(footprintMinX, footprintMinZ, footprintMaxX, footprintMaxZ);
        long lowBlock = Math.floorDiv((long) feetY16 - SUPPORT_DEPTH16, 16L) - 2L;
        long highBlock = Math.floorDiv((long) feetY16, 16L) + 1L;
        if (lowBlock < Integer.MIN_VALUE || highBlock > Integer.MAX_VALUE) return false;
        int minBlockY = (int) lowBlock, maxBlockY = (int) highBlock;
        int minX = blockMin(footprintMinX) - 1, maxX = blockMax(footprintMaxX) + 1;
        int minZ = blockMin(footprintMinZ) - 1, maxZ = blockMax(footprintMaxZ) + 1;
        shapeBoxCount = 0;
        shapeIncomplete = false;
        supportHazardSeen = false;
        shapeMode = MODE_SUPPORT_PROFILE;
        candidateFeetY16 = feetY16;
        supportDepth16 = SUPPORT_DEPTH16;
        for (int bx = minX; bx <= maxX; bx++) for (int by = minBlockY; by <= maxBlockY; by++) for (int bz = minZ; bz <= maxZ; bz++) {
            if (!loaded(bx, by, bz)) {
                shapeMode = MODE_NONE;
                out.loaded = false;
                return false;
            }
            BlockState state = blockState(bx, by, bz);
            if (unsupportedContextShape(state)) {
                shapeMode = MODE_NONE;
                out.loaded = false;
                return false;
            }
            shapeBlockHazard = hazardous(state);
            shapeBlockX = bx; shapeBlockY = by; shapeBlockZ = bz;
            collisionShape(state).forEachBox(shapeConsumer);
            if (shapeIncomplete) {
                shapeMode = MODE_NONE;
                out.loaded = false;
                return false;
            }
        }
        shapeMode = MODE_NONE;
        if (supportHazardSeen) out.hazard = true;
        return !shapeIncomplete && exact.isComplete() && all.isComplete();
    }

    private boolean highestSupportedFeetY(double feetX, double feetZ, int referenceFeetY16,
                                          int maxStep16, StanceProbe out, int[] result) {
        if (maxStep16 < 0 || !collectSupportHeights(feetX, feetZ, referenceFeetY16, maxStep16, stanceBuffer)) return false;
        setFootprint(feetX, feetZ);
        for (int i = stanceBuffer.size() - 1; i >= 0; i--) {
            int candidate = stanceBuffer.get(i);
            exactCoverage.clear(footprintMinX, footprintMinZ, footprintMaxX, footprintMaxZ);
            allCoverage.clear(footprintMinX, footprintMinZ, footprintMaxX, footprintMaxZ);
            // Transit permits a small first contact on the upper face while lower terrain remains continuous.
            StanceProbe profile = sweepProfileProbe;
            profile.clear(); profile.loaded = true; profile.hazard = false;
            if (!supportProfile(feetX, feetZ, candidate, exactCoverage, allCoverage, profile)) {
                if (!profile.loaded) return false;
                continue;
            }
            if (profile.hazard || exactCoverage.coveredArea() <= GEOMETRY_EPSILON || !allCoverage.coversAll()) continue;
            result[0] = candidate;
            return true;
        }
        return false;
    }

    private final StanceProbe sweepProfileProbe = new StanceProbe();

    private void addBreakTarget(StanceProbe out, BlockState state, int x, int y, int z) {
        if (!config.allowBreaking || !canMine(state, x, y, z)) {
            out.breakCount = StanceProbe.MAX_BREAK_TARGETS + 1;
            return;
        }
        if (out.breakCount >= StanceProbe.MAX_BREAK_TARGETS) {
            out.breakCount = StanceProbe.MAX_BREAK_TARGETS + 1;
            return;
        }
        var target = out.breakTargets[out.breakCount++];
        target.x = x; target.y = y; target.z = z;
        target.stateToken = Block.getRawIdFromState(state);
        position.set(x, y, z);
        target.cost = Math.max(30, (int) (state.getHardness(client.world, position) * 100));
    }

    private void verifyBreakNeighborhood(StanceProbe out) {
        if (out.breakCount < 1 || out.breakCount > StanceProbe.MAX_BREAK_TARGETS) return;
        for (int i = 0; i < out.breakCount; i++) {
            var target = out.breakTargets[i];
            BlockPos targetPos = new BlockPos(target.x, target.y, target.z);
            for (var direction : net.minecraft.util.math.Direction.values()) {
                BlockPos adjacent = targetPos.offset(direction);
                int x = adjacent.getX(), y = adjacent.getY(), z = adjacent.getZ();
                if (!loaded(x, y, z) || blockState(x, y, z).getFluidState().isIn(FluidTags.LAVA)) {
                    out.hazard = true;
                }
            }
        }
    }

    private VoxelShape collisionShape(BlockState state) {
        if (shapeContext == null && client.player != null) shapeContext = ShapeContext.of(client.player);
        int slot = findVoxelCacheSlot(position.getX(), position.getY(), position.getZ());
        if (slot >= 0 && voxelCacheGeneration[slot] == readCacheGeneration
                && voxelCacheState[slot] == state && voxelCacheShapeReady[slot] != 0) {
            return voxelCacheShape[slot];
        }
        shapeMisses++;
        VoxelShape shape = state.getCollisionShape(client.world, position, shapeContext);
        if (slot >= 0 && voxelCacheGeneration[slot] == readCacheGeneration
                && voxelCacheState[slot] == state) {
            voxelCacheShape[slot] = shape;
            voxelCacheShapeReady[slot] = 1;
        }
        return shape;
    }

    private static boolean unsupportedContextShape(BlockState state) {
        // 1.20.1 exposes the live entity context but cannot evaluate it at a hypothetical stance.
        return state.isOf(Blocks.SCAFFOLDING);
    }

    private boolean canMine(BlockState state, int x, int y, int z) {
        position.set(x, y, z);
        if (state.getHardness(client.world, position) < 0 || state.hasBlockEntity() || hazardous(state)) return false;
        if (!state.isToolRequired()) return true;
        for (ItemStack tool : ClientAccess.main(client.player.getInventory())) {
            if (!tool.isEmpty() && tool.isSuitableFor(state)
                    && (!tool.isDamageable() || tool.getMaxDamage() - tool.getDamage() > 2)) return true;
        }
        return false;
    }

    @Override public boolean isMotionClear(double fx, double fy, double fz, double tx, double ty, double tz,
                                           double arc, StanceProbe destination) {
        return isMotionClear(fx, fy, fz, tx, ty, tz, arc, null, destination);
    }

    @Override public boolean isMotionClear(double fx, double fy, double fz, double tx, double ty, double tz,
                                           double arc, StanceProbe source, StanceProbe destination) {
        syncReadCacheEpoch();
        if (!Double.isFinite(fx) || !Double.isFinite(fy) || !Double.isFinite(fz)
                || !Double.isFinite(tx) || !Double.isFinite(ty) || !Double.isFinite(tz)
                || !Double.isFinite(arc)) return false;
        double distance = Math.sqrt(square(tx - fx) + square(ty - fy) + square(tz - fz));
        int samples = Math.max(2, (int) Math.ceil(distance / 0.2));
        if (samples > MAX_WALK_PROOFS) return false;
        double previousX = fx, previousY = fy, previousZ = fz;
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            MovementTrajectory.sample(fx, fy, fz, tx, ty, tz, arc, t, trajectoryPoint);
            double x = trajectoryPoint[0], y = trajectoryPoint[1], z = trajectoryPoint[2];
            if (i > 0 && !clearSweptSegment(previousX, previousY, previousZ, x, y, z, source, destination)) return false;
            if (i == 0 && !clearBodyAt(x, y, z, source, destination)) return false;
            previousX = x; previousY = y; previousZ = z;
        }
        return true;
    }

    @Override public boolean isGroundedWalkClear(double fromX, int fromFeetY16, double fromZ,
                                                 double toX, int toFeetY16, double toZ,
                                                 StanceProbe source, StanceProbe destination) {
        syncReadCacheEpoch();
        if (!Double.isFinite(fromX) || !Double.isFinite(fromZ) || !Double.isFinite(toX) || !Double.isFinite(toZ)
                || Math.abs(fromX) > 33_554_430.0 || Math.abs(fromZ) > 33_554_430.0
                || Math.abs(toX) > 33_554_430.0 || Math.abs(toZ) > 33_554_430.0) return false;
        long riseLong = (long) toFeetY16 - fromFeetY16;
        if (Math.abs(riseLong) > 16L) return false;
        int rise = (int) riseLong;
        double dx = toX - fromX, dz = toZ - fromZ;
        double minorDelta = Math.min(Math.abs(dx), Math.abs(dz));
        // Planned height-changing WALK edges remain cardinal. Permit only the small off-axis
        // correction from the live player's actual position to the centered destination node.
        if (rise != 0 && minorDelta > 0.75) return false;
        int stepLimit16 = liveStepLimit16();
        if (stepLimit16 < 1) return false;
        if (Math.abs(rise) == 16 && stepLimit16 >= 16) return false;
        if (Math.abs(dx) > 1.75 || Math.abs(dz) > 1.75) return false;

        if (rise == 0 && source != null && destination != null
                && source.fullSupport && destination.fullSupport
                && Math.floorMod(fromFeetY16, 16) == 0
                && standingWidth <= 1.0
                && fullCubeSweepSupport(fromX, fromZ, toX, toZ, fromFeetY16)
                && clearSweptSegment(fromX, fromFeetY16 / 16.0, fromZ,
                toX, toFeetY16 / 16.0, toZ, source, destination)) return true;

        long minimumFeetY16 = (long) Math.min(fromFeetY16, toFeetY16) - 16L;
        long maximumFeetY16 = (long) Math.max(fromFeetY16, toFeetY16) + 16L;
        if (minimumFeetY16 < Integer.MIN_VALUE || maximumFeetY16 > Integer.MAX_VALUE) return false;
        int eventCount = buildWalkEvents(fromX, fromZ, toX, toZ,
                (int) minimumFeetY16, (int) maximumFeetY16);
        if (eventCount < 2) return false;
        if (eventCount > walkResolvedHeights.length) return false;
        double previousX = fromX, previousZ = fromZ;
        int previousY = fromFeetY16;
        StanceProbe profile = sweepProfileProbe;
        int proofCount = 0;
        for (int i = 0; i < eventCount; i++) {
            if (++proofCount > MAX_WALK_PROOFS) return false;
            double t = walkEvents.get(i);
            double x = fromX + dx * t, z = fromZ + dz * t;
            int[] candidate = walkCandidateY;
            if (!highestSupportedFeetY(x, z, previousY, stepLimit16, profile, candidate)) return false;
            int feetY16 = candidate[0];
            if (Math.abs(feetY16 - previousY) > stepLimit16) return false;
            if (i == 0 && feetY16 != fromFeetY16) return false;
            if (i == eventCount - 1 && feetY16 != toFeetY16) return false;
            walkResolvedHeights[i] = feetY16;

            if (i > 0) {
                if (feetY16 > previousY) {
                    // Vanilla first reaches the stair face at the old height, lifts at contact, then walks across.
                    if (!clearSweptSegment(previousX, previousY / 16.0, previousZ,
                            previousX, feetY16 / 16.0, previousZ, source, destination)
                            || !clearSweptSegment(previousX, feetY16 / 16.0, previousZ,
                            x, feetY16 / 16.0, z, source, destination)) return false;
                } else if (feetY16 < previousY) {
                    if (!clearSweptSegment(previousX, previousY / 16.0, previousZ,
                            x, previousY / 16.0, z, source, destination)
                            || !clearSweptSegment(x, previousY / 16.0, z,
                            x, feetY16 / 16.0, z, source, destination)) return false;
                } else if (!clearSweptSegment(previousX, previousY / 16.0, previousZ,
                        x, feetY16 / 16.0, z, source, destination)) return false;
            } else if (!clearBodyAt(x, feetY16 / 16.0, z, source, destination)) return false;
            previousX = x; previousZ = z; previousY = feetY16;
        }
        return true;
    }

    private final int[] walkResolvedHeights = new int[MAX_WALK_EVENTS];
    private final int[] walkCandidateY = new int[1];

    private int liveStepLimit16() {
        if (client.player == null) return 0;
        double step = client.player.getStepHeight();
        if (!Double.isFinite(step) || step <= 0.0) return 0;
        return Math.min(9, (int) Math.floor(step * 16.0 + 1.0e-8));
    }

    private boolean fullCubeSweepSupport(double fromX, double fromZ, double toX, double toZ,
                                         int feetY16) {
        int floorY = Math.floorDiv(feetY16, 16) - 1;
        int minX = blockMin(Math.min(fromX, toX) - standingWidth * 0.5);
        int maxX = blockMax(Math.max(fromX, toX) + standingWidth * 0.5);
        int minZ = blockMin(Math.min(fromZ, toZ) - standingWidth * 0.5);
        int maxZ = blockMax(Math.max(fromZ, toZ) + standingWidth * 0.5);
        for (int bx = minX; bx <= maxX; bx++) for (int bz = minZ; bz <= maxZ; bz++) {
            if (!loaded(bx, floorY, bz)) return false;
            BlockState state = blockState(bx, floorY, bz);
            if (hazardous(state) || !Block.isShapeFullCube(collisionShape(state))) return false;
        }
        return true;
    }

    private int buildWalkEvents(double fromX, double fromZ, double toX, double toZ,
                                int minFeetY16, int maxFeetY16) {
        walkFromX = fromX; walkFromZ = fromZ;
        walkDeltaX = toX - fromX; walkDeltaZ = toZ - fromZ;
        double distance = Math.hypot(walkDeltaX, walkDeltaZ);
        walkEvents.clear(GEOMETRY_EPSILON / Math.max(1.0, distance));
        int intervals = Math.max(1, (int) Math.ceil(distance / 0.2));
        if (intervals + 1 > MAX_WALK_EVENTS) return -1;
        for (int i = 0; i <= intervals; i++) {
            if (!addWalkEvent((double) i / intervals, false)) return -1;
        }

        double minX = Math.min(fromX, toX) - standingWidth * 0.5;
        double maxX = Math.max(fromX, toX) + standingWidth * 0.5;
        double minZ = Math.min(fromZ, toZ) - standingWidth * 0.5;
        double maxZ = Math.max(fromZ, toZ) + standingWidth * 0.5;
        footprintMinX = minX; footprintMaxX = maxX;
        footprintMinZ = minZ; footprintMaxZ = maxZ;
        int cellMinX = blockMin(minX) - 1, cellMaxX = blockMax(maxX) + 1;
        int cellMinZ = blockMin(minZ) - 1, cellMaxZ = blockMax(maxZ) + 1;
        int cellMinY = Math.floorDiv(minFeetY16, 16) - 2;
        int cellMaxY = Math.floorDiv(maxFeetY16, 16) + 1;
        shapeBoxCount = 0; shapeIncomplete = false; shapeMode = MODE_WALK_EVENTS;
        for (int bx = cellMinX; bx <= cellMaxX; bx++) for (int by = cellMinY; by <= cellMaxY; by++) for (int bz = cellMinZ; bz <= cellMaxZ; bz++) {
            if (!loaded(bx, by, bz)) { shapeMode = MODE_NONE; return -1; }
            shapeBlockX = bx; shapeBlockY = by; shapeBlockZ = bz;
            BlockState state = blockState(bx, by, bz);
            if (unsupportedContextShape(state)) { shapeMode = MODE_NONE; return -1; }
            collisionShape(state).forEachBox(shapeConsumer);
            if (shapeIncomplete) { shapeMode = MODE_NONE; return -1; }
        }
        shapeMode = MODE_NONE;

        // Include voxel faces even when a native shape omits a zero-width box boundary.
        if (Math.abs(walkDeltaX) > GEOMETRY_EPSILON) {
            for (int face = (int) Math.floor(minX) - 1; face <= (int) Math.ceil(maxX) + 1; face++) {
                if (!addWalkFaceEvents(face, true)) return -1;
            }
        }
        if (Math.abs(walkDeltaZ) > GEOMETRY_EPSILON) {
            for (int face = (int) Math.floor(minZ) - 1; face <= (int) Math.ceil(maxZ) + 1; face++) {
                if (!addWalkFaceEvents(face, false)) return -1;
            }
        }
        walkEvents.sort();
        return walkEvents.isComplete() ? walkEvents.size() : -1;
    }

    private boolean addWalkFaceEvents(double face, boolean alongX) {
        double half = standingWidth * 0.5;
        return addWalkCenterEvent(face - half, alongX) && addWalkCenterEvent(face + half, alongX);
    }

    private boolean addWalkCenterEvent(double centerCoordinate, boolean alongX) {
        double start = alongX ? walkFromX : walkFromZ;
        double delta = alongX ? walkDeltaX : walkDeltaZ;
        if (Math.abs(delta) <= GEOMETRY_EPSILON) return true;
        double t = (centerCoordinate - start) / delta;
        if (t < -GEOMETRY_EPSILON || t > 1.0 + GEOMETRY_EPSILON) return true;
        t = Math.max(0.0, Math.min(1.0, t));
        double epsilon = 1.0e-4 / Math.max(0.001, Math.abs(delta));
        return addWalkEvent(t, true) && addWalkEvent(Math.max(0.0, t - epsilon), true)
                && addWalkEvent(Math.min(1.0, t + epsilon), true);
    }

    private boolean addWalkEvent(double t, boolean critical) {
        // Preserve exact native shape contacts over nearby regular samples. This also
        // prevents repeated wall faces from consuming the bounded unique-event budget.
        return walkEvents.add(t, critical);
    }

    private boolean clearSweptSegment(double fromX, double fromY, double fromZ,
                                      double toX, double toY, double toZ,
                                      StanceProbe source, StanceProbe destination) {
        double distance = Math.sqrt(square(toX - fromX) + square(toY - fromY) + square(toZ - fromZ));
        int samples = Math.max(1, (int) Math.ceil(distance / 0.2));
        if (samples > MAX_WALK_PROOFS) return false;
        double oldX = fromX, oldY = fromY, oldZ = fromZ;
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            double x = fromX + (toX - fromX) * t;
            double y = fromY + (toY - fromY) * t;
            double z = fromZ + (toZ - fromZ) * t;
            if (!clearSweptChord(oldX, oldY, oldZ, x, y, z, source, destination)) return false;
            oldX = x; oldY = y; oldZ = z;
        }
        return true;
    }

    private boolean clearSweptChord(double fromX, double fromY, double fromZ,
                                    double toX, double toY, double toZ,
                                    StanceProbe source, StanceProbe destination) {
        setBodyUnion(fromX, fromY, fromZ, toX, toY, toZ);
        return clearBodyBounds(source, destination);
    }

    private boolean clearBodyAt(double feetX, double feetY, double feetZ,
                                StanceProbe source, StanceProbe destination) {
        setBodyBounds(feetX, feetY, feetZ);
        return clearBodyBounds(source, destination);
    }

    private boolean clearBodyBounds(StanceProbe source, StanceProbe destination) {
        int tightMinX = blockMin(bodyMinX), tightMaxX = blockMax(bodyMaxX);
        int tightMinY = blockMin(bodyMinY), tightMaxY = blockMax(bodyMaxY);
        int tightMinZ = blockMin(bodyMinZ), tightMaxZ = blockMax(bodyMaxZ);
        int minX = tightMinX - 1, maxX = tightMaxX + 1;
        int minY = tightMinY - 1, maxY = tightMaxY + 1;
        int minZ = tightMinZ - 1, maxZ = tightMaxZ + 1;
        shapeBoxCount = 0; shapeIncomplete = false; shapeMode = MODE_BODY;
        for (int bx = minX; bx <= maxX; bx++) for (int by = minY; by <= maxY; by++) for (int bz = minZ; bz <= maxZ; bz++) {
            if (!loaded(bx, by, bz)) { shapeMode = MODE_NONE; return false; }
            BlockState state = blockState(bx, by, bz);
            if (unsupportedContextShape(state)) { shapeMode = MODE_NONE; return false; }
            if (bx >= tightMinX && bx <= tightMaxX && by >= tightMinY && by <= tightMaxY
                    && bz >= tightMinZ && bz <= tightMaxZ && hazardous(state)) {
                shapeMode = MODE_NONE; return false;
            }
            if (isVerifiedBreakTarget(source, bx, by, bz, state)
                    || isVerifiedBreakTarget(destination, bx, by, bz, state)) continue;
            shapeBlockX = bx; shapeBlockY = by; shapeBlockZ = bz;
            bodyCollision = false;
            collisionShape(state).forEachBox(shapeConsumer);
            if (shapeIncomplete || bodyCollision) { shapeMode = MODE_NONE; return false; }
        }
        shapeMode = MODE_NONE;
        return true;
    }

    private boolean isVerifiedBreakTarget(StanceProbe probe, int x, int y, int z, BlockState state) {
        if (probe == null || probe.breakCount < 1 || probe.breakCount > StanceProbe.MAX_BREAK_TARGETS) return false;
        int token = Block.getRawIdFromState(state);
        for (int i = 0; i < probe.breakCount; i++) {
            var target = probe.breakTargets[i];
            if (target.x == x && target.y == y && target.z == z && target.stateToken == token) return true;
        }
        return false;
    }

    @Override public boolean canBreakFrom(int x, int y, int z, StanceProbe destination, int index) {
        syncReadCacheEpoch();
        if (index < 0 || index >= destination.breakCount || index >= StanceProbe.MAX_BREAK_TARGETS) return false;
        var target = destination.breakTargets[index];
        double feetY = y;
        double eyeY = feetY + standingHeight * 0.9;
        double distance = square(target.x + 0.5 - (x + 0.5))
                + square(target.y + 0.5 - eyeY)
                + square(target.z + 0.5 - (z + 0.5));
        return distance <= 16.0;
    }

    @Override public boolean canPlaceBridgeFrom(int x, int y, int z, int bx, int by, int bz,
                                                 int token, boolean plannedSupport) {
        syncReadCacheEpoch();
        if (!config.allowBuilding || !loaded(bx, by, bz)
                || !blockState(bx, by, bz).isReplaceable()) return false;
        if (Math.abs(bx - x) + Math.abs(bz - z) != 1 || by != y - 1) return false;
        if (plannedSupport) return true;
        if (!loaded(x, y - 1, z)) return false;
        BlockState support = blockState(x, y - 1, z);
        return !unsupportedContextShape(support) && !hazardous(support)
                && Block.isShapeFullCube(collisionShape(support));
    }

    private void visitShapeBox(double localMinX, double localMinY, double localMinZ,
                               double localMaxX, double localMaxY, double localMaxZ) {
        if (++shapeBoxCount > MAX_SHAPE_BOXES) { shapeIncomplete = true; return; }
        if (!Double.isFinite(localMinX) || !Double.isFinite(localMinY) || !Double.isFinite(localMinZ)
                || !Double.isFinite(localMaxX) || !Double.isFinite(localMaxY) || !Double.isFinite(localMaxZ)
                || localMinX < -1.0 || localMinY < -1.0 || localMinZ < -1.0
                || localMaxX > 2.0 || localMaxY > 2.0 || localMaxZ > 2.0
                || localMinX > localMaxX || localMinY > localMaxY || localMinZ > localMaxZ) {
            shapeIncomplete = true;
            return;
        }
        if (localMinX == localMaxX || localMinY == localMaxY || localMinZ == localMaxZ) return;
        double minX = shapeBlockX + localMinX, minY = shapeBlockY + localMinY, minZ = shapeBlockZ + localMinZ;
        double maxX = shapeBlockX + localMaxX, maxY = shapeBlockY + localMaxY, maxZ = shapeBlockZ + localMaxZ;
        if (shapeMode == MODE_BODY) {
            if (minX < bodyMaxX && maxX > bodyMinX && minY < bodyMaxY && maxY > bodyMinY
                    && minZ < bodyMaxZ && maxZ > bodyMinZ) bodyCollision = true;
            return;
        }
        if (maxX <= footprintMinX || minX >= footprintMaxX || maxZ <= footprintMinZ || minZ >= footprintMaxZ) return;
        if (shapeMode == MODE_SUPPORT_CANDIDATES) {
            if (maxY < candidateMinimumFeetY16 / 16.0 - HEIGHT_EPSILON
                    || maxY > candidateMaximumFeetY16 / 16.0 + HEIGHT_EPSILON) return;
            int feetY16 = quantizeTop(maxY);
            if (feetY16 == Integer.MIN_VALUE) { shapeIncomplete = true; return; }
            if (feetY16 >= candidateMinimumFeetY16 && feetY16 <= candidateMaximumFeetY16) candidateBuffer.add(feetY16);
            return;
        }
        if (shapeMode == MODE_SUPPORT_PROFILE) {
            double feetY = candidateFeetY16 / 16.0;
            if (maxY < feetY - supportDepth16 / 16.0 - HEIGHT_EPSILON || maxY > feetY + HEIGHT_EPSILON) return;
            int feetY16 = quantizeTop(maxY);
            if (feetY16 == Integer.MIN_VALUE) { shapeIncomplete = true; return; }
            if (feetY16 < candidateFeetY16 - supportDepth16 || feetY16 > candidateFeetY16) return;
            if (shapeBlockHazard) {
                if (feetY16 == candidateFeetY16) supportHazardSeen = true;
                return;
            }
            allCoverage.add(minX, minZ, maxX, maxZ);
            if (feetY16 == candidateFeetY16) exactCoverage.add(minX, minZ, maxX, maxZ);
            if (!allCoverage.isComplete() || !exactCoverage.isComplete()) shapeIncomplete = true;
            return;
        }
        if (shapeMode == MODE_WALK_EVENTS) {
            if (Math.abs(walkDeltaX) > GEOMETRY_EPSILON) {
                if (!addWalkFaceEvents(minX, true) || !addWalkFaceEvents(maxX, true)) shapeIncomplete = true;
            }
            if (Math.abs(walkDeltaZ) > GEOMETRY_EPSILON) {
                if (!addWalkFaceEvents(minZ, false) || !addWalkFaceEvents(maxZ, false)) shapeIncomplete = true;
            }
        }
    }

    private void setFootprint(double feetX, double feetZ) {
        double half = standingWidth * 0.5;
        footprintMinX = feetX - half; footprintMaxX = feetX + half;
        footprintMinZ = feetZ - half; footprintMaxZ = feetZ + half;
    }

    private void setBodyBounds(double feetX, double feetY, double feetZ) {
        double half = standingWidth * 0.5;
        bodyMinX = feetX - half; bodyMaxX = feetX + half;
        bodyMinY = feetY + 0.001; bodyMaxY = feetY + standingHeight;
        bodyMinZ = feetZ - half; bodyMaxZ = feetZ + half;
    }

    private void setBodyUnion(double fromX, double fromY, double fromZ,
                              double toX, double toY, double toZ) {
        double half = standingWidth * 0.5;
        bodyMinX = Math.min(fromX, toX) - half; bodyMaxX = Math.max(fromX, toX) + half;
        bodyMinY = Math.min(fromY, toY) + 0.001; bodyMaxY = Math.max(fromY, toY) + standingHeight;
        bodyMinZ = Math.min(fromZ, toZ) - half; bodyMaxZ = Math.max(fromZ, toZ) + half;
    }

    private static int blockMin(double coordinate) { return (int) Math.floor(coordinate + GEOMETRY_EPSILON); }
    private static int blockMax(double coordinate) { return (int) Math.floor(coordinate - GEOMETRY_EPSILON); }
    private static int quantizeTop(double y) {
        double scaled = y * 16.0;
        if (!Double.isFinite(scaled) || scaled < Integer.MIN_VALUE || scaled > Integer.MAX_VALUE) return Integer.MIN_VALUE;
        long nearest = Math.round(scaled);
        if (Math.abs(y - nearest / 16.0) > HEIGHT_EPSILON) return Integer.MIN_VALUE;
        return (int) nearest;
    }
    private static double square(double value) { return value * value; }
}
