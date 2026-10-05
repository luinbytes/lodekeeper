package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import java.util.function.IntSupplier;

/** Quick-moves owned station contents and waits for matching source and inventory deltas. */
final class VerifiedQuickMove {
    private static final int MAX_OBSERVATION_TICKS = 40;

    private final MinecraftClient client;
    private final ScreenHandler handler;
    private final int sourceSlot;
    private final Item item;
    private final String description;
    /** Cumulative authorized source regeneration, for example output from consumed furnace input. */
    private final IntSupplier sourceAdjustment;
    private final Runnable beforeClick;
    private ItemStack expectedStack;
    private int sourceBefore, inventoryBefore, adjustmentBefore;
    private int observations, movedCount;
    private boolean clicked, waitingForCapacity;
    private int capacityWhenBlocked, waitingSourceCount, waitingInventoryCount, waitingAdjustment;

    VerifiedQuickMove(MinecraftClient client, ScreenHandler handler,
                      int sourceSlot, Item item, String description) {
        this(client, handler, sourceSlot, item, description, null, null);
    }

    VerifiedQuickMove(MinecraftClient client, ScreenHandler handler,
                      int sourceSlot, Item item, String description, IntSupplier sourceAdjustment) {
        this(client, handler, sourceSlot, item, description, sourceAdjustment, null);
    }

    VerifiedQuickMove(MinecraftClient client, ScreenHandler handler, int sourceSlot, Item item,
                      String description, IntSupplier sourceAdjustment, Runnable beforeClick) {
        this.client = client;
        this.handler = handler;
        this.sourceSlot = sourceSlot;
        this.item = item;
        this.description = description;
        this.sourceAdjustment = sourceAdjustment;
        this.beforeClick = beforeClick;
    }

    boolean tick() {
        if (client.player == null || client.interactionManager == null
                || client.player.currentScreenHandler != handler) {
            throw new IllegalStateException("Container changed during " + description);
        }
        if (!handler.getCursorStack().isEmpty()) {
            throw cannotReturn("the cursor is occupied");
        }

        ItemStack source = handler.getSlot(sourceSlot).getStack();
        if (!clicked) {
            if (beforeClick != null) beforeClick.run();
            if (source.isEmpty() || !source.isOf(item)) {
                throw new IllegalStateException("Unexpected item in " + description + " slot; leaving the container open");
            }
            expectedStack = source.copy();
            rebaseline(source.getCount(), countMatchingInventory(), adjustment());
            client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.QUICK_MOVE, client.player);
            clicked = true;
            return false;
        }

        if (!source.isEmpty() && !GameApi.canCombine(source, expectedStack)) {
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
                // Rebaseline only a source change fully explained by the caller's explicit
                // production allowance. Wait one more observation for the source to settle.
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

    private int countMatchingInventory() {
        if (client.player == null || expectedStack == null) return 0;
        int count = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) {
            if (!stack.isEmpty() && GameApi.canCombine(stack, expectedStack)) count += stack.getCount();
        }
        return count;
    }

    private int inventoryCapacity() {
        if (client.player == null || expectedStack == null) return 0;
        int capacity = 0;
        int max = expectedStack.getMaxCount();
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) {
            if (stack.isEmpty()) capacity += max;
            else if (GameApi.canCombine(stack, expectedStack)) capacity += Math.max(0, max - stack.getCount());
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

    private IllegalStateException cannotReturn(String reason) {
        return new IllegalStateException("Cannot safely return " + description + "; " + reason);
    }
}
