package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/** Serialized cursor transaction. Never drops stacks and leaves remainders in the source slot. */
final class SlotTransfer {
    private final MinecraftClient client;
    private final ScreenHandler handler;
    private final int source, destination;
    private int remaining, phase, cooldown;
    private boolean complete;
    SlotTransfer(MinecraftClient client, ScreenHandler handler, int source, int destination, int amount) {
        this.client = client; this.handler = handler; this.source = source; this.destination = destination;
        if (amount < 1 || amount > 64 || source == destination) throw new IllegalArgumentException("invalid transfer");
        remaining = amount;
    }
    boolean tick() {
        if (complete) return true;
        if (client.player == null || client.interactionManager == null || client.player.currentScreenHandler != handler) throw new IllegalStateException("Container changed during transfer");
        if (cooldown-- > 0) return false;
        if (phase == 0) {
            if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");
            if (handler.getSlot(source).getStack().getCount() < remaining) throw new IllegalStateException("Ingredient changed during transfer");
            click(source, 0); phase = 1;
        } else if (phase == 1) {
            if (handler.getCursorStack().isEmpty()) throw new IllegalStateException("Server rejected source pickup");
            // Move the whole stack with one click when exactly the requested amount is held.
            if (handler.getCursorStack().getCount() == remaining) { click(destination, 0); remaining = 0; }
            else { click(destination, 1); remaining--; }
            if (remaining == 0) phase = 2;
        } else {
            if (!handler.getCursorStack().isEmpty()) click(source, 0);
            if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Could not return inventory remainder safely");
            complete = true;
        }
        cooldown = 2;
        return complete;
    }
    void recover() {
        if (client.player != null && client.interactionManager != null && client.player.currentScreenHandler == handler && !handler.getCursorStack().isEmpty()) click(source, 0);
    }
    private void click(int slot, int button) { client.interactionManager.clickSlot(handler.syncId, slot, button, SlotActionType.PICKUP, client.player); }
}
