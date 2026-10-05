package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.advancements.predicates.BlockPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchBlock;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProvider;
import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/** Small Mojang API seam for GUI and item-component fuel access in 26.3. */
final class GameApi {
    private GameApi() {}

    static Screen screen(Minecraft client) { return client.gui.screen(); }

    static void setScreen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }

    static KeyMapping keyMapping(String name, int keyCode, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYBOARD, keyCode, category);
    }

    static int blockBreakWear(ItemStack stack) {
        if (!stack.isDamageableItem()) return 0;
        var tool = stack.get(net.minecraft.core.component.DataComponents.TOOL);
        return tool == null ? -1 : tool.damagePerBlock();
    }

    static void swing(LocalPlayer player, InteractionHand hand) {
        player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
    }

    static Set<Item> tagItems(SlotDisplay.TagSlotDisplay display, net.minecraft.world.level.Level level) {
        if (level == null) throw new IllegalArgumentException("Item tag lookup needs a loaded level");
        if (!display.tag().isBound()) throw new IllegalArgumentException("Unbound item tag display");
        Set<Item> items = display.tag().stream().map(Holder::value).collect(Collectors.toUnmodifiableSet());
        if (items.isEmpty()) throw new IllegalArgumentException("Empty item tag display");
        return items;
    }

    static boolean dynamicCookingSpeed() { return true; }

    static boolean supportedCookingFuelStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxStackSize() <= 99
                && stack.getItem().getCraftingRemainder() == null
                && !stack.hasNonDefault(DataComponents.COOKING_FUEL);
    }

    static long cookingFuelProgressTicks(net.minecraft.world.level.Level level, ItemStack stack,
            net.minecraft.world.level.block.Block station, int recipeDuration) {
        if (recipeDuration < 1 || recipeDuration > 10_000_000 || !supportedCookingFuelStack(stack)
                || station != Blocks.FURNACE && station != Blocks.SMOKER && station != Blocks.BLAST_FURNACE) return 0;
        CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
        if (fuel == null) return 0;
        OptionalInt burn;
        Optional<Float> speed;
        try {
            if (level instanceof ServerLevel serverLevel && serverLevel.getServer().isSameThread()) {
                var lookup = serverLevel.getServer().reloadableRegistries().lookup();
                burn = resolveInt(fuel.burnTime(), lookup.lookupOrThrow(Registries.CONTEXT_INT_PROVIDER), station);
                speed = resolveFloat(fuel.speedMultiplier(), lookup.lookupOrThrow(Registries.CONTEXT_FLOAT_PROVIDER), station);
            } else {
                burn = fuel.burnTime() instanceof ResolvableInt.Constant constant
                        ? OptionalInt.of(constant.value()) : OptionalInt.empty();
                speed = fuel.speedMultiplier() instanceof ResolvableFloat.Constant constant
                        ? Optional.of(constant.value()) : Optional.empty();
            }
            if (burn.isEmpty() || burn.getAsInt() < 32 || speed.isEmpty()
                    || !Float.isFinite(speed.get()) || speed.get() <= 0 || speed.get() > 1024) return 0;
            if (stack.getMaxStackSize() > 1
                    && (long) Math.max(1, stack.getMaxStackSize() / 2) * burn.getAsInt() < 800) return 0;
            // Serialized cursor work needs enough timer slack for early refills and output recovery.
            if (Math.ceil((double) (recipeDuration / speed.get())) < 100) return 0;
            return dev.lodekeeper.core.CookingFuelCapacity.progressTicks(
                    Math.min(10_000_000, burn.getAsInt()), recipeDuration, speed.get(), stack.getMaxStackSize() > 1);
        } catch (RuntimeException unsupported) {
            return 0;
        }
    }

    static long initialFuelTicks(net.minecraft.world.level.Level level, ItemStack stack) {
        if (stack.isEmpty()) return 0;
        CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
        if (fuel == null || !(fuel.burnTime() instanceof ResolvableInt.Constant burnTime)
                || !(fuel.speedMultiplier() instanceof ResolvableFloat.Constant speed) || speed.value() != 1.0f)
            return 0;
        return Math.max(0, Math.min(10_000_000, burnTime.value()));
    }

    static long fuelTicks(ServerLevel level, ItemStack stack) {
        if (level == null || !level.getServer().isSameThread() || stack.isEmpty()) return 0;
        CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
        if (fuel == null) return 0;
        try {
            HolderLookup.RegistryLookup<ContextIntProvider> intProviders = level.getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.CONTEXT_INT_PROVIDER);
            HolderLookup.RegistryLookup<ContextFloatProvider> floatProviders = level.getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.CONTEXT_FLOAT_PROVIDER);
            OptionalInt burnTime = resolveInt(fuel.burnTime(), intProviders, Blocks.FURNACE);
            Optional<Float> speed = resolveFloat(fuel.speedMultiplier(), floatProviders, Blocks.FURNACE);
            if (burnTime.isEmpty() || speed.isEmpty() || !Float.isFinite(speed.get()) || speed.get() != 1.0f) return 0;
            int ticks = burnTime.getAsInt();
            return Math.max(0, Math.min(10_000_000, ticks));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static final int MAX_PROVIDER_DEPTH = 16;
    private static final int MAX_PROVIDER_NODES = 64;

    private static OptionalInt resolveInt(ResolvableInt provider, HolderLookup.RegistryLookup<ContextIntProvider> registry, net.minecraft.world.level.block.Block station) {
        if (provider instanceof ResolvableInt.Constant constant) return OptionalInt.of(constant.value());
        if (provider instanceof ResolvableInt.Reference reference) {
            ContextIntProvider resolved = registry.get(reference.key()).map(Holder::value).orElse(null);
            if (resolved == null) return OptionalInt.empty();
            return resolveInt(resolved, registry, station, 0, new int[]{MAX_PROVIDER_NODES},
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        return OptionalInt.empty();
    }

    private static OptionalInt resolveInt(ContextIntProvider provider, HolderLookup.RegistryLookup<ContextIntProvider> registry,
                                          net.minecraft.world.level.block.Block station, int depth, int[] remaining, Set<ContextIntProvider> path) {
        if (depth > MAX_PROVIDER_DEPTH || remaining[0]-- <= 0 || !path.add(provider)) return OptionalInt.empty();
        try {
            if (provider.getClass() == net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue.class) {
                var constant = (net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue) provider;
                return OptionalInt.of(constant.value());
            }
            if (provider.getClass() == net.minecraft.world.level.storage.loot.providers.number.ints.Quotient.class) {
                var quotient = (net.minecraft.world.level.storage.loot.providers.number.ints.Quotient) provider;
                OptionalInt left = resolveInt(quotient.left().value(), registry, station, depth + 1, remaining, path);
                OptionalInt right = resolveInt(quotient.right().value(), registry, station, depth + 1, remaining, path);
                if (left.isEmpty() || right.isEmpty() || right.getAsInt() == 0) return OptionalInt.empty();
                return OptionalInt.of(left.getAsInt() / right.getAsInt());
            }
            if (provider.getClass() == net.minecraft.world.level.storage.loot.providers.number.ints.FloorQuotient.class) {
                var quotient = (net.minecraft.world.level.storage.loot.providers.number.ints.FloorQuotient) provider;
                OptionalInt left = resolveInt(quotient.left().value(), registry, station, depth + 1, remaining, path);
                OptionalInt right = resolveInt(quotient.right().value(), registry, station, depth + 1, remaining, path);
                if (left.isEmpty() || right.isEmpty() || right.getAsInt() == 0) return OptionalInt.empty();
                return OptionalInt.of(Math.floorDivExact(left.getAsInt(), right.getAsInt()));
            }
            if (provider.getClass() == net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue.class) {
                var conditional = (net.minecraft.world.level.storage.loot.providers.number.ints.ConditionalValue) provider;
                Optional<Boolean> branch = cookingBranch(conditional.condition(), station);
                if (branch.isEmpty()) return OptionalInt.empty();
                ContextIntProvider selected = (branch.get() ? conditional.onTrue() : conditional.onFalse()).value();
                return resolveInt(selected, registry, station, depth + 1, remaining, path);
            }
            return OptionalInt.empty();
        } catch (RuntimeException ignored) {
            return OptionalInt.empty();
        } finally {
            path.remove(provider);
        }
    }

    private static Optional<Float> resolveFloat(ResolvableFloat provider, HolderLookup.RegistryLookup<ContextFloatProvider> registry, net.minecraft.world.level.block.Block station) {
        if (provider instanceof ResolvableFloat.Constant constant) return Optional.of(constant.value());
        if (provider instanceof ResolvableFloat.Reference reference) {
            ContextFloatProvider resolved = registry.get(reference.key()).map(Holder::value).orElse(null);
            if (resolved == null) return Optional.empty();
            return resolveFloat(resolved, registry, station, 0, new int[]{MAX_PROVIDER_NODES},
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        return Optional.empty();
    }

    private static Optional<Float> resolveFloat(ContextFloatProvider provider, HolderLookup.RegistryLookup<ContextFloatProvider> registry,
                                                net.minecraft.world.level.block.Block station, int depth, int[] remaining, Set<ContextFloatProvider> path) {
        if (depth > MAX_PROVIDER_DEPTH || remaining[0]-- <= 0 || !path.add(provider)) return Optional.empty();
        try {
            if (provider.getClass() == net.minecraft.world.level.storage.loot.providers.number.floats.ConstantValue.class) {
                var constant = (net.minecraft.world.level.storage.loot.providers.number.floats.ConstantValue) provider;
                return Optional.of(constant.value());
            }
            if (provider.getClass() == net.minecraft.world.level.storage.loot.providers.number.floats.ConditionalValue.class) {
                var conditional = (net.minecraft.world.level.storage.loot.providers.number.floats.ConditionalValue) provider;
                Optional<Boolean> branch = cookingBranch(conditional.condition(), station);
                if (branch.isEmpty()) return Optional.empty();
                ContextFloatProvider selected = (branch.get() ? conditional.onTrue() : conditional.onFalse()).value();
                return resolveFloat(selected, registry, station, depth + 1, remaining, path);
            }
            return Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        } finally {
            path.remove(provider);
        }
    }

    private static Optional<Boolean> cookingBranch(Holder<LootItemCondition> holder, net.minecraft.world.level.block.Block station) {
        try {
            LootItemCondition condition = holder.value();
            if (condition.getClass() != MatchBlock.class
                    || !condition.getReferencedContextParams().equals(Set.of(LootContextParams.BLOCK_STATE)))
                return Optional.empty();
            BlockPredicate predicate = ((MatchBlock) condition).predicate();
            if (predicate.blocks().isEmpty() || !predicate.blocks().get().isBound()
                    || predicate.blocks().get().size() == 0 || predicate.properties().isPresent()
                    || predicate.nbt().isPresent() || !predicate.components().isEmpty()) return Optional.empty();
            return Optional.of(predicate.matchesState(station.defaultBlockState()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
