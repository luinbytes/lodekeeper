package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.PlanKind;
import dev.lodekeeper.core.StationId;
import dev.lodekeeper.core.SelectedItemRequirement;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntSupplier;

/** Feeds a verified empty furnace and returns only component-exact, plan-owned contents. */
final class SmeltingAction {
    private static final int MAX_DYNAMIC_SPEED_COLD_WAIT_TICKS = 1_800;

    private final Minecraft client;
    private final PlayerActions actions;
    private final StationId station;
    private final Item output, inputItem, fuelItem;
    private final ItemStack expectedOutput;
    private final boolean ordinaryInputOnly;
    private final int target, plannedOutput, plannedInput, plannedFuel;
    private final int inputPerOperation, outputPerOperation;
    private int remainingInput, remainingFuel, collectedOutput;
    private int submittedInput, submittedFuel, returnedInput, returnedFuel;
    private int lastInputBalance, lastFuelBalance, lastOutputBalance, lastAuthorizedOutput;
    private ItemStack expectedInput, expectedFuel;
    private AbstractFurnaceMenu menu;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private int transferAmount, transferDestination, quickMoveSlot = -1, cooldown;
    private int dynamicSpeedColdWaitTicks;
    private boolean initialized, drainRequested;
    private boolean dynamicColdStartPending;

    SmeltingAction(Minecraft client, PlayerActions actions, GameCatalog.RecipeWork recipe, PlanStep step) {
        this.client = client;
        ordinaryInputOnly = Boolean.parseBoolean(step.attributes().getOrDefault("ordinaryInputOnly", "false"));
        this.actions = actions;
        station = recipe.cookingStation();
        if (step.kind() != PlanKind.SMELT || recipe.cookTicks() < 1 || station == null || !station.equals(step.station()))
            throw new IllegalArgumentException("Cooking plan disagrees with its native recipe station");
        dynamicColdStartPending = GameApi.dynamicCookingSpeed();
        expectedOutput = recipe.resultStack();
        output = expectedOutput.getItem();
        target = actions.count(output) + step.outputCount();
        plannedOutput = step.outputCount();
        SelectedItemRequirement input = selected(step, "smelting input");
        SelectedItemRequirement fuel = selected(step, "smelting fuel");
        inputItem = GameCatalog.item(input.item());
        fuelItem = GameCatalog.item(fuel.item());
        plannedInput = input.count();
        plannedFuel = fuel.count();
        if (plannedInput % step.operationCount() != 0 || plannedOutput % step.operationCount() != 0) {
            throw new IllegalStateException("Smelting plan has inconsistent per-operation quantities");
        }
        inputPerOperation = plannedInput / step.operationCount();
        outputPerOperation = plannedOutput / step.operationCount();
        if (inputPerOperation < 1 || outputPerOperation < 1 || expectedOutput.getCount() != outputPerOperation) {
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
        if (client.player == null || client.gameMode == null) throw new IllegalStateException("No player");
        if (cooldown-- > 0) return false;
        if (!initialized) initialize();
        if (client.player.containerMenu != menu || !matchesStation(menu, station))
            throw new IllegalStateException("Cooking station changed or closed");
        if (transfer == null && !menu.getCarried().isEmpty()) throw new IllegalStateException("Cursor occupied; finish your inventory action first");
        if (dynamicColdStartPending) {
            validateKnownContents();
            if (drainRequested) return drainKnownContents();
            if (menu.isLit()) {
                if (++dynamicSpeedColdWaitTicks >= MAX_DYNAMIC_SPEED_COLD_WAIT_TICKS) {
                    throw new IllegalStateException("The cooking station stayed lit for " + MAX_DYNAMIC_SPEED_COLD_WAIT_TICKS
                            + " ticks; its remaining 26.3 fuel speed is unknown, so automation will not start this recipe");
                }
                return false;
            }
            dynamicColdStartPending = false;
        }

        if (transfer != null) {
            try {
                if (!transfer.tick()) return false;
            } catch (RuntimeException exception) {
                if (transferDestination == AbstractFurnaceMenu.INGREDIENT_SLOT
                        && !menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty()) {
                    throw new IllegalStateException("This short-cook recipe consumed input before its inventory transfer was confirmed; this timing is unsupported and the furnace is left open", exception);
                }
                throw exception;
            }
            if (transferDestination == AbstractFurnaceMenu.INGREDIENT_SLOT) {
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
            if (quickMoveSlot == AbstractFurnaceMenu.INGREDIENT_SLOT) {
                returnedInput += moved;
                lastInputBalance = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().getCount();
            } else if (quickMoveSlot == AbstractFurnaceMenu.FUEL_SLOT) {
                returnedFuel += moved;
                lastFuelBalance = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().getCount();
            } else {
                collectedOutput += moved;
                lastOutputBalance = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().getCount();
            }
            quickMove = null;
            quickMoveSlot = -1;
            validateKnownContents();
            return false;
        }

        validateKnownContents();
        if (drainRequested) return drainKnownContents();
        if (actions.count(output) >= target) return drainKnownContents();

        boolean inputNeedsTopUp = remainingInput > 0 && needsTopUp(AbstractFurnaceMenu.INGREDIENT_SLOT);
        boolean fuelNeedsTopUp = remainingFuel > 0 && needsTopUp(AbstractFurnaceMenu.FUEL_SLOT);
        if (submittedInput == 0 && inputNeedsTopUp) feed(inputItem, AbstractFurnaceMenu.INGREDIENT_SLOT, remainingInput);
        else if (fuelNeedsTopUp) feed(fuelItem, AbstractFurnaceMenu.FUEL_SLOT, remainingFuel);
        else if (inputNeedsTopUp) feed(inputItem, AbstractFurnaceMenu.INGREDIENT_SLOT, remainingInput);
        if (transfer != null) return false;
        ItemStack result = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (!result.isEmpty()) {
            if (!same(result, expectedOutput)) throw unexpected("furnace output");
            quickMove = new VerifiedQuickMove(client, menu, AbstractFurnaceMenu.RESULT_SLOT, output,
                    "furnace output", this::authorizedOutputCount, this::validateKnownContents);
            quickMoveSlot = AbstractFurnaceMenu.RESULT_SLOT;
            return false;
        }
        return false;
    }

    private boolean needsTopUp(int slotIndex) {
        Slot slot = menu.getSlot(slotIndex);
        ItemStack current = slot.getItem();
        if (current.isEmpty()) return true;
        int capacity = slot.getMaxStackSize(current);
        int lowWatermark = Math.max(2, capacity / 2);
        return current.getCount() < capacity && current.getCount() <= lowWatermark;
    }

    static boolean matchesStation(net.minecraft.world.inventory.AbstractContainerMenu menu, StationId station) {
        if (menu == null || station == null) return false;
        return switch (station.toString()) {
            case "minecraft:furnace" -> menu.getClass() == net.minecraft.world.inventory.FurnaceMenu.class;
            case "minecraft:smoker" -> menu.getClass() == net.minecraft.world.inventory.SmokerMenu.class;
            case "minecraft:blast_furnace" -> menu.getClass() == net.minecraft.world.inventory.BlastFurnaceMenu.class;
            default -> false;
        };
    }

    private void initialize() {
        if (!matchesStation(client.player.containerMenu, station)
                || !(client.player.containerMenu instanceof AbstractFurnaceMenu furnace)) {
            throw new IllegalStateException("Open the owned cooking station");
        }
        menu = furnace;
        if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Cursor occupied");
        for (int slot = 0; slot < AbstractFurnaceMenu.SLOT_COUNT; slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()) {
                throw new IllegalStateException("Furnace already contains items; automation will not take them");
            }
        }
        if (plannedOutput > outputPerOperation
                && menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getMaxStackSize(expectedOutput) / outputPerOperation < 16)
            throw new IllegalStateException("Bulk cooking output needs room for at least 16 operations; no materials were inserted");
        int usableFuel = 0;
        for (Slot slot : menu.slots) {
            if (slot.container != client.player.getInventory() || slot.getContainerSlot() >= 36) continue;
            ItemStack stack = slot.getItem();
            if (stack.is(inputItem)
                    && (!ordinaryInputOnly || ItemStack.isSameItemSameComponents(stack, inputItem.getDefaultInstance())) && (stack.getMaxStackSize() > 99 || stack.getCount() > stack.getMaxStackSize()))
                throw new IllegalStateException("Cooking input exceeds the bounded cursor-transfer capacity; no materials were inserted");
            if (stack.is(inputItem)
                    && (!ordinaryInputOnly || ItemStack.isSameItemSameComponents(stack, inputItem.getDefaultInstance())) && plannedInput > inputPerOperation && menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getMaxStackSize(stack) < 16 * inputPerOperation)
                throw new IllegalStateException("Bulk cooking input needs room for at least 16 operations to refill without burn gaps; no materials were inserted");
            if (stack.is(fuelItem) && GameApi.supportedCookingFuelStack(stack)
                    && stack.getMaxStackSize() == fuelItem.getDefaultInstance().getMaxStackSize())
                usableFuel += stack.getCount();
        }
        if (usableFuel < plannedFuel)
            throw new IllegalStateException("Planned fuel has unsupported components, stack size or is missing; no materials were inserted");
        initialized = true;
    }

    private void validateKnownContents() {
        ItemStack input = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        ItemStack fuel = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem();
        ItemStack result = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        validateOwnedSlot(input, expectedInput, submittedInput - returnedInput, lastInputBalance, "furnace input");
        validateOwnedSlot(fuel, expectedFuel, submittedFuel - returnedFuel, lastFuelBalance, "furnace fuel");

        if (returnedInput > submittedInput || returnedFuel > submittedFuel) {
            throw unexpected("furnace returned-material conservation");
        }
        int authorizedOutput = authorizedOutputCount();
        if (collectedOutput > authorizedOutput) throw unexpected("furnace output conservation");
        if (!result.isEmpty() && (!same(result, expectedOutput)
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

    private void validateOwnedSlot(ItemStack stack, ItemStack expected, int ownedMaximum,
                                   int previousBalance, String description) {
        if (stack.isEmpty()) return;
        if (expected == null || !same(stack, expected) || stack.getCount() > Math.max(0, ownedMaximum)
                || stack.getCount() > previousBalance) throw unexpected(description);
    }

    private IllegalStateException unexpected(String description) {
        return new IllegalStateException("Unexpected or unexplained " + description + " contents; leaving the owned furnace open");
    }

    private int consumedInputCount() {
        int currentInput = menu == null ? 0 : menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().getCount();
        return Math.max(0, submittedInput - returnedInput - currentInput);
    }

    private int authorizedOutputCount() {
        if (menu == null || inputPerOperation < 1) return 0;
        int completedOperations = consumedInputCount() / inputPerOperation;
        return Math.min(plannedOutput, completedOperations * outputPerOperation);
    }

    /** Drains only confirmed owned contents after all transfer and output changes are observed. */
    private boolean drainKnownContents() {
        for (int slot = 0; slot < AbstractFurnaceMenu.SLOT_COUNT; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            Item expected = slot == AbstractFurnaceMenu.INGREDIENT_SLOT ? inputItem
                    : slot == AbstractFurnaceMenu.FUEL_SLOT ? fuelItem : output;
            String description = slot == AbstractFurnaceMenu.INGREDIENT_SLOT ? "furnace input"
                    : slot == AbstractFurnaceMenu.FUEL_SLOT ? "furnace fuel" : "furnace output";
            IntSupplier adjustment = slot == AbstractFurnaceMenu.RESULT_SLOT ? this::authorizedOutputCount
                    : slot == AbstractFurnaceMenu.INGREDIENT_SLOT ? this::inputConsumptionAdjustment : null;
            quickMove = new VerifiedQuickMove(client, menu, slot, expected, description, adjustment,
                    this::validateKnownContents);
            quickMoveSlot = slot;
            return false;
        }
        return true;
    }

    private int inputConsumptionAdjustment() {
        int produced = collectedOutput + menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().getCount();
        int completedOperations = produced / outputPerOperation;
        return -Math.min(plannedInput, completedOperations * inputPerOperation);
    }

    private void feed(Item item, int destination, int remaining) {
        int source = source(item, destination == AbstractFurnaceMenu.INGREDIENT_SLOT ? expectedInput : expectedFuel,
                destination == AbstractFurnaceMenu.FUEL_SLOT);
        ItemStack supply = menu.getSlot(source).getItem();
        if (destination == AbstractFurnaceMenu.FUEL_SLOT && !GameApi.supportedCookingFuelStack(supply))
            throw new IllegalStateException("Cooking fuel has unsupported components or a container remainder");
        if (destination == AbstractFurnaceMenu.INGREDIENT_SLOT && expectedInput == null) expectedInput = supply.copyWithCount(1);
        if (destination == AbstractFurnaceMenu.FUEL_SLOT && expectedFuel == null) expectedFuel = supply.copyWithCount(1);
        ItemStack expected = destination == AbstractFurnaceMenu.INGREDIENT_SLOT ? expectedInput : expectedFuel;
        if (!same(supply, expected)) throw new IllegalStateException("Furnace supply components changed: " + item);
        Slot destinationSlot = menu.getSlot(destination);
        ItemStack existing = destinationSlot.getItem();
        if (!existing.isEmpty() && !same(existing, expected)) throw unexpected("cooking station input or fuel");
        int destinationRoom = destinationSlot.getMaxStackSize(supply) - existing.getCount();
        transferAmount = Math.min(64, Math.min(remaining, Math.min(supply.getCount(), destinationRoom)));
        if (destinationRoom < supply.getCount()) {
            int half = supply.getCount() / 2 + supply.getCount() % 2;
            if (half < supply.getCount() && half <= transferAmount) transferAmount = half;
        }
        if (transferAmount < 1) throw new IllegalStateException("Cooking station has no room for the planned supply");
        transferDestination = destination;
        transfer = new SlotTransfer(client, menu, source, destination, transferAmount,
                destination == AbstractFurnaceMenu.FUEL_SLOT
                        ? () -> menu.isLit() ? Math.round(menu.getLitProgress() * 1_000_000f) + 1 : 0
                        : this::completedOutputOperations);
    }

    private double completedOutputOperations() {
        long produced = (long) collectedOutput + menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().getCount();
        return produced / (double) outputPerOperation;
    }

    private int source(Item item, ItemStack expected, boolean cookingFuel) {
        Inventory inventory = client.player.getInventory();
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.getSlot(index);
            ItemStack stack = slot.getItem();
            if (slot.container != inventory || slot.getContainerSlot() >= 36 || !stack.is(item)) continue;
            if (cookingFuel && (!GameApi.supportedCookingFuelStack(stack)
                    || stack.getMaxStackSize() != item.getDefaultInstance().getMaxStackSize())) continue;
            if (!cookingFuel && ordinaryInputOnly
                    && !ItemStack.isSameItemSameComponents(stack, item.getDefaultInstance())) continue;
            if (expected != null && !same(stack, expected)) continue;
            return index;
        }
        throw new IllegalStateException("Missing furnace supply: " + GameCatalog.id(item));
    }

    long progressToken() {
        if (menu == null) return 0;
        long token = ((long) remainingInput << 32) ^ ((long) remainingFuel << 20)
                ^ ((long) menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().getCount() << 12)
                ^ ((long) menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().getCount() << 5)
                ^ ((long) collectedOutput << 3) ^ (int) (menu.getBurnProgress() * 10_000);
        if (dynamicColdStartPending) token = token * 31 + Math.round(menu.getLitProgress() * 1_000_000f);
        return token;
    }

    void pause() { if (transfer != null) transfer.recover(); }
    void cancel() { pause(); }
    void requestDrain() { drainRequested = true; }

    private static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }
}
