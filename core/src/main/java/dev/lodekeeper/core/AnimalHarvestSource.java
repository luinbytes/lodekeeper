package dev.lodekeeper.core;

import java.util.List;
import java.util.Objects;

/** Requested ordinary stock, without predicting random animal drops. */
public record AnimalHarvestSource(String sourceId, ItemId output, NativeWork.AnimalHarvest work,
                                  List<Requirement> requirements) implements NativeAcquisitionSource {
    public AnimalHarvestSource {
        sourceId = SourceValidation.sourceId(sourceId);
        output = SourceValidation.output(output);
        Objects.requireNonNull(work, "work");
        requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        String item = output.toString();
        boolean valid = work.method() == NativeWork.HarvestMethod.SHEAR
                ? item.matches("minecraft:(white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_wool")
                : switch (work.animal()) {
                    case COW -> item.equals("minecraft:beef") || item.equals("minecraft:leather");
                    case PIG -> item.equals("minecraft:porkchop");
                    case SHEEP -> item.equals("minecraft:mutton");
                };
        if (!valid) throw new IllegalArgumentException("Animal output does not match its operation");
        if (work.method() == NativeWork.HarvestMethod.SHEAR) {
            if (requirements.size() != 1 || !(requirements.get(0) instanceof ToolRequirement tool)
                    || !tool.tools().equals(Ingredient.of(ItemId.parse("minecraft:shears")))
                    || tool.wearPerOperation() != 1 || tool.minimumDurability() < 2)
                throw new IllegalArgumentException("Shearing requires ordinary shears and reserved wear");
        } else if (!requirements.isEmpty()) throw new IllegalArgumentException("Requested kills use an empty hand");
    }
    @Override public int outputCount() { return 1; }
    @Override public int worldEffortPerOperation() { return 1; }
    @Override public String sourceType() { return "animal"; }
}
