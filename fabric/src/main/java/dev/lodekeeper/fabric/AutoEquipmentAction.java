package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Fills empty native armor slots with one verified vanilla quick-move at a time. */
final class AutoEquipmentAction {
    private static final int MAX_RECEIPT_TICKS = 40;
    private final MinecraftClient client;
    private ScreenHandler handler;
    private Object owningPlayer, owningWorld;
    private ItemStack expected = ItemStack.EMPTY;
    private int sourceSlot, armorSlot, sourceCount, waitTicks;
    private boolean blocked;
    private String status = "equipment idle";

    AutoEquipmentAction(MinecraftClient client) { this.client = client; }

    boolean active() { return handler != null; }
    String status() { return status; }

    /** True means an issued transfer still owns the inventory-action turn. */
    boolean tick() {
        if (owningPlayer != client.player || owningWorld != client.world) {
            if (active()) throw fail("world or player changed after the armor quick-move");
            blocked = false;
        }
        if (blocked) return false;
        if (active()) return observeTransfer();
        if (!safeContext()) { status = "equipment waiting for a safe player inventory"; return false; }
        ScreenHandler menu = client.player.playerScreenHandler;
        if (menu.slots.size() < 46) { status = "native player armor slots unavailable"; return false; }
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot source = menu.getSlot(index);
            if (source.inventory != client.player.getInventory() || source.getIndex() < 0 || source.getIndex() >= 36
                    || !source.canTakeItems(client.player)) continue;
            ItemStack stack = source.getStack();
            if (stack.isEmpty() || stack.hasEnchantments() || GameApi.hasCustomName(stack)
                    || stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage()) continue;
            if (!ordinary(stack)) continue;
            int destination = -1;
            boolean ambiguous = false;
            for (int armor = 5; armor <= 8; armor++) {
                Slot slot = menu.getSlot(armor);
                if (!slot.canInsert(stack)) continue;
                if (destination >= 0) { ambiguous = true; break; }
                destination = armor;
            }
            if (ambiguous || destination < 0 || !menu.getSlot(destination).getStack().isEmpty()
                    || menu.getSlot(destination).getMaxItemCount(stack) != 1) continue;
            handler = menu;
            owningPlayer = client.player;
            owningWorld = client.world;
            sourceSlot = index;
            armorSlot = destination;
            expected = stack.copy();
            sourceCount = stack.getCount();
            waitTicks = 0;
            status = "waiting for armor quick-move receipt";
            try {
                client.interactionManager.clickSlot(menu.syncId, index, 0, SlotActionType.QUICK_MOVE, client.player);
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
        if (normalized.isDamageable()) { normalized.setDamage(0); ordinary.setDamage(0); }
        return same(normalized, ordinary);
    }

    private boolean observeTransfer() {
        if (!safeContext() || client.player.playerScreenHandler != handler)
            throw fail("player inventory context changed after the armor quick-move");
        ItemStack source = handler.getSlot(sourceSlot).getStack();
        ItemStack destination = handler.getSlot(armorSlot).getStack();
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
        if (!expected.isDamageable()) return same(stack, expected);
        if (!stack.isDamageable() || stack.getDamage() < expected.getDamage()
                || stack.getDamage() >= stack.getMaxDamage()) return false;
        // Armor may take ordinary damage before the next client tick observes it.
        ItemStack before = expected.copy(), after = stack.copy();
        before.setDamage(0);
        after.setDamage(0);
        return same(before, after);
    }

    private boolean safeContext() {
        return client.player != null && client.world != null && client.interactionManager != null
                && client.player.isAlive() && Float.isFinite(client.player.getHealth())
                && !client.player.getAbilities().creativeMode && !client.player.isSpectator()
                && client.currentScreen == null && !client.player.isUsingItem()
                && client.player.currentScreenHandler == client.player.playerScreenHandler
                && client.player.playerScreenHandler.getCursorStack().isEmpty();
    }

    private static boolean same(ItemStack first, ItemStack second) { return GameApi.canCombine(first, second); }

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
