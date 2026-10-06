package dev.lodekeeper.core;

/** Durability and harvest behavior for one known physical inventory stack of a tool. */
public record InventoryToolLot(int remainingDurability, boolean silkTouch) {
    private static final int MAX_DURABILITY = 10_000_000;

    public InventoryToolLot {
        if (remainingDurability < 0 || remainingDurability > MAX_DURABILITY) {
            throw new IllegalArgumentException("Durability lot out of range");
        }
    }
}
