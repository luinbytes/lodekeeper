package dev.lodekeeper.core;

import java.util.List;
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
        Map<ItemId, Integer> protectedCounts,
        Map<ItemId, List<Integer>> durabilityLots
) {
    private static final int MAX_DURABILITY_LOTS_PER_ITEM = 36;
    private static final int MAX_DURABILITY = 10_000_000;

    public InventorySnapshot {
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(availableStations, "availableStations");
        Objects.requireNonNull(remainingDurability, "remainingDurability");
        Objects.requireNonNull(protectedCounts, "protectedCounts");
        Objects.requireNonNull(durabilityLots, "durabilityLots");
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
            if (durability < 0 || durability > MAX_DURABILITY) throw new IllegalArgumentException("Durability out of range");
            durabilityCopy.put(item, durability);
        });
        counts = Map.copyOf(countCopy);
        availableStations = Set.copyOf(new TreeSet<>(availableStations));

        var lotCopy = new TreeMap<ItemId, List<Integer>>();
        durabilityLots.forEach((item, lots) -> {
            Objects.requireNonNull(item, "durability lot item");
            Objects.requireNonNull(lots, "durability lots");
            if (lots.isEmpty()) {
                if (durabilityCopy.containsKey(item)) {
                    throw new IllegalArgumentException("Maximum durability was supplied without a durability lot for " + item);
                }
                return;
            }
            if (lots.size() > MAX_DURABILITY_LOTS_PER_ITEM) {
                throw new IllegalArgumentException("Too many durability lots for " + item);
            }
            var copiedLots = new java.util.ArrayList<Integer>(lots.size());
            for (Integer durability : lots) {
                Objects.requireNonNull(durability, "lot durability");
                if (durability < 0 || durability > MAX_DURABILITY) throw new IllegalArgumentException("Durability lot out of range");
                copiedLots.add(durability);
            }
            if (copiedLots.size() > countCopy.getOrDefault(item, 0)) {
                throw new IllegalArgumentException("Durability lots exceed inventory stacks for " + item);
            }
            copiedLots.sort(Integer::compareTo);
            lotCopy.put(item, List.copyOf(copiedLots));
            Integer suppliedMaximum = durabilityCopy.get(item);
            int actualMaximum = copiedLots.get(copiedLots.size() - 1);
            if (suppliedMaximum != null && suppliedMaximum != actualMaximum) {
                throw new IllegalArgumentException("Maximum durability disagrees with durability lots for " + item);
            }
            durabilityCopy.put(item, actualMaximum);
        });
        // Older callers expose only the best known stack. Preserve exactly one known lot,
        // never infer duplicate full-durability tools from the inventory count.
        durabilityCopy.forEach((item, durability) -> {
            if (countCopy.getOrDefault(item, 0) < 1) {
                throw new IllegalArgumentException("Durability was provided for an item absent from inventory: " + item);
            }
            lotCopy.putIfAbsent(item, List.of(durability));
        });
        remainingDurability = Map.copyOf(durabilityCopy);
        durabilityLots = Map.copyOf(lotCopy);

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

    /** Compatibility constructor: a max-only durability view contributes one known lot per item type. */
    public InventorySnapshot(Map<ItemId, Integer> counts, Set<StationId> availableStations,
                             Map<ItemId, Integer> remainingDurability, Map<ItemId, Integer> protectedCounts) {
        this(counts, availableStations, remainingDurability, protectedCounts, Map.of());
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
