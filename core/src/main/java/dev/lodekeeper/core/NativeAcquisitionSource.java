package dev.lodekeeper.core;

public sealed interface NativeAcquisitionSource extends AcquisitionSource
        permits AnimalHarvestSource, StoredItemSource, CropHarvestSource {
    NativeWork work();
    int worldEffortPerOperation();
}
