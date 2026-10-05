package dev.lodekeeper.core;

import java.util.List;
import java.util.Objects;

/** Furnace-style conversion, including explicit input, fuel and station dependencies. */
public record SmeltingSource(
        String sourceId,
        ItemId output,
        int outputCount,
        Ingredient input,
        List<ItemSelector> fuels,
        long cookTicks,
        List<Requirement> requirements
) implements AcquisitionSource {
    public SmeltingSource {
        sourceId = SourceValidation.sourceId(sourceId);
        output = SourceValidation.output(output);
        outputCount = SourceValidation.outputCount(outputCount);
        Objects.requireNonNull(input, "input");
        fuels = List.copyOf(Objects.requireNonNull(fuels, "fuels"));
        if (fuels.isEmpty() || fuels.size() > 256 || fuels.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Smelting source needs 1..256 fuel alternatives");
        }
        if (cookTicks < 1 || cookTicks > 1_000_000) throw new IllegalArgumentException("cookTicks out of range");
        requirements = SourceValidation.requirements(requirements);
    }
}
