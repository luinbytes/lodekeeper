package dev.lodekeeper.fabric.modern.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lodekeeper.fabric.modern.OwnedClickReceipts;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class IntegratedPickupObservationMixin extends ServerCommonPacketListenerImpl {
    @Shadow public ServerPlayer player;
    @Unique private Observer lodekeeper$pickupObserver;
    @Unique private boolean lodekeeper$pickupDisabled;
    @Unique private int lodekeeper$pickupErrors;
    @Unique private boolean lodekeeper$pickupIncompleteEmitted;

    protected IntegratedPickupObservationMixin(MinecraftServer server, Connection connection,
                                                CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @WrapOperation(
            method = "handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V"),
            require = 1, allow = 1)
    private void lodekeeper$observePickup(AbstractContainerMenu menu, int slot, int button,
                                         ContainerInput input, Player clickPlayer, Operation<Void> original,
                                         ServerboundContainerClickPacket packet) {
        Before before = null;
        try {
            if (!lodekeeper$pickupDisabled && OwnedClickReceipts.pickupBoundaryEnabled()
                    && server instanceof IntegratedServer && server.isSameThread()
                    && server.getSingleplayerProfile() != null
                    && server.getSingleplayerProfile().id().equals(player.getUUID())) {
                if (lodekeeper$pickupObserver == null) lodekeeper$pickupObserver = new Observer();
                before = lodekeeper$pickupObserver.before(menu, slot, button, input, clickPlayer,
                        player, packet, server, connection, this);
            }
        } catch (Throwable diagnosticFailure) {
            lodekeeper$pickupDisabled = true;
            if (lodekeeper$pickupErrors < Integer.MAX_VALUE) lodekeeper$pickupErrors++;
            if (lodekeeper$pickupObserver != null) {
                if (lodekeeper$pickupObserver.pending != null)
                    lodekeeper$pickupObserver.pending.phase = Candidate.INCOMPLETE;
                lodekeeper$pickupObserver.pending = null;
                lodekeeper$pickupObserver.packet = null;
                lodekeeper$pickupObserver.menu = null;
            }
        }
        original.call(menu, slot, button, input, clickPlayer);
        try {
            if (before != null && !lodekeeper$pickupDisabled)
                lodekeeper$pickupObserver.after(before, player, server);
        } catch (Throwable diagnosticFailure) {
            lodekeeper$pickupDisabled = true;
            if (lodekeeper$pickupErrors < Integer.MAX_VALUE) lodekeeper$pickupErrors++;
            if (lodekeeper$pickupObserver != null) {
                if (lodekeeper$pickupObserver.pending != null)
                    lodekeeper$pickupObserver.pending.phase = Candidate.INCOMPLETE;
                lodekeeper$pickupObserver.pending = null;
                lodekeeper$pickupObserver.packet = null;
                lodekeeper$pickupObserver.menu = null;
            }
        }
    }

    @Inject(method = "handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",
            at = @At("RETURN"))
    private void lodekeeper$pickupHandlerReturned(ServerboundContainerClickPacket packet, CallbackInfo callback) {
        try {
            if (!OwnedClickReceipts.pickupBoundaryEnabled() || !server.isSameThread()) return;
            Observer observer = lodekeeper$pickupObserver;
            if (!lodekeeper$pickupDisabled && observer != null) observer.returned(packet, lodekeeper$pickupErrors);
            if (!lodekeeper$pickupIncompleteEmitted && (lodekeeper$pickupDisabled
                    || observer != null && observer.omitted > 0)) {
                lodekeeper$pickupIncompleteEmitted = true;
                org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                        "[Lodekeeper] PICKUP_BOUNDARY side=SERVER event=INCOMPLETE run={} listenerTag={} admitted={} omitted={} errors={}",
                        OwnedClickReceipts.pickupBoundaryRun(), Integer.toHexString(System.identityHashCode(this)),
                        observer == null ? 0 : observer.admitted, observer == null ? 0 : observer.omitted,
                        lodekeeper$pickupErrors);
            }
        } catch (Throwable diagnosticFailure) {
            lodekeeper$pickupDisabled = true;
            if (lodekeeper$pickupErrors < Integer.MAX_VALUE) lodekeeper$pickupErrors++;
            if (lodekeeper$pickupObserver != null) {
                if (lodekeeper$pickupObserver.pending != null)
                    lodekeeper$pickupObserver.pending.phase = Candidate.INCOMPLETE;
                lodekeeper$pickupObserver.pending = null;
                lodekeeper$pickupObserver.packet = null;
                lodekeeper$pickupObserver.menu = null;
            }
        }
    }

    @Inject(method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void lodekeeper$clearPickupContext(DisconnectionDetails details, CallbackInfo callback) {
        lodekeeper$pickupDisabled = true;
        if (lodekeeper$pickupObserver != null) {
            if (lodekeeper$pickupObserver.pending != null)
                lodekeeper$pickupObserver.pending.phase = Candidate.INCOMPLETE;
            lodekeeper$pickupObserver.pending = null;
            lodekeeper$pickupObserver.packet = null;
            lodekeeper$pickupObserver.menu = null;
        }
    }

    private static final class Observer {
        final Candidate[] summaries = new Candidate[4];
        AbstractContainerMenu menu;
        ServerboundContainerClickPacket packet;
        Candidate pending;
        int admitted;
        int omitted;
        long epoch;
        long contextFirstSeen;
        long contextLastSeen;

        Before before(AbstractContainerMenu clickedMenu, int slot, int button, ContainerInput input,
                      Player clickPlayer, ServerPlayer player, ServerboundContainerClickPacket incoming,
                      MinecraftServer server, Connection connection, Object listener) {
            if (pending != null) throw new IllegalStateException("Unfinished or reentrant pickup observation");
            if (admitted == summaries.length) {
                if (input == ContainerInput.PICKUP && button >= 0 && button <= 1 && omitted < Integer.MAX_VALUE) omitted++;
                menu = null;
                return null;
            }
            long beforeCapture = System.nanoTime();
            if (menu != clickedMenu) {
                menu = clickedMenu;
                if (epoch < Long.MAX_VALUE) epoch++;
                contextFirstSeen = beforeCapture;
            }
            contextLastSeen = beforeCapture;
            if (input != ContainerInput.PICKUP || button < 0 || button > 1) return null;
            if (!(menu instanceof CraftingMenu crafting) || menu.slots.size() != 46
                    || clickPlayer != player || player.containerMenu != menu || slot < 0 || slot >= menu.slots.size()) {
                if (omitted < Integer.MAX_VALUE) omitted++;
                return null;
            }
            var inventory = player.getInventory();
            var source = menu.getSlot(slot);
            int inventorySlot = source.getContainerSlot();
            if (source.container != inventory || inventorySlot < 0 || inventorySlot >= 36) return null;
            var grid = crafting.getInputGridSlots();
            if (grid.size() != 9) {
                if (omitted < Integer.MAX_VALUE) omitted++;
                return null;
            }
            for (int i = 0; i < 9; i++) {
                var gridSlot = grid.get(i);
                if (gridSlot != menu.getSlot(i + 1) || gridSlot.container != grid.getFirst().container
                        || gridSlot.getContainerSlot() != i || gridSlot.container.getContainerSize() != 9) {
                    if (omitted < Integer.MAX_VALUE) omitted++;
                    return null;
                }
            }
            int index = admitted++;
            Candidate candidate = new Candidate();
            summaries[index] = candidate;
            pending = candidate;
            packet = incoming;
            candidate.index = index + 1;
            candidate.run = OwnedClickReceipts.pickupBoundaryRun();
            candidate.uuid = player.getUUID().toString();
            candidate.listenerTag = tag(listener);
            candidate.serverTag = tag(server);
            candidate.connectionTag = tag(connection);
            candidate.menuTag = tag(menu);
            candidate.playerTag = tag(player);
            candidate.worldTag = tag(player.level());
            candidate.inventoryTag = tag(inventory);
            candidate.packetTag = tag(incoming);
            candidate.epoch = epoch;
            candidate.contextFirstSeen = contextFirstSeen;
            candidate.contextLastSeen = contextLastSeen;
            candidate.menuId = incoming.containerId();
            candidate.stateId = incoming.stateId();
            candidate.slot = incoming.slotNum();
            candidate.button = incoming.buttonNum();
            candidate.input = incoming.containerInput().name();
            candidate.invocationSlot = slot;
            candidate.invocationButton = button;
            candidate.invocationInput = input.name();
            candidate.tupleMatches = candidate.menuId == menu.containerId && candidate.slot == slot
                    && candidate.button == button && incoming.containerInput() == input;
            candidate.inventorySlot = inventorySlot;
            candidate.beforeCapture = beforeCapture;
            candidate.beforeRevision = menu.getStateId();
            candidate.beforeTick = server.getTickCount();
            net.minecraft.world.inventory.Slot[] capturedSlots = new net.minecraft.world.inventory.Slot[10];
            capturedSlots[0] = source;
            for (int i = 1; i < capturedSlots.length; i++) capturedSlots[i] = grid.get(i - 1);
            ItemStack[] copies = copyStacks(menu, capturedSlots);
            Before before = new Before(candidate, menu, player, player.level(), inventory, capturedSlots, copies);
            candidate.beforeCaptureComplete = System.nanoTime();
            return before;
        }

        void after(Before before, ServerPlayer player, MinecraftServer server) {
            long nativeReturn = System.nanoTime();
            Candidate candidate = before.candidate;
            if (pending != candidate || menu != before.menu || player != before.player
                    || player.containerMenu != menu || player.level() != before.world
                    || player.getInventory() != before.inventory || !server.isSameThread())
                throw new IllegalStateException("Pickup observation changed context");
            if (menu.slots.size() != 46 || menu.getSlot(candidate.invocationSlot) != before.capturedSlots[0]
                    || before.capturedSlots[0].container != before.inventory
                    || before.capturedSlots[0].getContainerSlot() != candidate.inventorySlot)
                throw new IllegalStateException("Pickup observation changed source mapping");
            for (int i = 1; i < before.capturedSlots.length; i++)
                if (menu.getSlot(i) != before.capturedSlots[i])
                    throw new IllegalStateException("Pickup observation changed grid mapping");
            candidate.nativeReturn = nativeReturn;
            candidate.afterRevision = menu.getStateId();
            candidate.afterTick = server.getTickCount();
            ItemStack[] after = copyStacks(menu, before.capturedSlots);
            SlotSummary[] slots = new SlotSummary[11];
            for (int i = 0; i < slots.length; i++) {
                ItemStack oldStack = before.copies[i];
                ItemStack newStack = after[i];
                slots[i] = new SlotSummary(i == 0 ? "source" : i == 1 ? "cursor" : "grid",
                        i == 0 ? candidate.invocationSlot : i == 1 ? -1 : i - 1,
                        BuiltInRegistries.ITEM.getKey(oldStack.getItem()).toString(), oldStack.getCount(), oldStack.isEmpty(),
                        BuiltInRegistries.ITEM.getKey(newStack.getItem()).toString(), newStack.getCount(), newStack.isEmpty(),
                        ItemStack.isSameItemSameComponents(oldStack, newStack));
            }
            candidate.slots = slots;
            candidate.afterCursorSameBeforeSourceComponents = ItemStack.isSameItemSameComponents(before.copies[0], after[1]);
            candidate.afterCaptureComplete = System.nanoTime();
            candidate.phase = Candidate.AFTER;
        }

        void returned(ServerboundContainerClickPacket incoming, int errors) {
            Candidate candidate = pending;
            if (candidate == null) return;
            if (packet != incoming || candidate.phase != Candidate.AFTER)
                throw new IllegalStateException("Pickup observation missing matching handler return");
            pending = null;
            packet = null;
            if (admitted == summaries.length) menu = null;
            candidate.handlerReturn = System.nanoTime();
            candidate.phase = Candidate.HANDLER_RETURN;
            String summary = "side=SERVER event=HANDLER_RETURN run=" + candidate.run
                    + " candidate=" + candidate.index + " playerUuid=" + candidate.uuid
                    + " listenerTag=" + candidate.listenerTag + " serverTag=" + candidate.serverTag
                    + " connectionTag=" + candidate.connectionTag + " menuTag=" + candidate.menuTag
                    + " playerTag=" + candidate.playerTag + " worldTag=" + candidate.worldTag
                    + " inventoryTag=" + candidate.inventoryTag + " packetTag=" + candidate.packetTag
                    + " epoch=" + candidate.epoch + " contextFirstSeenNanos=" + candidate.contextFirstSeen
                    + " contextLastSeenNanos=" + candidate.contextLastSeen + " menuSize=46 menuId=" + candidate.menuId
                    + " stateId=" + candidate.stateId + " slot=" + candidate.slot + " button=" + candidate.button
                    + " input=" + candidate.input + " invocationSlot=" + candidate.invocationSlot
                    + " invocationButton=" + candidate.invocationButton + " invocationInput=" + candidate.invocationInput
                    + " tupleMatches=" + candidate.tupleMatches + " inventorySlot=" + candidate.inventorySlot
                    + " beforeRevision=" + candidate.beforeRevision + " afterRevision=" + candidate.afterRevision
                    + " beforeTick=" + candidate.beforeTick + " afterTick=" + candidate.afterTick
                    + " beforeCaptureNanos=" + candidate.beforeCapture
                    + " beforeCaptureCompleteNanos=" + candidate.beforeCaptureComplete
                    + " nativeReturnNanos=" + candidate.nativeReturn
                    + " afterCaptureCompleteNanos=" + candidate.afterCaptureComplete
                    + " handlerReturnNanos=" + candidate.handlerReturn
                    + " slots=" + java.util.Arrays.toString(candidate.slots)
                    + " afterCursorSameBeforeSourceComponents=" + candidate.afterCursorSameBeforeSourceComponents
                    + " omitted=" + omitted + " errors=" + errors
                    + " incomplete=" + (!candidate.tupleMatches || omitted > 0 || errors > 0);
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info("[Lodekeeper] PICKUP_BOUNDARY {}", summary);
        }

        private static ItemStack[] copyStacks(AbstractContainerMenu menu, net.minecraft.world.inventory.Slot[] slots) {
            ItemStack[] copies = new ItemStack[11];
            copies[0] = slots[0].getItem().copy();
            copies[1] = menu.getCarried().copy();
            for (int i = 2; i < copies.length; i++) copies[i] = slots[i - 1].getItem().copy();
            return copies;
        }

        private static String tag(Object value) { return Integer.toHexString(System.identityHashCode(value)); }
    }

    private record Before(Candidate candidate, AbstractContainerMenu menu, ServerPlayer player,
                          Object world, Object inventory, net.minecraft.world.inventory.Slot[] capturedSlots, ItemStack[] copies) {}

    private record SlotSummary(String role, int menuSlot, String beforeItem, int beforeCount, boolean beforeEmpty,
                               String afterItem, int afterCount, boolean afterEmpty, boolean sameComponents) {
        @Override
        public String toString() {
            return "SlotSummary[role=" + role + ", menuSlot=" + menuSlot
                    + ", beforeItem=" + beforeItem + ", beforeCount=" + beforeCount
                    + ", beforeEmpty=" + beforeEmpty + ", afterItem=" + afterItem
                    + ", afterCount=" + afterCount + ", afterEmpty=" + afterEmpty
                    + ", sameComponents=" + sameComponents + "]";
        }
    }

    private static final class Candidate {
        static final int BEFORE = 0, AFTER = 1, HANDLER_RETURN = 2, INCOMPLETE = 3;
        int phase = BEFORE;
        String run, uuid, listenerTag, serverTag, connectionTag, menuTag, playerTag, worldTag, inventoryTag, packetTag;
        String input, invocationInput;
        int index, menuId, stateId, slot, button, invocationSlot, invocationButton, inventorySlot;
        int beforeRevision, afterRevision, beforeTick, afterTick;
        long epoch, contextFirstSeen, contextLastSeen, beforeCapture, beforeCaptureComplete;
        long nativeReturn, afterCaptureComplete, handlerReturn;
        boolean tupleMatches, afterCursorSameBeforeSourceComponents;
        SlotSummary[] slots;
    }
}
