package dev.lodekeeper.fabric.modern;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.world.level.entity.EntityTypeTest;

/** Recovers only the exact bot-owned station after its native contents are server-verified empty. */
final class OwnedStationRecoveryAction {
    private static final double DROP_RADIUS = 4.0;
    private static final long MAX_DURATION_NANOS = 20_000_000_000L;

    private enum Phase {
        IDLE, APPROACH, APPROACH_STOPPING, WAITING_FOR_HANDLER, WAITING_FOR_CONTENTS,
        CLOSING_HANDLER, MINING, PICKUP, FINISHING, COMPLETE, STOPPED
    }

    private enum StationKind {
        CRAFTING_TABLE(Blocks.CRAFTING_TABLE, "crafting table", 10),
        FURNACE(Blocks.FURNACE, "furnace", 3),
        SMOKER(Blocks.SMOKER, "smoker", 3),
        BLAST_FURNACE(Blocks.BLAST_FURNACE, "blast furnace", 3),
        STONECUTTER(Blocks.STONECUTTER, "stonecutter", 2);

        final Block block;
        final String name;
        final int nativeSlotCount;

        StationKind(Block block, String name, int nativeSlotCount) {
            this.block = block;
            this.name = name;
            this.nativeSlotCount = nativeSlotCount;
        }

        static StationKind forBlock(Block block) {
            for (StationKind kind : values()) if (kind.block == block) return kind;
            return null;
        }

        boolean matches(AbstractContainerMenu handler) {
            if (handler == null) return false;
            return switch (this) {
                case CRAFTING_TABLE -> handler.getClass() == CraftingMenu.class;
                case FURNACE -> handler.getClass() == net.minecraft.world.inventory.FurnaceMenu.class;
                case SMOKER -> handler.getClass() == net.minecraft.world.inventory.SmokerMenu.class;
                case BLAST_FURNACE -> handler.getClass() == net.minecraft.world.inventory.BlastFurnaceMenu.class;
                case STONECUTTER -> handler.getClass() == StonecutterMenu.class;
            };
        }

        boolean contentsEmpty(AbstractContainerMenu handler) {
            if (!matches(handler) || handler.slots.size() < nativeSlotCount) return false;
            for (int slot = 0; slot < nativeSlotCount; slot++) {
                if (!handler.getSlot(slot).getItem().isEmpty()) return false;
            }
            return true;
        }
    }

    private final Minecraft client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final MovementController movement;
    private final Predicate<BlockPos> mayBreak;

    private Phase phase = Phase.IDLE;
    private String status = "owned station recovery idle";
    private StationKind stationKind;
    private Block expectedBlock;
    private Item blockItem;
    private BlockPos ownedPosition;
    private Object ownerPlayer, ownerWorld;
    private AbstractContainerMenu originalHandler, stationHandler;
    private Object originalScreen, stationScreen;
    private ItemEntity drop;
    private Map<ItemEntity, Integer> startingNearbyDrops = Map.of();
    private int highestObservedDropCount;
    private int startingItemCount;
    private long startedAtNanos;
    private boolean miningStarted, contentsVerifiedEmpty, collectedBeforeDropObserved;

    OwnedStationRecoveryAction(Minecraft client, LodekeeperConfig config, PlayerActions actions,
                               MovementController movement, Predicate<BlockPos> mayBreak) {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.movement = Objects.requireNonNull(movement, "movement");
        this.mayBreak = Objects.requireNonNull(mayBreak, "mayBreak");
    }

    boolean begin(BlockPos position, Block block) {
        if (active()) {
            status = "owned station recovery is already active";
            return false;
        }
        clearOwnership();
        phase = Phase.IDLE;
        String reason = unsafeContextReason();
        if (reason != null) return refuse(reason);
        if (position == null || block == null) return refuse("no owned station position and block were supplied");
        stationKind = StationKind.forBlock(block);
        if (stationKind == null) return refuse("unsupported owned station block");
        blockItem = block.asItem();
        if (blockItem == Items.AIR) return refuse("owned station has no recoverable block item");
        if (!hasRoomForStation()) return refuse("inventory has no room for the recovered station");
        if (config.stationRecoveryRange < 1) return refuse("station recovery range is not configured");

        BlockPos candidate = new BlockPos(position.getX(), position.getY(), position.getZ());
        if (!isExpectedBlock(candidate, block)) return refuse("recorded owned position is not a loaded matching station");
        if (!withinRecoveryRange(candidate)) {
            return refuse("owned station is farther than the configured recovery range");
        }
        try {
            if (!mayBreak.test(candidate)) return refuse("owned station is inside a protected claim");
        } catch (RuntimeException failure) {
            return refuse("owned station claim check failed: " + diagnostic(failure));
        }
        if (!originalContextSafe()) return refuse("inventory screen or cursor is not safe");

        ownedPosition = candidate;
        expectedBlock = block;
        ownerPlayer = client.player;
        ownerWorld = client.level;
        originalHandler = client.player.containerMenu;
        originalScreen = currentScreen();
        startingItemCount = actions.count(blockItem);
        startingNearbyDrops = Map.of();
        highestObservedDropCount = 0;
        drop = null;
        miningStarted = contentsVerifiedEmpty = collectedBeforeDropObserved = false;
        startedAtNanos = System.nanoTime();

        try {
            movement.checkAirRecoveryOwnership();
            if (actions.hit(candidate) == null) {
                phase = Phase.APPROACH;
                status = "approaching the recorded owned " + stationKind.name;
                movement.startInteraction(candidate);
                return true;
            }
            movement.stop();
            phase = Phase.APPROACH_STOPPING;
            status = "stopping movement before opening the owned " + stationKind.name;
            return true;
        } catch (RuntimeException failure) {
            throw abort("could not start owned station recovery: " + diagnostic(failure), failure);
        }
    }

    boolean active() {
        return phase == Phase.APPROACH || phase == Phase.APPROACH_STOPPING
                || phase == Phase.WAITING_FOR_HANDLER || phase == Phase.WAITING_FOR_CONTENTS
                || phase == Phase.CLOSING_HANDLER || phase == Phase.MINING
                || phase == Phase.PICKUP || phase == Phase.FINISHING;
    }

    boolean tick() {
        if (phase == Phase.COMPLETE) return true;
        if (!active()) return false;
        try {
            if (System.nanoTime() - startedAtNanos >= MAX_DURATION_NANOS) {
                throw new IllegalStateException("owned station recovery exceeded 20 seconds");
            }
            String reason = unsafeContextReason();
            if (reason != null) throw new IllegalStateException(reason);
            movement.checkAirRecoveryOwnership();
            return switch (phase) {
                case APPROACH -> tickApproach();
                case APPROACH_STOPPING -> tickApproachStopping();
                case WAITING_FOR_HANDLER -> tickWaitingForHandler();
                case WAITING_FOR_CONTENTS -> tickWaitingForContents();
                case CLOSING_HANDLER -> tickClosingHandler();
                case MINING -> tickMining();
                case PICKUP -> tickPickup();
                case FINISHING -> tickFinishing();
                default -> false;
            };
        } catch (RuntimeException failure) {
            if (phase == Phase.STOPPED) throw failure;
            throw abort("owned station recovery failed: " + diagnostic(failure), failure);
        }
    }

    void stop() {
        if (!active()) {
            clearOwnership();
            return;
        }
        actions.cancel();
        if (phase != Phase.CLOSING_HANDLER && !manualInput()) closeOwnedEmptyHandler();
        boolean handlerLeftOpen = ownsStationContext();
        boolean cancelled = cancelMovement();
        clearOwnership();
        phase = Phase.STOPPED;
        status = cancelled ? "owned station recovery stopped"
                : "owned station recovery stopped; movement cancellation remains pending";
        if (handlerLeftOpen) status += "; owned station handler left open for inspection";
    }

    String status() { return status; }
    BlockPos position() { return ownedPosition; }
    Block block() { return expectedBlock; }

    private boolean tickApproach() {
        requireExpectedTarget();
        requireOriginalContext();
        if (!withinRecoveryRange(ownedPosition)) {
            throw new IllegalStateException("owned station approach moved beyond the configured recovery range");
        }
        if (actions.hit(ownedPosition) != null) {
            movement.stop();
            phase = Phase.APPROACH_STOPPING;
            status = "stopping movement before opening the owned " + stationKind.name;
            return false;
        }
        if (movement.tick()) {
            movement.stop();
            phase = Phase.APPROACH_STOPPING;
            status = "checking reach after approaching the owned " + stationKind.name;
        }
        return false;
    }

    private boolean tickApproachStopping() {
        requireExpectedTarget();
        requireOriginalContext();
        if (!withinRecoveryRange(ownedPosition)) {
            throw new IllegalStateException("owned station approach moved beyond the configured recovery range");
        }
        if (!movement.finishCancellation()) return false;
        if (actions.hit(ownedPosition) == null) {
            throw new IllegalStateException("movement stopped without reach to the owned station");
        }
        openOwnedHandler();
        return false;
    }

    private void openOwnedHandler() {
        requireExpectedTarget();
        requireOriginalContext();
        if (!actions.use(ownedPosition)) throw new IllegalStateException("native station open was refused");
        phase = Phase.WAITING_FOR_HANDLER;
        status = "waiting for the owned " + stationKind.name + " handler";
    }

    private boolean tickWaitingForHandler() {
        requireExpectedTarget();
        if (client.player.containerMenu == originalHandler && currentScreen() == originalScreen) {
            status = "waiting for the server to open the owned " + stationKind.name;
            return false;
        }
        AbstractContainerMenu current = client.player.containerMenu;
        if (!stationKind.matches(current) || currentScreen() == null) {
            throw new IllegalStateException("station open changed the player's handler unexpectedly; leaving it open");
        }
        stationHandler = current;
        stationScreen = currentScreen();
        phase = Phase.WAITING_FOR_CONTENTS;
        return tickWaitingForContents();
    }

    private boolean tickWaitingForContents() {
        requireExpectedTarget();
        requireStationContext();
        if (!stationHandler.getCarried().isEmpty()) {
            throw new IllegalStateException("station cursor is occupied; leaving the owned handler open");
        }
        if (!(stationHandler instanceof OwnedClickReceipts.Receipt receipt)) {
            throw new IllegalStateException("owned station handler has no server contents receipt; leaving it open");
        }
        if (receipt.lodekeeper$contentsSequence() <= 0) {
            status = "waiting for the server contents receipt from the owned " + stationKind.name;
            return false;
        }
        if (!stationKind.contentsEmpty(stationHandler)) {
            throw new IllegalStateException("owned " + stationKind.name + " contains items; leaving it open without moving them");
        }
        contentsVerifiedEmpty = true;
        if (!closeOwnedEmptyHandler()) {
            throw new IllegalStateException("could not safely close the verified empty owned station handler");
        }
        phase = Phase.CLOSING_HANDLER;
        status = "closing the verified empty owned " + stationKind.name + " handler";
        return false;
    }

    private boolean tickClosingHandler() {
        requireExpectedTarget();
        if (client.player.containerMenu == originalHandler && currentScreen() == originalScreen) {
            phase = Phase.MINING;
            status = "mining the verified empty owned " + stationKind.name;
            return tickMining();
        }
        if (ownsStationContext()) {
            requireStationContentsStillEmpty();
            status = "waiting for the owned station handler to close";
            return false;
        }
        throw new IllegalStateException("station handler changed during close; leaving the new handler open");
    }

    private boolean tickMining() {
        requireOriginalContext();
        if (miningStarted && client.level.hasChunkAt(ownedPosition)
                && client.level.getBlockState(ownedPosition).isAir()) {
            actions.cancel();
            phase = Phase.PICKUP;
            status = "checking for the owned " + stationKind.name + " drop";
            return false;
        }
        requireExpectedTarget();
        if (!withinRecoveryRange(ownedPosition)) {
            throw new IllegalStateException("owned station is beyond the configured recovery range");
        }
        if (actions.hit(ownedPosition) == null) {
            throw new IllegalStateException("owned station is no longer reachable");
        }
        if (!miningStarted) {
            startingNearbyDrops = snapshotNearbyDrops(ownedPosition);
            miningStarted = true;
        }
        boolean allowed;
        try {
            allowed = mayBreak.test(ownedPosition);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("owned station claim check failed: " + diagnostic(failure), failure);
        }
        if (!allowed) throw new IllegalStateException("owned station is inside a protected claim");
        if (!hasRoomForStation()) throw new IllegalStateException("inventory has no room for the recovered station");
        if (!actions.mine(ownedPosition, null)) {
            throw new IllegalStateException("native mining refused: " + actions.mineFailure());
        }
        return false;
    }

    private boolean tickPickup() {
        requireClearedTarget();
        requireOriginalContext();
        if (!miningStarted) throw new IllegalStateException("owned station disappeared before recovery started mining it");
        if (drop == null) {
            drop = nearestBlockDrop();
            if (drop == null) {
                if (hasInventoryGain()) {
                    collectedBeforeDropObserved = true;
                    return startFinishing();
                }
                status = "waiting for the owned " + stationKind.name + " drop";
                return false;
            }
            highestObservedDropCount = drop.getItem().getCount();
            status = "collecting the owned " + stationKind.name + " drop";
            movement.startPickup(drop);
            return false;
        }
        boolean collectedDrop = dropWasCollected();
        if (hasInventoryGain()) {
            if (!collectedDrop) {
                throw new IllegalStateException("station item count increased while its owned drop remained untouched");
            }
            return startFinishing();
        }
        if (!drop.isAlive()) {
            throw new IllegalStateException("owned station drop disappeared without an inventory receipt");
        }
        if (!movement.tick()) return false;
        if (!hasInventoryGain() || !dropWasCollected()) {
            throw new IllegalStateException("pickup route ended without inventory and entity receipts for the owned station");
        }
        return startFinishing();
    }

    private boolean tickFinishing() {
        requireClearedTarget();
        requireOriginalContext();
        actions.cancel();
        movement.stop();
        if (!movement.finishCancellation()) {
            status = "station item gained; finishing movement cancellation";
            return false;
        }
        if (!hasInventoryGain() || !dropWasCollected()) {
            throw new IllegalStateException("owned station recovery lost its inventory or entity receipt");
        }
        ownerPlayer = ownerWorld = null;
        phase = Phase.COMPLETE;
        status = "recovered the owned " + stationKind.name;
        return true;
    }

    private boolean startFinishing() {
        actions.cancel();
        movement.stop();
        phase = Phase.FINISHING;
        status = "station item gained; confirming safe movement cancellation";
        return tickFinishing();
    }

    private ItemEntity nearestBlockDrop() {
        double x = ownedPosition.getX() + 0.5;
        double y = ownedPosition.getY() + 0.5;
        double z = ownedPosition.getZ() + 0.5;
        AABB area = new AABB(x - DROP_RADIUS, y - DROP_RADIUS, z - DROP_RADIUS,
                x + DROP_RADIUS, y + DROP_RADIUS, z + DROP_RADIUS);
        return client.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), area, item ->
                        item.isAlive() && item.getItem().is(blockItem)
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
                candidate.isAlive() && candidate.getItem().is(blockItem))) {
            counts.put(item, item.getItem().getCount());
        }
        return counts;
    }

    private boolean dropWasCollected() {
        if (drop == null) return collectedBeforeDropObserved;
        if (!drop.isAlive()) return true;
        int count = drop.getItem().getCount();
        boolean decreased = count < highestObservedDropCount;
        highestObservedDropCount = Math.max(highestObservedDropCount, count);
        return decreased;
    }

    private boolean hasRoomForStation() {
        if (client.player == null || blockItem == null) return false;
        for (int slot = 0; slot < 36; slot++) {
            var stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty() || stack.is(blockItem) && stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }

    private boolean hasInventoryGain() { return actions.count(blockItem) > startingItemCount; }

    private boolean isExpectedBlock(BlockPos position, Block block) {
        return client.level != null && client.level.hasChunkAt(position)
                && client.level.getBlockState(position).is(block);
    }

    private boolean isExpectedTarget(BlockPos position) {
        return isExpectedBlock(position, expectedBlock);
    }

    private void requireExpectedTarget() {
        if (!isExpectedTarget(ownedPosition)) {
            throw new IllegalStateException("recorded owned station changed or unloaded before it was mined");
        }
    }

    private void requireClearedTarget() {
        if (client.level == null || !client.level.hasChunkAt(ownedPosition)
                || !client.level.getBlockState(ownedPosition).isAir()) {
            throw new IllegalStateException("owned station position changed or unloaded after mining");
        }
    }

    private boolean withinRecoveryRange(BlockPos position) {
        int range = config.stationRecoveryRange;
        if (range < 1 || client.player == null) return false;
        double dx = position.getX() + 0.5 - client.player.getX();
        double dy = position.getY() + 0.5 - client.player.getY();
        double dz = position.getZ() + 0.5 - client.player.getZ();
        return dx * dx + dy * dy + dz * dz <= (double) range * range;
    }

    private String unsafeContextReason() {
        if (client.player == null || client.level == null || client.gameMode == null) return "world unavailable";
        if (active() && (ownerPlayer != client.player || ownerWorld != client.level)) {
            return "player or world changed during owned station recovery";
        }
        if (manualInput()) return "manual player input has priority";
        var player = client.player;
        if (!player.isAlive() || player.isCreative() || player.isSpectator()) {
            return "owned station recovery requires survival play";
        }
        if (player.isUnderWater()) return "owned station recovery waits for breathable air";
        if (!config.allowBreaking) return "block breaking is disabled";
        return null;
    }

    private boolean originalContextSafe() {
        return GameApi.screen(client) == null && client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty();
    }

    private void requireOriginalContext() {
        if (client.player.containerMenu != originalHandler || currentScreen() != originalScreen
                || !client.player.containerMenu.getCarried().isEmpty()) {
            throw new IllegalStateException("player handler or cursor changed; leaving the current screen open");
        }
    }

    private boolean ownsStationContext() {
        return stationHandler != null && client.player.containerMenu == stationHandler
                && currentScreen() == stationScreen;
    }

    private void requireStationContext() {
        if (!ownsStationContext()) {
            throw new IllegalStateException("owned station handler or screen changed; leaving the current screen open");
        }
    }

    private void requireStationContentsStillEmpty() {
        requireStationContext();
        if (!contentsVerifiedEmpty || !stationHandler.getCarried().isEmpty()
                || !stationKind.contentsEmpty(stationHandler)) {
            throw new IllegalStateException("owned station contents changed; leaving the handler open");
        }
    }

    private boolean closeOwnedEmptyHandler() {
        if (!ownsStationContext() || !contentsVerifiedEmpty) return false;
        try {
            requireStationContentsStillEmpty();
            client.player.closeContainer();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Object currentScreen() { return GameApi.screen(client); }

    private boolean manualInput() {
        var options = client.options;
        return options.keyAttack.isDown() || options.keyUse.isDown()
                || options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown() || options.keyShift.isDown() || options.keySprint.isDown();
    }

    private boolean refuse(String reason) {
        phase = Phase.STOPPED;
        status = reason.length() > 180 ? reason.substring(0, 180) : reason;
        return false;
    }

    private IllegalStateException abort(String reason, RuntimeException cause) {
        actions.cancel();
        boolean cancelled = cancelMovement();
        phase = Phase.STOPPED;
        if (!cancelled) reason += "; movement cancellation remains pending";
        status = reason.length() > 180 ? reason.substring(0, 180) : reason;
        return new IllegalStateException(status, cause);
    }

    private void clearOwnership() {
        stationKind = null;
        expectedBlock = null;
        blockItem = null;
        ownedPosition = null;
        ownerPlayer = ownerWorld = null;
        originalHandler = stationHandler = null;
        originalScreen = stationScreen = null;
        drop = null;
        startingNearbyDrops = Map.of();
        highestObservedDropCount = startingItemCount = 0;
        miningStarted = contentsVerifiedEmpty = collectedBeforeDropObserved = false;
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
