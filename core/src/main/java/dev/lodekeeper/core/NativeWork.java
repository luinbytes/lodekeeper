package dev.lodekeeper.core;

import java.util.Objects;

/** Immutable native operation facts. Quantities remain demand units on the step. */
public sealed interface NativeWork permits NativeWork.AnimalHarvest, NativeWork.Retrieve, NativeWork.CropHarvest {
    enum CropKind { WHEAT, CARROT, POTATO, BEETROOT }

    record CropHarvest(CropKind crop) implements NativeWork {
        public CropHarvest { Objects.requireNonNull(crop, "crop"); }
    }

    enum AnimalKind { COW, PIG, SHEEP }
    enum HarvestMethod { KILL, SHEAR }

    record AnimalHarvest(AnimalKind animal, HarvestMethod method) implements NativeWork {
        public AnimalHarvest {
            Objects.requireNonNull(animal, "animal");
            Objects.requireNonNull(method, "method");
            if (method == HarvestMethod.SHEAR && animal != AnimalKind.SHEEP)
                throw new IllegalArgumentException("Only sheep support shearing");
        }
    }

    record Retrieve(String stockReference, long generation) implements NativeWork {
        public Retrieve {
            if (stockReference == null || stockReference.isBlank() || stockReference.length() > 512 || generation < 0)
                throw new IllegalArgumentException("Invalid finite stock identity");
        }
    }
}
