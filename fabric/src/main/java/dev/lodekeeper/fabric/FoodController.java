package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.List;

/** Holds ordinary item use while eating; selection excludes foods with configured status effects. */
public final class FoodController {
    record Preparation(ItemId raw, ItemId item, int targetCount) { }
    private record CookingPair(Item raw, Item cooked) { }

    private static final List<CookingPair> COOKING_PAIRS = List.of(
            new CookingPair(Items.BEEF, Items.COOKED_BEEF),
            new CookingPair(Items.PORKCHOP, Items.COOKED_PORKCHOP),
            new CookingPair(Items.MUTTON, Items.COOKED_MUTTON),
            new CookingPair(Items.RABBIT, Items.COOKED_RABBIT),
            new CookingPair(Items.COD, Items.COOKED_COD),
            new CookingPair(Items.SALMON, Items.COOKED_SALMON));
    private static final int PREPARATION_NUTRITION_TARGET = 36;
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
            if (stack.isEmpty() || stack.hasEnchantments() || GameApi.hasCustomName(stack)
                    || !GameApi.canCombine(stack, stack.getItem().getDefaultStack())) continue;
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

    int availableCookedNutrition() {
        if (client.player == null) return 0;
        long nutrition = availableNutrition();
        for (CookingPair pair : COOKING_PAIRS) {
            var rawFood = GameApi.food(pair.raw().getDefaultStack());
            var cookedFood = GameApi.food(pair.cooked().getDefaultStack());
            if (rawFood == null || !rawFood.safe() || rawFood.nutrition() < 1
                    || cookedFood == null || !cookedFood.safe()) continue;
            int gainPerItem = cookedFood.nutrition() - rawFood.nutrition();
            if (gainPerItem <= 0) continue;
            int reserved = Math.max(0, protectedCounts.getOrDefault(GameCatalog.id(pair.raw()), 0));
            int unreservedRaw = Math.max(0, availableSafeRaw(pair.raw(), rawFood.nutrition()) - reserved);
            nutrition += (long) unreservedRaw * gainPerItem;
            if (nutrition >= 120) return 120;
        }
        return (int) nutrition;
    }

    Preparation preparationGoal() {
        if (client.player == null) return null;
        int currentNutrition = availableNutrition();
        if (currentNutrition >= PREPARATION_NUTRITION_TARGET) return null;
        int deficit = PREPARATION_NUTRITION_TARGET - currentNutrition;

        Preparation best = null;
        int bestGain = 0;
        for (CookingPair pair : COOKING_PAIRS) {
            var rawFood = GameApi.food(pair.raw().getDefaultStack());
            var cookedFood = GameApi.food(pair.cooked().getDefaultStack());
            if (rawFood == null || !rawFood.safe() || rawFood.nutrition() < 1
                    || cookedFood == null || !cookedFood.safe()) continue;

            int gainPerItem = cookedFood.nutrition() - rawFood.nutrition();
            if (gainPerItem <= 0) continue;
            ItemId rawId = GameCatalog.id(pair.raw());
            int safeRaw = availableSafeRaw(pair.raw(), rawFood.nutrition());
            int reserved = Math.max(0, protectedCounts.getOrDefault(rawId, 0));
            int unreservedRaw = Math.max(0, safeRaw - reserved);
            if (unreservedRaw == 0) continue;

            int needed = (deficit + gainPerItem - 1) / gainPerItem;
            int operations = Math.min(unreservedRaw, needed);
            int totalGain = operations * gainPerItem;
            ItemId cookedId = GameCatalog.id(pair.cooked());
            if (totalGain < bestGain || totalGain == bestGain && best != null
                    && cookedId.compareTo(best.item()) >= 0) continue;

            int targetCount = Math.addExact(actions.count(pair.cooked()), operations);
            best = new Preparation(rawId, cookedId, targetCount);
            bestGain = totalGain;
        }
        return best;
    }

    private int availableSafeRaw(Item raw, int expectedNutrition) {
        int count = 0;
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            if (stack.isEmpty() || !stack.isOf(raw) || stack.hasEnchantments()
                    || GameApi.hasCustomName(stack) || !GameApi.canCombine(stack, raw.getDefaultStack())) continue;
            var food = GameApi.food(stack);
            if (food == null || !food.safe() || food.nutrition() != expectedNutrition) continue;
            count = Math.addExact(count, stack.getCount());
        }
        return count;
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
            if (stack.isEmpty() || stack.hasEnchantments() || GameApi.hasCustomName(stack)
                    || !GameApi.canCombine(stack, stack.getItem().getDefaultStack())) continue;
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
