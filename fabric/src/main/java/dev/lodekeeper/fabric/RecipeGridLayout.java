package dev.lodekeeper.fabric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure grid geometry shared by normalized recipe placement and remainder expansion. */
final class RecipeGridLayout {
    private RecipeGridLayout() {}

    static int craftingSlot(boolean shaped, int recipeWidth, int recipeHeight, int recipeSlot,
                            int ingredientCount, int gridWidth) {
        if (gridWidth < 2 || gridWidth > 3) throw new IllegalArgumentException("crafting grid width must be 2 or 3");
        if (!shaped) {
            if (recipeSlot < 0 || recipeSlot >= ingredientCount || ingredientCount > gridWidth * gridWidth) {
                throw new IllegalArgumentException("shapeless recipe does not fit the crafting grid");
            }
            return recipeSlot;
        }
        if (recipeWidth < 1 || recipeHeight < 1 || recipeWidth > gridWidth || recipeHeight > gridWidth
                || recipeSlot < 0 || recipeSlot >= recipeWidth * recipeHeight) {
            throw new IllegalArgumentException("shaped recipe does not fit the crafting grid");
        }
        int rowOffset = (gridWidth - recipeHeight) / 2;
        int columnOffset = (gridWidth - recipeWidth) / 2;
        return (recipeSlot / recipeWidth + rowOffset) * gridWidth + recipeSlot % recipeWidth + columnOffset;
    }

    static <T> List<T> expandPositioned(int gridWidth, int gridHeight,
                                        int left, int top, int positionedWidth, int positionedHeight,
                                        List<T> positionedValues, T emptyValue) {
        if (gridWidth < 1 || gridHeight < 1 || gridWidth > 3 || gridHeight > 3
                || left < 0 || top < 0 || positionedWidth < 1 || positionedHeight < 1
                || left + positionedWidth > gridWidth || top + positionedHeight > gridHeight
                || positionedValues == null || positionedValues.size() != positionedWidth * positionedHeight) {
            throw new IllegalArgumentException("positioned values do not fit the crafting grid");
        }
        Objects.requireNonNull(emptyValue, "empty grid value");
        List<T> fullGrid = new ArrayList<>(Collections.nCopies(gridWidth * gridHeight, emptyValue));
        for (int row = 0; row < positionedHeight; row++) {
            for (int column = 0; column < positionedWidth; column++) {
                T value = positionedValues.get(row * positionedWidth + column);
                if (value == null) throw new IllegalArgumentException("positioned value is unknown");
                fullGrid.set((top + row) * gridWidth + left + column, value);
            }
        }
        return List.copyOf(fullGrid);
    }
}
