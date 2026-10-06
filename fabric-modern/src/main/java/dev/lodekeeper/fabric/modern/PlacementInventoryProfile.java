package dev.lodekeeper.fabric.modern;

/** Maps native container packet slots to the player's 36 storage slots. */
final class PlacementInventoryProfile {
    static final int MAIN_SLOTS = 36;
    static final int PLAYER_MENU_CONTENTS = 46;
    private static final int PLAYER_MAIN_START = 9;
    private static final int PLAYER_HOTBAR_START = 36;
    private static final int PLAYER_HOTBAR_END = 45;

    private PlacementInventoryProfile() { }

    static int mainSlotFromContainerSlot(int containerId, int slot) {
        if (containerId == -2) return slot >= 0 && slot < MAIN_SLOTS ? slot : -1;
        if (containerId == 0) return mainSlotFromPlayerMenuSlot(slot);
        return -1;
    }

    static int mainSlotFromPlayerInventorySlot(int slot) {
        return slot >= 0 && slot < MAIN_SLOTS ? slot : -1;
    }

    static int mainSlotFromPlayerMenuContents(int slot) {
        return mainSlotFromPlayerMenuSlot(slot);
    }

    private static int mainSlotFromPlayerMenuSlot(int slot) {
        if (slot >= PLAYER_MAIN_START && slot < 36) return slot;
        if (slot >= PLAYER_HOTBAR_START && slot < PLAYER_HOTBAR_END) return slot - PLAYER_HOTBAR_START;
        return -1;
    }
}
