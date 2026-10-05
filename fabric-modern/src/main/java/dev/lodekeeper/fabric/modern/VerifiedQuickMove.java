package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntSupplier;

/** Quick-moves owned station stacks and verifies exact inventory/source conservation. */
final class VerifiedQuickMove {
    private static final int MAX_OBSERVATION_TICKS = 40;

    private final Minecraft client;
    private final AbstractContainerMenu menu;
    private final int sourceSlot;
    private final Item item;
    private final String description;
    /** Cumulative authorized source regeneration or consumption. */
    private final IntSupplier sourceAdjustment;
    private final Runnable beforeClick;
    private ItemStack expectedStack;
    private int sourceBefore, inventoryBefore, adjustmentBefore;
    private int observations, movedCount;
    private boolean clicked, waitingForCapacity;
    private int capacityWhenBlocked, waitingSourceCount, waitingInventoryCount, waitingAdjustment;

    VerifiedQuickMove(Minecraft client, AbstractContainerMenu menu, int sourceSlot, Item item, String description) {
        this(client, menu, sourceSlot, item, description, null, null);
    }

    VerifiedQuickMove(Minecraft client, AbstractContainerMenu menu, int sourceSlot, Item item, String description,
                      IntSupplier sourceAdjustment) {
        this(client, menu, sourceSlot, item, description, sourceAdjustment, null);
    }

    VerifiedQuickMove(Minecraft client, AbstractContainerMenu menu, int sourceSlot, Item item, String description,
                      IntSupplier sourceAdjustment, Runnable beforeClick) {
        this.client = client;
        this.menu = menu;
        this.sourceSlot = sourceSlot;
        this.item = item;
        this.description = description;
        this.sourceAdjustment = sourceAdjustment;
        this.beforeClick = beforeClick;
    }

    boolean tick() {
        if (client.player == null || client.gameMode == null || client.player.containerMenu != menu) {
            throw new IllegalStateException("Container changed during " + description);
        }
        if (!menu.getCarried().isEmpty()) throw cannotReturn("the cursor is occupied");

        ItemStack source = menu.getSlot(sourceSlot).getItem();
        if (!clicked) {
            if (beforeClick != null) beforeClick.run();
            if (source.isEmpty() || !source.is(item)) {
                throw new IllegalStateException("Unexpected item in " + description + " slot; leaving the container open");
            }
            expectedStack = source.copy();
            rebaseline(source.getCount(), countMatchingInventory(), adjustment());
            click();
            clicked = true;
            return false;
        }

        if (!source.isEmpty() && !same(source, expectedStack)) {
            throw new IllegalStateException("Unexpected item appeared in " + description + " slot; leaving the container open");
        }
        int sourceAfter = source.isEmpty() ? 0 : source.getCount();
        int inventoryNow = countMatchingInventory();
        int inventoryGain = inventoryNow - inventoryBefore;
        int adjustmentNow = adjustment();
        int observedMove = sourceBefore - sourceAfter + adjustmentNow - adjustmentBefore;

        if (waitingForCapacity) {
            if (inventoryNow != waitingInventoryCount) {
                throw cannotReturn("matching inventory contents changed while waiting; inspect the container before resuming");
            }
            if (sourceAfter != waitingSourceCount || adjustmentNow != waitingAdjustment) {
                // Allow and rebaseline a source change only when the caller's exact production/consumption
                // allowance explains it and no inventory transfer happened.
                if (observedMove != inventoryGain || inventoryGain != 0) {
                    throw cannotReturn("container contents changed while waiting; inspect the owned source before resuming");
                }
                rebaseline(sourceAfter, inventoryNow, adjustmentNow);
                waitingSourceCount = sourceAfter;
                waitingInventoryCount = inventoryNow;
                waitingAdjustment = adjustmentNow;
                return false;
            }
            if (inventoryCapacity() <= capacityWhenBlocked) {
                throw cannotReturn("free inventory space, then resume; the source has not been clicked again");
            }
            rebaseline(sourceAfter, inventoryNow, adjustmentNow);
            waitingForCapacity = false;
            clicked = false;
            return false;
        }

        if (inventoryGain > 0 && observedMove == inventoryGain) {
            movedCount = inventoryGain;
            return true;
        }
        if (++observations >= MAX_OBSERVATION_TICKS) {
            if (observedMove == 0 && inventoryGain == 0) {
                waitingForCapacity = true;
                capacityWhenBlocked = inventoryCapacity();
                waitingSourceCount = sourceAfter;
                waitingInventoryCount = inventoryNow;
                waitingAdjustment = adjustmentNow;
                throw cannotReturn("the move was rejected or inventory is full; clear space and resume");
            }
            throw cannotReturn("the source and inventory deltas did not match; leaving the owned container open");
        }
        return false;
    }

    int movedCount() { return movedCount; }

    private void click() {
        client.gameMode.handleContainerInput(menu.containerId, sourceSlot, 0, ContainerInput.QUICK_MOVE, client.player);
    }

    private int countMatchingInventory() {
        if (client.player == null || expectedStack == null) return 0;
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && same(stack, expectedStack)) count += stack.getCount();
        }
        return count;
    }

    private int inventoryCapacity() {
        if (client.player == null || expectedStack == null) return 0;
        int capacity = 0;
        int max = expectedStack.getMaxStackSize();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty()) capacity += max;
            else if (same(stack, expectedStack)) capacity += Math.max(0, max - stack.getCount());
        }
        return capacity;
    }

    private int adjustment() { return sourceAdjustment == null ? 0 : sourceAdjustment.getAsInt(); }

    private void rebaseline(int sourceCount, int inventoryCount, int adjustmentCount) {
        sourceBefore = sourceCount;
        inventoryBefore = inventoryCount;
        adjustmentBefore = adjustmentCount;
        observations = 0;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }

    private IllegalStateException cannotReturn(String reason) {
        return new IllegalStateException("Cannot safely return " + description + "; " + reason);
    }
}
