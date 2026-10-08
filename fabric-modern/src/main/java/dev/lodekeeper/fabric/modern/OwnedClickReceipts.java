package dev.lodekeeper.fabric.modern;

import net.minecraft.world.item.ItemStack;
import java.util.function.BooleanSupplier;

/** Requests native server echoes only inside a synchronous bot inventory click. */
public final class OwnedClickReceipts {
    public interface FullReceiptObserver {
        boolean contextCurrent();
        void fullContentsApplied(Receipt receipt);
        void slotUpdated(int slot, int revision, long sequence);
        void clickStarted(boolean owned);
    }
    public interface Receipt {
        long lodekeeper$inputSequence();
        long lodekeeper$contentsSequence();
        ItemStack lodekeeper$receivedInput();
        default long lodekeeper$slotSequence(int slot) { return 0; }
        default ItemStack lodekeeper$receivedSlot(int slot) { return ItemStack.EMPTY; }
        default ItemStack lodekeeper$receivedContentsSlot(int slot) { return ItemStack.EMPTY.copy(); }
        default long lodekeeper$cursorSequence() { return 0; }
        default ItemStack lodekeeper$receivedCursor() { return ItemStack.EMPTY; }
        default int lodekeeper$contentsRevision() { return -1; }
        default int lodekeeper$contentsSize() { return -1; }
        default void lodekeeper$watchClick(FullReceiptObserver observer) {
            throw new IllegalStateException("Native full inventory receipt observation is unavailable");
        }
        default void lodekeeper$unwatchClick(FullReceiptObserver observer) {}
    }
    private static final boolean PICKUP_BOUNDARY_ENABLED = pickupBoundaryProperty();
    private static final String PICKUP_BOUNDARY_RUN = pickupBoundaryRunLabel();
    private static PickupConstructors pickupConstructors;
    private static boolean pickupConstructorsDisabled;
    private static int pickupConstructorErrors;
    private static boolean pickupConstructorIncompleteEmitted;
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final class Scope {
        final int containerId;
        final boolean inputEcho;
        boolean claimed;
        boolean clicking;
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
            if (scope.clicking) throw new IllegalStateException("Reentrant owned inventory click");
            scope.clicking = true;
            try { client.gameMode.handleContainerInput(containerId, slot, button, type, player); }
            finally { scope.clicking = false; }
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

    static void craftingDrag(net.minecraft.client.Minecraft client, int containerId,
                             int[] destinations, net.minecraft.world.entity.player.Player player) {
        inventoryClick(client, containerId, -999, net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftMask(0, 1), net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, player);
        for (int slot : destinations)
            inventoryClick(client, containerId, slot, net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftMask(1, 1), net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, player);
        enter(containerId, false);
        try {
            CURRENT.get().cursorEcho = true;
            inventoryClick(client, containerId, -999, net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftMask(2, 1), net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT, player);
        } finally { CURRENT.remove(); }
    }

    static boolean inputTransfer(int containerId, BooleanSupplier operation) {
        enter(containerId, true);
        try { return operation.getAsBoolean(); } finally { CURRENT.remove(); }
    }
    static void outputClick(int containerId, Runnable operation) {
        enter(containerId, true);
        try { operation.run(); } finally { CURRENT.remove(); }
    }
    public static boolean isOwnedClick(Object handler) {
        Scope scope = CURRENT.get();
        return scope != null && scope.menu == handler && scope.contextCurrent();
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

    public static boolean pickupBoundaryEnabled() {
        return PICKUP_BOUNDARY_ENABLED && PICKUP_BOUNDARY_RUN != null;
    }

    public static String pickupBoundaryRun() { return PICKUP_BOUNDARY_RUN; }

    private static boolean pickupBoundaryProperty() {
        try { return Boolean.parseBoolean(System.getProperty("lodekeeper.debug.pickupBoundary", "false")); }
        catch (Throwable diagnosticFailure) { return false; }
    }

    private static String pickupBoundaryRunLabel() {
        if (!PICKUP_BOUNDARY_ENABLED) return null;
        try { return java.util.UUID.randomUUID().toString(); }
        catch (Throwable diagnosticFailure) { return null; }
    }

    public static void observeFinalizedPickupPacket(
            net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
        if (!pickupBoundaryEnabled()) return;
        try {
            Scope scope = CURRENT.get();
            if (scope == null || !scope.claimed || scope.client == null || !scope.client.isSameThread()
                    || !scope.contextCurrent() || LodekeeperClient.engine == null
                    || !LodekeeperClient.engine.config.debugLogging) return;
            if (!pickupConstructorsDisabled) {
                if (pickupConstructors == null) pickupConstructors = new PickupConstructors();
                pickupConstructors.observe(scope, packet);
            }
        } catch (Throwable diagnosticFailure) {
            pickupConstructorsDisabled = true;
            if (pickupConstructorErrors < Integer.MAX_VALUE) pickupConstructorErrors++;
            if (pickupConstructors != null) pickupConstructors.menu = null;
        }
        try {
            if (!pickupConstructorIncompleteEmitted && (pickupConstructorsDisabled
                    || pickupConstructors != null && pickupConstructors.omitted > 0)) {
                pickupConstructorIncompleteEmitted = true;
                org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                        "[Lodekeeper] PICKUP_BOUNDARY side=CLIENT event=INCOMPLETE run={} admitted={} omitted={} errors={}",
                        PICKUP_BOUNDARY_RUN, pickupConstructors == null ? 0 : pickupConstructors.admitted,
                        pickupConstructors == null ? 0 : pickupConstructors.omitted, pickupConstructorErrors);
            }
        } catch (Throwable diagnosticFailure) {
            pickupConstructorsDisabled = true;
            if (pickupConstructorErrors < Integer.MAX_VALUE) pickupConstructorErrors++;
            if (pickupConstructors != null) pickupConstructors.menu = null;
        }
    }

    private static final class PickupConstructors {
        final String[] summaries = new String[4];
        net.minecraft.world.inventory.AbstractContainerMenu menu;
        int admitted;
        int omitted;
        long epoch;
        long contextFirstSeen;
        long contextLastSeen;

        void observe(Scope scope, net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
            if (packet.containerInput() != net.minecraft.world.inventory.ContainerInput.PICKUP
                    || packet.buttonNum() < 0 || packet.buttonNum() > 1) return;
            if (admitted == summaries.length) {
                if (omitted < Integer.MAX_VALUE) omitted++;
                menu = null;
                return;
            }
            long constructorReturn = System.nanoTime();
            if (menu != scope.menu) {
                menu = scope.menu;
                if (epoch < Long.MAX_VALUE) epoch++;
                contextFirstSeen = constructorReturn;
            }
            contextLastSeen = constructorReturn;
            if (!(menu instanceof net.minecraft.world.inventory.CraftingMenu crafting) || menu.slots.size() != 46
                    || packet.containerId() != scope.containerId || packet.slotNum() < 0
                    || packet.slotNum() >= menu.slots.size()) {
                if (omitted < Integer.MAX_VALUE) omitted++;
                return;
            }
            var source = menu.getSlot(packet.slotNum());
            int inventorySlot = source.getContainerSlot();
            if (source.container != scope.inventory || inventorySlot < 0 || inventorySlot >= 36) return;
            var grid = crafting.getInputGridSlots();
            if (grid.size() != 9) {
                if (omitted < Integer.MAX_VALUE) omitted++;
                return;
            }
            for (int i = 0; i < 9; i++) {
                var slot = grid.get(i);
                if (slot != menu.getSlot(i + 1) || slot.container != grid.getFirst().container
                        || slot.getContainerSlot() != i || slot.container.getContainerSize() != 9) {
                    if (omitted < Integer.MAX_VALUE) omitted++;
                    return;
                }
            }
            int candidate = admitted++;
            var network = scope.client.getConnection();
            String summary = "side=CLIENT event=CONSTRUCTOR_FINALIZED run=" + PICKUP_BOUNDARY_RUN
                    + " candidate=" + (candidate + 1) + " playerUuid=" + scope.player.getUUID()
                    + " menuTag=" + tag(menu) + " playerTag=" + tag(scope.player)
                    + " worldTag=" + tag(scope.client.level) + " networkTag=" + tag(network)
                    + " connectionTag=" + tag(network == null ? null : network.getConnection())
                    + " inventoryTag=" + tag(scope.inventory) + " epoch=" + epoch
                    + " contextFirstSeenNanos=" + contextFirstSeen + " contextLastSeenNanos=" + contextLastSeen
                    + " menuSize=46 menuId=" + packet.containerId() + " stateId=" + packet.stateId()
                    + " slot=" + packet.slotNum() + " button=" + packet.buttonNum()
                    + " input=" + packet.containerInput() + " inventorySlot=" + inventorySlot
                    + " localRevision=" + menu.getStateId() + " constructorReturnNanos=" + constructorReturn
                    + " omitted=" + omitted + " errors=" + pickupConstructorErrors;
            summaries[candidate] = summary;
            if (admitted == summaries.length) menu = null;
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info("[Lodekeeper] PICKUP_BOUNDARY {}", summary);
        }

        private static String tag(Object value) {
            return value == null ? "null" : Integer.toHexString(System.identityHashCode(value));
        }
    }
}
