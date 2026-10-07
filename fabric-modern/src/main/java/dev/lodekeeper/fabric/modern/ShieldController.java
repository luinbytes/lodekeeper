package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.ItemId;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.Map;

public final class ShieldController {
    private enum Phase { IDLE, EQUIP_WAIT, READY, RESTORE_WAIT, ABANDONED }
    private static ShieldController owner;
    private final Minecraft client;
    private final PlayerActions actions;
    private Phase phase = Phase.IDLE;
    private Object player, world;
    private AbstractContainerMenu handler;
    private ItemStack shield = ItemStack.EMPTY;
    private ItemStack[] inventory;
    private int source = -1, waitTicks;
    private long sourceSequence, offhandSequence;
    private boolean holding, temporary;

    ShieldController(Minecraft client, PlayerActions actions) {
        this.client = client;
        this.actions = actions;
        owner = this;
    }

    public static boolean isHoldingUse() {
        return owner != null && owner.holding && owner.contextCurrent() && !owner.manualInput()
                && GameApi.ownsShieldUse(owner.client.player, owner.shield);
    }

    boolean ownsUse() {
        return holding && !client.options.keyUse.isDown() && client.player == player && client.level == world && client.player != null
                && GameApi.ownsShieldUse(client.player, shield);
    }

    boolean available(Map<ItemId, Integer> protection) {
        if (client.player == null) return false;
        ItemStack offhand = client.player.getOffhandItem();
        int reserved = protection.getOrDefault(GameCatalog.id(Items.SHIELD), 0);
        int usable = GameApi.ordinaryShield(offhand) ? 1 : 0;
        for (int index = 0; index < 36; index++)
            if (GameApi.ordinaryShield(client.player.getInventory().getItem(index))) usable++;
        if (usable <= reserved) return false;
        if (!offhand.isEmpty()) return GameApi.ordinaryShield(offhand);
        for (int index = 0; index < 36; index++)
            if (GameApi.ordinaryShield(client.player.getInventory().getItem(index))) return true;
        return false;
    }

    boolean prepare(Map<ItemId, Integer> protection) {
        if (phase == Phase.ABANDONED) return true;
        if (phase == Phase.EQUIP_WAIT || phase == Phase.RESTORE_WAIT) return observeSwap();
        if (phase == Phase.READY) {
            if (!available(protection)) return finish();
            if (!contextCurrent() || !inventoryCurrent() || temporary && !handler.getSlot(source).getItem().isEmpty() || !GameApi.sameShield(client.player.getOffhandItem(), shield)) {
                abandon();
                throw new IllegalStateException("shield inventory ownership changed");
            }
            return true;
        }
        if (!safeContext() || manualInput() || !available(protection)) return true;
        player = client.player;
        world = client.level;
        handler = client.player.inventoryMenu;
        ItemStack offhand = client.player.getOffhandItem();
        if (!offhand.isEmpty()) {
            shield = offhand.copy();
            captureInventory();
            phase = Phase.READY;
            return true;
        }
        if (!(handler instanceof OwnedClickReceipts.Receipt) || handler.slots.size() != 46) return true;
        for (var slot : handler.slots) {
            if (slot.container != client.player.getInventory() || slot.getContainerSlot() < 0 || slot.getContainerSlot() >= 36
                    || !GameApi.ordinaryShield(slot.getItem()) || !slot.mayPickup(client.player)) continue;
            source = slot.index;
            shield = slot.getItem().copy();
            temporary = true;
            captureInventory();
            issueSwap(Phase.EQUIP_WAIT);
            return false;
        }
        return true;
    }

    void block() {
        if (phase != Phase.READY || !contextCurrent() || !inventoryCurrent() || manualInput()
                || !GameApi.ordinaryShield(client.player.getOffhandItem())) { release(); return; }
        if (ownsUse()) return;
        if (client.player.isUsingItem()) return;
        holding = GameApi.startShieldUse(client);
        if (holding) shield = client.player.getOffhandItem().copy();
    }

    void release() {
        boolean owned = ownsUse();
        holding = false;
        if (owned && client.gameMode != null) client.gameMode.releaseUsingItem(client.player);
    }

    boolean finish() {
        release();
        if (phase == Phase.EQUIP_WAIT || phase == Phase.RESTORE_WAIT) return observeSwap();
        if (phase != Phase.READY || !temporary) { reset(); return true; }
        if (!contextCurrent() || !inventoryCurrent() || manualInput()
                || !handler.getSlot(source).getItem().isEmpty()
                || !GameApi.sameShield(client.player.getOffhandItem(), shield)) { abandon(); return true; }
        shield = client.player.getOffhandItem().copy();
        issueSwap(Phase.RESTORE_WAIT);
        return false;
    }

    private void issueSwap(Phase next) {
        var receipt = (OwnedClickReceipts.Receipt) handler;
        sourceSequence = receipt.lodekeeper$slotSequence(source);
        offhandSequence = receipt.lodekeeper$slotSequence(45);
        phase = next;
        waitTicks = 0;
        OwnedClickReceipts.inventoryClick(client, handler.containerId, source, 40, ContainerInput.SWAP, client.player);
    }

    private boolean observeSwap() {
        if (!contextCurrent() || !inventoryCurrent() || manualInput()) { abandon(); return true; }
        var receipt = (OwnedClickReceipts.Receipt) handler;
        ItemStack sourceNow = handler.getSlot(source).getItem(), offhand = handler.getSlot(45).getItem();
        boolean equipping = phase == Phase.EQUIP_WAIT;
        boolean moved = equipping ? sourceNow.isEmpty() && GameApi.sameShield(offhand, shield)
                : offhand.isEmpty() && GameApi.sameShield(sourceNow, shield);
        boolean unchanged = equipping ? GameApi.sameShield(sourceNow, shield) && offhand.isEmpty()
                : sourceNow.isEmpty() && GameApi.sameShield(offhand, shield);
        if (!moved && !unchanged) { abandon(); throw new IllegalStateException("shield swap source or offhand changed"); }
        ItemStack receivedSource = receipt.lodekeeper$receivedSlot(source), receivedOffhand = receipt.lodekeeper$receivedSlot(45);
        boolean confirmed = receipt.lodekeeper$slotSequence(source) > sourceSequence
                && receipt.lodekeeper$slotSequence(45) > offhandSequence
                && (equipping ? receivedSource.isEmpty() && GameApi.sameShield(receivedOffhand, shield)
                : receivedOffhand.isEmpty() && GameApi.sameShield(receivedSource, shield));
        if (moved && confirmed) {
            if (equipping) { shield = offhand.copy(); phase = Phase.READY; captureInventory(); }
            else reset();
            return true;
        }
        if (++waitTicks >= 40) { abandon(); throw new IllegalStateException("shield swap not confirmed after 40 ticks; inspect the inventory"); }
        return false;
    }

    private void captureInventory() {
        inventory = new ItemStack[36];
        for (int index = 0; index < 36; index++) inventory[index] = client.player.getInventory().getItem(index).copy();
    }

    private boolean inventoryCurrent() {
        if (inventory == null || client.player == null) return false;
        for (int index = 0; index < 36; index++) {
            if (source >= 0 && handler.getSlot(source).getContainerSlot() == index) continue;
            ItemStack before = inventory[index], after = client.player.getInventory().getItem(index);
            if (before.isEmpty() && after.isEmpty()) continue;
            ItemStack normalized = after.copy(), expected = before.copy();
            if (normalized.isDamageableItem() && expected.isDamageableItem()) { normalized.setDamageValue(0); expected.setDamageValue(0); }
            if (after.getCount() != before.getCount() || !ItemStack.isSameItemSameComponents(normalized, expected)) return false;
        }
        return true;
    }

    private boolean contextCurrent() {
        return safeContext() && client.player == player && client.level == world
                && client.player.inventoryMenu == handler;
    }

    private boolean safeContext() {
        return client.player != null && client.level != null && client.gameMode != null
                && client.player.isAlive() && GameApi.screen(client) == null
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.inventoryMenu.getCarried().isEmpty();
    }

    boolean manualInput() {
        var options = client.options;
        return options.keyAttack.isDown() || options.keyUse.isDown()
                || options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown() || options.keyShift.isDown() || options.keySprint.isDown();
    }

    void stop() {
        release();
        try { finish(); } catch (RuntimeException ignored) { abandon(); }
    }

    private void abandon() { release(); phase = Phase.ABANDONED; temporary = false; }
    void begin() { if (phase != Phase.RESTORE_WAIT && phase != Phase.EQUIP_WAIT) reset(); }
    private void reset() {
        holding = temporary = false;
        phase = Phase.IDLE;
        player = world = null;
        handler = null;
        inventory = null;
        shield = ItemStack.EMPTY;
        source = -1;
    }
}
