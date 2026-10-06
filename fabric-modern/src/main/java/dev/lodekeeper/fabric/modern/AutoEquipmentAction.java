package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;

import java.lang.ref.WeakReference;

/** Fills empty native armor slots with one verified vanilla quick-move at a time. */
final class AutoEquipmentAction {
    private static final int MAX_RECEIPT_TICKS = 40;
    private final Minecraft client;
    private AbstractContainerMenu handler;
    private WeakReference<Object> owningPlayer = new WeakReference<>(null);
    private WeakReference<Object> owningWorld = new WeakReference<>(null);
    private ItemStack expected = ItemStack.EMPTY;
    private int sourceSlot, armorSlot, sourceCount, waitTicks;
    private boolean blocked;
    private String status = "equipment idle";

    AutoEquipmentAction(Minecraft client) { this.client = client; }

    boolean active() { return handler != null; }
    String status() { return status; }

    /** True means an issued transfer still owns the inventory-action turn. */
    boolean tick() {
        if (owningPlayer.get() != client.player || owningWorld.get() != client.level) {
            if (active()) throw fail("world or player changed after the armor quick-move");
            blocked = false;
        }
        if (blocked) return false;
        if (active()) return observeTransfer();
        if (!safeContext()) { status = "equipment waiting for a safe player inventory"; return false; }
        AbstractContainerMenu menu = client.player.inventoryMenu;
        if (menu.slots.size() < 46) { status = "native player armor slots unavailable"; return false; }
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot source = menu.getSlot(index);
            if (source.container != client.player.getInventory() || source.getContainerSlot() < 0 || source.getContainerSlot() >= 36
                    || !source.mayPickup(client.player)) continue;
            ItemStack stack = source.getItem();
            if (stack.isEmpty() || stack.isEnchanted() || GameApi.hasCustomName(stack)
                    || stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage()) continue;
            if (!ordinary(stack)) continue;
            int destination = -1;
            boolean ambiguous = false;
            for (int armor = 5; armor <= 8; armor++) {
                Slot slot = menu.getSlot(armor);
                if (!slot.mayPlace(stack)) continue;
                if (destination >= 0) { ambiguous = true; break; }
                destination = armor;
            }
            if (ambiguous || destination < 0 || !menu.getSlot(destination).getItem().isEmpty()
                    || menu.getSlot(destination).getMaxStackSize(stack) != 1) continue;
            handler = menu;
            owningPlayer = new WeakReference<>(client.player);
            owningWorld = new WeakReference<>(client.level);
            sourceSlot = index;
            armorSlot = destination;
            expected = stack.copy();
            sourceCount = stack.getCount();
            waitTicks = 0;
            status = "waiting for armor quick-move receipt";
            try {
                OwnedClickReceipts.inventoryClick(client, menu.containerId, index, 0, ContainerInput.QUICK_MOVE, client.player);
            } catch (RuntimeException failure) {
                throw fail("native armor quick-move failed: " + failure.getClass().getSimpleName());
            }
            return true;
        }
        status = "empty armor slots checked";
        return false;
    }

    private static boolean ordinary(ItemStack stack) {
        ItemStack normalized = stack.copy(), ordinary = new ItemStack(stack.getItem());
        if (normalized.isDamageableItem()) { normalized.setDamageValue(0); ordinary.setDamageValue(0); }
        return same(normalized, ordinary);
    }

    private boolean observeTransfer() {
        if (!safeContext() || client.player.inventoryMenu != handler)
            throw fail("player inventory context changed after the armor quick-move");
        ItemStack source = handler.getSlot(sourceSlot).getItem();
        ItemStack destination = handler.getSlot(armorSlot).getItem();
        boolean unchangedSource = source.getCount() == sourceCount && same(source, expected);
        boolean movedSource = sourceCount == 1 ? source.isEmpty()
                : source.getCount() == sourceCount - 1 && same(source, expected);
        if (movedSource && destination.getCount() == 1 && matchesEquipped(destination)) {
            clearTransfer();
            status = "armor equipped; source and destination confirmed";
            return true;
        }
        if ((!unchangedSource && !movedSource) || (!destination.isEmpty() && !matchesEquipped(destination)))
            throw fail("armor source or destination changed unexpectedly");
        if (++waitTicks >= MAX_RECEIPT_TICKS)
            throw fail("armor quick-move has no matching receipt after 40 ticks; inspect the player inventory");
        return true;
    }

    private boolean matchesEquipped(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!expected.isDamageableItem()) return same(stack, expected);
        if (!stack.isDamageableItem() || stack.getDamageValue() < expected.getDamageValue()
                || stack.getDamageValue() >= stack.getMaxDamage()) return false;
        // Armor may take ordinary damage before the next client tick observes it.
        ItemStack before = expected.copy(), after = stack.copy();
        before.setDamageValue(0);
        after.setDamageValue(0);
        return same(before, after);
    }

    private boolean safeContext() {
        return client.player != null && client.level != null && client.gameMode != null
                && client.player.isAlive() && Float.isFinite(client.player.getHealth())
                && !client.player.getAbilities().instabuild && !client.player.isSpectator()
                && GameApi.screen(client) == null && !client.player.isUsingItem()
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.inventoryMenu.getCarried().isEmpty();
    }

    private static boolean same(ItemStack first, ItemStack second) { return ItemStack.isSameItemSameComponents(first, second); }

    void stop() {
        if (active()) {
            blocked = true;
            status = "equipment stopped; issued armor quick-move remains unconfirmed";
        }
        clearTransfer();
    }

    private IllegalStateException fail(String reason) {
        blocked = true;
        status = reason;
        clearTransfer();
        return new IllegalStateException(reason);
    }

    private void clearTransfer() {
        handler = null;
        expected = ItemStack.EMPTY;
        waitTicks = 0;
    }
}
