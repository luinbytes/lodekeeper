package dev.lodekeeper.fabric;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipeWorkTest {
    @Test void shapedSlotsKeepSparseIndicesAndCenterInTheSelectedGrid() {
        RecipeWork recipe = new RecipeWork(RecipeWork.Kind.SHAPED_CRAFTING, new ItemStack(Items.STICK),
                2, 2, List.of(input(0), input(3)), 0, (handler, width, grid) -> List.of());

        assertEquals(0, recipe.gridIndex(recipe.inputs().get(0), 2));
        assertEquals(3, recipe.gridIndex(recipe.inputs().get(1), 2));
        assertEquals(0, recipe.gridIndex(recipe.inputs().get(0), 3));
        assertEquals(4, recipe.gridIndex(recipe.inputs().get(1), 3));
    }

    @Test void shapelessSlotsRemainStableAcrossRepeatedOperations() {
        RecipeWork recipe = new RecipeWork(RecipeWork.Kind.SHAPELESS_CRAFTING, new ItemStack(Items.STICK),
                0, 0, List.of(input(0), input(1), input(2)), 0, (handler, width, grid) -> List.of());

        assertEquals(List.of(0, 1, 2), recipe.inputs().stream().map(input -> recipe.gridIndex(input, 3)).toList());
        assertEquals(List.of(0, 1, 2), recipe.inputs().stream().map(RecipeWork.Input::slot).toList());
    }

    @Test void outputStackIsCopiedAtTheCatalogBoundary() {
        ItemStack supplied = new ItemStack(Items.STICK, 3);
        RecipeWork recipe = new RecipeWork(RecipeWork.Kind.SHAPELESS_CRAFTING, supplied,
                0, 0, List.of(input(0)), 0, (handler, width, grid) -> List.of());

        supplied.setCount(1);
        ItemStack read = recipe.outputPerOperation();
        read.setCount(2);
        assertEquals(3, recipe.outputPerOperation().getCount());
    }

    @Test void shapedRecipeMustFitBeforeItCanBePlaced() {
        RecipeWork recipe = new RecipeWork(RecipeWork.Kind.SHAPED_CRAFTING, new ItemStack(Items.STICK),
                3, 2, List.of(input(0)), 0, (handler, width, grid) -> List.of());

        assertThrows(IllegalArgumentException.class, () -> recipe.gridIndex(recipe.inputs().get(0), 2));
    }

    private static RecipeWork.Input input(int slot) {
        return new RecipeWork.Input(slot, Ingredient.ofItems(Items.STONE));
    }
}
