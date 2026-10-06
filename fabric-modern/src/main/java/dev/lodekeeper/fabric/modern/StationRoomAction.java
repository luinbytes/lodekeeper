package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/** Clears one nearby two-block station pocket with ordinary survival mining. */
final class StationRoomAction {
    private static final long MAX_DURATION_NANOS = 8_000_000_000L;
    private static final int MAX_BLOCKS = 2;

    private enum Phase { IDLE, PREPARING, COMPLETE, STOPPED }

    private final Minecraft client;
    private final PlayerActions actions;
    private final BlockPos[] targets = new BlockPos[MAX_BLOCKS];
    private final BlockState[] startingStates = new BlockState[MAX_BLOCKS];
    private final boolean[] miningStarted = new boolean[MAX_BLOCKS];
    private final boolean[] cleared = new boolean[MAX_BLOCKS];

    private Phase phase = Phase.IDLE;
    private String status = "station room idle";
    private BlockPos site;
    private ClientLevel ownerLevel;
    private LocalPlayer ownerPlayer;
    private BlockState startingFloor;
    private BlockPos breakingPosition;
    private long startedAtNanos;

    StationRoomAction(Minecraft client, PlayerActions actions) {
        this.client = client;
        this.actions = actions;
    }

    boolean canPrepareAt(BlockPos candidate) {
        return preparationProblem(candidate, client.level, client.player) == null;
    }

    boolean begin(BlockPos candidate) {
        if (active()) {
            status = "station room preparation is already active";
            return false;
        }
        reset();
        String reason = preparationProblem(candidate, client.level, client.player);
        if (reason != null) {
            status = reason;
            return false;
        }

        site = candidate.immutable();
        targets[0] = site.above();
        targets[1] = site;
        ownerLevel = client.level;
        ownerPlayer = client.player;
        startingFloor = ownerLevel.getBlockState(site.below());
        for (int index = 0; index < MAX_BLOCKS; index++) {
            startingStates[index] = ownerLevel.getBlockState(targets[index]);
            cleared[index] = startingStates[index].canBeReplaced();
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

    BlockPos site() { return site == null ? null : site.immutable(); }

    private void reset() {
        site = null;
        ownerLevel = null;
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

    private String preparationProblem(BlockPos candidate, ClientLevel level, LocalPlayer player) {
        String reason = contextProblem(level, player);
        if (reason != null) return reason;
        if (candidate == null) return "no station placement position was supplied";
        if (!nearPlayer(candidate, player.blockPosition())) return "station room position is not nearby";

        BlockPos head = candidate.above();
        BlockPos floor = candidate.below();
        for (BlockPos position : new BlockPos[]{candidate, head, floor, head.above()}) {
            if (!loaded(level, position)) return "station room or a required neighbour is unloaded";
        }

        BlockState floorState = level.getBlockState(floor);
        if (!actions.safePlacementSupport(floor) || !floorState.getFluidState().isEmpty() || hazardous(floorState)) {
            return "station room floor is not safe placement support";
        }
        if (level.getBlockState(head.above()).getBlock() instanceof FallingBlock) {
            return "unstable falling block is above the station room";
        }

        BlockPos playerSupport = playerSupport(player);
        boolean firstUncleared = true;
        for (BlockPos target : new BlockPos[]{head, candidate}) {
            if (target.equals(playerSupport) || player.getBoundingBox().intersects(new AABB(target))) {
                return "station room would remove player support or intersect the player";
            }
            BlockState state = level.getBlockState(target);
            if (!state.getFluidState().isEmpty() || hazardous(state)) {
                return "station room contains fluid or a dangerous block";
            }
            if (state.hasBlockEntity() || !state.canBeReplaced() && !clearable(state)) {
                return "station room contains a block that is not safe to clear";
            }
            if (!withinNativeReach(target, player)) return "station room block is outside native reach";
            if (!state.canBeReplaced() && firstUncleared) {
                if (actions.hit(target) == null) return "first uncleared station block is outside line of sight";
                firstUncleared = false;
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = target.relative(direction);
                if (neighbour.equals(candidate) || neighbour.equals(head)) continue;
                if (!loaded(level, neighbour)) return "station room neighbour is unloaded";
                BlockState neighbourState = level.getBlockState(neighbour);
                if (!neighbourState.getFluidState().isEmpty() || hazardous(neighbourState)) {
                    return "station room is beside fluid or a dangerous block";
                }
            }
        }
        if (hasLivingObstruction(level, player, candidate)) return "living entity occupies the station room";
        return null;
    }

    private String continuingProblem() {
        if (client.level == null || client.level != ownerLevel || client.player != ownerPlayer) {
            return "world or player changed during station room preparation";
        }
        String reason = contextProblem(client.level, client.player);
        if (reason != null) return reason;
        if (!nearPlayer(site, client.player.blockPosition())) return "player moved away from the station room";
        if (!loaded(client.level, site.below()) || !startingFloor.equals(client.level.getBlockState(site.below()))
                || !actions.safePlacementSupport(site.below())) {
            return "station room floor changed or is no longer safe";
        }
        if (!client.level.getBlockState(site.below()).getFluidState().isEmpty()) {
            return "station room floor became fluid-filled";
        }
        if (!loaded(client.level, site.above(2))
                || client.level.getBlockState(site.above(2)).getBlock() instanceof FallingBlock) {
            return "station room overhead is unloaded or unstable";
        }
        return preparationProblem(site, client.level, client.player);
    }

    private void refreshTarget(int index) {
        BlockPos position = targets[index];
        if (!loaded(ownerLevel, position)) throw new IllegalStateException("station room target chunk unloaded");
        BlockState current = ownerLevel.getBlockState(position);
        BlockState initial = startingStates[index];
        if (initial.canBeReplaced()) {
            if (!current.canBeReplaced()) throw new IllegalStateException("station room changed after preparation began");
            cleared[index] = true;
            return;
        }
        if (current.canBeReplaced()) {
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
        ownerLevel = null;
        ownerPlayer = null;
        startingFloor = null;
    }

    private String contextProblem(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null || client.gameMode == null) return "world unavailable";
        if (!Double.isFinite(player.getX()) || !Double.isFinite(player.getY()) || !Double.isFinite(player.getZ())) {
            return "player position is not finite";
        }
        var eye = player.getEyePosition();
        if (!Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)) {
            return "player eye position is not finite";
        }
        if (!player.isAlive() || !Float.isFinite(player.getHealth()) || player.getHealth() <= 0.0f) {
            return "player health is not safe";
        }
        if (client.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            return "station room preparation requires survival mode";
        }
        if (!player.onGround()) return "player must be on the ground";
        if (GameApi.screen(client) != null || player.containerMenu == null
                || player.containerMenu != player.inventoryMenu
                || !player.containerMenu.getCarried().isEmpty()) return "screen or inventory cursor is not safe";
        if (player.isUsingItem() || player.isPassenger()) return "player is using an item or riding";
        return null;
    }

    private static boolean nearPlayer(BlockPos candidate, BlockPos player) {
        return Math.abs((long) candidate.getX() - player.getX()) <= 2
                && Math.abs((long) candidate.getY() - player.getY()) <= 1
                && Math.abs((long) candidate.getZ() - player.getZ()) <= 2;
    }

    private static BlockPos playerSupport(LocalPlayer player) {
        return new BlockPos(floor(player.getX()), floor(player.getY() - 1.0e-5), floor(player.getZ()));
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static boolean loaded(ClientLevel level, BlockPos position) {
        return position.getY() >= level.getMinY() && position.getY() < level.getMaxY() && level.hasChunkAt(position);
    }

    private static boolean withinNativeReach(BlockPos position, LocalPlayer player) {
        var eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        if (!Double.isFinite(reach) || reach <= 0.0) return false;
        double dx = Math.max(position.getX() - eye.x, Math.max(0.0, eye.x - (position.getX() + 1.0)));
        double dy = Math.max(position.getY() - eye.y, Math.max(0.0, eye.y - (position.getY() + 1.0)));
        double dz = Math.max(position.getZ() - eye.z, Math.max(0.0, eye.z - (position.getZ() + 1.0)));
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private static boolean hasLivingObstruction(ClientLevel level, LocalPlayer player, BlockPos candidate) {
        AABB room = new AABB(candidate.getX(), candidate.getY(), candidate.getZ(),
                candidate.getX() + 1.0, candidate.getY() + 2.0, candidate.getZ() + 1.0);
        List<LivingEntity> occupants = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(LivingEntity.class), room,
                living -> living != player, occupants, 17);
        return occupants.size() >= 17 || !occupants.isEmpty();
    }

    private static boolean clearable(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF)
                || state.is(Blocks.GRANITE) || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE)
                || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.GRASS_BLOCK);
    }

    private static boolean hazardous(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE);
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
