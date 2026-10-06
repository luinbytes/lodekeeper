package dev.lodekeeper.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Furnace-style conversion with source-specific effective cooking progress per fuel item. */
public record SmeltingSource(
        String sourceId,
        ItemId output,
        int outputCount,
        Ingredient input,
        List<ItemSelector> fuels,
        long cookTicks,
        List<Requirement> requirements,
        Map<ItemId, Long> fuelProgressTicks
) implements AcquisitionSource {
    public static final int MAX_FUELS = 512;
    private static final long MAX_FUEL_PROGRESS_TICKS = 1_000_000_000L;

    public SmeltingSource {
        sourceId = SourceValidation.sourceId(sourceId);
        output = SourceValidation.output(output);
        outputCount = SourceValidation.outputCount(outputCount);
        Objects.requireNonNull(input, "input");
        fuels = List.copyOf(Objects.requireNonNull(fuels, "fuels"));
        if (fuels.isEmpty() || fuels.size() > MAX_FUELS || fuels.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Smelting source needs 1.." + MAX_FUELS + " fuel alternatives");
        }
        if (cookTicks < 1 || cookTicks > 1_000_000) throw new IllegalArgumentException("cookTicks out of range");
        requirements = SourceValidation.requirements(requirements);
        Objects.requireNonNull(fuelProgressTicks, "fuelProgressTicks");
        if (fuelProgressTicks.size() > MAX_FUELS) throw new IllegalArgumentException("fuelProgressTicks must contain at most " + MAX_FUELS + " fuels");
        Map<ItemId, Long> fuelProgressCopy = new HashMap<>();
        for (Map.Entry<ItemId, Long> entry : fuelProgressTicks.entrySet()) {
            ItemId fuel = entry.getKey();
            Long ticks = entry.getValue();
            if (fuel == null || ticks == null) throw new IllegalArgumentException("fuelProgressTicks cannot contain null keys or values");
            if (ticks < 1 || ticks > MAX_FUEL_PROGRESS_TICKS) {
                throw new IllegalArgumentException("fuel progress ticks out of range for " + fuel);
            }
            fuelProgressCopy.put(fuel, ticks);
        }
        fuelProgressTicks = Map.copyOf(fuelProgressCopy);
    }

    /** Compatibility constructor using catalog fuel capacities for all alternatives. */
    public SmeltingSource(String sourceId, ItemId output, int outputCount, Ingredient input,
                          List<ItemSelector> fuels, long cookTicks, List<Requirement> requirements) {
        this(sourceId, output, outputCount, input, fuels, cookTicks, requirements, Map.of());
    }

    /**
     * Returns effective cooking progress per fuel item in this recipe's tick units.
     * An empty override map falls back to catalog fuel capacities; a nonempty map is
     * authoritative, so fuels absent from it have capacity zero.
     */
    public long effectiveFuelTicks(CatalogSnapshot catalog, ItemId fuel) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(fuel, "fuel");
        return fuelProgressTicks.isEmpty() ? catalog.fuelBurnTicks(fuel) : fuelProgressTicks.getOrDefault(fuel, 0L);
    }
}
