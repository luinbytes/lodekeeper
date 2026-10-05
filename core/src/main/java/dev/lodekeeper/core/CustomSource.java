package dev.lodekeeper.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Extension source whose execution is provided by an adapter registered for sourceType. */
public record CustomSource(
        String sourceId,
        String sourceType,
        ItemId output,
        int outputCount,
        List<Requirement> requirements,
        Map<String, String> attributes
) implements AcquisitionSource {
    public CustomSource {
        sourceId = SourceValidation.sourceId(sourceId);
        Objects.requireNonNull(sourceType, "sourceType");
        sourceType = sourceType.trim();
        if (sourceType.isEmpty() || sourceType.length() > 128) throw new IllegalArgumentException("Invalid sourceType");
        output = SourceValidation.output(output);
        outputCount = SourceValidation.outputCount(outputCount);
        requirements = SourceValidation.requirements(requirements);
        attributes = SourceValidation.attributes(attributes);
    }
}
