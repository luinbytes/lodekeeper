package dev.lodekeeper.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** A shaped or shapeless crafting recipe. outputCount is produced per operation. */
public record CraftingSource(
        String sourceId,
        ItemId output,
        int outputCount,
        RecipeType recipeType,
        int width,
        int height,
        List<RecipeSlot> slots,
        List<Requirement> requirements
) implements AcquisitionSource {
    public CraftingSource {
        sourceId = SourceValidation.sourceId(sourceId);
        output = SourceValidation.output(output);
        outputCount = SourceValidation.outputCount(outputCount);
        Objects.requireNonNull(recipeType, "recipeType");
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        if (slots.isEmpty() || slots.size() > 256 || slots.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Crafting source needs 1..256 ingredient slots");
        }
        if (recipeType == RecipeType.SHAPED) {
            if (width < 1 || height < 1 || width > 16 || height > 16 || (long) width * height > 256) {
                throw new IllegalArgumentException("Invalid shaped recipe dimensions");
            }
            var seen = new HashSet<Integer>();
            for (RecipeSlot slot : slots) {
                if (slot.slotIndex() < 0 || slot.slotIndex() >= width * height || !seen.add(slot.slotIndex())) {
                    throw new IllegalArgumentException("Invalid or duplicate shaped recipe slot");
                }
            }
        } else {
            if (width != 0 || height != 0 || slots.stream().anyMatch(slot -> slot.slotIndex() != -1)) {
                throw new IllegalArgumentException("Shapeless recipes use zero dimensions and slot index -1");
            }
        }
        requirements = SourceValidation.requirements(requirements);
    }
}
