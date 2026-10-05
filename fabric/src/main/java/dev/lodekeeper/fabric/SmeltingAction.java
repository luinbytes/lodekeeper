package dev.lodekeeper.fabric;

import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.SelectedItemRequirement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.FurnaceScreenHandler;

/** Uses one initially empty furnace, feeds planned materials, and returns only confirmed owned stacks. */
final class SmeltingAction {
    private final MinecraftClient client;
    private final PlayerActions actions;
    private final Item output, inputItem, fuelItem;
    private final ItemStack expectedOutput;
    private final int target, plannedOutput, plannedInput, plannedFuel;
    private final int inputPerOperation, outputPerOperation;
    private int remainingInput, remainingFuel, collectedOutput;
    private int submittedInput, submittedFuel, returnedInput, returnedFuel;
    private int lastInputBalance, lastFuelBalance, lastOutputBalance, lastAuthorizedOutput;
    private ItemStack expectedInput, expectedFuel;
    private FurnaceScreenHandler handler;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private int transferAmount, transferDestination, quickMoveSlot = -1, cooldown;
    private boolean initialized, drainRequested;

    SmeltingAction(MinecraftClient client, PlayerActions actions, RecipeWork recipe, PlanStep step) {
        this.client = client;
        this.actions = actions;
        if (recipe.kind() != RecipeWork.Kind.SMELTING) throw new IllegalArgumentException("Smelting action received non-smelting recipe work");
        output = GameCatalog.item(step.output());
        expectedOutput = recipe.outputPerOperation();
        target = actions.count(output) + step.outputCount();
        plannedOutput = step.outputCount();
        var input = selected(step, "smelting input");
        var fuel = selected(step, "smelting fuel");
        inputItem = GameCatalog.item(input.item());
        fuelItem = GameCatalog.item(fuel.item());
        plannedInput = input.count();
        plannedFuel = fuel.count();
        if (plannedInput % step.operationCount() != 0 || plannedOutput % step.operationCount() != 0) {
            throw new IllegalStateException("Smelting plan has inconsistent per-operation quantities");
        }
        inputPerOperation = plannedInput / step.operationCount();
        outputPerOperation = plannedOutput / step.operationCount();
        if (inputPerOperation < 1 || outputPerOperation < 1 || !expectedOutput.isOf(output)
                || expectedOutput.getCount() != outputPerOperation) {
            throw new IllegalStateException("Smelting plan disagrees with its recipe output");
        }
        remainingInput = plannedInput;
        remainingFuel = plannedFuel;
    }

    private static SelectedItemRequirement selected(PlanStep step, String purpose) {
        return step.requirements().stream().filter(SelectedItemRequirement.class::isInstance)
                .map(SelectedItemRequirement.class::cast).filter(requirement -> requirement.purpose().equals(purpose))
                .findFirst().orElseThrow(() -> new IllegalStateException("Plan omitted " + purpose));
    }

    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        if (cooldown-- > 0) return false;
        if (!initialized) initialize();
        if (client.player.currentScreenHandler != handler) throw new IllegalStateException("Furnace changed or closed");
        if (transfer == null && !handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor occupied; finish your inventory action first");

        if (transfer != null) {
            try {
                if (!transfer.tick()) return false;
            } catch (RuntimeException exception) {
                if (transferDestination == 0 && !handler.getSlot(2).getStack().isEmpty()) {
                    throw new IllegalStateException("This short-cook recipe consumed input before its inventory transfer was confirmed; this timing is unsupported and the furnace is left open", exception);
                }
                throw exception;
            }
            if (transferDestination == 0) {
                remainingInput -= transferAmount;
                submittedInput += transferAmount;
                lastInputBalance += transferAmount;
            } else {
                remainingFuel -= transferAmount;
                submittedFuel += transferAmount;
                lastFuelBalance += transferAmount;
            }
            transfer = null;
            validateKnownContents();
            return false;
        }
        if (quickMove != null) {
            if (!quickMove.tick()) return false;
            int moved = quickMove.movedCount();
            if (quickMoveSlot == 0) {
                returnedInput += moved;
                lastInputBalance = handler.getSlot(0).getStack().getCount();
            } else if (quickMoveSlot == 1) {
                returnedFuel += moved;
                lastFuelBalance = handler.getSlot(1).getStack().getCount();
            } else {
                collectedOutput += moved;
                lastOutputBalance = handler.getSlot(2).getStack().getCount();
            }
            quickMove = null;
            quickMoveSlot = -1;
            validateKnownContents();
            return false;
        }

        validateKnownContents();
        if (drainRequested) return drainKnownContents();
        if (actions.count(output) >= target) return drainKnownContents();

        if (!handler.getSlot(2).getStack().isEmpty()) {
            quickMove = new VerifiedQuickMove(client, handler, 2, output, "furnace output", this::authorizedOutputCount, this::validateKnownContents);
            quickMoveSlot = 2;
            return false;
        }
        if (handler.getSlot(0).getStack().isEmpty() && remainingInput > 0) feed(inputItem, 0, remainingInput);
        else if (handler.getSlot(1).getStack().isEmpty() && remainingFuel > 0) feed(fuelItem, 1, remainingFuel);
        return false;
    }

    private void initialize() {
        if (!(client.player.currentScreenHandler instanceof FurnaceScreenHandler furnace)) {
            throw new IllegalStateException("Open the owned furnace");
        }
        handler = furnace;
        if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor occupied");
        for (int slot = 0; slot < 3; slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) {
                throw new IllegalStateException("Furnace already contains items; automation will not take them");
            }
        }
        initialized = true;
    }

    private void validateKnownContents() {
        ItemStack input = handler.getSlot(0).getStack();
        ItemStack fuel = handler.getSlot(1).getStack();
        ItemStack result = handler.getSlot(2).getStack();
        validateOwnedSlot(input, expectedInput, submittedInput - returnedInput, lastInputBalance, "furnace input");
        validateOwnedSlot(fuel, expectedFuel, submittedFuel - returnedFuel, lastFuelBalance, "furnace fuel");

        if (returnedInput > submittedInput || returnedFuel > submittedFuel) throw unexpected("furnace returned-material conservation");
        int authorizedOutput = authorizedOutputCount();
        if (collectedOutput > authorizedOutput) throw unexpected("furnace output conservation");
        if (!result.isEmpty() && (!GameApi.canCombine(result, expectedOutput)
                || result.getCount() > Math.max(0, authorizedOutput - collectedOutput))) {
            throw unexpected("furnace output");
        }
        int authorizedIncrease = Math.max(0, authorizedOutput - lastAuthorizedOutput);
        if (result.getCount() > lastOutputBalance + authorizedIncrease || result.getCount() < lastOutputBalance) {
            throw unexpected("furnace output balance");
        }
        lastInputBalance = input.getCount();
        lastFuelBalance = fuel.getCount();
        lastOutputBalance = result.getCount();
        lastAuthorizedOutput = authorizedOutput;
    }

    private void validateOwnedSlot(ItemStack stack, ItemStack expected, int ownedMaximum, int previousBalance, String name) {
        if (stack.isEmpty()) return;
        if (expected == null || !GameApi.canCombine(stack, expected) || stack.getCount() > Math.max(0, ownedMaximum)
                || stack.getCount() > previousBalance) {
            throw unexpected(name);
        }
    }

    private IllegalStateException unexpected(String name) {
        return new IllegalStateException("Unexpected or unexplained " + name + " contents; leaving the owned furnace open");
    }

    private int consumedInputCount() {
        int currentInput = handler == null ? 0 : handler.getSlot(0).getStack().getCount();
        return Math.max(0, submittedInput - returnedInput - currentInput);
    }

    private int authorizedOutputCount() {
        if (handler == null || inputPerOperation < 1) return 0;
        int completedOperations = consumedInputCount() / inputPerOperation;
        return Math.min(plannedOutput, completedOperations * outputPerOperation);
    }

    /** Returns true only after the planned output and any planned input/fuel leftovers are back in inventory. */
    private boolean drainKnownContents() {
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            Item expected = slot == 0 ? inputItem : slot == 1 ? fuelItem : output;
            String description = slot == 0 ? "furnace input" : slot == 1 ? "furnace fuel" : "furnace output";
            VerifiedQuickMove move = slot == 2 ? new VerifiedQuickMove(client, handler, slot, expected, description, this::authorizedOutputCount, this::validateKnownContents)
                    : slot == 0 ? new VerifiedQuickMove(client, handler, slot, expected, description, this::inputConsumptionAdjustment, this::validateKnownContents)
                    : new VerifiedQuickMove(client, handler, slot, expected, description, null, this::validateKnownContents);
            quickMove = move;
            quickMoveSlot = slot;
            return false;
        }
        return true;
    }

    private int inputConsumptionAdjustment() {
        int produced = collectedOutput + handler.getSlot(2).getStack().getCount();
        int completedOperations = produced / outputPerOperation;
        return -Math.min(plannedInput, completedOperations * inputPerOperation);
    }

    private void feed(Item item, int destination, int remaining) {
        int source = source(item, destination == 0 ? expectedInput : expectedFuel);
        ItemStack supply = handler.getSlot(source).getStack();
        if (destination == 0 && expectedInput == null) expectedInput = supply.copyWithCount(1);
        if (destination == 1 && expectedFuel == null) expectedFuel = supply.copyWithCount(1);
        ItemStack expected = destination == 0 ? expectedInput : expectedFuel;
        if (!GameApi.canCombine(supply, expected)) throw new IllegalStateException("Furnace supply components changed: " + item);
        transferAmount = Math.min(remaining, Math.min(supply.getCount(), item.getMaxCount()));
        transferDestination = destination;
        transfer = new SlotTransfer(client, handler, source, destination, transferAmount,
                destination == 1 ? () -> handler.isBurning() ? handler.getFuelProgress() + 1 : 0 : null);
    }

    private int source(Item item, ItemStack expected) {
        for (var slot : handler.slots) {
            if (slot.inventory == client.player.getInventory() && slot.getIndex() < 36
                    && slot.getStack().isOf(item) && (expected == null || GameApi.canCombine(slot.getStack(), expected))) return slot.id;
        }
        throw new IllegalStateException("Missing furnace supply: " + item);
    }

    long progressToken() {
        if (handler == null) return 0;
        return ((long) remainingInput << 32) ^ ((long) remainingFuel << 20)
                ^ ((long) handler.getSlot(0).getStack().getCount() << 12)
                ^ ((long) handler.getSlot(2).getStack().getCount() << 5)
                ^ ((long) collectedOutput << 3) ^ (int) (handler.getCookProgress() * 10000);
    }

    void pause() { if (transfer != null) transfer.recover(); }
    void cancel() { pause(); }
    void requestDrain() { drainRequested = true; }
}
