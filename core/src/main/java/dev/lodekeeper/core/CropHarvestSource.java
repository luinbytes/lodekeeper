package dev.lodekeeper.core;

import java.util.List;
import java.util.Objects;

/** One demand unit; a harvest never predicts planting stock or random seed yield. */
public record CropHarvestSource(String sourceId, ItemId output, NativeWork.CropHarvest work)
        implements NativeAcquisitionSource {
    public CropHarvestSource {
        sourceId = SourceValidation.sourceId(sourceId);
        output = SourceValidation.output(output);
        Objects.requireNonNull(work, "work");
        List<String> valid = switch (work.crop()) {
            case WHEAT -> List.of("minecraft:wheat", "minecraft:wheat_seeds");
            case CARROT -> List.of("minecraft:carrot");
            case POTATO -> List.of("minecraft:potato");
            case BEETROOT -> List.of("minecraft:beetroot", "minecraft:beetroot_seeds");
        };
        if (!valid.contains(output.toString())) throw new IllegalArgumentException("Crop output does not match its operation");
    }
    @Override public List<Requirement> requirements() { return List.of(); }
    @Override public int outputCount() { return 1; }
    @Override public int worldEffortPerOperation() { return 1; }
    @Override public String sourceType() { return "crop"; }
}
