package dev.lodekeeper.fabric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipeGridLayoutTest {
    @Test void shapedSlotsKeepSparseIndicesAndCenterInTheSelectedGrid() {
        assertEquals(0, RecipeGridLayout.craftingSlot(true, 2, 2, 0, 2, 2));
        assertEquals(3, RecipeGridLayout.craftingSlot(true, 2, 2, 3, 2, 2));
        assertEquals(0, RecipeGridLayout.craftingSlot(true, 2, 2, 0, 2, 3));
        assertEquals(4, RecipeGridLayout.craftingSlot(true, 2, 2, 3, 2, 3));
    }

    @Test void shapelessSlotIdentityIsStableAcrossOperations() {
        assertEquals(0, RecipeGridLayout.craftingSlot(false, 0, 0, 0, 3, 3));
        assertEquals(1, RecipeGridLayout.craftingSlot(false, 0, 0, 1, 3, 3));
        assertEquals(2, RecipeGridLayout.craftingSlot(false, 0, 0, 2, 3, 3));
    }

    @Test void centeredSingleRemainderReturnsToItsOriginalGridCell() {
        List<String> grid = RecipeGridLayout.expandPositioned(3, 3, 1, 1, 1, 1,
                List.of("empty-bucket"), "empty");

        assertEquals(List.of("empty", "empty", "empty", "empty", "empty-bucket", "empty", "empty", "empty", "empty"), grid);
    }

    @Test void sparseRecipeRemaindersExpandFromTrimmedBounds() {
        List<String> grid = RecipeGridLayout.expandPositioned(3, 3, 1, 0, 2, 2,
                List.of("empty", "empty", "glass-bottle", "empty"), "empty");

        assertEquals("glass-bottle", grid.get(4));
        assertEquals(9, grid.size());
        assertEquals(8, grid.stream().filter("empty"::equals).count());
    }

    @Test void craftingGridMappingRejectsUnplaceableInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> RecipeGridLayout.craftingSlot(true, 3, 2, 0, 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> RecipeGridLayout.craftingSlot(false, 0, 0, 4, 5, 2));
        assertThrows(IllegalArgumentException.class,
                () -> RecipeGridLayout.expandPositioned(3, 3, 2, 2, 2, 2, List.of("r"), "empty"));
    }
}
