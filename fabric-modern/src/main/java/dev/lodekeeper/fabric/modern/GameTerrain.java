package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.nav.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.level.chunk.status.ChunkStatus;

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
    // Dynamic shapes stay live; overflow makes this search's geometry proofs fail closed.
    private static final int DYNAMIC_SHAPE_WATCH_LIMIT = 512;
    private static final int DYNAMIC_SHAPE_WATCH_SIZE = 1_024;

    private static final int MODE_NONE = 0;
    private static final int MODE_BODY = 1;
    private static final int MODE_SUPPORT_CANDIDATES = 2;
    private static final int MODE_SUPPORT_PROFILE = 3;
    private static final int MODE_WALK_EVENTS = 4;

    private final Minecraft client;
    private final LodekeeperConfig config;
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private final FootprintCoverage exactCoverage = new FootprintCoverage();
    private final FootprintCoverage allCoverage = new FootprintCoverage();
    private final GroundedStanceBuffer stanceBuffer = new GroundedStanceBuffer();
    private final double[] trajectoryPoint = new double[3];
    private final MotionEventBuffer walkEvents = new MotionEventBuffer(MAX_WALK_EVENTS);
    private final Shapes.DoubleLineConsumer shapeConsumer = this::visitShapeBox;

    private long revision;
    private double standingWidth = PLAYER_WIDTH_FALLBACK;
    private double standingHeight = PLAYER_HEIGHT_FALLBACK;
    private boolean standingDimensionsValid = true;
    private final PlayerContext observedContext = new PlayerContext();
    private CollisionContext shapeContext;
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
    private final int[] dynamicShapeWatchGeneration = new int[DYNAMIC_SHAPE_WATCH_SIZE];
    private final int[] dynamicShapeWatchX = new int[DYNAMIC_SHAPE_WATCH_SIZE];
    private final int[] dynamicShapeWatchY = new int[DYNAMIC_SHAPE_WATCH_SIZE];
    private final int[] dynamicShapeWatchZ = new int[DYNAMIC_SHAPE_WATCH_SIZE];
    private final byte[] dynamicShapeWatchLoaded = new byte[DYNAMIC_SHAPE_WATCH_SIZE];
    private final BlockState[] dynamicShapeWatchState = new BlockState[DYNAMIC_SHAPE_WATCH_SIZE];
    private final VoxelShape[] dynamicShapeWatchShape = new VoxelShape[DYNAMIC_SHAPE_WATCH_SIZE];
    private int readCacheGeneration = 1;
    private int voxelCacheEntries, chunkCacheEntries;
    private boolean readCacheEpochValid;
    private Object readCacheWorld;
    private long readCacheRevision, readCacheTick;
    private int dynamicShapeWatchEpoch = 1;
    private int dynamicShapeWatchEntries;
    private Object dynamicShapeWatchWorld;
    private Object dynamicShapePollWorld;
    private long dynamicShapePollTick = Long.MIN_VALUE;
    private boolean dynamicShapeWatchSaturated;

    // Package-visible for bounded native benchmarks; reset between measured searches if needed.
    long voxelQueries, readMisses, shapeMisses, chunkQueries, chunkMisses;

    GameTerrain(Minecraft client, LodekeeperConfig config) {
        this.client = client;
        this.config = config;
        clearDynamicShapeWatch(client.level);
        refreshStandingDimensions();
    }

    void changed() { revision++; }
    void beginSearch() {
        WorldRevision.beginSearch();
        observeContext();
        clearDynamicShapeWatch(client.level);
    }
    void refreshStandingDimensions() { observeContext(); }

    private void observeContext() {
        Object world = client.level;
        var player = client.player;
        double width = PLAYER_WIDTH_FALLBACK;
        double height = PLAYER_HEIGHT_FALLBACK;
        boolean valid = false;
        Pose pose = null;
        boolean sneaking = false;
        boolean onGround = false;
        boolean descending = false;
        double feetY = Double.NaN;
        ItemStack mainHand = ItemStack.EMPTY;
        ItemStack offHand = ItemStack.EMPTY;
        if (player != null) {
            AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(0.0, 0.0, 0.0);
            width = box.maxX - box.minX;
            height = box.maxY - box.minY;
            valid = Double.isFinite(width) && Double.isFinite(height)
                    && width >= 0.1 && width <= MAX_STANDING_WIDTH
                    && height >= 0.5 && height <= MAX_STANDING_HEIGHT;
            pose = player.getPose();
            sneaking = player.isShiftKeyDown();
            onGround = player.onGround();
            descending = player.isDescending();
            feetY = player.getY();
            mainHand = player.getMainHandItem();
            offHand = player.getOffhandItem();
        }

        boolean worldChanged = observedContext.worldChanged(world);
        boolean changed = observedContext.update(world, player, width, height, valid, pose,
                sneaking, onGround, descending, feetY, mainHand, offHand);
        standingDimensionsValid = valid;
        if (valid) {
            standingWidth = width;
            standingHeight = height;
        }
        if (!changed) return;
        if (worldChanged) clearDynamicShapeWatch(world);
        revision++;
        shapeContext = player == null ? null : CollisionContext.of(player);
    }

    private static final class PlayerContext {
        private Object world;
        private Object player;
        private long standingWidthBits;
        private long standingHeightBits;
        private long feetYBits;
        private Pose pose;
        private ItemStack mainHand = ItemStack.EMPTY;
        private ItemStack offHand = ItemStack.EMPTY;
        private boolean dimensionsValid;
        private boolean sneaking;
        private boolean onGround;
        private boolean descending;
        private boolean observed;

        boolean worldChanged(Object currentWorld) { return observed && world != currentWorld; }

        boolean update(Object currentWorld, Object currentPlayer, double width, double height,
                       boolean valid, Pose currentPose, boolean currentSneaking,
                       boolean currentOnGround, boolean currentDescending, double currentFeetY,
                       ItemStack currentMainHand, ItemStack currentOffHand) {
            long currentWidthBits = Double.doubleToLongBits(width);
            long currentHeightBits = Double.doubleToLongBits(height);
            long currentFeetYBits = Double.doubleToLongBits(currentFeetY);
            boolean changed = !observed || world != currentWorld || player != currentPlayer
                    || standingWidthBits != currentWidthBits || standingHeightBits != currentHeightBits
                    || dimensionsValid != valid || pose != currentPose || sneaking != currentSneaking
                    || onGround != currentOnGround || descending != currentDescending
                    || feetYBits != currentFeetYBits || !sameStack(mainHand, currentMainHand)
                    || !sameStack(offHand, currentOffHand);
            if (!changed) return false;
            world = currentWorld;
            player = currentPlayer;
            standingWidthBits = currentWidthBits;
            standingHeightBits = currentHeightBits;
            dimensionsValid = valid;
            pose = currentPose;
            sneaking = currentSneaking;
            onGround = currentOnGround;
            descending = currentDescending;
            feetYBits = currentFeetYBits;
            mainHand = currentMainHand.copy();
            offHand = currentOffHand.copy();
            observed = true;
            return true;
        }

        private static boolean sameStack(ItemStack snapshot, ItemStack current) {
            return snapshot.getCount() == current.getCount()
                    && (snapshot.isEmpty() && current.isEmpty()
                    || ItemStack.isSameItemSameComponents(snapshot, current));
        }
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
    @Override public long revision() {
        observeContext();
        observeDynamicShapes();
        return currentRevisionValue();
    }

    private long currentRevisionValue() { return revision + WorldRevision.value(); }

    private boolean loaded(int x, int y, int z) {
        chunkQueries++;
        if (dynamicShapeWatchSaturated || client.level == null
                || (y < client.level.getMinY() || y >= client.level.getMaxY())) return false;
        WorldRevision.watch(x >> 4, z >> 4);
        int chunkX = x >> 4, chunkZ = z >> 4;
        int slot = findChunkCacheSlot(chunkX, chunkZ);
        if (slot >= 0 && chunkCacheGeneration[slot] == readCacheGeneration) {
            return chunkCacheLoaded[slot] != 0;
        }
        chunkMisses++;
        boolean available = client.level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
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
        BlockState state = client.level.getBlockState(position.set(x, y, z));
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
        observeContext();
        observeDynamicShapes();
        Object world = client.level;
        long currentRevision = currentRevisionValue();
        long tick = world == null ? Long.MIN_VALUE : client.level.getGameTime();
        if (!readCacheEpochValid || readCacheWorld != world
                || readCacheRevision != currentRevision || readCacheTick != tick) {
            invalidateReadCache();
            readCacheWorld = world;
            readCacheRevision = currentRevision;
            readCacheTick = tick;
            readCacheEpochValid = true;
        }
    }

    private void observeDynamicShapes() {
        Object worldObject = client.level;
        if (worldObject == null || client.player == null || dynamicShapeWatchEntries == 0
                || dynamicShapeWatchSaturated) return;
        if (dynamicShapeWatchWorld != worldObject) {
            clearDynamicShapeWatch(worldObject);
            return;
        }
        var world = client.level;
        long tick = world.getGameTime();
        if (dynamicShapePollWorld == worldObject && dynamicShapePollTick == tick) return;
        dynamicShapePollWorld = worldObject;
        dynamicShapePollTick = tick;

        boolean changed = false;
        int oldX = position.getX(), oldY = position.getY(), oldZ = position.getZ();
        try {
            for (int slot = 0; slot < DYNAMIC_SHAPE_WATCH_SIZE; slot++) {
                if (dynamicShapeWatchGeneration[slot] != dynamicShapeWatchEpoch) continue;
                int x = dynamicShapeWatchX[slot];
                int y = dynamicShapeWatchY[slot];
                int z = dynamicShapeWatchZ[slot];
                boolean available = world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
                BlockState state = null;
                VoxelShape shape = Shapes.empty();
                if (available) {
                    state = world.getBlockState(position.set(x, y, z));
                    shape = state.getCollisionShape(world, position, shapeContext);
                }
                boolean cellChanged = (dynamicShapeWatchLoaded[slot] != 0) != available
                        || dynamicShapeWatchState[slot] != state
                        || !sameShape(dynamicShapeWatchShape[slot], shape);
                if (cellChanged) changed = true;
                dynamicShapeWatchLoaded[slot] = (byte) (available ? 1 : 0);
                dynamicShapeWatchState[slot] = state;
                dynamicShapeWatchShape[slot] = shape;
            }
        } finally {
            position.set(oldX, oldY, oldZ);
        }
        if (changed) revision++;
    }

    private void clearDynamicShapeWatch(Object world) {
        if (dynamicShapeWatchEpoch == Integer.MAX_VALUE) {
            Arrays.fill(dynamicShapeWatchGeneration, 0);
            dynamicShapeWatchEpoch = 1;
        } else {
            dynamicShapeWatchEpoch++;
        }
        dynamicShapeWatchEntries = 0;
        dynamicShapeWatchWorld = world;
        dynamicShapePollWorld = null;
        dynamicShapePollTick = Long.MIN_VALUE;
        dynamicShapeWatchSaturated = false;
    }

    private int findDynamicShapeWatchSlot(int x, int y, int z) {
        int slot = voxelHash(x, y, z) & (DYNAMIC_SHAPE_WATCH_SIZE - 1);
        int start = slot;
        while (dynamicShapeWatchGeneration[slot] == dynamicShapeWatchEpoch) {
            if (dynamicShapeWatchX[slot] == x && dynamicShapeWatchY[slot] == y
                    && dynamicShapeWatchZ[slot] == z) return slot;
            slot = (slot + 1) & (DYNAMIC_SHAPE_WATCH_SIZE - 1);
            if (slot == start) return -1;
        }
        return slot;
    }

    private void watchDynamicShape(int x, int y, int z, BlockState state, VoxelShape shape) {
        int slot = findDynamicShapeWatchSlot(x, y, z);
        if (slot < 0) {
            saturateDynamicShapeWatch();
            return;
        }
        if (dynamicShapeWatchGeneration[slot] != dynamicShapeWatchEpoch) {
            if (dynamicShapeWatchEntries >= DYNAMIC_SHAPE_WATCH_LIMIT) {
                saturateDynamicShapeWatch();
                return;
            }
            dynamicShapeWatchGeneration[slot] = dynamicShapeWatchEpoch;
            dynamicShapeWatchX[slot] = x;
            dynamicShapeWatchY[slot] = y;
            dynamicShapeWatchZ[slot] = z;
            dynamicShapeWatchLoaded[slot] = 1;
            dynamicShapeWatchState[slot] = state;
            dynamicShapeWatchShape[slot] = shape;
            dynamicShapeWatchEntries++;
            return;
        }
        if (dynamicShapeWatchState[slot] != state
                || !sameShape(dynamicShapeWatchShape[slot], shape)) {
            dynamicShapeWatchState[slot] = state;
            dynamicShapeWatchShape[slot] = shape;
            revision++;
        }
    }

    private void saturateDynamicShapeWatch() {
        if (dynamicShapeWatchSaturated) return;
        dynamicShapeWatchSaturated = true;
        revision++;
    }

    private static boolean sameShape(VoxelShape first, VoxelShape second) {
        return !Shapes.joinIsNotEmpty(first, second, BooleanOp.NOT_SAME);
    }

    private boolean hazardous(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE);
    }

    @Override public void probeStance(int x, int y, int z, StanceProbe out) {
        syncReadCacheEpoch();
        if (dynamicShapeWatchSaturated) { out.clear(); return; }
        if (y < -2_048 || y > 2_047) { out.clear(); return; }
        probeAt(x + 0.5, Math.multiplyExact(y, 16), z + 0.5, true, out);
    }

    @Override public boolean probeStance16(int x, int feetY16, int z, StanceProbe out) {
        syncReadCacheEpoch();
        if (dynamicShapeWatchSaturated) { out.clear(); return false; }
        probeAt(x + 0.5, feetY16, z + 0.5, true, out);
        return out.loaded;
    }

    @Override public boolean probeCurrentStance(double feetX, int feetY16, double feetZ, StanceProbe out) {
        syncReadCacheEpoch();
        if (dynamicShapeWatchSaturated) { out.clear(); return false; }
        if (!Double.isFinite(feetX) || !Double.isFinite(feetZ)) { out.clear(); return false; }
        probeAt(feetX, feetY16, feetZ, false, out);
        return out.loaded;
    }

    private void probeAt(double feetX, int feetY16, double feetZ, boolean centered, StanceProbe out) {
        out.clear();
        if (dynamicShapeWatchSaturated || client.player == null || client.level == null || !standingDimensionsValid
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
                out.water |= state.getFluidState().is(FluidTags.WATER);
                out.climbable |= state.is(BlockTags.CLIMBABLE);
            }
            shapeBlockX = bx; shapeBlockY = by; shapeBlockZ = bz;
            bodyCollision = false;
            VoxelShape shape = collisionShape(state);
            shape.forAllBoxes(shapeConsumer);
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
            VoxelShape floorShape = collisionShape(floor);
            if (dynamicShapeWatchSaturated) { out.clear(); return; }
            out.fullSupport = !out.hazard && exactCoverage.coversAll() && allCoverage.coversAll()
                    && Block.isShapeFullBlock(floorShape);
        }
        if (out.breakCount > 0) verifyBreakNeighborhood(out);
        if (dynamicShapeWatchSaturated) { out.clear(); return; }
        out.loaded = true;
    }

    @Override public boolean collectGroundedStances(int x, int referenceFeetY16, int z,
                                                    GroundedStanceBuffer out) {
        syncReadCacheEpoch();
        out.clear();
        if (dynamicShapeWatchSaturated) return false;
        return collectSupportHeights(x + 0.5, z + 0.5, referenceFeetY16, 16, out);
    }

    private boolean collectSupportHeights(double feetX, double feetZ, int referenceFeetY16,
                                          int range16, GroundedStanceBuffer out) {
        out.clear();
        if (client.level == null || client.player == null || !standingDimensionsValid
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
            collisionShape(state).forAllBoxes(shapeConsumer);
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
            collisionShape(state).forAllBoxes(shapeConsumer);
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
        target.stateToken = Block.getId(state);
        position.set(x, y, z);
        target.cost = Math.max(30, (int) (state.getDestroySpeed(client.level, position) * 100));
    }

    private void verifyBreakNeighborhood(StanceProbe out) {
        if (out.breakCount < 1 || out.breakCount > StanceProbe.MAX_BREAK_TARGETS) return;
        for (int i = 0; i < out.breakCount; i++) {
            var target = out.breakTargets[i];
            BlockPos targetPos = new BlockPos(target.x, target.y, target.z);
            for (var direction : net.minecraft.core.Direction.values()) {
                BlockPos adjacent = targetPos.relative(direction);
                int x = adjacent.getX(), y = adjacent.getY(), z = adjacent.getZ();
                if (!loaded(x, y, z) || blockState(x, y, z).getFluidState().is(FluidTags.LAVA)) {
                    out.hazard = true;
                }
            }
        }
    }

    private VoxelShape collisionShape(BlockState state) {
        if (shapeContext == null && client.player != null) shapeContext = CollisionContext.of(client.player);
        int x = position.getX(), y = position.getY(), z = position.getZ();
        if (state.getBlock().hasDynamicShape()) {
            shapeMisses++;
            VoxelShape shape = state.getCollisionShape(client.level, position, shapeContext);
            watchDynamicShape(x, y, z, state, shape);
            if (dynamicShapeWatchSaturated) shapeIncomplete = true;
            return shape;
        }
        int slot = findVoxelCacheSlot(position.getX(), position.getY(), position.getZ());
        if (slot >= 0 && voxelCacheGeneration[slot] == readCacheGeneration
                && voxelCacheState[slot] == state && voxelCacheShapeReady[slot] != 0) {
            return voxelCacheShape[slot];
        }
        shapeMisses++;
        VoxelShape shape = state.getCollisionShape(client.level, position, shapeContext);
        if (slot >= 0 && voxelCacheGeneration[slot] == readCacheGeneration
                && voxelCacheState[slot] == state) {
            voxelCacheShape[slot] = shape;
            voxelCacheShapeReady[slot] = 1;
        }
        return shape;
    }

    private static boolean unsupportedContextShape(BlockState state) {
        // 1.20.1 exposes the live entity context but cannot evaluate it at a hypothetical stance.
        return state.is(Blocks.SCAFFOLDING);
    }

    private boolean canMine(BlockState state, int x, int y, int z) {
        position.set(x, y, z);
        if (state.getDestroySpeed(client.level, position) < 0 || state.hasBlockEntity() || hazardous(state)) return false;
        if (!state.requiresCorrectToolForDrops()) return true;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack tool = client.player.getInventory().getItem(slot);
            if (!tool.isEmpty() && tool.isCorrectToolForDrops(state)
                    && (!tool.isDamageableItem() || tool.getMaxDamage() - tool.getDamageValue() > 2)) return true;
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
        if (dynamicShapeWatchSaturated || !Double.isFinite(fx) || !Double.isFinite(fy) || !Double.isFinite(fz)
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
        return !dynamicShapeWatchSaturated;
    }

    @Override public boolean isGroundedWalkClear(double fromX, int fromFeetY16, double fromZ,
                                                 double toX, int toFeetY16, double toZ,
                                                 StanceProbe source, StanceProbe destination) {
        syncReadCacheEpoch();
        if (dynamicShapeWatchSaturated || !Double.isFinite(fromX) || !Double.isFinite(fromZ)
                || !Double.isFinite(toX) || !Double.isFinite(toZ)
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
        double step = client.player.maxUpStep();
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
            if (hazardous(state)) return false;
            VoxelShape shape = collisionShape(state);
            if (dynamicShapeWatchSaturated || !Block.isShapeFullBlock(shape)) return false;
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
            collisionShape(state).forAllBoxes(shapeConsumer);
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
            collisionShape(state).forAllBoxes(shapeConsumer);
            if (shapeIncomplete || bodyCollision) { shapeMode = MODE_NONE; return false; }
        }
        shapeMode = MODE_NONE;
        return !dynamicShapeWatchSaturated;
    }

    private boolean isVerifiedBreakTarget(StanceProbe probe, int x, int y, int z, BlockState state) {
        if (probe == null || probe.breakCount < 1 || probe.breakCount > StanceProbe.MAX_BREAK_TARGETS) return false;
        int token = Block.getId(state);
        for (int i = 0; i < probe.breakCount; i++) {
            var target = probe.breakTargets[i];
            if (target.x == x && target.y == y && target.z == z && target.stateToken == token) return true;
        }
        return false;
    }

    enum BreakFailure {
        NONE, INVALID_TARGET, CONTEXT_UNAVAILABLE, UNLOADED_TARGET, STATE_CHANGED,
        UNMINEABLE_TARGET, PLAYER_SUPPORT, NO_REACHABLE_OUTLINE_HIT
    }
    private BreakFailure breakFailure = BreakFailure.NONE;
    BreakFailure breakFailure() { return breakFailure; }
    private boolean refuseBreak(BreakFailure reason) { breakFailure = reason; return false; }

    @Override public boolean canBreakFrom(int x, int y, int z, StanceProbe destination, int index) {
        syncReadCacheEpoch();
        breakFailure = BreakFailure.NONE;
        if (destination == null || destination.breakCount < 1
                || destination.breakCount > StanceProbe.MAX_BREAK_TARGETS
                || index < 0 || index >= destination.breakCount) return refuseBreak(BreakFailure.INVALID_TARGET);
        if (dynamicShapeWatchSaturated || !standingDimensionsValid || client.level == null
                || client.player == null || client.gameMode == null) return refuseBreak(BreakFailure.CONTEXT_UNAVAILABLE);
        var target = destination.breakTargets[index];
        if (!loaded(target.x, target.y, target.z)) return refuseBreak(BreakFailure.UNLOADED_TARGET);
        BlockState state = blockState(target.x, target.y, target.z);
        if (Block.getId(state) != target.stateToken) return refuseBreak(BreakFailure.STATE_CHANGED);
        if (!config.allowBreaking || !canMine(state, target.x, target.y, target.z))
            return refuseBreak(BreakFailure.UNMINEABLE_TARGET);
        double halfWidth = standingWidth * 0.5;
        if (target.y < y && target.y + 1 >= y - .05
                && target.x < x + .5 + halfWidth && target.x + 1 > x + .5 - halfWidth
                && target.z < z + .5 + halfWidth && target.z + 1 > z + .5 - halfWidth)
            return refuseBreak(BreakFailure.PLAYER_SUPPORT);
        double eyeHeight = client.player.getEyeHeight(Pose.STANDING);
        if (!Double.isFinite(eyeHeight) || eyeHeight <= 0.0 || eyeHeight > standingHeight)
            return refuseBreak(BreakFailure.CONTEXT_UNAVAILABLE);
        Vec3 eye = new Vec3(x + .5, y + eyeHeight, z + .5);
        if (PlayerActions.hitFrom(client, new BlockPos(target.x, target.y, target.z), eye) == null)
            return refuseBreak(BreakFailure.NO_REACHABLE_OUTLINE_HIT);
        return true;
    }

    @Override public boolean canPlaceBridgeFrom(int x, int y, int z, int bx, int by, int bz,
                                                 int token, boolean plannedSupport) {
        syncReadCacheEpoch();
        if (dynamicShapeWatchSaturated || !config.allowBuilding || !loaded(bx, by, bz)
                || !blockState(bx, by, bz).canBeReplaced()) return false;
        if (Math.abs(bx - x) + Math.abs(bz - z) != 1 || by != y - 1) return false;
        if (plannedSupport) return !dynamicShapeWatchSaturated;
        if (!loaded(x, y - 1, z)) return false;
        BlockState support = blockState(x, y - 1, z);
        if (unsupportedContextShape(support) || hazardous(support)) return false;
        VoxelShape shape = collisionShape(support);
        return !dynamicShapeWatchSaturated && Block.isShapeFullBlock(shape);
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
