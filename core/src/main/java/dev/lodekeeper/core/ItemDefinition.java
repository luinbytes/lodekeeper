package dev.lodekeeper.core;

import java.util.List;
import java.util.Objects;

/** Catalog metadata used for friendly lookup and safe tool planning. */
public record ItemDefinition(ItemId id, List<String> aliases, int maximumDurability, long fuelBurnTicks) {
    public ItemDefinition {
        Objects.requireNonNull(id, "id");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases")).stream()
                .map(CatalogSnapshot::normalizeAlias).distinct().toList();
        if (aliases.size() > 128 || aliases.stream().anyMatch(alias -> alias.isEmpty() || alias.length() > 256)) {
            throw new IllegalArgumentException("Invalid or excessive item aliases");
        }
        if (maximumDurability < 0 || maximumDurability > 10_000_000) {
            throw new IllegalArgumentException("maximumDurability out of range");
        }
        if (fuelBurnTicks < 0 || fuelBurnTicks > 10_000_000) {
            throw new IllegalArgumentException("fuelBurnTicks out of range");
        }
    }

    public ItemDefinition(ItemId id, int maximumDurability, String... aliases) {
        this(id, List.of(aliases), maximumDurability, 0);
    }

    public ItemDefinition(ItemId id, int maximumDurability, long fuelBurnTicks, String... aliases) {
        this(id, List.of(aliases), maximumDurability, fuelBurnTicks);
    }
}
