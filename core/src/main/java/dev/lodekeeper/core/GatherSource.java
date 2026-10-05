package dev.lodekeeper.core;

import java.util.List;
import java.util.Objects;

/** Obtains drops by breaking one of the listed candidate blocks. */
public record GatherSource(
        String sourceId,
        ItemId output,
        int outputCount,
        List<BlockId> blocks,
        List<Requirement> requirements
) implements AcquisitionSource {
    public GatherSource {
        sourceId = SourceValidation.sourceId(sourceId);
        output = SourceValidation.output(output);
        outputCount = SourceValidation.outputCount(outputCount);
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (blocks.isEmpty() || blocks.size() > 256 || blocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Gather source needs 1..256 candidate blocks");
        }
        requirements = SourceValidation.requirements(requirements);
    }

    public GatherSource(String sourceId, ItemId output, int outputCount, List<BlockId> blocks) {
        this(sourceId, output, outputCount, blocks, List.of());
    }
}
