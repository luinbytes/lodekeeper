package dev.lodekeeper.fabric;

import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.screen.ScreenHandler;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Version-neutral facts required by the crafting and furnace transaction executors. */
record RecipeWork(
        Kind kind,
        ItemStack outputPerOperation,
        int width,
        int height,
        List<Input> inputs,
        int cookTicks,
        RemainderResolver remainderResolver
) {
    enum Kind { SHAPED_CRAFTING, SHAPELESS_CRAFTING, SMELTING }

    /**
     * slot is the planner's selected-requirement index: sparse source index for shaped,
     * dense declaration-order index for shapeless, and -1 for the single smelting input.
     */
    record Input(int slot, Ingredient predicate) {
        Input {
            Objects.requireNonNull(predicate, "predicate");
            if (slot < -1) throw new IllegalArgumentException("recipe input slot must be -1 or nonnegative");
        }
    }

    /**
     * Computes one exact recipe-specific remainder stack per cell of the concrete grid, in row-major order.
     * Known no-remainder cells must be represented by empty stacks; unknown remainder behavior must throw.
     */
    @FunctionalInterface
    interface RemainderResolver {
        List<ItemStack> resolve(ScreenHandler handler, int gridWidth, List<ItemStack> inputGrid);
    }

    RecipeWork {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outputPerOperation, "outputPerOperation");
        if (outputPerOperation.isEmpty()) throw new IllegalArgumentException("recipe output must not be empty");
        outputPerOperation = outputPerOperation.copy();
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (inputs.isEmpty() || inputs.size() > 9) throw new IllegalArgumentException("executor supports 1..9 inputs");

        HashSet<Integer> slots = new HashSet<>();
        for (Input input : inputs) {
            Objects.requireNonNull(input, "recipe input");
            if (!slots.add(input.slot())) throw new IllegalArgumentException("duplicate recipe input slot " + input.slot());
        }

        switch (kind) {
            case SHAPED_CRAFTING -> {
                if (width < 1 || height < 1 || width > 3 || height > 3 || width * height > 9
                        || cookTicks != 0 || remainderResolver == null
                        || inputs.stream().anyMatch(input -> input.slot() < 0 || input.slot() >= width * height)) {
                    throw new IllegalArgumentException("invalid shaped recipe work");
                }
            }
            case SHAPELESS_CRAFTING -> {
                if (width != 0 || height != 0 || cookTicks != 0 || remainderResolver == null
                        || inputs.stream().anyMatch(input -> input.slot() < 0 || input.slot() >= inputs.size())) {
                    throw new IllegalArgumentException("invalid shapeless recipe work");
                }
                for (int slot = 0; slot < inputs.size(); slot++) {
                    if (!slots.contains(slot)) throw new IllegalArgumentException("shapeless recipe slots must be sequential");
                }
            }
            case SMELTING -> {
                if (width != 0 || height != 0 || cookTicks < 1 || remainderResolver != null
                        || inputs.size() != 1 || inputs.get(0).slot() != -1) {
                    throw new IllegalArgumentException("invalid smelting recipe work");
                }
            }
        }
    }

    @Override
    public ItemStack outputPerOperation() {
        return outputPerOperation.copy();
    }

    int gridIndex(Input input, int gridWidth) {
        if (gridWidth < 2 || gridWidth > 3) throw new IllegalArgumentException("crafting grid width must be 2 or 3");
        if (kind == Kind.SHAPED_CRAFTING) {
            if (width > gridWidth || height > gridWidth || input.slot() >= width * height) {
                throw new IllegalArgumentException("recipe does not fit the crafting grid");
            }
            int rowOffset = (gridWidth - height) / 2;
            int columnOffset = (gridWidth - width) / 2;
            return (input.slot() / width + rowOffset) * gridWidth + input.slot() % width + columnOffset;
        }
        if (kind == Kind.SHAPELESS_CRAFTING && input.slot() < inputs.size() && gridWidth * gridWidth >= inputs.size()) return input.slot();
        throw new IllegalArgumentException("recipe input has no crafting grid position");
    }
}
