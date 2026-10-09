package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.stream.Collectors;

/** Small Mojang API seam for GUI and fuel changes in 26.2. */
final class GameApi {
    private GameApi() {}

    static boolean supportsTravel() { return false; }
    static java.util.UUID resolveTravelPlayer(net.minecraft.client.Minecraft client, String selector) {
        throw new IllegalStateException("Player travel is unsupported in this game family");
    }
    static net.minecraft.world.entity.Entity loadedTravelPlayer(net.minecraft.client.Minecraft client, java.util.UUID id) { return null; }
    static boolean travelPose(net.minecraft.client.Minecraft client) { return false; }
    static boolean travelBounds(net.minecraft.client.Minecraft client, net.minecraft.core.BlockPos feet) { return false; }


    static boolean animalAttackWindow(net.minecraft.world.entity.LivingEntity animal) {
        return animal.hurtTime <= 0;
    }

    static boolean supportsAnimalHarvest() { return false; }
    static dev.lodekeeper.core.ItemId sheepWool(net.minecraft.world.entity.animal.sheep.Sheep sheep) {
        return dev.lodekeeper.core.ItemId.parse("minecraft:" + sheep.getColor().getName() + "_wool");
    }
    static void shearAnimal(net.minecraft.client.Minecraft client, net.minecraft.world.entity.animal.sheep.Sheep sheep) {
        throw new IllegalStateException("Animal shearing is unsupported in this API family");
    }



    static boolean ordinaryShield(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(net.minecraft.world.item.Items.SHIELD) || stack.getCount() != 1
                || !stack.isDamageableItem() || stack.isEnchanted() || hasCustomName(stack)
                || stack.getMaxDamage() - stack.getDamageValue() <= 100) return false;
        ItemStack normalized = stack.copy(), ordinary = new ItemStack(net.minecraft.world.item.Items.SHIELD);
        normalized.setDamageValue(0);
        ordinary.setDamageValue(0);
        return ItemStack.isSameItemSameComponents(normalized, ordinary);
    }

    static boolean sameShield(ItemStack current, ItemStack expected) {
        if (current.isEmpty() || expected.isEmpty() || current.getCount() != 1 || expected.getCount() != 1
                || !current.is(net.minecraft.world.item.Items.SHIELD) || current.getDamageValue() < expected.getDamageValue()) return false;
        ItemStack first = current.copy(), second = expected.copy();
        first.setDamageValue(0);
        second.setDamageValue(0);
        return ItemStack.isSameItemSameComponents(first, second);
    }

    static boolean startShieldUse(net.minecraft.client.Minecraft client) {
        return client.gameMode.useItem(client.player, net.minecraft.world.InteractionHand.OFF_HAND).consumesAction()
                && client.player.isUsingItem() && client.player.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND;
    }

    static boolean ownsShieldUse(net.minecraft.world.entity.player.Player player, ItemStack expected) {
        return player.isUsingItem() && player.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND
                && sameShield(player.getUseItem(), expected) && sameShield(player.getOffhandItem(), expected);
    }
    static boolean isHostileMob(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.Mob mob)
                || !(entity instanceof net.minecraft.world.entity.monster.Enemy)
                || entity instanceof net.minecraft.world.entity.NeutralMob
                || entity instanceof net.minecraft.world.entity.monster.piglin.Piglin) return false;
        return !(entity instanceof net.minecraft.world.entity.monster.spider.Spider)
                || mob.getTarget() != null || mob.getLightLevelDependentMagicValue() < 0.5f;
    }

    static void attackAirborneForDefense(Minecraft client, net.minecraft.world.entity.Entity target) {
        var player = client.player;
        if (player == null || client.gameMode == null || player.onGround())
            throw new IllegalStateException("airborne defense pose is unavailable");
        player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly(
                player.onGround(), player.horizontalCollision));
        client.gameMode.attack(player, target);
    }

    static double defenseReach(net.minecraft.world.entity.player.Player player) { return player.entityInteractionRange(); }

    static boolean defenseWithinReach(net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.Entity target) {
        double reach = defenseReach(player);
        return Double.isFinite(reach) && reach > 0 && player.isWithinEntityInteractionRange(target, 0.0)
                && player.isWithinAttackRange(player.getMainHandItem(), target.getBoundingBox(), 0.0);
    }

    static boolean defenseHasSweepCollateral(net.minecraft.world.level.Level world, net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.Entity target) {
        java.util.List<net.minecraft.world.entity.LivingEntity> nearby = new java.util.ArrayList<>();
        world.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.LivingEntity.class),
                target.getBoundingBox().inflate(1.0, 0.25, 1.0), living -> living != player && living != target, nearby, 17);
        if (nearby.size() >= 17) return true;
        for (var living : nearby) {
            double distance = player.distanceToSqr(living);
            if (!Double.isFinite(distance)) return true;
            if (distance >= 9.0) continue;
            if (!(living instanceof net.minecraft.world.entity.Mob mob) || !isHostileMob(mob)
                    || mob.getTarget() != null && mob.getTarget() != player) return true;
        }
        return false;
    }

    static int defenseAttackWear(ItemStack stack) {
        if (stack.getItem().getClass() != Item.class) return -1;
        if (!(stack.is(net.minecraft.tags.ItemTags.SWORDS) || stack.is(net.minecraft.tags.ItemTags.AXES)
                || stack.is(net.minecraft.tags.ItemTags.PICKAXES) || stack.is(net.minecraft.tags.ItemTags.SHOVELS)
                || stack.is(net.minecraft.tags.ItemTags.HOES))) return -1;
        return attackWear(stack);
    }

    static double defenseAttackDamage(net.minecraft.world.entity.player.Player player, ItemStack stack) {
        var attribute = net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE;
        var current = player.getAttribute(attribute);
        if (current == null) return Double.NaN;
        var trial = new net.minecraft.world.entity.ai.attributes.AttributeInstance(attribute, ignored -> {});
        trial.replaceFrom(current);
        try {
            player.getMainHandItem().forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND, (type, modifier) -> {
                if (type.equals(attribute)) trial.removeModifier(modifier);
            });
            stack.forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND, (type, modifier) -> {
                if (type.equals(attribute)) trial.addTransientModifier(modifier);
            });
            double value = trial.getValue();
            return Double.isFinite(value) && value > 0 ? value : Double.NaN;
        } catch (IllegalArgumentException unsupportedModifiers) {
            return Double.NaN;
        }
    }

    static boolean hasCustomName(ItemStack stack) { return stack.getCustomName() != null; }

    static boolean hasSilkTouch(ItemStack stack) {
        var enchantments = stack.getEnchantments();
        return enchantments.keySet().stream().anyMatch(enchantment ->
                enchantment.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchantment) > 0);
    }

    static boolean isSword(ItemStack stack) { return stack.is(net.minecraft.tags.ItemTags.SWORDS); }

    static boolean isAxe(ItemStack stack) { return stack.is(net.minecraft.tags.ItemTags.AXES); }

    static int attackWear(ItemStack stack) {
        if (!stack.isDamageableItem()) return 0;
        var weapon = stack.get(net.minecraft.core.component.DataComponents.WEAPON);
        return weapon == null ? -1 : weapon.itemDamagePerAttack();
    }

    static double launchInputAcceleration(net.minecraft.client.player.LocalPlayer player, float friction) {
        if (player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FRICTION_MODIFIER) != 1
                || player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.AIR_DRAG_MODIFIER) != 1) return Double.NaN;
        float acceleration = friction > .6 ? player.getSpeed() * (.21600002f / (friction * friction * friction)) : player.getSpeed();
        return acceleration * .98f;
    }

    static Object stonecuttingProviderIdentity(Minecraft client) {
        if (client.level == null || client.getConnection() == null) return null;
        return client.getConnection().recipes().stonecutterRecipes();
    }

    static java.util.List<StonecuttingWork> stonecuttingRecipes(Minecraft client) {
        if (client.level == null || client.getConnection() == null) return java.util.List.of();
        var entries = client.getConnection().recipes().stonecutterRecipes().entries();
        if (entries.size() > 4096) throw new IllegalArgumentException("stonecutting recipe source exceeds 4096 entries");
        java.util.List<StonecuttingWork> works = new java.util.ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            try {
                var entry = entries.get(index);
                var input = entry.input();
                if (input == null || input.isEmpty()) continue;
                ItemStack output = stonecuttingDisplayOutput(entry.recipe().optionDisplay(), client.level);
                if (output.isEmpty()) continue;
                works.add(new StonecuttingWork("stonecutting:sync:" + index, input, output, entry));
            } catch (RuntimeException unsupportedRow) {
                // Reject only this malformed display row; preserve the remaining synced options.
            }
        }
        return java.util.List.copyOf(works);
    }

    static int stonecuttingRecipeIndex(Minecraft client, net.minecraft.world.inventory.StonecutterMenu menu,
                                       StonecuttingWork work) {
        if (client == null || client.level == null || client.getConnection() == null || menu == null || work == null
                || !(work.selectionKey() instanceof net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry<?> selected)) return -1;
        var currentEntries = client.getConnection().recipes().stonecutterRecipes().entries();
        if (currentEntries.stream().noneMatch(entry -> entry == selected)) return -1;
        ItemStack heldInput = menu.getSlot(net.minecraft.world.inventory.StonecutterMenu.INPUT_SLOT).getItem();
        if (heldInput.isEmpty() || !work.input().test(heldInput)) return -1;
        int match = -1;
        var visible = menu.getVisibleRecipes().entries();
        for (int index = 0; index < visible.size(); index++) {
            var candidate = visible.get(index);
            if (candidate != selected) continue;
            if (match >= 0 || candidate.input() != work.input() || !candidate.input().test(heldInput)) return -1;
            ItemStack actual = stonecuttingDisplayOutput(candidate.recipe().optionDisplay(), client.level);
            ItemStack expected = work.outputPerOperation();
            if (actual.isEmpty() || actual.getCount() != expected.getCount()
                    || !ItemStack.isSameItemSameComponents(actual, expected)) return -1;
            match = index;
        }
        return match;
    }

    private static ItemStack stonecuttingDisplayOutput(SlotDisplay display, Level level) {
        java.util.List<ItemStack> alternatives = display.resolve(SlotDisplayContext.fromLevel(level),
                SlotDisplay.ItemStackContentsFactory.INSTANCE).limit(65).toList();
        if (alternatives.isEmpty() || alternatives.size() > 64 || alternatives.stream().anyMatch(ItemStack::isEmpty)) {
            return ItemStack.EMPTY;
        }
        ItemStack expected = alternatives.getFirst();
        if (expected.getCount() > 99) return ItemStack.EMPTY;
        for (int index = 1; index < alternatives.size(); index++) {
            ItemStack alternative = alternatives.get(index);
            if (alternative.getCount() != expected.getCount()
                    || !ItemStack.isSameItemSameComponents(alternative, expected)) return ItemStack.EMPTY;
        }
        return expected.copy();
    }

    static Screen screen(Minecraft client) { return client.gui.screen(); }

    static void setScreen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }

    static KeyMapping keyMapping(String name, int keyCode, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, category);
    }

    static int blockBreakWear(ItemStack stack) {
        if (!stack.isDamageableItem()) return 0;
        var tool = stack.get(net.minecraft.core.component.DataComponents.TOOL);
        return tool == null ? -1 : tool.damagePerBlock();
    }

    static void swing(LocalPlayer player, InteractionHand hand) { player.swing(hand); }

    static Set<Item> tagItems(SlotDisplay.TagSlotDisplay display, Level level) {
        var holders = level.registryAccess().lookupOrThrow(Registries.ITEM).get(display.tag())
                .orElseThrow(() -> new IllegalArgumentException("Unknown item tag: " + display.tag()));
        if (!holders.isBound()) throw new IllegalArgumentException("Unbound item tag: " + display.tag());
        Set<Item> items = holders.stream().map(Holder::value).collect(Collectors.toUnmodifiableSet());
        if (items.isEmpty()) throw new IllegalArgumentException("Empty item tag: " + display.tag());
        return items;
    }

    static boolean dynamicCookingSpeed() { return false; }

    static boolean supportedCookingFuelStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxStackSize() <= 99
                && stack.getItem().getCraftingRemainder() == null;
    }

    static long cookingFuelProgressTicks(Level level, ItemStack stack,
            net.minecraft.world.level.block.Block station, int recipeDuration) {
        if (level == null || recipeDuration < 100 || recipeDuration > 10_000_000
                || !supportedCookingFuelStack(stack)) return 0;
        long burn = fuelTicks(level, stack);
        if (station == net.minecraft.world.level.block.Blocks.SMOKER
                || station == net.minecraft.world.level.block.Blocks.BLAST_FURNACE) burn /= 2;
        else if (station != net.minecraft.world.level.block.Blocks.FURNACE) return 0;
        if (burn < 32 || stack.getMaxStackSize() > 1
                && (long) Math.max(1, stack.getMaxStackSize() / 2) * burn < 800) return 0;
        return stack.getMaxStackSize() == 1 ? burn - burn % recipeDuration : burn;
    }

    static long initialFuelTicks(Level level, ItemStack stack) { return fuelTicks(level, stack); }

    static long fuelTicks(Level level, ItemStack stack) {
        if (level == null || stack.isEmpty()) return 0;
        return Math.max(0, Math.min(10_000_000, level.fuelValues().burnDuration(stack)));
    }
}
