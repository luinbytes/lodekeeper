package dev.lodekeeper.fabric;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;

/** Clears one nearby two-block station pocket with ordinary survival mining. */
final class StationRoomAction {
    private static final long MAX_DURATION_NANOS = 8_000_000_000L;
    private static final int MAX_BLOCKS = 2;

    private enum Phase { IDLE, PREPARING, COMPLETE, STOPPED }

    private final MinecraftClient client;
    private final PlayerActions actions;
    private final BlockPos[] targets = new BlockPos[MAX_BLOCKS];
    private final BlockState[] startingStates = new BlockState[MAX_BLOCKS];
    private final boolean[] miningStarted = new boolean[MAX_BLOCKS];
    private final boolean[] cleared = new boolean[MAX_BLOCKS];

    private Phase phase = Phase.IDLE;
    private String status = "station room idle";
    private BlockPos site;
    private ClientWorld ownerWorld;
    private ClientPlayerEntity ownerPlayer;
    private BlockState startingFloor;
    private BlockPos breakingPosition;
    private long startedAtNanos;

    StationRoomAction(MinecraftClient client, PlayerActions actions) {
        this.client = client;
        this.actions = actions;
    }

    boolean canPrepareAt(BlockPos candidate) {
        return preparationProblem(candidate, client.world, client.player) == null;
    }

    boolean begin(BlockPos candidate) {
        if (active()) {
            status = "station room preparation is already active";
            return false;
        }
        reset();
        String reason = preparationProblem(candidate, client.world, client.player);
        if (reason != null) {
            status = reason;
            return false;
        }

        site = candidate.toImmutable();
        targets[0] = site.up();
        targets[1] = site;
        ownerWorld = client.world;
        ownerPlayer = client.player;
        startingFloor = ownerWorld.getBlockState(site.down());
        for (int index = 0; index < MAX_BLOCKS; index++) {
            startingStates[index] = ownerWorld.getBlockState(targets[index]);
            cleared[index] = startingStates[index].isReplaceable();
        }
        startedAtNanos = System.nanoTime();
        phase = Phase.PREPARING;
        status = "clearing station head space first";
        return true;
    }

    boolean active() { return phase == Phase.PREPARING; }

    boolean tick() {
        if (phase == Phase.COMPLETE) return true;
        if (!active()) return false;
        try {
            if (System.nanoTime() - startedAtNanos >= MAX_DURATION_NANOS) {
                throw new IllegalStateException("station room preparation exceeded 8 seconds");
            }
            String reason = continuingProblem();
            if (reason != null) throw new IllegalStateException(reason);

            for (int index = 0; index < MAX_BLOCKS; index++) {
                refreshTarget(index);
                if (cleared[index]) {
                    cancelOwnBreakingAt(targets[index]);
                    continue;
                }

                reason = continuingProblem();
                if (reason != null) throw new IllegalStateException(reason);
                refreshTarget(index);
                if (cleared[index]) {
                    cancelOwnBreakingAt(targets[index]);
                    continue;
                }
                if (actions.hit(targets[index]) == null) {
                    throw new IllegalStateException("station room block is outside native reach or line of sight");
                }
                if (!actions.mine(targets[index])) {
                    throw new IllegalStateException("native mining refused: " + actions.mineFailure());
                }
                miningStarted[index] = true;
                breakingPosition = targets[index];
                status = index == 0 ? "mining station head space" : "mining station feet space";
                return false;
            }

            cancelOwnBreaking();
            phase = Phase.COMPLETE;
            status = "station room is clear";
            releaseOwner();
            return true;
        } catch (RuntimeException failure) {
            throw abort("station room preparation failed: " + diagnostic(failure), failure);
        }
    }

    void stop() {
        if (!active()) return;
        try {
            cancelOwnBreaking();
            phase = Phase.STOPPED;
            status = "station room preparation stopped";
            releaseOwner();
        } catch (RuntimeException failure) {
            throw abort("station room preparation could not stop native mining: " + diagnostic(failure), failure);
        }
    }

    String status() { return status; }

    BlockPos site() { return site == null ? null : site.toImmutable(); }

    private void reset() {
        site = null;
        ownerWorld = null;
        ownerPlayer = null;
        startingFloor = null;
        breakingPosition = null;
        for (int index = 0; index < MAX_BLOCKS; index++) {
            targets[index] = null;
            startingStates[index] = null;
            miningStarted[index] = false;
            cleared[index] = false;
        }
        phase = Phase.IDLE;
    }

    private String preparationProblem(BlockPos candidate, ClientWorld world, ClientPlayerEntity player) {
        String reason = contextProblem(world, player);
        if (reason != null) return reason;
        if (candidate == null) return "no station placement position was supplied";
        if (!nearPlayer(candidate, player.getBlockPos())) return "station room position is not nearby";

        BlockPos head = candidate.up();
        BlockPos floor = candidate.down();
        for (BlockPos position : new BlockPos[]{candidate, head, floor, head.up()}) {
            if (!loaded(world, position)) return "station room or a required neighbour is unloaded";
        }

        BlockState floorState = world.getBlockState(floor);
        if (!actions.safePlacementSupport(floor) || !floorState.getFluidState().isEmpty() || hazardous(floorState)) {
            return "station room floor is not safe placement support";
        }
        if (world.getBlockState(head.up()).getBlock() instanceof FallingBlock) {
            return "unstable falling block is above the station room";
        }

        BlockPos playerSupport = playerSupport(player);
        boolean firstUncleared = true;
        for (BlockPos target : new BlockPos[]{head, candidate}) {
            if (target.equals(playerSupport) || player.getBoundingBox().intersects(new Box(target))) {
                return "station room would remove player support or intersect the player";
            }
            BlockState state = world.getBlockState(target);
            if (!state.getFluidState().isEmpty() || hazardous(state)) {
                return "station room contains fluid or a dangerous block";
            }
            if (state.hasBlockEntity() || !state.isReplaceable() && !clearable(state)) {
                return "station room contains a block that is not safe to clear";
            }
            if (!withinNativeReach(target, player)) return "station room block is outside native reach";
            if (!state.isReplaceable() && firstUncleared) {
                if (actions.hit(target) == null) return "first uncleared station block is outside line of sight";
                firstUncleared = false;
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = target.offset(direction);
                if (neighbour.equals(candidate) || neighbour.equals(head)) continue;
                if (!loaded(world, neighbour)) return "station room neighbour is unloaded";
                BlockState neighbourState = world.getBlockState(neighbour);
                if (!neighbourState.getFluidState().isEmpty() || hazardous(neighbourState)) {
                    return "station room is beside fluid or a dangerous block";
                }
            }
        }
        if (hasLivingObstruction(world, player, candidate)) return "living entity occupies the station room";
        return null;
    }

    private String continuingProblem() {
        if (client.world == null || client.world != ownerWorld || client.player != ownerPlayer) {
            return "world or player changed during station room preparation";
        }
        String reason = contextProblem(client.world, client.player);
        if (reason != null) return reason;
        if (!nearPlayer(site, client.player.getBlockPos())) return "player moved away from the station room";
        if (!loaded(client.world, site.down()) || !startingFloor.equals(client.world.getBlockState(site.down()))
                || !actions.safePlacementSupport(site.down())) {
            return "station room floor changed or is no longer safe";
        }
        if (!client.world.getBlockState(site.down()).getFluidState().isEmpty()) {
            return "station room floor became fluid-filled";
        }
        if (!loaded(client.world, site.up(2))
                || client.world.getBlockState(site.up(2)).getBlock() instanceof FallingBlock) {
            return "station room overhead is unloaded or unstable";
        }
        return preparationProblem(site, client.world, client.player);
    }

    private void refreshTarget(int index) {
        BlockPos position = targets[index];
        if (!loaded(ownerWorld, position)) throw new IllegalStateException("station room target chunk unloaded");
        BlockState current = ownerWorld.getBlockState(position);
        BlockState initial = startingStates[index];
        if (initial.isReplaceable()) {
            if (!current.isReplaceable()) throw new IllegalStateException("station room changed after preparation began");
            cleared[index] = true;
            return;
        }
        if (current.isReplaceable()) {
            if (!miningStarted[index]) throw new IllegalStateException("station room block changed before native mining");
            cleared[index] = true;
            return;
        }
        if (!current.equals(initial) || current.hasBlockEntity() || !clearable(current)) {
            throw new IllegalStateException("station room solid block changed during mining");
        }
        cleared[index] = false;
    }

    private void cancelOwnBreakingAt(BlockPos position) {
        if (position.equals(breakingPosition)) cancelOwnBreaking();
    }

    private void cancelOwnBreaking() {
        if (breakingPosition == null) return;
        actions.cancel();
        breakingPosition = null;
    }

    private IllegalStateException abort(String reason, RuntimeException cause) {
        try {
            cancelOwnBreaking();
        } catch (RuntimeException cancellationFailure) {
            reason += "; native mining cancellation failed: " + diagnostic(cancellationFailure);
        }
        phase = Phase.STOPPED;
        status = limit(reason);
        releaseOwner();
        return new IllegalStateException(status, cause);
    }

    private void releaseOwner() {
        ownerWorld = null;
        ownerPlayer = null;
        startingFloor = null;
    }

    private String contextProblem(ClientWorld world, ClientPlayerEntity player) {
        if (world == null || player == null || client.interactionManager == null) return "world unavailable";
        if (!Double.isFinite(player.getX()) || !Double.isFinite(player.getY()) || !Double.isFinite(player.getZ())) {
            return "player position is not finite";
        }
        Vec3d eye = player.getEyePos();
        if (!Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)) {
            return "player eye position is not finite";
        }
        if (!player.isAlive() || !Float.isFinite(player.getHealth()) || player.getHealth() <= 0.0f) {
            return "player health is not safe";
        }
        if (client.interactionManager.getCurrentGameMode() != GameMode.SURVIVAL) {
            return "station room preparation requires survival mode";
        }
        if (!player.isOnGround()) return "player must be on the ground";
        if (client.currentScreen != null || player.currentScreenHandler == null
                || player.currentScreenHandler != player.playerScreenHandler
                || !player.currentScreenHandler.getCursorStack().isEmpty()) return "screen or inventory cursor is not safe";
        if (player.isUsingItem() || player.hasVehicle()) return "player is using an item or riding";
        return null;
    }

    private static boolean nearPlayer(BlockPos candidate, BlockPos player) {
        return Math.abs((long) candidate.getX() - player.getX()) <= 2
                && Math.abs((long) candidate.getY() - player.getY()) <= 1
                && Math.abs((long) candidate.getZ() - player.getZ()) <= 2;
    }

    private static BlockPos playerSupport(ClientPlayerEntity player) {
        return new BlockPos(floor(player.getX()), floor(player.getY() - 1.0e-5), floor(player.getZ()));
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static boolean loaded(ClientWorld world, BlockPos position) {
        return !world.isOutOfHeightLimit(position.getY()) && world.isChunkLoaded(position);
    }

    private boolean withinNativeReach(BlockPos position, ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        double reach = GameApi.blockReach(client);
        if (!Double.isFinite(reach) || reach <= 0.0) return false;
        double dx = Math.max(position.getX() - eye.x, Math.max(0.0, eye.x - (position.getX() + 1.0)));
        double dy = Math.max(position.getY() - eye.y, Math.max(0.0, eye.y - (position.getY() + 1.0)));
        double dz = Math.max(position.getZ() - eye.z, Math.max(0.0, eye.z - (position.getZ() + 1.0)));
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private static boolean hasLivingObstruction(ClientWorld world, ClientPlayerEntity player, BlockPos candidate) {
        List<LivingEntity> occupants = new ArrayList<>();
        Box room = new Box(candidate.getX(), candidate.getY(), candidate.getZ(),
                candidate.getX() + 1.0, candidate.getY() + 2.0, candidate.getZ() + 1.0);
        world.collectEntitiesByType(TypeFilter.instanceOf(LivingEntity.class), room,
                living -> living != player, occupants, 17);
        return occupants.size() >= 17 || !occupants.isEmpty();
    }

    private static boolean clearable(BlockState state) {
        return state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.TUFF)
                || state.isOf(Blocks.GRANITE) || state.isOf(Blocks.ANDESITE) || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.DIRT) || state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.GRASS_BLOCK);
    }

    private static boolean hazardous(BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.WITHER_ROSE);
    }

    private static String diagnostic(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private static String limit(String value) {
        return value.length() > 180 ? value.substring(0, 180) : value;
    }
}
