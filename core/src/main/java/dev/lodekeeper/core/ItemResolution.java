package dev.lodekeeper.core;

import java.util.List;

/** Result of resolving an item command or ingredient name. */
public record ItemResolution(ItemId item, List<ItemId> candidates) {
    public ItemResolution {
        candidates = List.copyOf(candidates);
        if (item != null && (!candidates.isEmpty() && !candidates.equals(List.of(item)))) {
            throw new IllegalArgumentException("Resolved item must be the only candidate");
        }
    }

    public boolean found() { return item != null; }
    public boolean ambiguous() { return item == null && candidates.size() > 1; }
}
