package dev.lodekeeper.fabric;

import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.SelectedItemRequirement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

/** Manual grid placement works even for synchronized recipes absent from the unlocked recipe book. */
final class CraftingAction {
    private enum MovePurpose { OUTPUT, GRID_CONTENT }

    private final MinecraftClient client;
    private final PlayerActions actions;
    private final java.util.function.Supplier<Map<dev.lodekeeper.core.ItemId, Integer>> liveReservations;
    private final java.util.function.BooleanSupplier shieldEnabled;
    private final java.util.Set<dev.lodekeeper.core.ItemId> plankItems;
    private final java.util.function.IntSupplier ironFloor, plankFloor;
    private boolean transferStarted;

    private final RecipeWork recipe;
    private final BooleanSupplier recipeCurrent, optionalWorkCurrent;
    private final ItemStack expectedOutput;
    private final int targetCount;
    private final PlanStep step;
    private final List<Placement> placements = new ArrayList<>();
    private final Map<String, Integer> remainingMaterials = new HashMap<>();
    private final Map<Integer, List<ItemStack>> expectedGridContents = new HashMap<>();
    private final Map<Integer, List<ItemStack>> expectedGridRemainders = new HashMap<>();
    private ScreenHandler handler;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private CompletableFuture<List<ItemStack>> remainderFuture;
    private List<ItemStack> remainderInputGrid;
    private MovePurpose movePurpose;
    private int placementIndex, transferPlacementCount;
    private boolean initialized, awaitingResult, drainGridPending, drainRequested, remaindersResolved;
    private boolean outputMoveObserved, interruptedOutputDrain;
    private static final class StaleRecipeBeforeOutputClick extends RuntimeException {}
    private record Placement(int gridSlot, int sourceSlot, Item item, Ingredient predicate, ItemStack inputStack, String budgetKey) {
        private Placement {
            inputStack = inputStack.copy();
        }
        @Override public ItemStack inputStack() { return inputStack.copy(); }
    }

    CraftingAction(MinecraftClient client, PlayerActions actions, RecipeWork recipe, PlanStep step,
                   BooleanSupplier recipeCurrent) {
        this(client, actions, recipe, step, recipeCurrent, () -> true, Map::of, () -> false, java.util.Set.of(), () -> 0, () -> 0);
    }

    CraftingAction(MinecraftClient client, PlayerActions actions, RecipeWork recipe, PlanStep step,
                   BooleanSupplier recipeCurrent, BooleanSupplier optionalWorkCurrent,
                   java.util.function.Supplier<Map<dev.lodekeeper.core.ItemId, Integer>> liveReservations,
                   BooleanSupplier shieldEnabled, java.util.Set<dev.lodekeeper.core.ItemId> plankItems,
                   java.util.function.IntSupplier ironFloor, java.util.function.IntSupplier plankFloor) {
        this.liveReservations = liveReservations;
        this.shieldEnabled = shieldEnabled;
        this.plankItems = java.util.Set.copyOf(plankItems);
        this.ironFloor = ironFloor;
        this.plankFloor = plankFloor;
        this.client = client;
        this.actions = actions;
        this.recipe = recipe;
        this.recipeCurrent = recipeCurrent;
        this.optionalWorkCurrent = optionalWorkCurrent;
        if (recipe.kind() != RecipeWork.Kind.SHAPED_CRAFTING && recipe.kind() != RecipeWork.Kind.SHAPELESS_CRAFTING) {
            throw new IllegalArgumentException("Crafting action received non-crafting recipe work");
        }
        expectedOutput = recipe.outputPerOperation();
        this.step = step;
        targetCount = actions.count(expectedOutput.getItem()) + step.outputCount();
        step.requirements().stream().filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast)
                .filter(requirement -> requirement.purpose().equals("recipe ingredient"))
                .forEach(requirement -> remainingMaterials.merge(key(requirement.recipeSlot(), GameCatalog.item(requirement.item())), requirement.count(), Integer::sum));
    }

    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        Item output = expectedOutput.getItem();
        if (!initialized) initialize();
        if (!optionalWorkCurrent.getAsBoolean()) drainRequested = true;
        if (client.player.currentScreenHandler != handler) throw new IllegalStateException("Crafting container closed or changed");
        if (transfer == null && !handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");

        if (transfer != null) {
            if (!transferStarted) { verifyShieldBudget(); transferStarted = true; }
            if (transfer.tick()) {
                for (int index = placementIndex; index < placementIndex + transferPlacementCount; index++) {
                    Placement placed = placements.get(index);
                    ItemStack gridStack = handler.getSlot(placed.gridSlot()).getStack();
                    if (gridStack.getCount() != 1 || !GameApi.canCombine(gridStack, placed.inputStack())
                            || !placed.predicate().test(gridStack)) {
                        throw new IllegalStateException("Crafting grid input changed or no longer matches the planned recipe; leaving the container open");
                    }
                }
                for (int index = placementIndex; index < placementIndex + transferPlacementCount; index++) {
                    Placement placed = placements.get(index);
                    remainingMaterials.compute(placed.budgetKey, (key, count) -> count - 1);
                    rememberOwnedGridContents(placed.gridSlot(), handler.getSlot(placed.gridSlot()).getStack());
                }
                transfer = null;
                placementIndex += transferPlacementCount;
                transferPlacementCount = 0;
            }
            if (transfer != null) return false;
        }
        if (quickMove != null) {
            try {
                if (quickMove.tick()) {
                    MovePurpose completedPurpose = movePurpose;
                    quickMove = null;
                    movePurpose = null;
                    if (completedPurpose == MovePurpose.OUTPUT) {
                        outputMoveObserved = true;
                        ItemStack remainingOutput = handler.getSlot(0).getStack();
                        if (!remainingOutput.isEmpty()) {
                            if (!GameApi.canCombine(remainingOutput, expectedOutput)
                                    || remainingOutput.getCount() > expectedOutput.getCount()) {
                                throw new IllegalStateException("Unexpected item remained in the crafting output slot; leaving the container open");
                            }
                            startOutputMove(remainingOutput);
                            return false;
                        }
                        awaitingResult = false;
                        placements.clear();
                        placementIndex = 0;
                        drainGridPending = true;
                    }
                }
            } catch (StaleRecipeBeforeOutputClick stale) {
                quickMove = null;
                movePurpose = null;
                drainRequested = true;
                interruptedOutputDrain |= outputMoveObserved;
                if (outputMoveObserved) {
                    awaitingResult = false;
                    remainderFuture = null;
                    remainderInputGrid = null;
                    remaindersResolved = false;
                    if (!drainKnownGridContents(true, true)) return false;
                    return true;
                }
                awaitingResult = false;
                remainderFuture = null;
                remainderInputGrid = null;
                remaindersResolved = false;
                if (!drainKnownGridContents(true)) return false;
                return true;
            }
            return false;
        }
        if (awaitingResult && drainRequested) {
            interruptedOutputDrain |= outputMoveObserved;
            if (outputMoveObserved) {
                awaitingResult = false;
                remainderFuture = null;
                remainderInputGrid = null;
                remaindersResolved = false;
                if (!drainKnownGridContents(true, true)) return false;
                return true;
            }
            awaitingResult = false;
            remainderFuture = null;
            remainderInputGrid = null;
            remaindersResolved = false;
            if (!drainKnownGridContents(true)) return false;
            return true;
        }
        if (awaitingResult) {
            ItemStack actual = handler.getSlot(0).getStack();
            if (actual.isEmpty()) return false;
            if (!GameApi.canCombine(actual, expectedOutput) || actual.getCount() != expectedOutput.getCount()) {
                throw new IllegalStateException("Crafting output disagrees with the planned recipe; leaving the container open");
            }
            startOutputMove(actual);
            return false;
        }

        if (drainRequested) {
            if (drainGridPending) {
                if (!drainKnownGridContents(false)) return false;
                drainGridPending = false;
                return true;
            }
            if (!placements.isEmpty() && placementIndex == placements.size()) {
                remainderFuture = null;
                remainderInputGrid = null;
                if (!drainKnownGridContents(true)) return false;
                return true;
            }
            if (!drainKnownGridContents(true)) return false;
            return true;
        }

        boolean targetReached = actions.count(output) >= targetCount;
        if (targetReached || drainGridPending) {
            if (!drainKnownGridContents(targetReached && !drainGridPending)) return false;
            drainGridPending = false;
            if (targetReached) return true;
        }

        if (placements.isEmpty()) {
            outputMoveObserved = false;
            interruptedOutputDrain = false;
            buildPlacements();
        }
        if (placementIndex == placements.size()) {
            if (!resolveExpectedRemainders()) return false;
            awaitingResult = true;
            return false;
        }
        Placement placement = placements.get(placementIndex);
        ItemStack currentInput = handler.getSlot(placement.sourceSlot()).getStack();
        int groupSize = 1;
        while (placementIndex + groupSize < placements.size()) {
            Placement next = placements.get(placementIndex + groupSize);
            if (next.sourceSlot() != placement.sourceSlot() || !GameApi.canCombine(next.inputStack(), placement.inputStack())) break;
            groupSize++;
        }
        verifyPlacementInputs(groupSize, currentInput);
        verifyShieldBudget();
        transferStarted = true;
        transferPlacementCount = groupSize;
        if (groupSize > 1) {
            int count = groupSize;
            int[] destinations = new int[count];
            for (int index = 0; index < count; index++) destinations[index] = placements.get(placementIndex + index).gridSlot();
            transfer = new SlotTransfer(client, handler, placement.sourceSlot(), destinations, () -> {
                verifyPlacementInputs(count, handler.getSlot(placement.sourceSlot()).getStack());
                verifyShieldBudget();
            }, () -> verifyPlacementInputs(count, handler.getCursorStack()));
        } else {
            transfer = new SlotTransfer(client, handler, placement.sourceSlot(), placement.gridSlot(), 1);
        }
        transfer.tick();
        return false;
    }

    private void verifyPlacementInputs(int count, ItemStack currentInput) {
        if (!recipeCurrent.getAsBoolean())
            throw new IllegalStateException("Recipe catalog changed before ingredient placement; leaving the grid open for safe recovery");
        if (currentInput.getCount() < count || ordinaryInputsOnly() && !ordinary(currentInput))
            throw new IllegalStateException("The selected inventory stack no longer matches the planned recipe input; no mismatching item was consumed");
        for (int index = placementIndex; index < placementIndex + count; index++) {
            Placement placement = placements.get(index);
            if (currentInput.isEmpty() || !currentInput.isOf(placement.item())
                    || !GameApi.canCombine(currentInput, placement.inputStack()) || !placement.predicate().test(currentInput)
                    || !handler.getSlot(placement.gridSlot()).getStack().isEmpty()) {
                throw new IllegalStateException("The selected inventory stack or crafting grid no longer matches the planned recipe input; no mismatching item was consumed");
            }
        }
    }

    private void initialize() {
        handler = client.player.currentScreenHandler;
        if (!(handler instanceof PlayerScreenHandler || handler instanceof CraftingScreenHandler)) throw new IllegalStateException("Open the required crafting grid");
        if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied");
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        if (recipe.kind() == RecipeWork.Kind.SHAPED_CRAFTING && (recipe.width() > width || recipe.height() > width)) {
            throw new IllegalStateException("Recipe requires a crafting table");
        }
        for (int index = 1; index <= width * width; index++) {
            if (!handler.getSlot(index).getStack().isEmpty()) throw new IllegalStateException("Crafting grid contains your items; clear it before automation");
        }
        initialized = true;
    }

    private static String key(int slot, Item item) {
        return slot + ":" + net.minecraft.registry.Registries.ITEM.getId(item);
    }

    private void buildPlacements() {
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        for (int slot = 1; slot <= width * width; slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) {
                throw new IllegalStateException("Unexpected crafting grid contents; leaving the container open");
            }
        }
        for (RecipeWork.Input input : recipe.inputs()) {
            int gridIndex = recipe.gridIndex(input, width);
            int gridSlot = 1 + gridIndex;
            if (gridIndex < 0 || gridIndex >= width * width) throw new IllegalStateException("Recipe does not fit the open crafting grid");
            Item selected = null;
            int sourceSlot = -1;
            ItemStack selectedStack = null;
            String budgetKey = null;
            for (var requirement : step.requirements()) {
                if (!(requirement instanceof SelectedItemRequirement choice)
                        || !choice.purpose().equals("recipe ingredient") || choice.recipeSlot() != input.slot()) continue;
                Item item = GameCatalog.item(choice.item());
                String candidateKey = key(input.slot(), item);
                long alreadyForSlot = placements.stream().filter(placement -> placement.budgetKey.equals(candidateKey)).count();
                long alreadyForItem = placements.stream().filter(placement -> placement.item.equals(item)).count();
                if (remainingMaterials.getOrDefault(candidateKey, 0) <= alreadyForSlot
                        || actions.count(item) <= alreadyForItem) continue;
                var source = findAvailableInput(item, input.predicate());
                if (source == null) continue;
                selected = item;
                sourceSlot = source.slotId();
                selectedStack = source.stack();
                budgetKey = candidateKey;
                break;
            }
            if (selected == null) throw new IllegalStateException("Planned ingredient no longer matches an actual inventory stack for " + step.sourceId() + "; the planner tracks item IDs, so component-specific choices are blocked safely");
            placements.add(new Placement(gridSlot, sourceSlot, selected, input.predicate(), selectedStack, budgetKey));
        }
        if (placements.isEmpty()) throw new IllegalStateException("Known recipe has no placeable ingredients");
    }

    private record AvailableInput(int slotId, ItemStack stack) {}

    private AvailableInput findAvailableInput(Item item, Ingredient predicate) {
        for (var slot : handler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !stack.isOf(item) || !predicate.test(stack)
                    || ordinaryInputsOnly() && !ordinary(stack)) continue;
            long reserved = placements.stream().filter(placement -> placement.sourceSlot() == slot.id).count();
            if (stack.getCount() > reserved) return new AvailableInput(slot.id, stack.copyWithCount(1));
        }
        return null;
    }

    private void rememberOwnedGridContents(int gridSlot, ItemStack input) {
        addExpected(expectedGridContents, gridSlot, input);
    }

    private void startOutputMove(ItemStack expectedSource) {
        ItemStack guardedSource = expectedSource.copy();
        quickMove = new VerifiedQuickMove(client, handler, 0, guardedSource.getItem(), "crafting output", null, () -> {
            if (drainRequested || !recipeCurrent.getAsBoolean() || !optionalWorkCurrent.getAsBoolean())
                throw new StaleRecipeBeforeOutputClick();
            ItemStack current = handler.getSlot(0).getStack();
            if (current.isEmpty() || current.getCount() != guardedSource.getCount()
                    || !GameApi.canCombine(current, guardedSource)) {
                throw new IllegalStateException("Crafting output changed before transfer; leaving the container open");
            }
        });
        movePurpose = MovePurpose.OUTPUT;
    }

    private static void addExpected(Map<Integer, List<ItemStack>> expected, int gridSlot, ItemStack stack) {
        List<ItemStack> values = expected.computeIfAbsent(gridSlot, ignored -> new ArrayList<>());
        if (values.stream().noneMatch(existing -> GameApi.canCombine(existing, stack) && existing.getCount() == stack.getCount())) {
            values.add(stack.copy());
        }
    }

    private boolean resolveExpectedRemainders() {
        if (remaindersResolved) return true;
        if (!recipeCurrent.getAsBoolean()) {
            throw new IllegalStateException("Recipe catalog changed before the crafting result was authorized; leaving the grid open for safe recovery");
        }
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        int gridSlots = width * width;
        if (remainderFuture == null) {
            List<ItemStack> grid = new ArrayList<>(gridSlots);
            for (int index = 0; index < gridSlots; index++) grid.add(handler.getSlot(index + 1).getStack().copy());
            remainderInputGrid = grid.stream().map(ItemStack::copy).toList();
            remainderFuture = recipe.remainderResolver().resolve(handler, width, remainderInputGrid);
            if (remainderFuture == null) {
                throw new IllegalStateException("Recipe remainder resolver did not start; leaving the crafting container open");
            }
            return false;
        }
        if (!remainderFuture.isDone()) return false;
        List<ItemStack> remainders;
        try {
            remainders = remainderFuture.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("Recipe remainder resolution failed; leaving the crafting container open: "
                    + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
        }
        for (int index = 0; index < gridSlots; index++) {
            ItemStack expected = remainderInputGrid.get(index);
            ItemStack actual = handler.getSlot(index + 1).getStack();
            if (expected.isEmpty() != actual.isEmpty() || (!expected.isEmpty()
                    && (expected.getCount() != actual.getCount() || !GameApi.canCombine(expected, actual)))) {
                throw new IllegalStateException("Crafting grid changed while recipe remainders were being checked; leaving the container open");
            }
        }
        if (remainders == null || remainders.size() != gridSlots) {
            throw new IllegalStateException("Recipe remainder prediction did not cover the complete crafting grid; leaving the container open");
        }
        expectedGridRemainders.clear();
        for (int index = 0; index < remainders.size(); index++) {
            ItemStack remainder = remainders.get(index);
            if (remainder == null) throw new IllegalStateException("Recipe remainder prediction returned unknown contents; leaving the container open");
            if (!remainder.isEmpty()) addExpected(expectedGridRemainders, index + 1, remainder);
        }
        remainderFuture = null;
        remainderInputGrid = null;
        remaindersResolved = true;
        return true;
    }

    /** Returns true only after every known ingredient or recipe remainder is observed in inventory. */
    private boolean drainKnownGridContents(boolean allowIngredients) {
        return drainKnownGridContents(allowIngredients, !allowIngredients || interruptedOutputDrain);
    }

    private boolean drainKnownGridContents(boolean allowIngredients, boolean allowRemainders) {
        int gridSlots = handler instanceof CraftingScreenHandler ? 9 : 4;
        for (int slot = 1; slot <= gridSlots; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            boolean knownIngredient = allowIngredients && expectedGridContents.getOrDefault(slot, List.of()).stream()
                    .anyMatch(expected -> GameApi.canCombine(stack, expected) && stack.getCount() == expected.getCount());
            boolean knownRemainder = allowRemainders && expectedGridRemainders.getOrDefault(slot, List.of()).stream()
                    .anyMatch(expected -> GameApi.canCombine(stack, expected) && stack.getCount() == expected.getCount());
            if (!knownIngredient && !knownRemainder) {
                throw new IllegalStateException("Unexpected crafting grid contents; leaving the container open");
            }
            quickMove = new VerifiedQuickMove(client, handler, slot, stack.getItem(), "crafting remainder");
            movePurpose = MovePurpose.GRID_CONTENT;
            return false;
        }
        expectedGridContents.clear();
        expectedGridRemainders.clear();
        remaindersResolved = false;
        interruptedOutputDrain = false;
        return true;
    }

    private boolean ordinaryInputsOnly() {
        return Boolean.parseBoolean(step.attributes().getOrDefault("ordinaryInputOnly", "false"));
    }

    private static boolean ordinary(ItemStack stack) {
        return !stack.isEmpty() && !stack.hasEnchantments() && !GameApi.hasCustomName(stack)
                && GameApi.canCombine(stack, new ItemStack(stack.getItem()));
    }

    private void verifyShieldBudget() {
        if (drainRequested) return;
        if (!Boolean.parseBoolean(step.attributes().getOrDefault("shieldPreparation", "false"))) return;
        if (!shieldEnabled.getAsBoolean()) throw new IllegalStateException("automatic shield crafting was disabled; no further ingredient was transferred");
        Map<dev.lodekeeper.core.ItemId, Integer> reservations = new HashMap<>(liveReservations.get());
        Map<dev.lodekeeper.core.ItemId, Integer> needed = new HashMap<>();
        step.attributes().forEach((key, value) -> {
            if (key.startsWith("shieldReserved:")) reservations.merge(dev.lodekeeper.core.ItemId.parse(key.substring(15)), Integer.parseInt(value), Math::max);
            if (key.startsWith("shieldFuture:")) needed.merge(dev.lodekeeper.core.ItemId.parse(key.substring(13)), Integer.parseInt(value), Math::addExact);
        });
        remainingMaterials.forEach((key, count) -> {
            if (count > 0) needed.merge(dev.lodekeeper.core.ItemId.parse(key.substring(key.indexOf(':') + 1)), count, Math::addExact);
        });
        Map<dev.lodekeeper.core.ItemId, Integer> counts = new HashMap<>();
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            if (ordinary(stack)) counts.merge(GameCatalog.id(stack.getItem()), stack.getCount(), Math::addExact);
        }
        dev.lodekeeper.core.ItemId iron = dev.lodekeeper.core.ItemId.parse("minecraft:iron_ingot");
        long spareIron = counts.getOrDefault(iron, 0) - (long) reservations.getOrDefault(iron, 0) - needed.getOrDefault(iron, 0);
        long sparePlanks = 0;
        for (dev.lodekeeper.core.ItemId item : plankItems) {
            long spare = counts.getOrDefault(item, 0) - (long) reservations.getOrDefault(item, 0) - needed.getOrDefault(item, 0);
            if (spare < 0) throw new IllegalStateException("reserved shield planks changed; no further ingredient was transferred");
            sparePlanks += spare;
        }
        int keepIron = Math.max(ironFloor.getAsInt(), Integer.parseInt(step.attributes().getOrDefault("shieldIronFloor", "0")));
        int keepPlanks = Math.max(plankFloor.getAsInt(), Integer.parseInt(step.attributes().getOrDefault("shieldPlankFloor", "0")));
        if (spareIron < keepIron || sparePlanks < keepPlanks)
            throw new IllegalStateException("shield stock floors or reserved materials changed; no further ingredient was transferred");
    }

    void pause() { if (transfer != null) transfer.recover(); }
    void cancel() { pause(); }
    void requestDrain() { drainRequested = true; }
}
