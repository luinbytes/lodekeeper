package dev.lodekeeper.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded proof for replacing a distant station using one stored-material recipe. */
public final class StoredCrafting {
    private StoredCrafting() { }

    public static boolean canSupplyOne(CatalogSnapshot catalog, InventorySnapshot inventory, ItemId item) {
        return canSupplyOne(catalog, inventory, item, System::nanoTime);
    }

    static boolean canSupplyOne(CatalogSnapshot catalog, InventorySnapshot inventory, ItemId item, LongSupplier clock) {
        if (inventory.spendableCount(item) > 0) return true;
        List<AcquisitionSource> sources = catalog.sourcesFor(item);
        if (sources.size() != 1 || !(sources.get(0) instanceof CraftingSource recipe)) return false;
        long deadline = clock.getAsLong() + 1_000_000L;
        Map<ItemId, Integer> stock = new HashMap<>(inventory.counts());
        inventory.protectedCounts().forEach((protectedItem, count) -> stock.merge(protectedItem, -count, Integer::sum));
        var stations = new HashSet<>(inventory.availableStations());
        int inspected = 0;
        for (Requirement requirement : recipe.requirements()) {
            if (++inspected > 512 || clock.getAsLong() >= deadline) return false;
            if (!(requirement instanceof StationRequirement station)) return false;
            if (stations.add(station.station())) {
                int held = stock.getOrDefault(station.placementItem(), 0);
                if (held < 1) return false;
                stock.put(station.placementItem(), held - 1);
            }
        }
        for (RecipeSlot slot : recipe.slots()) {
            if (++inspected > 512 || clock.getAsLong() >= deadline) return false;
            ItemId chosen = null;
            for (ItemSelector selector : slot.ingredient().alternatives()) {
                for (ItemId candidate : catalog.expand(selector)) {
                    if (++inspected > 512 || clock.getAsLong() >= deadline) return false;
                    if (stock.getOrDefault(candidate, 0) >= slot.ingredient().count()) { chosen = candidate; break; }
                }
                if (chosen != null) break;
            }
            if (chosen == null) return false;
            stock.merge(chosen, -slot.ingredient().count(), Integer::sum);
        }
        return true;
    }
}
