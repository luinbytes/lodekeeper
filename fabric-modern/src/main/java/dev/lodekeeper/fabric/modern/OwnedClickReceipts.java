package dev.lodekeeper.fabric.modern;

import net.minecraft.world.item.ItemStack;
import java.util.function.BooleanSupplier;

/** Requests native server echoes only inside a synchronous bot inventory click. */
public final class OwnedClickReceipts {
    public interface Receipt {
        long lodekeeper$inputSequence();
        long lodekeeper$contentsSequence();
        ItemStack lodekeeper$receivedInput();
        default long lodekeeper$slotSequence(int slot) { return 0; }
        default ItemStack lodekeeper$receivedSlot(int slot) { return ItemStack.EMPTY; }
        default long lodekeeper$cursorSequence() { return 0; }
        default ItemStack lodekeeper$receivedCursor() { return ItemStack.EMPTY; }
        default int lodekeeper$contentsRevision() { return -1; }
    }
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final class Scope {
        final int containerId;
        final boolean inputEcho;
        boolean claimed;
        boolean cursorEcho;
        net.minecraft.client.Minecraft client;
        net.minecraft.world.inventory.AbstractContainerMenu menu;
        net.minecraft.world.entity.player.Player player;
        Object inventory;
        Scope(int containerId, boolean inputEcho) { this.containerId = containerId; this.inputEcho = inputEcho; }
        boolean contextCurrent() {
            return client != null && menu != null && player != null && client.player == player
                    && player.containerMenu == menu && menu.containerId == containerId
                    && player.getInventory() == inventory;
        }
    }
    private OwnedClickReceipts() {}
    private static void enter(int containerId, boolean inputEcho) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested owned inventory click");
        CURRENT.set(new Scope(containerId, inputEcho));
    }
    static void inventoryClick(net.minecraft.client.Minecraft client, int containerId,
                               int slot, int button, net.minecraft.world.inventory.ContainerInput type, net.minecraft.world.entity.player.Player player) {
        if (!client.isSameThread()) throw new IllegalStateException("Inventory clicks require the Minecraft thread");
        Scope existing = CURRENT.get();
        if (existing != null && existing.containerId != containerId)
            throw new IllegalStateException("Owned inventory click changed containers");
        if (player != client.player || player == null || player.containerMenu.containerId != containerId)
            throw new IllegalStateException("Owned inventory click has stale player or menu");
        if (existing == null) enter(containerId, false);
        try {
            Scope scope = CURRENT.get();
            if (scope.menu == null) {
                scope.client = client;
                scope.menu = player.containerMenu;
                scope.player = player;
                scope.inventory = player.getInventory();
            }
            if (!scope.contextCurrent()) throw new IllegalStateException("Owned inventory click changed context");
            client.gameMode.handleContainerInput(containerId, slot, button, type, player);
        }
        finally { if (existing == null) CURRENT.remove(); }
    }

    static void cursorClick(net.minecraft.client.Minecraft client, int containerId,
                            int slot, int button, net.minecraft.world.entity.player.Player player) {
        Scope existing = CURRENT.get();
        if (existing == null) enter(containerId, false);
        try {
            CURRENT.get().cursorEcho = true;
            inventoryClick(client, containerId, slot, button, net.minecraft.world.inventory.ContainerInput.PICKUP, player);
        } finally { if (existing == null) CURRENT.remove(); }
    }

    static boolean inputTransfer(int containerId, BooleanSupplier operation) {
        enter(containerId, true);
        try { return operation.getAsBoolean(); } finally { CURRENT.remove(); }
    }
    static void outputClick(int containerId, Runnable operation) {
        enter(containerId, true);
        try { operation.run(); } finally { CURRENT.remove(); }
    }
    public static boolean reconcileCursorContents(int containerId) {
        Scope scope = CURRENT.get();
        return scope != null && scope.containerId == containerId && scope.cursorEcho && scope.contextCurrent();
    }
    public static boolean reconcileInputSlot(int containerId) {
        Scope scope = CURRENT.get();
        return scope != null && scope.containerId == containerId && scope.inputEcho && scope.contextCurrent();
    }
    public static boolean reconcileStorageSlot(int containerId, int slotIndex) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.containerId != containerId || !scope.contextCurrent()
                || slotIndex < 0 || slotIndex >= scope.menu.slots.size()) return false;
        var slot = scope.menu.slots.get(slotIndex);
        int index = slot.getContainerSlot();
        return slot.container == scope.inventory && index >= 0 && (index < 36 || index == 40);
    }

    /** Called only by the synchronous client packet constructor, once per owned click. */
    public static boolean claimInputReconciliation(int containerId) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.claimed || scope.containerId != containerId || !scope.contextCurrent()) return false;
        scope.claimed = true;
        return true;
    }
}
