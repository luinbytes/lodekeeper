package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.ItemId;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.PlaySoundConsumeEffect;

import java.util.Set;
import java.util.List;

/** Holds only the use key for a verified ordinary food stack owned by this controller. */
public final class FoodController {
    record Preparation(ItemId raw, ItemId item, int targetCount) { }
    private record CookingPair(Item raw, Item cooked) { }
    private record FoodInfo(int nutrition, boolean safe) { }

    private static final List<CookingPair> COOKING_PAIRS = List.of(
            new CookingPair(Items.BEEF, Items.COOKED_BEEF),
            new CookingPair(Items.PORKCHOP, Items.COOKED_PORKCHOP),
            new CookingPair(Items.MUTTON, Items.COOKED_MUTTON),
            new CookingPair(Items.RABBIT, Items.COOKED_RABBIT),
            new CookingPair(Items.COD, Items.COOKED_COD),
            new CookingPair(Items.SALMON, Items.COOKED_SALMON));
    private static final int PREPARATION_NUTRITION_TARGET = 36;
    private static final Set<String> ORDINARY_FOODS = Set.of(
            "apple", "bread", "baked_potato", "beetroot", "beetroot_soup", "carrot", "cookie", "dried_kelp",
            "golden_carrot", "melon_slice", "mushroom_stew", "potato", "pumpkin_pie", "rabbit_stew",
            "sweet_berries", "glow_berries", "cooked_beef", "cooked_chicken", "cooked_cod", "cooked_mutton",
            "cooked_porkchop", "cooked_rabbit", "cooked_salmon", "beef", "cod", "mutton", "porkchop",
            "rabbit", "salmon", "tropical_fish", "honey_bottle");

    private static FoodController owner;

    public static boolean isHoldingUse() {
        return owner != null && owner.ownsUse() && GameApi.screen(owner.client) == null
                && owner.client.player.containerMenu == owner.client.player.inventoryMenu
                && owner.client.player.containerMenu.getCarried().isEmpty();
    }

    private final Minecraft client;
    private final PlayerActions actions;
    private java.util.Map<dev.lodekeeper.core.ItemId, Integer> protectedCounts = java.util.Map.of();
    private boolean active;
    private net.minecraft.client.player.LocalPlayer usingPlayer;
    private ItemStack usingFood = ItemStack.EMPTY;
    private int ticks, initialHunger;

    FoodController(Minecraft client, PlayerActions actions) {
        this.client = client;
        this.actions = actions;
        owner = this;
    }

    void updateProtection(java.util.Map<dev.lodekeeper.core.ItemId, Integer> counts) {
        protectedCounts = java.util.Map.copyOf(counts);
    }
    boolean ready() { return !active && selectFood() >= 0; }
    int availableNutrition() {
        if (client.player == null) return 0;
        var remainingReservations = new java.util.HashMap<>(protectedCounts);
        long nutrition = 0;
        for (int index = 0; index < 36; index++) {
            var stack = client.player.getInventory().getItem(index);
            if (stack.isEmpty() || stack.isEnchanted() || GameApi.hasCustomName(stack)
                    || !ItemStack.isSameItemSameComponents(stack, stack.getItem().getDefaultInstance())) continue;
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!id.getNamespace().equals("minecraft") || !ORDINARY_FOODS.contains(id.getPath())) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            Consumable consumable = stack.get(DataComponents.CONSUMABLE);
            if (food == null || consumable == null || !safeEffects(consumable) || food.nutrition() < 1) continue;
            var item = GameCatalog.id(stack.getItem());
            int reserved = remainingReservations.getOrDefault(item, 0);
            int protectedInStack = Math.min(stack.getCount(), reserved);
            remainingReservations.put(item, reserved - protectedInStack);
            nutrition += (long) food.nutrition() * (stack.getCount() - protectedInStack);
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
            FoodInfo rawFood = foodInfo(pair.raw().getDefaultInstance());
            FoodInfo cookedFood = foodInfo(pair.cooked().getDefaultInstance());
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
            ItemStack stack = client.player.getInventory().getItem(index);
            if (stack.isEmpty() || !stack.is(raw) || stack.isEnchanted()
                    || GameApi.hasCustomName(stack)
                    || !ItemStack.isSameItemSameComponents(stack, raw.getDefaultInstance())) continue;
            FoodInfo food = foodInfo(stack);
            if (food == null || !food.safe() || food.nutrition() != expectedNutrition) continue;
            count = Math.addExact(count, stack.getCount());
        }
        return count;
    }


    private int selectFood() {
        if (client.player == null || client.level == null || client.gameMode == null || !client.player.isAlive()
                || client.player.isUsingItem() || GameApi.screen(client) != null
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || !client.player.onGround() && !client.player.isInWater()) return -1;

        FoodData hunger = client.player.getFoodData();
        int missing = 20 - hunger.getFoodLevel();
        if (missing < 1 || missing < 6 && client.player.getHealth() >= client.player.getMaxHealth()) return -1;

        float best = -Float.MAX_VALUE;
        int selected = -1;
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getItem(index);
            if (stack.isEmpty() || stack.isEnchanted() || GameApi.hasCustomName(stack)
                    || !ItemStack.isSameItemSameComponents(stack, stack.getItem().getDefaultInstance())) continue;
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!id.getNamespace().equals("minecraft") || !ORDINARY_FOODS.contains(id.getPath())) continue;
            if (actions.count(stack.getItem()) <= protectedCounts.getOrDefault(GameCatalog.id(stack.getItem()), 0)) continue;

            FoodProperties food = stack.get(DataComponents.FOOD);
            Consumable consumable = stack.get(DataComponents.CONSUMABLE);
            if (food == null || consumable == null || !safeEffects(consumable) || food.nutrition() < 1) continue;

            float score = Math.min(missing, food.nutrition()) + food.saturation()
                    - Math.max(0, food.nutrition() - missing);
            if (score > best) { best = score; selected = index; }
        }
        return selected;
    }

    private static boolean safeEffects(Consumable consumable) {
        // Playing a sound has no gameplay effect. Reject status, teleport, custom and unknown effects.
        return consumable.onConsumeEffects().stream().allMatch(PlaySoundConsumeEffect.class::isInstance);
    }

    private static FoodInfo foodInfo(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        return food == null || consumable == null ? null
                : new FoodInfo(food.nutrition(), safeEffects(consumable));
    }

    boolean begin() {
        if (active) return false;
        int slot = selectFood();
        if (slot < 0) return false;
        ItemStack expected = client.player.getInventory().getItem(slot).copy();
        if (!actions.selectSlot(slot)) return false;
        ItemStack mainHand = client.player.getInventory().getSelectedItem();
        if (!same(mainHand, expected)) return false;

        initialHunger = client.player.getFoodData().getFoodLevel();
        ticks = 0;
        usingPlayer = client.player;
        usingFood = mainHand.copy();
        InteractionResult result = client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        active = result.consumesAction();
        if (!ownsUse()) {
            clearOwnership();
            return false;
        }
        return true;
    }

    private boolean ownsUse() {
        return active && client.player == usingPlayer && usingPlayer != null
                && usingPlayer.isUsingItem() && usingPlayer.getUsedItemHand() == InteractionHand.MAIN_HAND
                && same(usingPlayer.getUseItem(), usingFood)
                && same(usingPlayer.getInventory().getSelectedItem(), usingFood);
    }

    boolean active() { return active; }

    /** Completes on observed eating, loss of exact ownership, or a bounded timeout. */
    boolean tick() {
        if (!active) return true;
        if (!ownsUse()) {
            clearOwnership();
            return true;
        }
        if (++ticks > 100 || client.player.getFoodData().getFoodLevel() > initialHunger) {
            stop();
            return true;
        }
        return false;
    }

    void stop() {
        boolean ownedUse = ownsUse();
        clearOwnership();
        if (ownedUse && client.player != null && client.gameMode != null) {
            client.gameMode.releaseUsingItem(client.player);
        }
    }

    private void clearOwnership() {
        active = false;
        usingPlayer = null;
        usingFood = ItemStack.EMPTY;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }
}
