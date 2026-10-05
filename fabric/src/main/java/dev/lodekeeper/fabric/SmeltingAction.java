package dev.lodekeeper.fabric;

import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.SelectedItemRequirement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/** Uses one initially empty furnace, feeds split stacks, and consumes exactly the planned fuel. */
final class SmeltingAction {
    private final MinecraftClient client;
    private final PlayerActions actions;
    private final Item output, inputItem, fuelItem;
    private final int target;
    private int remainingInput, remainingFuel;
    private FurnaceScreenHandler handler;
    private SlotTransfer transfer;
    private int transferAmount, transferDestination, cooldown;
    private boolean initialized;
    SmeltingAction(MinecraftClient client, PlayerActions actions, AbstractCookingRecipe recipe, PlanStep step) {
        this.client = client; this.actions = actions;
        output = GameCatalog.item(step.output()); target = actions.count(output) + step.outputCount();
        var input = step.requirements().stream().filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast).filter(r -> r.purpose().equals("smelting input")).findFirst().orElseThrow();
        var fuel = step.requirements().stream().filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast).filter(r -> r.purpose().equals("smelting fuel")).findFirst().orElseThrow();
        inputItem = GameCatalog.item(input.item()); remainingInput = input.count();
        fuelItem = GameCatalog.item(fuel.item()); remainingFuel = fuel.count();
    }
    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        if (cooldown-- > 0) return false;
        if (actions.count(output) >= target) return true;
        if (!initialized) {
            if (!(client.player.currentScreenHandler instanceof FurnaceScreenHandler furnace)) throw new IllegalStateException("Open the owned furnace");
            handler = furnace;
            if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor occupied");
            for (int i = 0; i < 3; i++) if (!handler.getSlot(i).getStack().isEmpty()) throw new IllegalStateException("Furnace already contains items; automation will not take them");
            initialized = true;
        }
        if (client.player.currentScreenHandler != handler) throw new IllegalStateException("Furnace changed or closed");
        if (transfer != null) {
            if (transfer.tick()) {
                if (transferDestination == 0) remainingInput -= transferAmount; else remainingFuel -= transferAmount;
                transfer = null;
            }
            return false;
        }
        if (!handler.getSlot(2).getStack().isEmpty()) {
            if (!handler.getSlot(2).getStack().isOf(output)) throw new IllegalStateException("Unexpected furnace output");
            client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, client.player); cooldown = 8; return false;
        }
        if (handler.getSlot(0).getStack().isEmpty() && remainingInput > 0) feed(inputItem, 0, remainingInput);
        else if (handler.getSlot(1).getStack().isEmpty() && remainingFuel > 0) feed(fuelItem, 1, remainingFuel);
        return false;
    }
    private void feed(Item item, int destination, int remaining) {
        int source = source(item);
        transferAmount = Math.min(remaining, Math.min(handler.getSlot(source).getStack().getCount(), item.getMaxCount()));
        transferDestination = destination;
        transfer = new SlotTransfer(client, handler, source, destination, transferAmount);
    }
    private int source(Item item) {
        for (var slot : handler.slots) if (slot.inventory == client.player.getInventory() && slot.getIndex() < 36 && slot.getStack().isOf(item)) return slot.id;
        throw new IllegalStateException("Missing furnace supply: " + item);
    }
    long progressToken() {
        if (handler == null) return 0;
        return ((long) remainingInput << 32) ^ ((long) remainingFuel << 20) ^ ((long) handler.getSlot(0).getStack().getCount() << 12) ^ ((long) handler.getSlot(2).getStack().getCount() << 5) ^ (int) (handler.getCookProgress() * 10000);
    }
    void pause() { if (transfer != null) transfer.recover(); }
    void cancel() { pause(); }
}
