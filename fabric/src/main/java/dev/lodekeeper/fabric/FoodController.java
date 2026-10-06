package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;

/** Holds ordinary item use while eating; selection excludes foods with configured status effects. */
public final class FoodController {
    private static final java.util.Set<String> ORDINARY_FOODS = java.util.Set.of(
        "apple", "bread", "baked_potato", "beetroot", "beetroot_soup", "carrot", "cookie", "dried_kelp",
        "golden_carrot", "melon_slice", "mushroom_stew", "potato", "pumpkin_pie", "rabbit_stew",
        "sweet_berries", "glow_berries", "cooked_beef", "cooked_chicken", "cooked_cod", "cooked_mutton",
        "cooked_porkchop", "cooked_rabbit", "cooked_salmon", "beef", "cod", "mutton", "porkchop",
        "rabbit", "salmon", "tropical_fish", "honey_bottle");
    private static FoodController owner;
    public static boolean isHoldingUse() { return owner != null && owner.ownsUse(); }
    private final MinecraftClient client;
    private final PlayerActions actions;
    private java.util.Map<dev.lodekeeper.core.ItemId, Integer> protectedCounts = java.util.Map.of();
    private boolean active;
    private net.minecraft.client.network.ClientPlayerEntity usingPlayer;
    private net.minecraft.item.ItemStack usingFood = net.minecraft.item.ItemStack.EMPTY;
    private int ticks, initialHunger, slot = -1;
    FoodController(MinecraftClient client, PlayerActions actions) { this.client = client; this.actions = actions; owner = this; }
    void updateProtection(java.util.Map<dev.lodekeeper.core.ItemId, Integer> counts) {
        protectedCounts = java.util.Map.copyOf(counts);
    }
    boolean ready() { return !active && selectFood() >= 0; }
    int availableNutrition() {
        if (client.player == null) return 0;
        var remainingReservations = new java.util.HashMap<>(protectedCounts);
        long nutrition = 0;
        for (int index = 0; index < 36; index++) {
            var stack = client.player.getInventory().getStack(index);
            if (stack.isEmpty()) continue;
            var id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
            if (!id.getNamespace().equals("minecraft") || !ORDINARY_FOODS.contains(id.getPath())) continue;
            var food = GameApi.food(stack);
            if (food == null || !food.safe() || food.nutrition() < 1) continue;
            var item = GameCatalog.id(stack.getItem());
            int reserved = remainingReservations.getOrDefault(item, 0);
            int protectedInStack = Math.min(stack.getCount(), reserved);
            remainingReservations.put(item, reserved - protectedInStack);
            nutrition += (long) food.nutrition() * (stack.getCount() - protectedInStack);
            if (nutrition >= 120) return 120;
        }
        return (int) nutrition;
    }

    private int selectFood() {
        if (client.player == null || client.interactionManager == null || client.player.isUsingItem()
            || client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler
            || !client.player.playerScreenHandler.getCursorStack().isEmpty()
            || !client.player.isOnGround() && !client.player.isTouchingWater()) return -1;
        int missing = 20 - client.player.getHungerManager().getFoodLevel();
        if (missing < 1 || missing < 6 && client.player.getHealth() >= client.player.getMaxHealth()) return -1;
        float best = -Float.MAX_VALUE;
        int selected = -1;
        for (int index = 0; index < 36; index++) {
            var stack = client.player.getInventory().getStack(index);
            var food = GameApi.food(stack);
            var id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
            // A component alone cannot describe teleporting foods, NBT stew effects or custom item hooks.
            if (!id.getNamespace().equals("minecraft") || !ORDINARY_FOODS.contains(id.getPath())) continue;
            if (actions.count(stack.getItem()) <= protectedCounts.getOrDefault(GameCatalog.id(stack.getItem()), 0)) continue;
            if (stack.isEmpty() || food == null || !food.safe() || food.nutrition() < 1) continue;
            float score = Math.min(missing, food.nutrition()) + food.saturation() - Math.max(0, food.nutrition() - missing);
            if (score > best) { best = score; selected = index; }
        }
        return selected;
    }
    boolean begin() {
        if (active) return false;
        slot = selectFood();
        if (slot < 0 || !actions.selectSlot(slot)) { slot = -1; return false; }
        initialHunger = client.player.getHungerManager().getFoodLevel(); ticks = 0;
        active = client.interactionManager.interactItem(client.player, Hand.MAIN_HAND).isAccepted() && client.player.isUsingItem();
        if (active) { usingPlayer = client.player; usingFood = client.player.getActiveItem().copy(); }
        return active;
    }
    private boolean ownsUse() {
        return active && client.player == usingPlayer && usingPlayer != null && usingPlayer.isUsingItem()
            && usingPlayer.getActiveHand() == Hand.MAIN_HAND && GameApi.canCombine(usingPlayer.getActiveItem(), usingFood);
    }
    boolean active() { return active; }
    /** True when finished or rejected. A timeout does not silently keep the use control held. */
    boolean tick() {
        if (!active) return true;
        if (!ownsUse()) { active = false; usingPlayer = null; usingFood = net.minecraft.item.ItemStack.EMPTY; return true; }
        if (++ticks > 100
            || client.player.getHungerManager().getFoodLevel() > initialHunger) { stop(); return true; }
        return false;
    }
    void stop() {
        boolean ownedUse = ownsUse();
        active = false; slot = -1; usingPlayer = null; usingFood = net.minecraft.item.ItemStack.EMPTY;
        if (ownedUse && client.player != null && client.player.isUsingItem() && client.interactionManager != null)
            client.interactionManager.stopUsingItem(client.player);
    }
}
