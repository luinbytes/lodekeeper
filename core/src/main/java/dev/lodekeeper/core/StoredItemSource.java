package dev.lodekeeper.core;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One observed, permitted storage lot. The adapter owns its opaque reference and generation. */
public record StoredItemSource(
        String sourceId,
        ItemId output,
        int availableCount,
        String stockReference,
        long generation,
        InventoryToolLot toolLot
) implements AcquisitionSource {
    public StoredItemSource {
        sourceId = SourceValidation.sourceId(sourceId);
        output = SourceValidation.output(output);
        if (availableCount < 0 || availableCount > 1_000_000_000) {
            throw new IllegalArgumentException("Stored count out of range");
        }
        if (stockReference == null || stockReference.isBlank() || stockReference.length() > 512) {
            throw new IllegalArgumentException("Invalid stock reference");
        }
        if (generation < 0) throw new IllegalArgumentException("Storage generation must be nonnegative");
    }

    public StoredItemSource(String sourceId, ItemId output, int availableCount, String stockReference, long generation) {
        this(sourceId, output, availableCount, stockReference, generation, null);
    }

    @Override public int outputCount() { return 1; }
    @Override public List<Requirement> requirements() { return List.of(); }
    @Override public String sourceType() { return "storage"; }

    @Override public Map<String, String> attributes() {
        var attributes = new TreeMap<String, String>();
        attributes.put("stockReference", stockReference);
        attributes.put("stockGeneration", Long.toString(generation));
        attributes.put("observedCount", Integer.toString(availableCount));
        if (toolLot != null) {
            attributes.put("remainingDurability", Integer.toString(toolLot.remainingDurability()));
            attributes.put("silkTouch", Boolean.toString(toolLot.silkTouch()));
        }
        return Map.copyOf(attributes);
    }
}
