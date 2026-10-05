package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.PlanKind;
import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.RecipeType;
import dev.lodekeeper.core.SelectedItemRequirement;
import dev.lodekeeper.core.StationId;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Executes a planned native stonecutting recipe without touching unrelated station contents. */
final class StonecuttingAction {
    private static final StationId STONECUTTER = StationId.parse("minecraft:stonecutter");
    private static final int MAX_SELECTION_WAIT_TICKS = 40;
    private static final int SELECTION_RETRY_TICKS = 5;

    private enum MovePurpose { OUTPUT, INPUT_DRAIN }

    private final MinecraftClient client;
    private final StonecuttingWork work;
    private final BooleanSupplier recipeCurrent;
    private final Ingredient inputPredicate;
    private final ItemStack expectedOutput;
    private final Item outputItem;
    private final int operations, outputPerOperation, plannedOutput;
    private final Map<ItemId, Integer> remainingMaterials = new LinkedHashMap<>();

    private StonecutterScreenHandler handler;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private ItemId transferMaterial;
    private ItemStack transferInput;
    private ItemStack expectedInput;
    private int transferAmount;
    private int submittedInput, returnedInput, collectedOutput;
    private int quickMoveInputAmount;
    private int lastObservedConsumed;
    private int outputSelectionIndex = -1;
    private int selectionTargetIndex = -1;
    private int selectionWaitTicks, previewWaitTicks;
    private MovePurpose movePurpose;
    private boolean initialized, drainRequested;

    private static final class StaleRecipeBeforeOutputClick extends RuntimeException {}
    private static final class SelectionChangedBeforeOutputClick extends RuntimeException {}
    private static final class PreviewChangedBeforeOutputClick extends RuntimeException {}

    StonecuttingAction(MinecraftClient client, PlayerActions actions, StonecuttingWork work,
                       PlanStep step, BooleanSupplier recipeCurrent) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(actions, "actions");
        this.work = Objects.requireNonNull(work, "work");
        Objects.requireNonNull(step, "step");
        this.recipeCurrent = Objects.requireNonNull(recipeCurrent, "recipeCurrent");
        if (step.kind() != PlanKind.CRAFT || step.recipeType() != RecipeType.SHAPELESS
                || step.recipeWidth() != 0 || step.recipeHeight() != 0
                || !STONECUTTER.equals(step.station()) || !work.sourceId().equals(step.sourceId())) {
            throw new IllegalArgumentException("Stonecutter action received a mismatched plan step");
        }
        if (work.selectionKey() == null) throw new IllegalArgumentException("Stonecutting recipe has no native selection key");

        inputPredicate = Objects.requireNonNull(work.input(), "stonecutting input");
        expectedOutput = work.outputPerOperation();
        if (expectedOutput == null || expectedOutput.isEmpty()) throw new IllegalArgumentException("Stonecutting output is empty");
        outputItem = expectedOutput.getItem();
        outputPerOperation = expectedOutput.getCount();
        operations = step.operationCount();
        long outputCount = (long) operations * outputPerOperation;
        if (operations < 1 || outputPerOperation < 1 || outputPerOperation > expectedOutput.getMaxCount()
                || outputCount > Integer.MAX_VALUE
                || step.outputCount() != outputCount || !step.output().equals(GameCatalog.id(outputItem))) {
            throw new IllegalArgumentException("Stonecutting plan disagrees with its native output quantity");
        }
        plannedOutput = (int) outputCount;

        ItemStack[] matchingInputs = inputPredicate.getMatchingStacks();
        if (matchingInputs.length == 0) throw new IllegalArgumentException("Stonecutting input has no known item alternatives");
        for (ItemStack candidate : matchingInputs) {
            if (!candidate.isEmpty() && candidate.isOf(outputItem)) {
                throw new IllegalArgumentException("Stonecutting input and output must be different items");
            }
        }

        int selectedCount = 0;
        for (var requirement : step.requirements()) {
            if (!(requirement instanceof SelectedItemRequirement selected)) continue;
            if (!"recipe ingredient".equals(selected.purpose()) || selected.recipeSlot() != 0 || !selected.consumed()) {
                throw new IllegalArgumentException("Stonecutting plan has an unsupported selected-item requirement");
            }
            Item item = GameCatalog.item(selected.item());
            if (item == outputItem) throw new IllegalArgumentException("Stonecutting input and output must be different items");
            remainingMaterials.merge(selected.item(), selected.count(), Math::addExact);
            selectedCount = Math.addExact(selectedCount, selected.count());
        }
        if (remainingMaterials.isEmpty() || selectedCount != operations) {
            throw new IllegalArgumentException("Stonecutting plan must select exactly one input per operation");
        }
    }

    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        if (!initialized) initialize();
        if (client.player.currentScreenHandler != handler) {
            throw new IllegalStateException("Stonecutter changed or closed during the planned transaction");
        }

        if (transfer != null) return advanceInputTransfer();
        if (quickMove != null) return advanceQuickMove();
        if (!handler.getCursorStack().isEmpty()) {
            throw new IllegalStateException("Cursor is occupied; finish your inventory action before stonecutting");
        }
        validateLedger();

        if (!drainRequested && submittedInput > returnedInput && !recipeCurrent.getAsBoolean()) {
            drainRequested = true;
        }
        if (drainRequested) return drainOwnedInput();

        ItemStack currentInput = handler.getSlot(0).getStack();
        if (!currentInput.isEmpty()) {
            prepareOutputMove();
            return false;
        }

        if (collectedOutput == plannedOutput) {
            if (submittedInput != operations || !allMaterialsSubmitted()) {
                throw new IllegalStateException("Stonecutting output completed before all planned input was submitted");
            }
            return true;
        }
        if (submittedInput >= operations || !hasRemainingMaterials()) {
            throw new IllegalStateException("Stonecutting plan ended before its exact output was collected; leaving the station open");
        }
        if (!recipeCurrent.getAsBoolean()) {
            throw new IllegalStateException("Stonecutting recipe changed before any new input was transferred");
        }
        if (!handler.getSlot(1).getStack().isEmpty()) {
            if (++previewWaitTicks > MAX_SELECTION_WAIT_TICKS) {
                throw new IllegalStateException("Stonecutter output did not clear after its input was consumed; leaving the station open");
            }
            return false;
        }
        previewWaitTicks = 0;
        startNextInputBatch();
        return false;
    }

    private void initialize() {
        ScreenHandler current = client.player.currentScreenHandler;
        if (current == null || current.getClass() != StonecutterScreenHandler.class) {
            throw new IllegalStateException("Open the required native stonecutter");
        }
        handler = (StonecutterScreenHandler) current;
        if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied");
        if (!handler.getSlot(0).getStack().isEmpty() || !handler.getSlot(1).getStack().isEmpty()) {
            throw new IllegalStateException("Stonecutter input or output already contains items; clear it before automation");
        }
        initialized = true;
    }

    private boolean advanceInputTransfer() {
        if (!transfer.tick()) return false;
        ItemStack placed = handler.getSlot(0).getStack();
        if (placed.isEmpty() || placed.getCount() != transferAmount
                || !GameApi.canCombine(placed, transferInput) || !inputPredicate.test(placed)) {
            throw new IllegalStateException("Stonecutter input transfer did not match the selected recipe cohort; leaving the station open");
        }
        int budget = remainingMaterials.getOrDefault(transferMaterial, 0);
        if (budget < transferAmount) throw new IllegalStateException("Stonecutting material budget changed during input transfer");
        remainingMaterials.put(transferMaterial, budget - transferAmount);
        submittedInput = Math.addExact(submittedInput, transferAmount);
        expectedInput = placed.copyWithCount(1);
        transfer = null;
        transferMaterial = null;
        transferInput = null;
        transferAmount = 0;
        selectionTargetIndex = -1;
        selectionWaitTicks = 0;
        previewWaitTicks = 0;
        validateLedger();
        return false;
    }

    private boolean advanceQuickMove() {
        try {
            if (!quickMove.tick()) return false;
        } catch (StaleRecipeBeforeOutputClick stale) {
            quickMove = null;
            movePurpose = null;
            drainRequested = true;
            return false;
        } catch (SelectionChangedBeforeOutputClick changed) {
            quickMove = null;
            movePurpose = null;
            outputSelectionIndex = -1;
            selectionTargetIndex = -1;
            selectionWaitTicks = 0;
            return false;
        } catch (PreviewChangedBeforeOutputClick changed) {
            quickMove = null;
            movePurpose = null;
            outputSelectionIndex = -1;
            previewWaitTicks = 0;
            return false;
        }

        int moved = quickMove.movedCount();
        MovePurpose completedPurpose = movePurpose;
        if (completedPurpose == MovePurpose.OUTPUT) {
            int consumedNow = consumedInputCount();
            int consumedBefore = lastObservedConsumed;
            int consumed = consumedNow - consumedBefore;
            long expectedMove = (long) outputPerOperation * consumed;
            long nextCollected = (long) collectedOutput + moved;
            if (consumed < 1 || moved != expectedMove || nextCollected > plannedOutput
                    || nextCollected != (long) outputPerOperation * consumedNow) {
                throw new IllegalStateException("Stonecutter output and input deltas did not match the planned operations; leaving the station open");
            }
            if (!handler.getSlot(0).getStack().isEmpty()) validateOwnedInput();
            collectedOutput = Math.addExact(collectedOutput, moved);
            outputSelectionIndex = -1;
            lastObservedConsumed = consumedNow;
            validateLedger();
            quickMove = null;
            movePurpose = null;
        } else if (completedPurpose == MovePurpose.INPUT_DRAIN) {
            if (moved < 1 || moved > quickMoveInputAmount) {
                throw new IllegalStateException("Stonecutter returned an unexpected amount of owned input; leaving the station open");
            }
            int nextReturned = Math.addExact(returnedInput, moved);
            int current = handler.getSlot(0).getStack().getCount();
            int nextConsumed = submittedInput - nextReturned - current;
            if (nextReturned > submittedInput || nextConsumed != lastObservedConsumed
                    || nextConsumed < 0 || collectedOutput != (long) outputPerOperation * nextConsumed) {
                throw new IllegalStateException("Returned stonecutter input did not preserve the transaction ledger; leaving the station open");
            }
            if (current > 0) validateOwnedInput();
            returnedInput = nextReturned;
            quickMoveInputAmount = 0;
            validateLedger();
            quickMove = null;
            movePurpose = null;
        } else {
            throw new IllegalStateException("Stonecutter transaction lost its movement purpose");
        }
        return false;
    }

    private void startNextInputBatch() {
        if (!handler.getSlot(0).getStack().isEmpty()) throw new IllegalStateException("Stonecutter input is not empty before a new batch");
        Supply supply = findSupply();
        ItemStack source = supply.stack();
        var destination = handler.getSlot(0);
        if (!destination.canInsert(source)) throw new IllegalStateException("Stonecutter cannot accept the selected input stack");
        int destinationRoom = destination.getMaxItemCount(source);
        int outputCapacity = exactOutputCapacity();
        int outputOperationCapacity = outputCapacity / outputPerOperation;
        int remainingOperations = operations - submittedInput;
        int materialBudget = remainingMaterials.getOrDefault(supply.id(), 0);
        int amount = Math.min(64, Math.min(source.getCount(), Math.min(destinationRoom,
                Math.min(remainingOperations, Math.min(materialBudget, outputOperationCapacity)))));
        if (amount < 1) {
            throw new IllegalStateException("Stonecutter output inventory lacks room for one planned operation; no new input was inserted");
        }
        transferMaterial = supply.id();
        transferInput = source.copyWithCount(1);
        transferAmount = amount;
        transfer = new SlotTransfer(client, handler, supply.slotId(), 0, amount);
    }

    private record Supply(int slotId, ItemId id, ItemStack stack) {
        private Supply { stack = stack.copy(); }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    private Supply findSupply() {
        for (Map.Entry<ItemId, Integer> material : remainingMaterials.entrySet()) {
            if (material.getValue() < 1) continue;
            Item item = GameCatalog.item(material.getKey());
            if (item == outputItem) throw new IllegalStateException("Stonecutting input and output must be different items");
            for (var slot : handler.slots) {
                if (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36) continue;
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty() && stack.isOf(item) && inputPredicate.test(stack)) {
                    return new Supply(slot.id, material.getKey(), stack);
                }
            }
        }
        throw new IllegalStateException("A selected stonecutting ingredient no longer matches an inventory stack; no unmatched item was consumed");
    }

    private void prepareOutputMove() {
        if (!recipeCurrent.getAsBoolean()) {
            drainRequested = true;
            return;
        }
        int recipeIndex = GameApi.stonecuttingRecipeIndex(client, handler, work);
        if (recipeIndex < 0) {
            drainRequested = true;
            return;
        }
        if (handler.getSelectedRecipe() != recipeIndex) {
            if (selectionTargetIndex != recipeIndex) {
                selectionTargetIndex = recipeIndex;
                selectionWaitTicks = 0;
                client.interactionManager.clickButton(handler.syncId, recipeIndex);
            } else if (++selectionWaitTicks % SELECTION_RETRY_TICKS == 0) {
                client.interactionManager.clickButton(handler.syncId, recipeIndex);
            }
            if (selectionWaitTicks > MAX_SELECTION_WAIT_TICKS) drainRequested = true;
            return;
        }
        selectionTargetIndex = recipeIndex;
        selectionWaitTicks = 0;
        ItemStack result = handler.getSlot(1).getStack();
        if (!isExpectedOutput(result)) {
            if (++previewWaitTicks > MAX_SELECTION_WAIT_TICKS) drainRequested = true;
            return;
        }
        previewWaitTicks = 0;
        validateLedger();
        validateOwnedInput();
        outputSelectionIndex = recipeIndex;
        quickMove = new VerifiedQuickMove(client, handler, 1, outputItem, "stonecutter output",
                this::authorizedOutputAdjustment, this::validateOutputBeforeClick);
        movePurpose = MovePurpose.OUTPUT;
    }

    private void validateOutputBeforeClick() {
        if (drainRequested || !recipeCurrent.getAsBoolean()) throw new StaleRecipeBeforeOutputClick();
        int recipeIndex = GameApi.stonecuttingRecipeIndex(client, handler, work);
        if (recipeIndex < 0) throw new StaleRecipeBeforeOutputClick();
        if (handler.getSelectedRecipe() != recipeIndex) throw new SelectionChangedBeforeOutputClick();
        ItemStack result = handler.getSlot(1).getStack();
        if (!isExpectedOutput(result)) throw new PreviewChangedBeforeOutputClick();
        validateLedger();
        validateOwnedInput();
        int currentInput = handler.getSlot(0).getStack().getCount();
        long requiredCapacity = (long) outputPerOperation * currentInput;
        if (currentInput < 1 || exactOutputCapacity() < requiredCapacity) {
            throw new IllegalStateException("Stonecutter output needs room for every owned input before transfer; clear inventory space and resume");
        }
        outputSelectionIndex = recipeIndex;
    }

    private int authorizedOutputAdjustment() {
        long adjustment = (long) outputPerOperation * consumedInputCount();
        if (outputSelectionIndex >= 0 && handler.getSelectedRecipe() == outputSelectionIndex
                && handler.getSlot(0).getStack().getCount() > 0
                && isExpectedOutput(handler.getSlot(1).getStack())) {
            adjustment += outputPerOperation;
        }
        if (adjustment < 0 || adjustment > Integer.MAX_VALUE) {
            throw new IllegalStateException("Stonecutter output authorization exceeded its planned bound");
        }
        return (int) adjustment;
    }

    private boolean isExpectedOutput(ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == outputPerOperation
                && GameApi.canCombine(stack, expectedOutput);
    }

    private boolean drainOwnedInput() {
        validateLedger();
        ItemStack input = handler.getSlot(0).getStack();
        if (input.isEmpty()) {
            if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied while draining stonecutter input");
            return true;
        }
        validateOwnedInput();
        quickMoveInputAmount = input.getCount();
        quickMove = new VerifiedQuickMove(client, handler, 0, input.getItem(), "owned stonecutter input",
                null, this::validateDrainBeforeClick);
        movePurpose = MovePurpose.INPUT_DRAIN;
        return false;
    }

    private void validateDrainBeforeClick() {
        if (!drainRequested) throw new IllegalStateException("Stonecutter drain was cancelled before returning owned input");
        validateLedger();
        validateOwnedInput();
        if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied while draining stonecutter input");
        // The result slot is virtual while input remains. Drain must not collect or trust it.
    }

    private void validateOwnedInput() {
        ItemStack input = handler.getSlot(0).getStack();
        if (input.isEmpty()) throw new IllegalStateException("Owned stonecutter input disappeared; leaving the station open");
        if (expectedInput == null || !GameApi.canCombine(input, expectedInput) || !inputPredicate.test(input)) {
            throw new IllegalStateException("Stonecutter input components or recipe predicate changed; leaving the station open");
        }
        int outstanding = submittedInput - returnedInput;
        if (outstanding < 0 || input.getCount() > outstanding) {
            throw new IllegalStateException("Stonecutter contains unexplained input; leaving the station open");
        }
    }

    private int consumedInputCount() {
        if (handler == null) return 0;
        int outstanding = submittedInput - returnedInput;
        int current = handler.getSlot(0).getStack().getCount();
        int consumed = outstanding - current;
        if (outstanding < 0 || current > outstanding || consumed < 0 || consumed > operations) {
            throw new IllegalStateException("Stonecutter input ownership ledger is inconsistent; leaving the station open");
        }
        return consumed;
    }

    private void validateLedger() {
        if (handler == null) return;
        int consumed = consumedInputCount();
        long authorizedOutput = (long) outputPerOperation * consumed;
        if (collectedOutput != authorizedOutput || collectedOutput < 0 || collectedOutput > plannedOutput
                || submittedInput > operations || returnedInput > submittedInput) {
            throw new IllegalStateException("Stonecutter input and collected-output ledger disagree; leaving the station open");
        }
        ItemStack input = handler.getSlot(0).getStack();
        if (!input.isEmpty()) validateOwnedInput();
    }

    private int exactOutputCapacity() {
        long capacity = 0;
        for (var slot : handler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36
                    || !slot.canInsert(expectedOutput)) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                capacity += slot.getMaxItemCount(expectedOutput);
            } else if (GameApi.canCombine(stack, expectedOutput)) {
                capacity += Math.max(0, slot.getMaxItemCount(expectedOutput) - stack.getCount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    private boolean hasRemainingMaterials() {
        return remainingMaterials.values().stream().anyMatch(count -> count > 0);
    }

    private boolean allMaterialsSubmitted() {
        return remainingMaterials.values().stream().allMatch(count -> count == 0);
    }

    void requestDrain() { drainRequested = true; }

    void recover() {
        if (transfer != null) transfer.recover();
        if (handler == null) return;
        if (client.player == null || client.player.currentScreenHandler != handler) {
            if (quickMove != null || !handler.getSlot(0).getStack().isEmpty()) {
                throw new IllegalStateException("Stonecutter ownership is unresolved; keep the station open for recovery");
            }
        }
        if (!handler.getCursorStack().isEmpty()) {
            throw new IllegalStateException("Return the cursor stack before closing the stonecutter");
        }
        if (quickMove != null) {
            throw new IllegalStateException("A stonecutter quickmove is still being verified; keep the station open and resume recovery");
        }
        if (!handler.getSlot(0).getStack().isEmpty()) {
            throw new IllegalStateException("Owned stonecutter input remains; keep the station open and resume to drain it");
        }
    }

    void pause() { recover(); }
}
