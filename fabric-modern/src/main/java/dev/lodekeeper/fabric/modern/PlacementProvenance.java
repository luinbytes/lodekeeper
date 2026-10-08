package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.OwnedStationLedger;
import dev.lodekeeper.core.WorldScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Owns server-receipt attribution for one station placement at a time. */
public final class PlacementProvenance {
    private static final long PLACEMENT_TIMEOUT_TICKS = 40;
    private static final int MAX_TRACKED_INVENTORY_ITEMS = 8_192;
    private static final ItemId AIR = ItemId.parse("minecraft:air");
    private static volatile PlacementProvenance active;

    private final Minecraft client;
    private final WorldProtection protection;
    private final StackCount[] serverMainInventory = new StackCount[PlacementInventoryProfile.MAIN_SLOTS];
    private final Map<ItemId, Long> inventoryIncreaseSequences = new HashMap<>();
    private boolean inventoryIncreaseTrackingOverflowed;
    private OwnedStationLedger ledger;
    private Binding binding;
    private PendingPlacement pending;
    private PlacementWarmup warmup;
    private long generation = -1;
    private long receiptSequence;
    private long clientTicks;
    private boolean inventoryComplete;
    private int receiptDiagnostics;
    private int stationBlockDiagnostics;
    private int retainedStationDiagnostics;
    private final java.util.Map<OwnedStationLedger.BlockPosition, BlockReceipt> recentStationBlocks = new java.util.LinkedHashMap<>();
    private boolean placementQuarantined;
    record ServerBlockReceipt(OwnedStationLedger.Session session, long sequence, BlockPos position, BlockState state) { }
    private java.util.function.Consumer<ServerBlockReceipt> blockReceiptObserver = receipt -> { };

    void observeServerBlocks(java.util.function.Consumer<ServerBlockReceipt> observer) {
        blockReceiptObserver = Objects.requireNonNull(observer, "observer");
    }

    long serverReceiptSequence() { return receiptSequence; }

    boolean confirmedInventoryReady() { return refreshBinding() && inventoryComplete && inventoryMatchesLocal(); }

    PlacementProvenance(Minecraft client, WorldProtection protection) {
        this.client = Objects.requireNonNull(client, "client");
        this.protection = Objects.requireNonNull(protection, "protection");
        PlacementProvenance previous = active;
        if (previous != null && previous != this) previous.cancelPending();
        active = this;
    }

    public static void serverBlockUpdate(ClientPacketListener source, BlockPos position, BlockState state) {
        PlacementProvenance receiver = active;
        if (receiver != null) receiver.receiveBlockUpdate(source, position, state);
    }

    public static void serverChunkDelta(
            ClientPacketListener source, ClientboundSectionBlocksUpdatePacket packet) {
        PlacementProvenance receiver = active;
        if (receiver != null && receiver.sourceCurrent(source)) {
            receiver.cancelPendingIfContextChanged();
            packet.runUpdates((position, state) -> receiver.applyBlockUpdate(position, state));
        }
    }

    public static void serverInventoryContents(
            ClientPacketListener source, int containerId, List<ItemStack> contents) {
        PlacementProvenance receiver = active;
        if (receiver != null) receiver.receiveInventoryContents(source, containerId, contents);
    }

    public static void serverInventorySlot(
            ClientPacketListener source, int containerId, int slot, ItemStack stack) {
        PlacementProvenance receiver = active;
        if (receiver != null) receiver.receiveInventorySlot(source, containerId, slot, stack);
    }

    public static void serverPlayerInventorySlot(
            ClientPacketListener source, int slot, ItemStack stack) {
        PlacementProvenance receiver = active;
        if (receiver != null) receiver.receiveDirectInventorySlot(source, slot, stack);
    }

    public static void serverWorldUnloaded(ClientPacketListener source) {
        PlacementProvenance receiver = active;
        if (receiver != null && receiver.binding != null && receiver.binding.networkHandler == source) {
            receiver.cancelPending();
            receiver.warmup = null;
            receiver.clearInventory();
        }
    }

    void tick() {
        clientTicks = Math.addExact(clientTicks, 1);
        refreshBinding();
        refreshRemovalContinuity();
        if (pending != null && (!pendingContextCurrent(pending) || clientTicks >= pending.deadlineTick)) {
            cancelPending();
        }
        if (warmup != null && !warmup.expired) {
            if (!warmupContextCurrent(warmup)) warmup = null;
            else if (clientTicks >= warmup.deadlineTick) warmup.expired = true;
        }
    }

    PlacementReservation reservePlacement(
            long jobToken, BlockPos destination, Block station) {
        if (jobToken <= 0 || destination == null || station == null || !refreshBinding()) {
            return PlacementReservation.rejected();
        }
        if (placementQuarantined) return PlacementReservation.quarantined();
        if (pending != null) return PlacementReservation.rejected();
        LocalPlayer player = client.player;
        if (player == null || player.getHealth() <= 0.0f || GameApi.screen(client) != null
                || player.containerMenu != player.inventoryMenu) {
            warmup = null;
            return PlacementReservation.rejected();
        }

        int selectedSlot = player.getInventory().getSelectedSlot();
        if (selectedSlot < 0 || selectedSlot >= 9) {
            warmup = null;
            return PlacementReservation.rejected();
        }
        ItemId stationItemId = itemId(station.asItem());
        ItemId expectedBlockId = ItemId.parse(BuiltInRegistries.BLOCK.getKey(station).toString());
        OwnedStationLedger.BlockPosition destinationPosition = new OwnedStationLedger.BlockPosition(
                destination.getX(), destination.getY(), destination.getZ());
        PlacementWarmup nextWarmup = new PlacementWarmup(jobToken, destinationPosition, expectedBlockId,
                stationItemId, binding, player.containerMenu, selectedSlot,
                Math.addExact(clientTicks, PLACEMENT_TIMEOUT_TICKS));
        if (warmup != null && !warmup.sameAttempt(nextWarmup)) warmup = null;
        if (warmup != null && warmup.expired) return PlacementReservation.expired();
        if (!inventoryComplete || !inventoryMatchesLocal()) return waitForInventory(nextWarmup);

        StackCount selected = serverMainInventory[selectedSlot];
        if (selected == null || !stationItemId.equals(selected.itemId) || selected.count < 1) {
            if (countItem(stationItemId, serverMainInventory) > 0) return waitForInventory(nextWarmup);
            warmup = null;
            return PlacementReservation.rejected();
        }
        int startingCount = countItem(stationItemId, serverMainInventory);
        if (startingCount < 1) {
            warmup = null;
            return PlacementReservation.rejected();
        }

        OwnedStationLedger.BeginPlacementResult result = ledger.tryBeginPlacement(
                binding.session, jobToken,
                destinationPosition,
                expectedBlockId, stationItemId, receiptSequence, startingCount);
        if (!result.accepted()) {
            warmup = null;
            return PlacementReservation.rejected();
        }

        OwnedStationLedger.PlacementTicket ticket = result.ticket().orElseThrow();
        pending = new PendingPlacement(ticket, binding, player.containerMenu, selectedSlot,
                stationItemId, startingCount, selected, Math.addExact(clientTicks, PLACEMENT_TIMEOUT_TICKS));
        warmup = null;
        return PlacementReservation.reserved(ticket);
    }

    private PlacementReservation waitForInventory(PlacementWarmup requested) {
        if (warmup == null) {
            warmup = requested;
            receiptDiagnostic("placement wait " + inventoryReadiness());
        }
        return PlacementReservation.notReady();
    }

    void interactionRejected(OwnedStationLedger.PlacementTicket ticket) {
        if (pending != null && pending.ticket == ticket) cancelPending();
    }

    void cancelPending() {
        expirePendingTicket();
        warmup = null;
    }

    private void expirePendingTicket() {
        if (pending != null) {
            placementQuarantined = true;
            if (ledger != null) ledger.expirePending(pending.binding.session, pending.ticket);
        }
        pending = null;
    }

    void manualTakeover() {
        recentStationBlocks.replaceAll((cell, receipt) -> revokeRemovalContinuity(cell, receipt, "manual-takeover"));
        cancelPending();
    }

    Optional<ReservationStatus> readinessStatus(long jobToken, BlockPos destination, Block station) {
        if (!refreshBinding()) return Optional.empty();
        if (placementQuarantined) return Optional.of(ReservationStatus.QUARANTINED);
        if (warmup == null || destination == null || station == null) return Optional.empty();
        OwnedStationLedger.BlockPosition position = new OwnedStationLedger.BlockPosition(
                destination.getX(), destination.getY(), destination.getZ());
        if (warmup.jobToken != jobToken || !warmup.position.equals(position)
                || !warmup.expectedBlockId.equals(ItemId.parse(
                        BuiltInRegistries.BLOCK.getKey(station).toString()))) return Optional.empty();
        return Optional.of(warmup.expired ? ReservationStatus.EXPIRED : ReservationStatus.NOT_READY);
    }

    void forgetChunk(ClientLevel sourceWorld, int chunkX, int chunkZ) {
        if (binding == null || binding.world != sourceWorld) return;
        recentStationBlocks.entrySet().removeIf(entry -> {
            var cell = entry.getKey();
            if ((cell.x() >> 4) != chunkX || (cell.z() >> 4) != chunkZ) return false;
            removalDiagnostic("chunk-unload", cell, entry.getValue());
            return true;
        });
        if (ledger == null || !ledger.currentSession().equals(binding.session)) return;
        if (pending != null) {
            OwnedStationLedger.BlockPosition position = pending.ticket.intent().position();
            if ((position.x() >> 4) == chunkX && (position.z() >> 4) == chunkZ) cancelPending();
        }
        if (warmup != null && (warmup.position.x() >> 4) == chunkX && (warmup.position.z() >> 4) == chunkZ) {
            warmup = null;
        }
        for (OwnedStationLedger.StationRecord record : ledger.records()) {
            OwnedStationLedger.BlockPosition position = record.position();
            if ((position.x() >> 4) == chunkX && (position.z() >> 4) == chunkZ) {
                ledger.forget(binding.session, record);
            }
        }
    }

    void dispose() {
        cancelPending();
        warmup = null;
        if (active == this) active = null;
        binding = null;
        clearInventory();
    }

    boolean serializesBotActions() {
        return pending != null || warmup != null && !warmup.expired;
    }

    Optional<OwnedStationLedger.Session> session() {
        return !refreshBinding() ? Optional.empty() : Optional.of(binding.session);
    }

    List<OwnedStationLedger.StationRecord> records() {
        if (binding == null || ledger == null || !ledger.currentSession().equals(binding.session)) return List.of();
        return ledger.records();
    }

    private void receiveBlockUpdate(ClientPacketListener source, BlockPos position, BlockState state) {
        if (position == null || state == null || !sourceCurrent(source)) return;
        cancelPendingIfContextChanged();
        applyBlockUpdate(position, state);
    }

    private void applyBlockUpdate(BlockPos position, BlockState state) {
        if (position == null || state == null || binding == null) return;
        refreshRemovalContinuity();
        long sequence = nextReceiptSequence();
        ItemId observedBlockId = ItemId.parse(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var cell = new OwnedStationLedger.BlockPosition(position.getX(), position.getY(), position.getZ());
        boolean tracked = recentStationBlocks.containsKey(cell)
                || pending != null && pending.ticket.intent().position().equals(cell)
                || ledger.records().stream().anyMatch(record -> record.position().equals(cell))
                || retainsStationReceipt(cell);
        if (tracked) {
            BlockReceipt previous = recentStationBlocks.get(cell);
            BlockReceipt observed = BlockReceipt.observed(previous, binding.session, observedBlockId, sequence,
                    removalContextCurrent(cell));
            if (stationDiagnosticAllowed(cell)) {
                org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                        "[Lodekeeper] STATION_BLOCK_RECEIPT generation={} position={} previousBlock={} previousSequence={} previousRemovalSequence={} observedBlock={} observedSequence={} stableRemovalSequence={}",
                        generation, position, previous == null ? "none" : previous.block(),
                        previous == null ? -1 : previous.sequence(),
                        previous == null ? 0 : previous.stableRemovalSequence(),
                        observedBlockId, sequence, observed.stableRemovalSequence());
            }
            if (previous == null && recentStationBlocks.size() >= 256) {
                var evicted = recentStationBlocks.keySet().iterator().next();
                removalDiagnostic("cache-eviction", evicted, recentStationBlocks.remove(evicted));
            }
            recentStationBlocks.put(cell, observed);
        }
        ledger.onServerBlockUpdate(binding.session, cell, observedBlockId, sequence);
        reconcilePending();
        blockReceiptObserver.accept(new ServerBlockReceipt(binding.session, sequence, position.immutable(), state));
    }

    private void receiveInventoryContents(
            ClientPacketListener source, int menuId, List<ItemStack> contents) {
        if (!sourceCurrent(source)) return;
        cancelPendingIfContextChanged();
        long sequence = nextReceiptSequence();
        AbstractContainerMenu menu = inventoryMenu(menuId);
        receiptDiagnostic("contents menu=" + menuId + " slots=" + (contents == null ? -1 : contents.size())
                + " mapped=" + (menu != null) + " complete=" + inventoryComplete);
        if (menu == null) return;
        if (contents == null || contents.size() != menu.slots.size()) {
            invalidateInventory();
            return;
        }
        StackCount[] next = new StackCount[PlacementInventoryProfile.MAIN_SLOTS];
        for (int contentSlot = 0; contentSlot < contents.size(); contentSlot++) {
            int mainSlot = mainSlot(menu, contentSlot);
            if (mainSlot < 0) continue;
            StackCount value = snapshot(contents.get(contentSlot));
            if (value == null || next[mainSlot] != null) {
                invalidateInventory();
                return;
            }
            next[mainSlot] = value;
        }
        if (java.util.Arrays.stream(next).anyMatch(Objects::isNull)) {
            invalidateInventory();
            return;
        }
        acceptInventorySnapshot(next, sequence);
    }

    private AbstractContainerMenu inventoryMenu(int menuId) {
        if (client.player == null) return null;
        if (menuId == 0) return client.player.inventoryMenu;
        AbstractContainerMenu current = client.player.containerMenu;
        return current != null && current.containerId == menuId ? current : null;
    }

    private int mainSlot(AbstractContainerMenu menu, int slotIndex) {
        if (client.player == null || slotIndex < 0 || slotIndex >= menu.slots.size()) return -1;
        net.minecraft.world.inventory.Slot slot = menu.slots.get(slotIndex);
        int index = slot.getContainerSlot();
        return slot.container == client.player.getInventory()
                && index >= 0 && index < PlacementInventoryProfile.MAIN_SLOTS ? index : -1;
    }

    private void receiveInventorySlot(
            ClientPacketListener source, int menuId, int slot, ItemStack stack) {
        if (!sourceCurrent(source)) return;
        cancelPendingIfContextChanged();
        long sequence = nextReceiptSequence();
        AbstractContainerMenu menu = inventoryMenu(menuId);
        int mainSlot = menuId == -2
                ? (slot >= 0 && slot < PlacementInventoryProfile.MAIN_SLOTS ? slot : -1)
                : menu == null ? -1 : mainSlot(menu, slot);
        if (mainSlot < 0) return;
        acceptInventorySlot(mainSlot, stack, sequence);
    }

    private void receiveDirectInventorySlot(ClientPacketListener source, int slot, ItemStack stack) {
        if (!sourceCurrent(source)) return;
        cancelPendingIfContextChanged();
        long sequence = nextReceiptSequence();
        int mainSlot = PlacementInventoryProfile.mainSlotFromPlayerInventorySlot(slot);
        if (mainSlot < 0) return;
        acceptInventorySlot(mainSlot, stack, sequence);
    }

    private void acceptInventorySlot(int slot, ItemStack stack, long sequence) {
        StackCount value = snapshot(stack);
        if (value == null) {
            invalidateInventory();
            return;
        }
        StackCount[] next = serverMainInventory.clone();
        next[slot] = value;
        acceptInventorySnapshot(next, sequence);
    }

    private void acceptInventorySnapshot(StackCount[] next, long sequence) {
        StackCount[] previous = serverMainInventory.clone();
        boolean wasComplete = inventoryComplete;
        System.arraycopy(next, 0, serverMainInventory, 0, serverMainInventory.length);
        inventoryComplete = java.util.Arrays.stream(next).noneMatch(Objects::isNull);
        if (wasComplete && inventoryComplete) rememberInventoryIncreases(previous, next, sequence);
        if (!wasComplete && inventoryComplete) receiptDiagnostic("baseline complete sequence=" + sequence);
        if (!wasComplete || !inventoryComplete || pending == null) return;

        int changedSlot = -1;
        for (int slot = 0; slot < serverMainInventory.length; slot++) {
            if (sameSlot(previous[slot], next[slot])) continue;
            if (changedSlot >= 0) {
                cancelPending();
                return;
            }
            changedSlot = slot;
        }
        if (changedSlot < 0) return;
        if (changedSlot != pending.selectedSlot) {
            cancelPending();
            return;
        }

        StackCount before = previous[changedSlot];
        StackCount after = next[changedSlot];
        int beforeTotal = countItem(pending.stationItemId, previous);
        int afterTotal = countItem(pending.stationItemId, next);
        int afterSelectedCount = after != null && pending.stationItemId.equals(after.itemId) ? after.count : 0;
        boolean exactDecrement = before != null && pending.stationItemId.equals(before.itemId)
                && before.count == pending.startingSelectedCount
                && afterSelectedCount == before.count - 1
                && sameItemAndComponents(pending.startingSelectedStack, before)
                && (afterSelectedCount == 0
                        || sameItemAndComponents(pending.startingSelectedStack, after))
                && beforeTotal == pending.startingCount && afterTotal == pending.startingCount - 1;
        if (exactDecrement) {
            pending.inventoryDecrementSeen = true;
            ledger.onInventoryCount(pending.binding.session, pending.stationItemId, afterTotal, sequence);
            reconcilePending();
            return;
        }

        boolean refunded = pending.inventoryDecrementSeen
                && beforeTotal == pending.startingCount - 1 && afterTotal == pending.startingCount
                && before != null && after != null && pending.stationItemId.equals(after.itemId)
                && after.count == pending.startingSelectedCount
                && sameItemAndComponents(pending.startingSelectedStack, after)
                && (before.count == 0 || sameItemAndComponents(pending.startingSelectedStack, before));
        if (refunded) ledger.onInventoryCount(pending.binding.session, pending.stationItemId, afterTotal, sequence);
        cancelPending();
    }

    private boolean refreshBinding() {
        WorldScope scope = protection.capture().scope();
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        ClientPacketListener networkHandler = client.getConnection();
        Connection connection = networkHandler == null ? null : networkHandler.getConnection();
        if (scope == null || player == null || world == null || networkHandler == null || connection == null
                || !networkHandler.isAcceptingMessages()) {
            cancelPending();
            warmup = null;
            binding = null;
            clearInventory();
            return false;
        }

        if (binding != null && binding.matches(scope, player, world, networkHandler, connection)) return true;
        cancelPending();
        warmup = null;
        generation = Math.addExact(generation, 1);
        OwnedStationLedger.Session nextSession = new OwnedStationLedger.Session(scope, generation);
        if (ledger == null) ledger = new OwnedStationLedger(nextSession);
        else ledger.changeSession(nextSession);
        binding = new Binding(nextSession, player, world, networkHandler, connection);
        placementQuarantined = false;
        receiptDiagnostics = 0;
        stationBlockDiagnostics = 0;
        retainedStationDiagnostics = 0;
        receiptDiagnostic("binding established");
        clearInventory();
        return true;
    }

    OptionalLong confirmedStationRemovalSequence(OwnedStationLedger.StationRecord record) {
        if (!refreshBinding()) return OptionalLong.empty();
        refreshRemovalContinuity();
        if (record == null || !record.session().equals(binding.session)) {
            return OptionalLong.empty();
        }
        BlockReceipt receipt = recentStationBlocks.get(record.position());
        if (receipt == null || !receipt.session().equals(record.session())
                || receipt.stableRemovalSequence() <= record.blockReceiptSequence()
                || !receipt.block().equals(AIR)) return OptionalLong.empty();
        return OptionalLong.of(receipt.stableRemovalSequence());
    }

    boolean confirmedStationRemoved(OwnedStationLedger.StationRecord record) {
        return confirmedStationRemovalSequence(record).isPresent();
    }

    void recovered(OwnedStationLedger.StationRecord record) {
        if (record == null || ledger == null || binding == null) return;
        ledger.removeAfterPickup(binding.session, record);
        BlockReceipt removed = recentStationBlocks.remove(record.position());
        if (removed != null) removalDiagnostic("recovered", record.position(), removed);
    }

    private record BlockReceipt(OwnedStationLedger.Session session, ItemId block, long sequence,
                                long stableRemovalSequence) {
        private BlockReceipt {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(block, "block");
            if (sequence <= 0 || stableRemovalSequence < 0 || stableRemovalSequence > sequence
                    || !block.equals(AIR) && stableRemovalSequence != 0) {
                throw new IllegalArgumentException("invalid station block receipt");
            }
        }

        private static BlockReceipt observed(BlockReceipt previous, OwnedStationLedger.Session session,
                                             ItemId block, long sequence, boolean continuityAllowed) {
            long removal = 0;
            if (block.equals(AIR) && continuityAllowed) {
                removal = previous != null && previous.session().equals(session)
                        && previous.block().equals(AIR) && previous.stableRemovalSequence() > 0
                        ? previous.stableRemovalSequence() : sequence;
            }
            return new BlockReceipt(session, block, sequence, removal);
        }

        private BlockReceipt withoutRemovalContinuity() {
            return stableRemovalSequence == 0 ? this : new BlockReceipt(session, block, sequence, 0);
        }
    }

    private void refreshRemovalContinuity() {
        recentStationBlocks.replaceAll((cell, receipt) -> receipt.stableRemovalSequence() > 0
                && !removalContextCurrent(cell) ? revokeRemovalContinuity(cell, receipt, "native-context-unavailable")
                : receipt);
    }

    private boolean removalContextCurrent(OwnedStationLedger.BlockPosition cell) {
        LocalPlayer player = client.player;
        if (binding == null || player == null || client.gameMode == null
                || !player.isAlive() || player.isCreative() || player.isSpectator()
                || GameApi.screen(client) != null || player.containerMenu != player.inventoryMenu
                || player.containerMenu == null || !player.containerMenu.getCarried().isEmpty()) return false;
        BlockPos position = new BlockPos(cell.x(), cell.y(), cell.z());
        return client.level != null && client.level.hasChunkAt(position)
                && client.level.getBlockState(position).isAir();
    }

    private BlockReceipt revokeRemovalContinuity(OwnedStationLedger.BlockPosition cell, BlockReceipt receipt,
                                                 String reason) {
        if (receipt.stableRemovalSequence() > 0) removalDiagnostic(reason, cell, receipt);
        return receipt.withoutRemovalContinuity();
    }

    private boolean retainsStationReceipt(OwnedStationLedger.BlockPosition cell) {
        return binding != null && LodekeeperClient.engine != null
                && LodekeeperClient.engine.retainsOwnedStationReceipt(this, binding.session, cell);
    }

    private boolean stationDiagnosticAllowed(OwnedStationLedger.BlockPosition cell) {
        if (LodekeeperClient.engine == null || !LodekeeperClient.engine.config.debugLogging) return false;
        if (retainsStationReceipt(cell)) {
            if (retainedStationDiagnostics >= 48) return false;
            retainedStationDiagnostics++;
            return true;
        }
        if (stationBlockDiagnostics >= 24) return false;
        stationBlockDiagnostics++;
        return true;
    }

    private void removalDiagnostic(String reason, OwnedStationLedger.BlockPosition cell, BlockReceipt receipt) {
        if (stationDiagnosticAllowed(cell)) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] STATION_REMOVAL_DISCARDED generation={} position={} reason={} latestRawSequence={} discardedStableRemovalSequence={}",
                receipt.session().generation(), cell, reason, receipt.sequence(), receipt.stableRemovalSequence());
    }

    String inventoryReadiness() {
        long known = java.util.Arrays.stream(serverMainInventory).filter(Objects::nonNull).count();
        if (!inventoryComplete) return "server slots " + known + "/" + serverMainInventory.length;
        if (client.player == null) return "player unavailable";
        for (int slot = 0; slot < serverMainInventory.length; slot++) {
            StackCount local = snapshot(client.player.getInventory().getItem(slot));
            if (!sameSlot(serverMainInventory[slot], local)) return "server inventory differs at storage slot " + slot;
        }
        return "server inventory current";
    }

    private void receiptDiagnostic(String message) {
        if (receiptDiagnostics >= 48) return;
        receiptDiagnostics++;
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] STATION_RECEIPT generation={} {}", generation, message);
    }

    private boolean inventoryMatchesLocal() {
        if (client.player == null) return false;
        for (int slot = 0; slot < serverMainInventory.length; slot++) {
            StackCount known = serverMainInventory[slot];
            ItemStack local = client.player.getInventory().getItem(slot);
            if (known == null || local == null || local.getCount() != known.count
                    || local.isEmpty() != (known.count == 0)
                    || known.count > 0 && !PlacementStackProfile.sameItemAndComponents(known.stack, local)) return false;
        }
        return true;
    }

    int confirmedInventoryCount(ItemId item) {
        return refreshBinding() && inventoryComplete ? countItem(item, serverMainInventory) : -1;
    }

    Optional<ServerInventoryReceipt> confirmedInventoryReceipt(ItemId item) {
        if (item == null || !refreshBinding() || !inventoryComplete || inventoryIncreaseTrackingOverflowed
                || !inventoryMatchesLocal()) {
            return Optional.empty();
        }
        return Optional.of(new ServerInventoryReceipt(countItem(item, serverMainInventory),
                inventoryIncreaseSequences.getOrDefault(item, 0L)));
    }

    Optional<ServerInventoryReceipt> confirmedOrdinaryInventoryReceipt(ItemId item) {
        if (item == null || !refreshBinding() || !inventoryComplete || inventoryIncreaseTrackingOverflowed
                || !inventoryMatchesLocal()) return Optional.empty();
        return Optional.of(new ServerInventoryReceipt(countOrdinary(item, serverMainInventory),
                ordinaryInventoryIncreaseSequences.getOrDefault(item, 0L)));
    }

    Optional<ItemStack> confirmedOrdinaryStack(int slot) {
        if (slot < 0 || slot >= serverMainInventory.length || !refreshBinding() || !inventoryComplete
                || !inventoryMatchesLocal()) return Optional.empty();
        StackCount known = serverMainInventory[slot];
        return known.count > 0 && AnimalHarvestAction.ordinary(known.stack)
                ? Optional.of(known.stack.copy()) : Optional.empty();
    }

    private static int countOrdinary(ItemId item, StackCount[] inventory) {
        int count = 0;
        for (StackCount slot : inventory) {
            if (slot == null) throw new IllegalStateException("authoritative inventory is incomplete");
            if (item.equals(slot.itemId) && AnimalHarvestAction.ordinary(slot.stack))
                count = Math.addExact(count, slot.count);
        }
        return count;
    }

    private boolean sourceCurrent(ClientPacketListener source) {
        if (!refreshBinding() || binding.networkHandler != source) return false;
        Connection sourceConnection = source.getConnection();
        return binding.connection == sourceConnection;
    }

    private void cancelPendingIfContextChanged() {
        if (pending != null && !pendingContextCurrent(pending)) cancelPending();
    }

    private boolean pendingContextCurrent(PendingPlacement attempt) {
        LocalPlayer player = client.player;
        return binding != null && binding.sameNativeIdentity(attempt.binding)
                && player == attempt.binding.player && client.level == attempt.binding.world
                && client.getConnection() == attempt.binding.networkHandler
                && attempt.binding.networkHandler.getConnection() == attempt.binding.connection
                && player != null && player.getHealth() > 0.0f
                && GameApi.screen(client) == null && player.containerMenu == attempt.screenHandler
                && player.getInventory().getSelectedSlot() == attempt.selectedSlot;
    }

    private boolean warmupContextCurrent(PlacementWarmup attempt) {
        LocalPlayer player = client.player;
        return binding != null && binding.sameNativeIdentity(attempt.binding)
                && player == attempt.binding.player && client.level == attempt.binding.world
                && client.getConnection() == attempt.binding.networkHandler
                && attempt.binding.networkHandler.getConnection() == attempt.binding.connection
                && player != null && player.getHealth() > 0.0f
                && GameApi.screen(client) == null && player.containerMenu == attempt.screenHandler
                && player.getInventory().getSelectedSlot() == attempt.selectedSlot;
    }

    private void reconcilePending() {
        if (pending == null || ledger.pendingTicket().orElse(null) == pending.ticket) return;
        var ticket = pending.ticket;
        if (ledger.records().stream().anyMatch(record -> record.ticket() == ticket)) pending = null;
        else cancelPending();
    }

    private long nextReceiptSequence() {
        receiptSequence = Math.addExact(receiptSequence, 1);
        return receiptSequence;
    }

    private static ItemId itemId(Item item) {
        return ItemId.parse(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    private static StackCount snapshot(ItemStack stack) {
        if (stack == null) return null;
        if (stack.isEmpty()) return StackCount.EMPTY;
        try {
            return new StackCount(itemId(stack.getItem()), stack.getCount(), stack.copy());
        } catch (IllegalArgumentException invalidItemId) {
            return null;
        }
    }

    private static boolean sameSlot(StackCount left, StackCount right) {
        if (left == right) return true;
        if (left == null || right == null || left.count != right.count) return false;
        return left.count == 0 || sameItemAndComponents(left, right);
    }

    private static boolean sameItemAndComponents(StackCount left, StackCount right) {
        return left != null && right != null && left.itemId != null && left.itemId.equals(right.itemId)
                && PlacementStackProfile.sameItemAndComponents(left.stack, right.stack);
    }

    private static int countItem(ItemId itemId, StackCount[] inventory) {
        int count = 0;
        for (StackCount slot : inventory) {
            if (slot == null) throw new IllegalStateException("authoritative inventory is incomplete");
            if (itemId.equals(slot.itemId)) count = Math.addExact(count, slot.count);
        }
        return count;
    }

    private final java.util.Map<ItemId, Long> ordinaryInventoryIncreaseSequences = new java.util.HashMap<>();

    private void rememberInventoryIncreases(StackCount[] previous, StackCount[] next, long sequence) {
        Set<ItemId> itemIds = new HashSet<>();
        for (StackCount slot : previous) if (slot.itemId != null) itemIds.add(slot.itemId);
        for (StackCount slot : next) if (slot.itemId != null) itemIds.add(slot.itemId);
        for (ItemId itemId : itemIds) {
            if (countOrdinary(itemId, next) > countOrdinary(itemId, previous)) {
                if (!ordinaryInventoryIncreaseSequences.containsKey(itemId)
                        && ordinaryInventoryIncreaseSequences.size() >= MAX_TRACKED_INVENTORY_ITEMS) {
                    inventoryIncreaseTrackingOverflowed = true;
                    ordinaryInventoryIncreaseSequences.clear();
                    return;
                }
                ordinaryInventoryIncreaseSequences.put(itemId, sequence);
            }
            if (countItem(itemId, next) > countItem(itemId, previous)) {
                if (!inventoryIncreaseSequences.containsKey(itemId)
                        && inventoryIncreaseSequences.size() >= MAX_TRACKED_INVENTORY_ITEMS) {
                    inventoryIncreaseTrackingOverflowed = true;
                    inventoryIncreaseSequences.clear();
                    return;
                }
                inventoryIncreaseSequences.put(itemId, sequence);
            }
        }
    }

    private void invalidateInventory() {
        clearInventory();
        expirePendingTicket();
    }

    private void clearInventory() {
        recentStationBlocks.forEach((cell, receipt) -> removalDiagnostic("inventory-or-session-clear", cell, receipt));
        recentStationBlocks.clear();
        inventoryIncreaseSequences.clear();
        ordinaryInventoryIncreaseSequences.clear();
        inventoryIncreaseTrackingOverflowed = false;
        java.util.Arrays.fill(serverMainInventory, null);
        inventoryComplete = false;
    }

    record ServerInventoryReceipt(int count, long increaseSequence) {
        ServerInventoryReceipt {
            if (count < 0 || increaseSequence < 0) {
                throw new IllegalArgumentException("invalid server inventory receipt");
            }
        }
    }

    private record StackCount(ItemId itemId, int count, ItemStack stack) {
        private static final StackCount EMPTY = new StackCount(null, 0, null);

        private StackCount {
            if (count < 0 || (count == 0) != (itemId == null) || (count == 0) != (stack == null)) {
                throw new IllegalArgumentException("invalid authoritative slot contents");
            }
        }
    }

    private record Binding(OwnedStationLedger.Session session, LocalPlayer player, ClientLevel world,
                           ClientPacketListener networkHandler, Connection connection) {
        private boolean matches(WorldScope scope, LocalPlayer player, ClientLevel world,
                                ClientPacketListener networkHandler, Connection connection) {
            return session.worldScope().equals(scope) && this.player == player && this.world == world
                    && this.networkHandler == networkHandler && this.connection == connection;
        }

        private boolean sameNativeIdentity(Binding other) {
            return session.equals(other.session) && player == other.player && world == other.world
                    && networkHandler == other.networkHandler && connection == other.connection;
        }
    }

    enum ReservationStatus { RESERVED, NOT_READY, REJECTED, EXPIRED, QUARANTINED }

    record PlacementReservation(ReservationStatus status,
                                Optional<OwnedStationLedger.PlacementTicket> ticket) {
        private static PlacementReservation reserved(OwnedStationLedger.PlacementTicket ticket) {
            return new PlacementReservation(ReservationStatus.RESERVED, Optional.of(ticket));
        }

        private static PlacementReservation notReady() {
            return new PlacementReservation(ReservationStatus.NOT_READY, Optional.empty());
        }

        private static PlacementReservation rejected() {
            return new PlacementReservation(ReservationStatus.REJECTED, Optional.empty());
        }

        private static PlacementReservation expired() {
            return new PlacementReservation(ReservationStatus.EXPIRED, Optional.empty());
        }

        private static PlacementReservation quarantined() {
            return new PlacementReservation(ReservationStatus.QUARANTINED, Optional.empty());
        }
    }

    private static final class PlacementWarmup {
        private final long jobToken;
        private final OwnedStationLedger.BlockPosition position;
        private final ItemId expectedBlockId;
        private final ItemId stationItemId;
        private final Binding binding;
        private final AbstractContainerMenu screenHandler;
        private final int selectedSlot;
        private final long deadlineTick;
        private boolean expired;

        private PlacementWarmup(long jobToken, OwnedStationLedger.BlockPosition position,
                                ItemId expectedBlockId, ItemId stationItemId, Binding binding,
                                AbstractContainerMenu screenHandler, int selectedSlot, long deadlineTick) {
            this.jobToken = jobToken;
            this.position = position;
            this.expectedBlockId = expectedBlockId;
            this.stationItemId = stationItemId;
            this.binding = binding;
            this.screenHandler = screenHandler;
            this.selectedSlot = selectedSlot;
            this.deadlineTick = deadlineTick;
        }

        private boolean sameAttempt(PlacementWarmup other) {
            return jobToken == other.jobToken && position.equals(other.position)
                    && expectedBlockId.equals(other.expectedBlockId) && stationItemId.equals(other.stationItemId)
                    && binding.sameNativeIdentity(other.binding) && screenHandler == other.screenHandler
                    && selectedSlot == other.selectedSlot;
        }
    }

    private static final class PendingPlacement {
        private final OwnedStationLedger.PlacementTicket ticket;
        private final Binding binding;
        private final AbstractContainerMenu screenHandler;
        private final int selectedSlot;
        private final ItemId stationItemId;
        private final int startingCount;
        private final int startingSelectedCount;
        private final StackCount startingSelectedStack;
        private final long deadlineTick;
        private boolean inventoryDecrementSeen;

        private PendingPlacement(OwnedStationLedger.PlacementTicket ticket, Binding binding,
                                 AbstractContainerMenu screenHandler, int selectedSlot, ItemId stationItemId,
                                 int startingCount, StackCount startingSelectedStack, long deadlineTick) {
            this.ticket = ticket;
            this.binding = binding;
            this.screenHandler = screenHandler;
            this.selectedSlot = selectedSlot;
            this.stationItemId = stationItemId;
            this.startingCount = startingCount;
            this.startingSelectedStack = startingSelectedStack;
            this.startingSelectedCount = startingSelectedStack.count;
            this.deadlineTick = deadlineTick;
        }
    }
}
