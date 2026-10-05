package dev.lodekeeper.core;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable inventory and nearby-station view captured on the game thread. */
public record InventorySnapshot(
        Map<ItemId, Integer> counts,
        Set<StationId> availableStations,
        Map<ItemId, Integer> remainingDurability,
        Map<ItemId, Integer> protectedCounts
) {
    public InventorySnapshot {
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(availableStations, "availableStations");
        Objects.requireNonNull(remainingDurability, "remainingDurability");
        Objects.requireNonNull(protectedCounts, "protectedCounts");
        var countCopy = new TreeMap<ItemId, Integer>();
        counts.forEach((item, count) -> {
            Objects.requireNonNull(item, "inventory item");
            Objects.requireNonNull(count, "inventory count");
            if (count < 0 || count > 1_000_000_000) throw new IllegalArgumentException("Inventory count out of range");
            if (count > 0) countCopy.put(item, count);
        });
        var durabilityCopy = new TreeMap<ItemId, Integer>();
        remainingDurability.forEach((item, durability) -> {
            Objects.requireNonNull(item, "durability item");
            Objects.requireNonNull(durability, "remaining durability");
            if (durability < 0 || durability > 10_000_000) throw new IllegalArgumentException("Durability out of range");
            durabilityCopy.put(item, durability);
        });
        counts = Map.copyOf(countCopy);
        availableStations = Set.copyOf(new TreeSet<>(availableStations));
        remainingDurability = Map.copyOf(durabilityCopy);

        var protectedCopy = new TreeMap<ItemId, Integer>();
        protectedCounts.forEach((item, count) -> {
            Objects.requireNonNull(item, "protected inventory item");
            Objects.requireNonNull(count, "protected inventory count");
            if (count < 0 || count > 1_000_000_000) throw new IllegalArgumentException("Protected inventory count out of range");
            if (count > countCopy.getOrDefault(item, 0)) {
                throw new IllegalArgumentException("Protected inventory count exceeds available inventory for " + item);
            }
            if (count > 0) protectedCopy.put(item, count);
        });
        protectedCounts = Map.copyOf(protectedCopy);
    }

    public InventorySnapshot(Map<ItemId, Integer> counts, Set<StationId> availableStations,
                             Map<ItemId, Integer> remainingDurability) {
        this(counts, availableStations, remainingDurability, Map.of());
    }

    public InventorySnapshot(Map<ItemId, Integer> counts) {
        this(counts, Set.of(), Map.of(), Map.of());
    }

    public int count(ItemId item) { return counts.getOrDefault(item, 0); }

    /** Count available for consuming recipe, fuel, or station requirements. */
    public int spendableCount(ItemId item) { return count(item) - protectedCounts.getOrDefault(item, 0); }
}
