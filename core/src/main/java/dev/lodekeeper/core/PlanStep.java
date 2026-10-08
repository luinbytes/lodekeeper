package dev.lodekeeper.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One ordered action. Dependencies list exact selected items, reserved tools and stations. */
public record PlanStep(
        PlanKind kind,
        String sourceId,
        ItemId output,
        int outputCount,
        int operationCount,
        List<SelectedRequirement> requirements,
        List<BlockId> candidateBlocks,
        RecipeType recipeType,
        int recipeWidth,
        int recipeHeight,
        StationId station,
        String customType,
        Map<String, String> attributes,
        NativeWork nativeWork
) {
    public PlanStep(PlanKind kind, String sourceId, ItemId output, int outputCount, int operationCount,
                    List<SelectedRequirement> requirements, List<BlockId> candidateBlocks,
                    RecipeType recipeType, int recipeWidth, int recipeHeight, StationId station,
                    String customType, Map<String, String> attributes) {
        this(kind, sourceId, output, outputCount, operationCount, requirements, candidateBlocks,
                recipeType, recipeWidth, recipeHeight, station, customType, attributes, null);
    }

    public static PlanStep nativeAction(String sourceId, ItemId output, int requestedIncrease,
                                        int demandUnits, List<SelectedRequirement> selected, NativeWork work) {
        return new PlanStep(PlanKind.NATIVE, sourceId, output, requestedIncrease, demandUnits, selected,
                List.of(), null, 0, 0, null, null, Map.of(), work);
    }

    public PlanStep {
        Objects.requireNonNull(kind, "kind");
        sourceId = SourceValidation.sourceId(sourceId);
        requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        candidateBlocks = List.copyOf(Objects.requireNonNull(candidateBlocks, "candidateBlocks"));
        attributes = SourceValidation.attributes(attributes);
        if (operationCount < 1) throw new IllegalArgumentException("operationCount must be positive");
        if (kind == PlanKind.PLACE_STATION) {
            if (output != null || outputCount != 0 || station == null) throw new IllegalArgumentException("Invalid station placement step");
        } else {
            Objects.requireNonNull(output, "output");
            if (outputCount < 1) throw new IllegalArgumentException("outputCount must be positive");
        }
        if (kind == PlanKind.CRAFT) {
            Objects.requireNonNull(recipeType, "recipeType");
            if (recipeWidth < 0 || recipeHeight < 0 || recipeWidth > 16 || recipeHeight > 16) throw new IllegalArgumentException("Invalid recipe dimensions");
        }
        if (kind == PlanKind.GATHER && candidateBlocks.isEmpty()) throw new IllegalArgumentException("Gather step needs candidate blocks");
        if ((kind == PlanKind.NATIVE) != (nativeWork != null))
            throw new IllegalArgumentException("Native work belongs exactly to NATIVE steps");
        if (kind == PlanKind.CUSTOM && (customType == null || customType.isBlank())) throw new IllegalArgumentException("Custom step needs customType");
    }
}
