package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable, version-neutral recipe and fuel data handed from a profile provider to GameCatalog. */
record RecipeCatalogSnapshot(
        Map<String, RecipeWork> recipes,
        List<String> unsupported,
        Map<ItemId, Long> fuelBurnTicks
) {
    RecipeCatalogSnapshot {
        var sortedRecipes = new TreeMap<String, RecipeWork>();
        Objects.requireNonNull(recipes, "recipes").forEach((key, value) ->
                sortedRecipes.put(Objects.requireNonNull(key, "recipe key"), Objects.requireNonNull(value, "recipe work")));
        recipes = Map.copyOf(sortedRecipes);
        unsupported = List.copyOf(Objects.requireNonNull(unsupported, "unsupported"));
        var sortedFuel = new TreeMap<ItemId, Long>();
        Objects.requireNonNull(fuelBurnTicks, "fuelBurnTicks").forEach((item, ticks) -> {
            Objects.requireNonNull(item, "fuel item");
            if (ticks == null || ticks < 1) throw new IllegalArgumentException("fuel duration must be positive");
            sortedFuel.put(item, ticks);
        });
        fuelBurnTicks = Map.copyOf(sortedFuel);
    }

    static RecipeCatalogSnapshot empty() {
        return new RecipeCatalogSnapshot(Map.of(), List.of(), Map.of());
    }
}
