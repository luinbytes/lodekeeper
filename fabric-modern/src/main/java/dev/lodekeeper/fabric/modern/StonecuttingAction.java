package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.PlanKind;
import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.SelectedItemRequirement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Executes only the selected native stonecutting recipe and tracks its input/output conservation. */
final class StonecuttingAction {
    private static final int INPUT_SLOT = 0;
    private static final int RESULT_SLOT = 1;
    private static final int MAX_BATCH_OPERATIONS = 64;
    private static final int MAX_OBSERVATION_TICKS = 40;

    private enum QuickMovePurpose { OUTPUT, INPUT_DRAIN }

    private static final class InputCohort {
        final Item item;
        final ItemStack template;
        int availableCount;
        int remainingPlanned;

        InputCohort(ItemStack stack) {
            item = stack.getItem();
            template = stack.copyWithCount(1);
            availableCount = stack.getCount();
        }
    }

    private final Minecraft client;
    private final StonecuttingWork work;
    private final PlanStep step;
    private final BooleanSupplier recipeCurrent;
    private final Item outputItem;
    private final ItemStack expectedOutput;
    private final Map<Item, Integer> selectedInputBudgets = new LinkedHashMap<>();
    private final Map<Item, Integer> remainingInputBudgets = new LinkedHashMap<>();
    private final List<InputCohort> inputCohorts = new ArrayList<>();
    private final int operationsPlanned;
    private final int inputPlanned;
    private final int outputPlanned;

    private StonecutterMenu menu;
    private ItemStack expectedInput;
    private InputCohort activeCohort;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private QuickMovePurpose quickMovePurpose;
    private int transferAmount;
    private int inputDrainSourceCount;
    private int outputClickConsumedOperations;
    private int remainingOperationsToSubmit;
    private int submittedInput;
    private int returnedInput;
    private int collectedOutput;
    private int activeRecipeIndex = -1;
    private int pendingRecipeIndex = -1;
    private int selectionWaitTicks;
    private int outputWaitTicks;
    private int lastInputBalance;
    private int lastOutputBalance;
    private int lastAuthorizedOutput;
    private boolean outputQuickMoveClickIssued;
    private boolean initialized;
    private boolean drainRequested;
    private boolean drainComplete;

    StonecuttingAction(Minecraft client, PlayerActions actions, StonecuttingWork work,
                       PlanStep step, BooleanSupplier recipeCurrent) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(actions, "actions");
        this.work = Objects.requireNonNull(work, "work");
        this.step = Objects.requireNonNull(step, "step");
        this.recipeCurrent = Objects.requireNonNull(recipeCurrent, "recipeCurrent");
        if (step.kind() != PlanKind.CRAFT || !step.sourceId().equals(work.sourceId())) {
            throw new IllegalArgumentException("Stonecutting plan does not identify this native recipe");
        }

        ItemStack operationOutput = work.outputPerOperation();
        if (operationOutput.isEmpty() || operationOutput.getCount() < 1) {
            throw new IllegalArgumentException("Stonecutting recipe has no valid output");
        }
        outputItem = operationOutput.getItem();
        if (!step.output().toString().equals(BuiltInRegistries.ITEM.getKey(outputItem).toString())) {
            throw new IllegalArgumentException("Stonecutting plan output disagrees with its native recipe");
        }
        if (work.input().items().anyMatch(holder -> holder.value() == outputItem)) {
            throw new IllegalArgumentException("Stonecutting input and output must be different item types");
        }
        expectedOutput = operationOutput.copy();
        outputPlanned = step.outputCount();
        operationsPlanned = step.operationCount();
        if (operationsPlanned < 1 || outputPlanned < 1) {
            throw new IllegalArgumentException("Stonecutting plan must contain positive operations and output");
        }
        long expectedPlanOutput = (long) operationsPlanned * expectedOutput.getCount();
        if (expectedPlanOutput != outputPlanned) {
            throw new IllegalArgumentException("Stonecutting plan output count disagrees with its operation count");
        }

        List<SelectedItemRequirement> selectedInputs = step.requirements().stream()
                .filter(SelectedItemRequirement.class::isInstance)
                .map(SelectedItemRequirement.class::cast)
                .filter(requirement -> requirement.purpose().equals("recipe ingredient"))
                .toList();
        if (selectedInputs.isEmpty()) {
            throw new IllegalArgumentException("Stonecutting plan omitted its selected recipe input");
        }
        int plannedInputCount = 0;
        for (SelectedItemRequirement requirement : selectedInputs) {
            if (requirement.recipeSlot() != 0 || !requirement.consumed() || requirement.count() < 1) {
                throw new IllegalArgumentException("Stonecutting inputs must be consumed recipe-slot-0 selections");
            }
            Item item = GameCatalog.item(requirement.item());
            if (item == outputItem) {
                throw new IllegalArgumentException("Stonecutting input and output must be different item types");
            }
            if (work.input().items().noneMatch(holder -> holder.value() == item)) {
                throw new IllegalArgumentException("Selected item is not an alternative in the native stonecutting input");
            }
            selectedInputBudgets.merge(item, requirement.count(), Math::addExact);
            plannedInputCount = Math.addExact(plannedInputCount, requirement.count());
        }
        inputPlanned = plannedInputCount;
        if (inputPlanned != operationsPlanned) {
            throw new IllegalArgumentException("Stonecutting plan must select one input item per operation");
        }
        remainingInputBudgets.putAll(selectedInputBudgets);
        remainingOperationsToSubmit = operationsPlanned;
    }

    boolean tick() {
        if (drainComplete) return true;
        if (client.player == null || client.gameMode == null) throw new IllegalStateException("No player");
        if (!initialized) initialize();
        requireOwnedMenu();
        if (transfer == null && !menu.getCarried().isEmpty()) {
            throw new IllegalStateException("Cursor is occupied; finish your inventory action first");
        }

        if (transfer != null) {
            if (!transfer.tick()) return false;
            submittedInput += transferAmount;
            remainingOperationsToSubmit -= transferAmount;
            activeCohort.remainingPlanned -= transferAmount;
            remainingInputBudgets.compute(activeCohort.item,
                    (ignored, count) -> Math.subtractExact(count, transferAmount));
            if (remainingOperationsToSubmit < 0 || activeCohort.remainingPlanned < 0
                    || remainingInputBudgets.get(activeCohort.item) < 0) {
                throw unexpected("stonecutter selected-input budget");
            }
            transferAmount = 0;
            transfer = null;
            activeRecipeIndex = -1;
            pendingRecipeIndex = -1;
            selectionWaitTicks = 0;
            outputWaitTicks = 0;
            lastInputBalance = menu.getSlot(INPUT_SLOT).getItem().getCount();
            validateKnownContents(false);
            return false;
        }

        if (quickMove != null) {
            if (drainRequested && quickMovePurpose == QuickMovePurpose.OUTPUT
                    && !outputQuickMoveClickIssued) {
                quickMove = null;
                quickMovePurpose = null;
                drainOwnedInput();
                return drainComplete;
            }
            if (!quickMove.tick()) return false;
            finishQuickMove();
            return false;
        }

        validateKnownContents(shouldValidateVirtualOutput());
        if (drainRequested) return drainOwnedInput();

        ItemStack currentInput = menu.getSlot(INPUT_SLOT).getItem();
        if (!currentInput.isEmpty()) {
            if (!ensureRecipeSelectionAndPreview()) return false;
            startOutputQuickMove();
            return false;
        }

        outputWaitTicks = 0;
        if (remainingOperationsToSubmit == 0) {
            if (collectedOutput != outputPlanned || !menu.getSlot(RESULT_SLOT).getItem().isEmpty()
                    || !menu.getCarried().isEmpty()) {
                throw new IllegalStateException("Stonecutting finished without collecting the exact planned output count");
            }
            return true;
        }
        startInputTransfer();
        return false;
    }

    private void initialize() {
        AbstractContainerMenu currentMenu = client.player.containerMenu;
        if (currentMenu == null || currentMenu.getClass() != StonecutterMenu.class
                || !(currentMenu instanceof StonecutterMenu stonecutter)) {
            throw new IllegalStateException("Open the owned native stonecutter");
        }
        menu = stonecutter;
        if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Stonecutter cursor is occupied");
        if (!menu.getSlot(INPUT_SLOT).getItem().isEmpty() || !menu.getSlot(RESULT_SLOT).getItem().isEmpty()) {
            throw new IllegalStateException("Stonecutter input or output already contains items; leaving it untouched");
        }

        Map<Item, List<InputCohort>> availableCohorts = new LinkedHashMap<>();
        Inventory inventory = client.player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container != inventory || slot.getContainerSlot() >= 36) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !selectedInputBudgets.containsKey(stack.getItem())
                    || !work.input().test(stack)) continue;
            List<InputCohort> cohortsForItem = availableCohorts.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>());
            InputCohort cohort = cohortsForItem.stream().filter(candidate -> same(candidate.template, stack))
                    .findFirst().orElse(null);
            if (cohort == null) {
                cohort = new InputCohort(stack);
                cohortsForItem.add(cohort);
            } else {
                cohort.availableCount = Math.addExact(cohort.availableCount, stack.getCount());
            }
        }

        for (Map.Entry<Item, Integer> budget : selectedInputBudgets.entrySet()) {
            int remaining = budget.getValue();
            for (InputCohort cohort : availableCohorts.getOrDefault(budget.getKey(), List.of())) {
                int assigned = Math.min(remaining, cohort.availableCount);
                if (assigned == 0) continue;
                cohort.remainingPlanned = assigned;
                inputCohorts.add(cohort);
                remaining -= assigned;
                if (remaining == 0) break;
            }
            if (remaining > 0) {
                throw new IllegalStateException("Selected component-exact stonecutting input cohort is missing or incomplete: "
                        + BuiltInRegistries.ITEM.getKey(budget.getKey()));
            }
        }
        if (inputCohorts.isEmpty()) throw new IllegalStateException("Stonecutting plan has no usable input cohort");
        if (menu.getSlot(INPUT_SLOT).getMaxStackSize(inputCohorts.getFirst().template) < 1) {
            throw new IllegalStateException("Stonecutter input slot cannot accept the selected item");
        }
        if (componentExactOutputInventoryCapacity() < expectedOutput.getCount()) {
            throw new IllegalStateException("Inventory has no component-exact room for one stonecutting output operation");
        }
        lastInputBalance = 0;
        lastOutputBalance = 0;
        lastAuthorizedOutput = 0;
        initialized = true;
    }

    private void startInputTransfer() {
        if (!recipeCurrent.getAsBoolean()) {
            throw new IllegalStateException("Stonecutting recipe catalog is stale; no input was submitted");
        }
        if (!menu.getSlot(INPUT_SLOT).getItem().isEmpty() || !menu.getSlot(RESULT_SLOT).getItem().isEmpty()) {
            throw unexpected("stonecutter contents before a new input batch");
        }
        activeCohort = inputCohorts.stream().filter(cohort -> cohort.remainingPlanned > 0).findFirst()
                .orElseThrow(() -> new IllegalStateException("Stonecutting input budget ended before the planned operations"));
        expectedInput = activeCohort.template;
        Slot source = findInputSource(expectedInput);
        ItemStack supply = source.getItem();
        int inputSlotLimit = menu.getSlot(INPUT_SLOT).getMaxStackSize(expectedInput);
        long capacityOperations = componentExactOutputInventoryCapacity() / expectedOutput.getCount();
        int batchOperations = (int) Math.min(MAX_BATCH_OPERATIONS,
                Math.min((long) remainingOperationsToSubmit,
                        Math.min((long) activeCohort.remainingPlanned,
                                Math.min((long) inputSlotLimit, capacityOperations))));
        int amount = Math.min(batchOperations, supply.getCount());
        if (amount < 1) {
            throw new IllegalStateException("Stonecutter batch has no safe input and exact output capacity");
        }
        int sourceIndex = menu.slots.indexOf(source);
        if (sourceIndex < 0) throw new IllegalStateException("Stonecutting input source is outside the current menu");
        transferAmount = amount;
        transfer = new SlotTransfer(client, menu, sourceIndex, INPUT_SLOT, amount);
    }

    private boolean ensureRecipeSelectionAndPreview() {
        int recipeIndex = currentRecipeIndex();
        if (pendingRecipeIndex >= 0 && recipeIndex != pendingRecipeIndex) {
            selectRecipe(recipeIndex);
            return false;
        }
        if (menu.getSelectedRecipeIndex() != recipeIndex) {
            if (pendingRecipeIndex != recipeIndex) {
                selectRecipe(recipeIndex);
                return false;
            }
            if (++selectionWaitTicks >= MAX_OBSERVATION_TICKS) {
                throw new IllegalStateException("Stonecutter did not confirm the selected native recipe; leaving the menu open");
            }
            return false;
        }

        activeRecipeIndex = recipeIndex;
        pendingRecipeIndex = -1;
        selectionWaitTicks = 0;
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        lastOutputBalance = result.getCount();
        lastAuthorizedOutput = authorizedOutputCount(menu.getSlot(INPUT_SLOT).getItem(), result);
        if (result.isEmpty()) {
            if (++outputWaitTicks >= MAX_OBSERVATION_TICKS) {
                throw new IllegalStateException("Stonecutter did not expose the selected exact output; leaving the menu open");
            }
            return false;
        }
        requireExpectedPreview(result);
        outputWaitTicks = 0;
        return true;
    }

    private void selectRecipe(int index) {
        if (index < 0) throw new IllegalStateException("The selected stonecutting recipe is no longer available");
        client.gameMode.handleInventoryButtonClick(menu.containerId, index);
        pendingRecipeIndex = index;
        activeRecipeIndex = index;
        selectionWaitTicks = 0;
    }

    private void startOutputQuickMove() {
        beforeOutputClick();
        outputClickConsumedOperations = completedOperations();
        quickMovePurpose = QuickMovePurpose.OUTPUT;
        outputQuickMoveClickIssued = false;
        quickMove = new VerifiedQuickMove(client, menu, RESULT_SLOT, outputItem, "stonecutter output",
                this::authorizedResultSourceAdjustment, this::beforeOutputQuickMoveClick);
    }

    private void beforeOutputQuickMoveClick() {
        beforeOutputClick();
        outputQuickMoveClickIssued = true;
    }

    private void beforeOutputClick() {
        if (drainRequested) {
            throw new IllegalStateException("Stonecutting drain was requested before output collection; leaving owned input in place");
        }
        requireOwnedMenu();
        validateKnownContents(true);
        int currentIndex = currentRecipeIndex();
        if (currentIndex != activeRecipeIndex || menu.getSelectedRecipeIndex() != currentIndex) {
            throw new IllegalStateException("Stonecutter selection changed before output collection; leaving the menu open");
        }
        ItemStack input = menu.getSlot(INPUT_SLOT).getItem();
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (input.isEmpty() || expectedInput == null || !same(input, expectedInput) || !work.input().test(input)) {
            throw unexpected("stonecutter input before output collection");
        }
        requireExpectedPreview(result);
        long requiredCapacity = (long) expectedOutput.getCount() * input.getCount();
        if (componentExactOutputInventoryCapacity() < requiredCapacity) {
            throw new IllegalStateException("Exact output inventory capacity no longer covers the owned stonecutter input; leaving the menu open");
        }
    }

    private void finishQuickMove() {
        int moved = quickMove.movedCount();
        if (quickMovePurpose == QuickMovePurpose.OUTPUT) {
            int consumedNow = completedOperations();
            int consumed = consumedNow - outputClickConsumedOperations;
            long expectedMoved = (long) consumed * expectedOutput.getCount();
            if (consumed < 1 || expectedMoved != moved || moved % expectedOutput.getCount() != 0) {
                throw unexpected("stonecutter output conservation");
            }
            collectedOutput += moved;
            if (collectedOutput > outputPlanned) throw unexpected("stonecutter output budget");
            lastInputBalance = menu.getSlot(INPUT_SLOT).getItem().getCount();
            lastOutputBalance = menu.getSlot(RESULT_SLOT).getItem().getCount();
        } else if (quickMovePurpose == QuickMovePurpose.INPUT_DRAIN) {
            int inputNow = menu.getSlot(INPUT_SLOT).getItem().getCount();
            int returned = inputDrainSourceCount - inputNow;
            if (returned < 1 || returned != moved) throw unexpected("stonecutter input return");
            returnedInput += returned;
            lastInputBalance = inputNow;
            lastOutputBalance = menu.getSlot(RESULT_SLOT).getItem().getCount();
        } else {
            throw new IllegalStateException("Stonecutter quick move has no known purpose");
        }
        quickMove = null;
        quickMovePurpose = null;
        inputDrainSourceCount = 0;
        outputQuickMoveClickIssued = false;
        validateKnownContents(shouldValidateVirtualOutput());
    }

    private boolean drainOwnedInput() {
        validateKnownContents(false);
        ItemStack input = menu.getSlot(INPUT_SLOT).getItem();
        if (input.isEmpty()) {
            if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Return the stonecutter cursor before finishing the drain");
            drainComplete = true;
            return true;
        }
        if (expectedInput == null || !same(input, expectedInput) || !work.input().test(input)
                || input.getCount() > submittedInput - returnedInput) {
            throw unexpected("unowned stonecutter input during drain");
        }
        inputDrainSourceCount = input.getCount();
        quickMovePurpose = QuickMovePurpose.INPUT_DRAIN;
        quickMove = new VerifiedQuickMove(client, menu, INPUT_SLOT, expectedInput.getItem(), "stonecutter input return",
                null, () -> validateKnownContents(false));
        return false;
    }

    private void validateKnownContents(boolean validateVirtualOutput) {
        if (menu == null) return;
        ItemStack input = menu.getSlot(INPUT_SLOT).getItem();
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        int currentInput = input.isEmpty() ? 0 : input.getCount();
        int availableOwnedInput = submittedInput - returnedInput;
        if (returnedInput > submittedInput || availableOwnedInput < 0 || submittedInput > inputPlanned
                || currentInput > availableOwnedInput || currentInput != lastInputBalance) {
            throw inputLedgerFailure(input, result, currentInput, availableOwnedInput);
        }
        if (!input.isEmpty() && (expectedInput == null || !same(input, expectedInput) || !work.input().test(input))) {
            throw unexpected("stonecutter input");
        }
        int consumedInput = submittedInput - returnedInput - currentInput;
        if (consumedInput < 0 || consumedInput > inputPlanned
                || (long) consumedInput * expectedOutput.getCount() != collectedOutput) {
            throw unexpected("stonecutter input/output conservation");
        }

        if (validateVirtualOutput) {
            int authorizedOutput = authorizedOutputCount(input, result);
            if (!result.isEmpty() && (!isExpectedPreview(result)
                    || result.getCount() > Math.max(0, authorizedOutput - collectedOutput))) {
                throw unexpected("stonecutter result");
            }
            int authorizedIncrease = Math.max(0, authorizedOutput - lastAuthorizedOutput);
            if (result.getCount() > lastOutputBalance + authorizedIncrease || result.getCount() < lastOutputBalance) {
                throw unexpected("stonecutter output balance");
            }
            lastAuthorizedOutput = authorizedOutput;
        } else {
            // The result is virtual: native selection/reload changes are harmless during a drain.
            lastAuthorizedOutput = authorizedOutputCount(input, result);
        }
        lastInputBalance = currentInput;
        lastOutputBalance = result.getCount();
    }

    private int completedOperations() {
        int currentInput = menu == null ? 0 : menu.getSlot(INPUT_SLOT).getItem().getCount();
        return Math.max(0, submittedInput - returnedInput - currentInput);
    }

    private int authorizedOutputCount(ItemStack input, ItemStack result) {
        long authorized = (long) completedOperations() * expectedOutput.getCount();
        if (!input.isEmpty() && isExpectedPreview(result)) authorized += expectedOutput.getCount();
        return (int) Math.min(outputPlanned, authorized);
    }

    private int authorizedResultSourceAdjustment() {
        ItemStack input = menu.getSlot(INPUT_SLOT).getItem();
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        long authorized = (long) completedOperations() * expectedOutput.getCount();
        if (!input.isEmpty() && isExpectedPreview(result)) authorized += expectedOutput.getCount();
        return (int) Math.min(outputPlanned, authorized);
    }

    private int currentRecipeIndex() {
        if (!recipeCurrent.getAsBoolean()) {
            throw new IllegalStateException("Stonecutting recipe catalog is stale; leaving the menu open");
        }
        int index = GameApi.stonecuttingRecipeIndex(client, menu, work);
        if (index < 0) throw new IllegalStateException("Selected stonecutting recipe is no longer available");
        return index;
    }

    private boolean shouldValidateVirtualOutput() {
        if (drainRequested) return false;
        ItemStack input = menu.getSlot(INPUT_SLOT).getItem();
        if (input.isEmpty()) return true;
        return pendingRecipeIndex < 0 && activeRecipeIndex >= 0
                && menu.getSelectedRecipeIndex() == activeRecipeIndex;
    }

    private void requireExpectedPreview(ItemStack result) {
        if (!isExpectedPreview(result)) throw unexpected("stonecutter output preview");
    }

    private boolean isExpectedPreview(ItemStack result) {
        return result != null && !result.isEmpty() && same(result, expectedOutput)
                && result.getCount() == expectedOutput.getCount();
    }

    private Slot findInputSource(ItemStack cohort) {
        Inventory inventory = client.player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container != inventory || slot.getContainerSlot() >= 36) continue;
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && same(stack, cohort) && work.input().test(stack)) return slot;
        }
        throw new IllegalStateException("Missing component-exact stonecutting input "
                + BuiltInRegistries.ITEM.getKey(cohort.getItem()));
    }

    private long componentExactOutputInventoryCapacity() {
        if (client.player == null || menu == null) return 0;
        long capacity = 0;
        Inventory inventory = client.player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container != inventory || slot.getContainerSlot() >= 36) continue;
            ItemStack existing = slot.getItem();
            if (existing.isEmpty()) {
                if (slot.mayPlace(expectedOutput)) capacity += Math.max(0, slot.getMaxStackSize(expectedOutput));
            } else if (same(existing, expectedOutput)) {
                capacity += Math.max(0, slot.getMaxStackSize(existing) - existing.getCount());
            }
        }
        return capacity;
    }

    private void requireOwnedMenu() {
        if (client.player == null || menu == null || client.player.containerMenu != menu
                || menu.getClass() != StonecutterMenu.class) {
            throw new IllegalStateException("Stonecutter container changed or closed; leaving owned contents untouched");
        }
    }

    private IllegalStateException unexpected(String contents) {
        return new IllegalStateException("Unexpected or unexplained " + contents + "; leaving the owned stonecutter open");
    }

    private IllegalStateException inputLedgerFailure(ItemStack input, ItemStack result,
                                                      int currentInput, int availableOwnedInput) {
        String phase = transfer != null ? "SLOT_TRANSFER"
                : quickMove != null ? String.valueOf(quickMovePurpose)
                : drainRequested ? "DRAIN_REQUESTED" : "IDLE_OR_RECIPE_SELECTION";
        return new IllegalStateException("Unexpected or unexplained stonecutter input ledger"
                + " [phase=" + phase
                + ", submitted=" + submittedInput
                + ", returned=" + returnedInput
                + ", current=" + currentInput
                + ", last=" + lastInputBalance
                + ", availableOwned=" + availableOwnedInput
                + ", planned=" + inputPlanned
                + ", collected=" + collectedOutput
                + ", remainingOperations=" + remainingOperationsToSubmit
                + ", activeRecipeIndex=" + activeRecipeIndex
                + ", pendingRecipeIndex=" + pendingRecipeIndex
                + ", nativeSelectedRecipeIndex=" + menu.getSelectedRecipeIndex()
                + ", transferPending=" + (transfer != null)
                + ", transferAmount=" + transferAmount
                + ", quickMovePurpose=" + quickMovePurpose
                + ", inputItem=" + (input.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(input.getItem()))
                + ", resultCount=" + result.getCount()
                + ", cursorCount=" + menu.getCarried().getCount()
                + "]; leaving the owned stonecutter open");
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }

    void requestDrain() { drainRequested = true; }

    void recover() {
        if (transfer != null) transfer.recover();
        if (quickMove != null) {
            throw new IllegalStateException("Stonecutter quick move is pending; resume to observe its inventory/source delta");
        }
        if (client.player != null && !client.player.containerMenu.getCarried().isEmpty()) {
            throw new IllegalStateException("Stonecutter cursor is occupied; recover it before closing the menu");
        }
    }

    void pause() {
        recover();
        if (menu == null) return;
        if (!menu.getCarried().isEmpty()) {
            throw new IllegalStateException("Stonecutter cursor is occupied; leaving the menu open");
        }
        if (client.player == null || client.player.containerMenu != menu) {
            if (transfer != null || submittedInput > returnedInput + completedOperations()) {
                throw new IllegalStateException("Stonecutter menu changed while owned input may remain; reopen it and resume before stopping");
            }
            return;
        }
        if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Stonecutter cursor is occupied; leaving the menu open");
        if (quickMove != null) throw new IllegalStateException("Stonecutter quick move is pending; resume to observe it before closing the menu");
        if (!menu.getSlot(INPUT_SLOT).getItem().isEmpty()) {
            throw new IllegalStateException("Owned stonecutter input remains; resume to finish or drain it before closing the menu");
        }
        if (transfer != null) throw new IllegalStateException("Stonecutter input transfer is unresolved; resume before closing the menu");
    }

    void cancel() { pause(); }
}
