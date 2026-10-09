package dev.lodekeeper.fabric;

import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.PlanKind;
import dev.lodekeeper.core.SelectedItemRequirement;
import dev.lodekeeper.core.StationId;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;

/** Uses one initially empty native cooking station, feeds planned materials, and returns only confirmed owned stacks. */
final class SmeltingAction {
    private static final StationId FURNACE = StationId.parse("minecraft:furnace");
    private static final StationId SMOKER = StationId.parse("minecraft:smoker");
    private static final StationId BLAST_FURNACE = StationId.parse("minecraft:blast_furnace");

    private final MinecraftClient client;
    private final PlayerActions actions;
    private final StationId station;
    private final Item output, inputItem, fuelItem;
    private final ItemStack expectedOutput;
    private final boolean ordinaryInputOnly;
    private final PlanStep plannedStep;
    private final java.util.function.BooleanSupplier optionalWorkCurrent;
    private final java.util.function.Supplier<java.util.Map<dev.lodekeeper.core.ItemId, Integer>> liveReservations;
    private final int target, plannedOutput, plannedInput, plannedFuel;
    private final int inputPerOperation, outputPerOperation;
    private int remainingInput, remainingFuel, collectedOutput;
    private int submittedInput, submittedFuel, returnedInput, returnedFuel;
    private int lastInputBalance, lastFuelBalance, lastOutputBalance, lastAuthorizedOutput;
    private ItemStack expectedInput, expectedFuel;
    private AbstractFurnaceScreenHandler handler;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private int transferAmount, transferDestination, quickMoveSlot = -1, cooldown;
    private boolean initialized, drainRequested;

    SmeltingAction(MinecraftClient client, PlayerActions actions, RecipeWork recipe, PlanStep step) {
        this(client, actions, recipe, step, () -> true, java.util.Map::of);
    }
    SmeltingAction(MinecraftClient client, PlayerActions actions, RecipeWork recipe, PlanStep step, java.util.function.BooleanSupplier optionalWorkCurrent,
                   java.util.function.Supplier<java.util.Map<dev.lodekeeper.core.ItemId, Integer>> liveReservations) {
        this.plannedStep = step;
        this.optionalWorkCurrent = optionalWorkCurrent;
        this.liveReservations = liveReservations;
        this.client = client;
        ordinaryInputOnly = Boolean.parseBoolean(step.attributes().getOrDefault("ordinaryInputOnly", "false"));
        this.actions = actions;
        if (recipe.kind() != RecipeWork.Kind.SMELTING) throw new IllegalArgumentException("Cooking station action received non-smelting recipe work");
        if (step.kind() != PlanKind.SMELT) throw new IllegalArgumentException("Cooking station action received a non-smelting plan step");
        station = validateStation(recipe, step);
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
            throw new IllegalStateException("Cooking plan has inconsistent per-operation quantities");
        }
        inputPerOperation = plannedInput / step.operationCount();
        outputPerOperation = plannedOutput / step.operationCount();
        if (inputPerOperation < 1 || outputPerOperation < 1 || !expectedOutput.isOf(output)
                || expectedOutput.getCount() != outputPerOperation) {
            throw new IllegalStateException("Cooking plan disagrees with its recipe output");
        }
        remainingInput = plannedInput;
        remainingFuel = plannedFuel;
    }

    private static StationId validateStation(RecipeWork recipe, PlanStep step) {
        StationId station = recipe.cookingStation();
        if (station == null || !station.equals(step.station())) {
            throw new IllegalStateException("Planned cooking station " + step.station()
                    + " does not match recipe cooking station " + station);
        }
        if (!FURNACE.equals(station) && !SMOKER.equals(station) && !BLAST_FURNACE.equals(station)) {
            throw new IllegalStateException("Unsupported cooking station " + station);
        }
        return station;
    }

    private boolean exactNativeHandler(ScreenHandler candidate) {
        if (candidate == null) return false;
        if (FURNACE.equals(station)) return candidate.getClass() == net.minecraft.screen.FurnaceScreenHandler.class;
        if (SMOKER.equals(station)) return candidate.getClass() == net.minecraft.screen.SmokerScreenHandler.class;
        if (BLAST_FURNACE.equals(station)) return candidate.getClass() == net.minecraft.screen.BlastFurnaceScreenHandler.class;
        return false;
    }

    private String stationName() {
        if (FURNACE.equals(station)) return "furnace";
        if (SMOKER.equals(station)) return "smoker";
        if (BLAST_FURNACE.equals(station)) return "blast furnace";
        return "cooking station";
    }

    private String slotName(String slot) { return stationName() + " " + slot; }

    private static SelectedItemRequirement selected(PlanStep step, String purpose) {
        return step.requirements().stream().filter(SelectedItemRequirement.class::isInstance)
                .map(SelectedItemRequirement.class::cast).filter(requirement -> requirement.purpose().equals(purpose))
                .findFirst().orElseThrow(() -> new IllegalStateException("Plan omitted " + purpose));
    }

    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        if (!optionalWorkCurrent.getAsBoolean()) drainRequested = true;
        if (cooldown-- > 0) return false;
        if (!initialized) initialize();
        if (client.player.currentScreenHandler != handler) throw new IllegalStateException("Cooking station changed or closed");
        if (transfer == null && !handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor occupied; finish your inventory action first");

        if (transfer != null) {
            if (drainRequested && isTravelFoodPreparation()) transfer.requestDrain();
            try {
                if (!transfer.tick()) return false;
            } catch (RuntimeException exception) {
                if (transferDestination == 0 && !handler.getSlot(2).getStack().isEmpty()) {
                    throw new IllegalStateException("This short-cook recipe consumed input before its inventory transfer was confirmed; this timing is unsupported and the " + stationName() + " is left open", exception);
                }
                throw exception;
            }
            int confirmed = isTravelFoodPreparation() ? transfer.placedCount() : transferAmount;
            if (transfer.drained()) drainRequested = true;
            if (transferDestination == 0) {
                remainingInput -= confirmed;
                submittedInput += confirmed;
                lastInputBalance += confirmed;
            } else {
                remainingFuel -= confirmed;
                submittedFuel += confirmed;
                lastFuelBalance += confirmed;
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

        boolean inputNeedsTopUp = remainingInput > 0 && needsTopUp(0);
        boolean fuelNeedsTopUp = remainingFuel > 0 && needsTopUp(1);
        if (submittedInput == 0 && inputNeedsTopUp) feed(inputItem, 0, remainingInput);
        else if (fuelNeedsTopUp) feed(fuelItem, 1, remainingFuel);
        else if (inputNeedsTopUp) feed(inputItem, 0, remainingInput);
        if (transfer != null) return false;
        if (!handler.getSlot(2).getStack().isEmpty()) {
            quickMove = new VerifiedQuickMove(client, handler, 2, output, slotName("output"), this::authorizedOutputCount, this::validateKnownContents);
            quickMoveSlot = 2;
            return false;
        }
        return false;
    }

    private boolean needsTopUp(int slotIndex) {
        var slot = handler.getSlot(slotIndex);
        ItemStack current = slot.getStack();
        if (current.isEmpty()) return true;
        int capacity = slot.getMaxItemCount(current);
        int lowWatermark = Math.max(2, capacity / 2);
        return current.getCount() < capacity && current.getCount() <= lowWatermark;
    }

    private void initialize() {
        ScreenHandler current = client.player.currentScreenHandler;
        if (!exactNativeHandler(current)) {
            throw new IllegalStateException("Open the owned " + stationName() + " cooking station");
        }
        handler = (AbstractFurnaceScreenHandler) current;
        if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor occupied");
        for (int slot = 0; slot < 3; slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) {
                throw new IllegalStateException("The owned " + stationName() + " already contains items; automation will not take them");
            }
        }
        if (plannedOutput > outputPerOperation
                && handler.getSlot(2).getMaxItemCount(expectedOutput) / outputPerOperation < 16)
            throw new IllegalStateException("Bulk cooking output needs room for at least 16 operations; no materials were inserted");
        int usableFuel = 0;
        for (var slot : handler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36) continue;
            ItemStack stack = slot.getStack();
            if (stack.isOf(inputItem)
                    && (!ordinaryInputOnly || GameApi.canCombine(stack, inputItem.getDefaultStack())) && (stack.getMaxCount() > 99 || stack.getCount() > stack.getMaxCount()))
                throw new IllegalStateException("Cooking input exceeds the bounded cursor-transfer capacity; no materials were inserted");
            if (stack.isOf(inputItem)
                    && (!ordinaryInputOnly || GameApi.canCombine(stack, inputItem.getDefaultStack())) && plannedInput > inputPerOperation && handler.getSlot(0).getMaxItemCount(stack) < 16 * inputPerOperation)
                throw new IllegalStateException("Bulk cooking input needs room for at least 16 operations to refill without burn gaps; no materials were inserted");
            if (stack.isOf(fuelItem) && stack.getMaxCount() == fuelItem.getDefaultStack().getMaxCount())
                usableFuel += stack.getCount();
        }
        if (usableFuel < plannedFuel)
            throw new IllegalStateException("Planned fuel has an unsupported stack-size override or is missing; no materials were inserted");
        initialized = true;
    }

    private void validateKnownContents() {
        ItemStack input = handler.getSlot(0).getStack();
        ItemStack fuel = handler.getSlot(1).getStack();
        ItemStack result = handler.getSlot(2).getStack();
        validateOwnedSlot(input, expectedInput, submittedInput - returnedInput, lastInputBalance, slotName("input"));
        validateOwnedSlot(fuel, expectedFuel, submittedFuel - returnedFuel, lastFuelBalance, slotName("fuel"));

        if (returnedInput > submittedInput || returnedFuel > submittedFuel) throw unexpected(slotName("returned-material conservation"));
        int authorizedOutput = authorizedOutputCount();
        if (collectedOutput > authorizedOutput) throw unexpected(slotName("output conservation"));
        if (!result.isEmpty() && (!GameApi.canCombine(result, expectedOutput)
                || result.getCount() > Math.max(0, authorizedOutput - collectedOutput))) {
            throw unexpected(slotName("output"));
        }
        int authorizedIncrease = Math.max(0, authorizedOutput - lastAuthorizedOutput);
        if (result.getCount() > lastOutputBalance + authorizedIncrease || result.getCount() < lastOutputBalance) {
            throw unexpected(slotName("output balance"));
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
        return new IllegalStateException("Unexpected or unexplained " + name + " contents; leaving the owned " + stationName() + " open");
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
            String description = slotName(slot == 0 ? "input" : slot == 1 ? "fuel" : "output");
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

    private boolean isTravelFoodPreparation() {
        return Boolean.parseBoolean(plannedStep.attributes().getOrDefault("travelFoodPreparation", "false"));
    }

    private boolean transferEffectAllowed() {
        if (drainRequested || !optionalWorkCurrent.getAsBoolean() || !travelFoodBudgetSafe()) {
            drainRequested = true;
            return false;
        }
        return true;
    }

    private boolean travelFoodBudgetSafe() {
        if (!isTravelFoodPreparation()) return true;
        if (!optionalWorkCurrent.getAsBoolean()) return false;
        java.util.Map<dev.lodekeeper.core.ItemId, Integer> reserved = new java.util.HashMap<>(liveReservations.get());
        java.util.Map<dev.lodekeeper.core.ItemId, Integer> needed = new java.util.HashMap<>();
        plannedStep.attributes().forEach((key, value) -> {
            if (key.startsWith("travelFoodReserved:")) reserved.merge(dev.lodekeeper.core.ItemId.parse(key.substring(19)), Integer.parseInt(value), Math::max);
            if (key.startsWith("travelFoodFuture:")) needed.merge(dev.lodekeeper.core.ItemId.parse(key.substring(17)), Integer.parseInt(value), Math::addExact);
        });
        needed.merge(GameCatalog.id(inputItem), remainingInput, Math::addExact);
        needed.merge(GameCatalog.id(fuelItem), remainingFuel, Math::addExact);
        java.util.Map<dev.lodekeeper.core.ItemId, Integer> counts = new java.util.HashMap<>();
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            if (!stack.isEmpty() && !stack.hasEnchantments() && !GameApi.hasCustomName(stack)
                    && GameApi.canCombine(stack, new ItemStack(stack.getItem())))
                counts.merge(GameCatalog.id(stack.getItem()), stack.getCount(), Math::addExact);
        }
        if (transfer != null) {
            ItemStack held = handler.getCursorStack();
            if (!held.isEmpty() && !held.hasEnchantments() && !GameApi.hasCustomName(held)
                    && GameApi.canCombine(held, new ItemStack(held.getItem())))
                counts.merge(GameCatalog.id(held.getItem()), held.getCount(), Math::addExact);
            dev.lodekeeper.core.ItemId inserted = GameCatalog.id(transferDestination == 0 ? inputItem : fuelItem);
            needed.computeIfPresent(inserted, (item, count) -> count - transfer.placedCount());
        }
        for (var need : needed.entrySet())
            if (counts.getOrDefault(need.getKey(), 0) - (long) reserved.getOrDefault(need.getKey(), 0) < need.getValue()) return false;
        return true;
    }

    private void feed(Item item, int destination, int remaining) {
        if (!travelFoodBudgetSafe()) { drainRequested = true; return; }
        int source = source(item, destination == 0 ? expectedInput : expectedFuel, destination == 1);
        ItemStack supply = handler.getSlot(source).getStack();
        if (destination == 1 && supply.getMaxCount() != fuelItem.getDefaultStack().getMaxCount())
            throw new IllegalStateException("Cooking fuel stack size changed from the captured native capacity");
        if (destination == 0 && expectedInput == null) expectedInput = supply.copyWithCount(1);
        if (destination == 1 && expectedFuel == null) expectedFuel = supply.copyWithCount(1);
        ItemStack expected = destination == 0 ? expectedInput : expectedFuel;
        if (!GameApi.canCombine(supply, expected)) throw new IllegalStateException("Cooking station supply components changed: " + item);
        var destinationSlot = handler.getSlot(destination);
        ItemStack existing = destinationSlot.getStack();
        if (!existing.isEmpty() && !GameApi.canCombine(existing, expected)) throw unexpected(slotName(destination == 0 ? "input" : "fuel"));
        int destinationRoom = destinationSlot.getMaxItemCount(supply) - existing.getCount();
        transferAmount = Math.min(64, Math.min(remaining, Math.min(supply.getCount(), destinationRoom)));
        if (destinationRoom < supply.getCount()) {
            int half = supply.getCount() / 2 + supply.getCount() % 2;
            if (half < supply.getCount() && half <= transferAmount) transferAmount = half;
        }
        if (transferAmount < 1) throw new IllegalStateException("Cooking station " + (destination == 0 ? "input" : "fuel") + " has no room for the planned supply");
        transferDestination = destination;
        java.util.function.DoubleSupplier progress =
                destination == 1 ? () -> handler.isBurning() ? handler.getFuelProgress() + 1 : 0
                        : this::completedOutputOperations;
        transfer = isTravelFoodPreparation()
                ? new SlotTransfer(client, handler, source, destination, transferAmount, progress, this::transferEffectAllowed)
                : new SlotTransfer(client, handler, source, destination, transferAmount, progress);
    }

    private double completedOutputOperations() {
        long produced = (long) collectedOutput + handler.getSlot(2).getStack().getCount();
        return produced / (double) outputPerOperation;
    }

    private int source(Item item, ItemStack expected, boolean fuel) {
        for (var slot : handler.slots) {
            if (slot.inventory == client.player.getInventory() && slot.getIndex() < 36
                    && slot.getStack().isOf(item)
                    && (!fuel || slot.getStack().getMaxCount() == item.getDefaultStack().getMaxCount())
                    && (!Boolean.parseBoolean(plannedStep.attributes().getOrDefault("ordinaryFuelOnly", "false"))
                        || GameApi.canCombine(slot.getStack(), item.getDefaultStack()))
                    && (fuel || !ordinaryInputOnly || GameApi.canCombine(slot.getStack(), item.getDefaultStack()))
                    && (expected == null || GameApi.canCombine(slot.getStack(), expected))) return slot.id;
        }
        throw new IllegalStateException("Missing cooking station supply: " + item);
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
