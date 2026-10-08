package dev.lodekeeper.core;

public sealed interface NativeAcquisitionSource extends AcquisitionSource
        permits AnimalHarvestSource, StoredItemSource {
    NativeWork work();
    int worldEffortPerOperation();
}
