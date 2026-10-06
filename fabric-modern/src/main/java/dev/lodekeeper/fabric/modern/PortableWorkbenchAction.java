package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

/** Recovers a crafting table only from the exact self-placed position supplied by the owner. */
final class PortableWorkbenchAction {
    private static final double DROP_RADIUS = 4.0;
    private static final double MAX_APPROACH_DISTANCE_SQUARED = 16.0 * 16.0;
    private static final long MAX_DURATION_NANOS = 20_000_000_000L;

    private enum Phase { IDLE, APPROACH, APPROACH_STOPPING, MINING, PICKUP, FINISHING, COMPLETE, STOPPED }

    private final Minecraft client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final MovementController movement;
    private final Item tableItem = Blocks.CRAFTING_TABLE.asItem();

    private Phase phase = Phase.IDLE;
    private String status = "portable workbench idle";
    private BlockPos ownedPosition;
    private Object ownerPlayer, ownerWorld;
    private ItemEntity drop;
    private Map<ItemEntity, Integer> startingNearbyDrops = Map.of();
    private int highestObservedDropCount;
    private int startingTableCount;
    private long startedAtNanos;

    PortableWorkbenchAction(Minecraft client, LodekeeperConfig config,
                            PlayerActions actions, MovementController movement) {
        this.client = client;
        this.config = config;
        this.actions = actions;
        this.movement = movement;
    }

    boolean begin(BlockPos ownedPosition) {
        if (active()) {
            status = "portable workbench action is already active";
            return false;
        }
        phase = Phase.IDLE;
        String reason = unsafeContextReason();
        if (reason != null) {
            status = reason;
            return false;
        }
        if (ownedPosition == null) {
            status = "no owned crafting-table position was supplied";
            return false;
        }
        BlockPos candidate = ownedPosition.immutable();
        if (!client.level.hasChunkAt(candidate)
                || !client.level.getBlockState(candidate).is(Blocks.CRAFTING_TABLE)) {
            status = "the recorded owned position is not a loaded crafting table";
            return false;
        }
        if (!withinApproachDistance(candidate)) {
            status = "the owned crafting table is farther than 16 blocks away";
            return false;
        }

        this.ownedPosition = candidate;
        ownerPlayer = client.player;
        ownerWorld = client.level;
        drop = null;
        startingNearbyDrops = snapshotNearbyDrops(candidate);
        highestObservedDropCount = 0;
        startingTableCount = actions.count(tableItem);
        startedAtNanos = System.nanoTime();
        try {
            movement.checkAirRecoveryOwnership();
            if (actions.hit(candidate) == null) {
                phase = Phase.APPROACH;
                status = "approaching the recorded owned crafting table";
                movement.startInteraction(candidate);
                return true;
            }
            phase = Phase.MINING;
            status = "mining the recorded owned crafting table";
            if (!actions.mine(candidate, null)) {
                String reasonText = "native mining refused: " + actions.mineFailure();
                actions.cancel();
                clearOwnership();
                phase = Phase.STOPPED;
                status = reasonText;
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            throw abort("could not start owned crafting-table recovery: " + diagnostic(failure), failure);
        }
    }

    boolean active() {
        return phase == Phase.APPROACH || phase == Phase.APPROACH_STOPPING
                || phase == Phase.MINING || phase == Phase.PICKUP || phase == Phase.FINISHING;
    }

    boolean tick() {
        if (phase == Phase.COMPLETE) return true;
        if (!active()) return false;
        try {
            if (System.nanoTime() - startedAtNanos >= MAX_DURATION_NANOS) {
                throw new IllegalStateException("portable workbench recovery exceeded 20 seconds");
            }
            String reason = unsafeContextReason();
            if (reason != null) throw new IllegalStateException(reason);
            movement.checkAirRecoveryOwnership();
            return switch (phase) {
                case APPROACH -> tickApproach();
                case APPROACH_STOPPING -> tickApproachStopping();
                case MINING -> tickMining();
                case PICKUP -> tickPickup();
                case FINISHING -> tickFinishing();
                default -> false;
            };
        } catch (RuntimeException failure) {
            if (phase == Phase.STOPPED) throw failure;
            throw abort("portable workbench recovery failed: " + diagnostic(failure), failure);
        }
    }

    void stop() {
        if (!active()) { clearOwnership(); return; }
        actions.cancel();
        boolean cancelled = cancelMovement();
        clearOwnership();
        phase = Phase.STOPPED;
        status = cancelled ? "portable workbench recovery stopped"
                : "portable workbench recovery stopped; movement cancellation remains pending";
    }

    String status() { return status; }

    private boolean tickApproach() {
        requireApproachTarget();
        if (actions.hit(ownedPosition) != null) {
            movement.stop();
            phase = Phase.APPROACH_STOPPING;
            status = "finishing movement before mining the owned crafting table";
            return tickApproachStopping();
        }
        if (movement.tick()) {
            movement.stop();
            phase = Phase.APPROACH_STOPPING;
            status = "checking reach after approaching the owned crafting table";
            return tickApproachStopping();
        }
        return false;
    }

    private boolean tickApproachStopping() {
        requireApproachTarget();
        if (!movement.finishCancellation()) return false;
        if (actions.hit(ownedPosition) == null) {
            throw new IllegalStateException("approach ended without reach to the owned crafting table");
        }
        phase = Phase.MINING;
        status = "mining the recorded owned crafting table";
        return tickMining();
    }

    private void requireApproachTarget() {
        if (!client.level.hasChunkAt(ownedPosition)
                || !client.level.getBlockState(ownedPosition).is(Blocks.CRAFTING_TABLE)) {
            throw new IllegalStateException("the owned crafting table changed or unloaded during approach");
        }
        if (!withinApproachDistance(ownedPosition)) {
            throw new IllegalStateException("owned crafting-table approach moved beyond 16 blocks");
        }
    }

    private boolean withinApproachDistance(BlockPos position) {
        double dx = position.getX() + 0.5 - client.player.getX();
        double dy = position.getY() + 0.5 - client.player.getY();
        double dz = position.getZ() + 0.5 - client.player.getZ();
        return dx * dx + dy * dy + dz * dz <= MAX_APPROACH_DISTANCE_SQUARED;
    }

    private boolean tickMining() {
        if (!client.level.hasChunkAt(ownedPosition)) {
            throw new IllegalStateException("owned crafting-table chunk unloaded during mining");
        }
        var state = client.level.getBlockState(ownedPosition);
        if (state.isAir()) {
            actions.cancel();
            phase = Phase.PICKUP;
            status = "checking for the owned crafting-table drop";
            return false;
        }
        if (!state.is(Blocks.CRAFTING_TABLE)) {
            throw new IllegalStateException("the recorded position changed before the owned table was mined");
        }
        if (actions.hit(ownedPosition) == null) {
            throw new IllegalStateException("the owned crafting table is no longer reachable");
        }
        if (!actions.mine(ownedPosition, null)) {
            throw new IllegalStateException("native mining refused: " + actions.mineFailure());
        }
        return false;
    }

    private boolean tickPickup() {
        if (!client.level.hasChunkAt(ownedPosition)) {
            throw new IllegalStateException("owned crafting-table chunk unloaded before pickup");
        }
        if (drop == null) {
            drop = nearestTableDrop();
            if (drop == null) {
                if (hasInventoryGain()) return startFinishing();
                status = "waiting for the owned crafting-table drop";
                return false;
            }
            highestObservedDropCount = drop.getItem().getCount();
            status = "collecting the crafting-table drop at the owned position";
            movement.startPickup(drop);
            return false;
        }

        if (hasInventoryGain()) {
            if (!dropWasCollected()) {
                throw new IllegalStateException("crafting-table count increased while the owned drop remained untouched");
            }
            return startFinishing();
        }
        if (!drop.isAlive()) {
            throw new IllegalStateException("owned crafting-table drop disappeared without an inventory receipt");
        }

        dropWasCollected();
        if (!movement.tick()) return false;
        if (!hasInventoryGain() || !dropWasCollected()) {
            throw new IllegalStateException("pickup route ended without collecting the owned crafting-table drop");
        }
        return startFinishing();
    }

    private boolean tickFinishing() {
        actions.cancel();
        movement.stop();
        if (!movement.finishCancellation()) {
            status = "crafting table gained; finishing movement cancellation";
            return false;
        }
        if (!hasInventoryGain()) {
            throw new IllegalStateException("crafting-table inventory count returned to its starting value");
        }
        clearOwnership();
        phase = Phase.COMPLETE;
        status = "recovered the owned crafting table";
        return true;
    }

    private boolean startFinishing() {
        actions.cancel();
        movement.stop();
        phase = Phase.FINISHING;
        status = "crafting table gained; confirming safe movement cancellation";
        return tickFinishing();
    }

    private ItemEntity nearestTableDrop() {
        double x = ownedPosition.getX() + 0.5;
        double y = ownedPosition.getY() + 0.5;
        double z = ownedPosition.getZ() + 0.5;
        AABB area = new AABB(x - DROP_RADIUS, y - DROP_RADIUS, z - DROP_RADIUS,
                x + DROP_RADIUS, y + DROP_RADIUS, z + DROP_RADIUS);
        return client.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), area, item ->
                        item.isAlive() && item.getItem().is(tableItem)
                                && item.getItem().getCount() > startingNearbyDrops.getOrDefault(item, 0))
                .stream()
                .min(Comparator.comparingDouble(item -> item.distanceToSqr(x, y, z)))
                .orElse(null);
    }

    private Map<ItemEntity, Integer> snapshotNearbyDrops(BlockPos position) {
        double x = position.getX() + 0.5;
        double y = position.getY() + 0.5;
        double z = position.getZ() + 0.5;
        AABB area = new AABB(x - DROP_RADIUS, y - DROP_RADIUS, z - DROP_RADIUS,
                x + DROP_RADIUS, y + DROP_RADIUS, z + DROP_RADIUS);
        Map<ItemEntity, Integer> counts = new IdentityHashMap<>();
        for (ItemEntity item : client.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), area, candidate ->
                candidate.isAlive() && candidate.getItem().is(tableItem))) {
            counts.put(item, item.getItem().getCount());
        }
        return counts;
    }

    private boolean dropWasCollected() {
        if (drop == null) return false;
        if (!drop.isAlive()) return true;
        int count = drop.getItem().getCount();
        boolean decreased = count < highestObservedDropCount;
        highestObservedDropCount = Math.max(highestObservedDropCount, count);
        return decreased;
    }

    private boolean hasInventoryGain() {
        return actions.count(tableItem) > startingTableCount;
    }

    private String unsafeContextReason() {
        if (client.player == null || client.level == null || client.gameMode == null) return "world unavailable";
        if (active() && (ownerPlayer != client.player || ownerWorld != client.level)) {
            return "player or world changed during portable workbench recovery";
        }
        if (manualInput()) return "manual player input has priority";
        var player = client.player;
        if (!player.isAlive() || player.isCreative() || player.isSpectator()) {
            return "portable workbench recovery requires survival play";
        }
        if (player.isUnderWater()) return "portable workbench recovery waits for breathable air";
        if (!config.allowBreaking) return "block breaking is disabled";
        if (GameApi.screen(client) != null || player.containerMenu == null
                || player.containerMenu != player.inventoryMenu
                || !player.containerMenu.getCarried().isEmpty()) return "inventory screen or cursor is not safe";
        return null;
    }

    private boolean manualInput() {
        var options = client.options;
        return options.keyAttack.isDown() || options.keyUse.isDown()
                || options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown() || options.keyShift.isDown() || options.keySprint.isDown();
    }

    private IllegalStateException abort(String reason, RuntimeException cause) {
        actions.cancel();
        boolean cancelled = cancelMovement();
        clearOwnership();
        phase = Phase.STOPPED;
        if (!cancelled) reason += "; movement cancellation remains pending";
        status = reason.length() > 180 ? reason.substring(0, 180) : reason;
        return new IllegalStateException(status, cause);
    }

    private void clearOwnership() {
        ownedPosition = null;
        ownerPlayer = ownerWorld = null;
        drop = null;
        startingNearbyDrops = Map.of();
    }

    private boolean cancelMovement() {
        try {
            movement.checkAirRecoveryOwnership();
            movement.stop();
            return movement.finishCancellation();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String diagnostic(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
