package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.SelectedToolRequirement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import java.util.HashMap;
import java.util.Map;

/** All actions use vanilla client input and the server's ordinary survival interaction protocol. */
final class PlayerActions {
    private final Minecraft client;
    private net.minecraft.core.BlockPos miningTarget;

    PlayerActions(Minecraft client) { this.client = client; }

    Map<String, Integer> inventory() {
        Map<String, Integer> result = new HashMap<>();
        if (client.player == null) return result;
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) result.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return result;
    }

    int count(Item item) {
        if (client.player == null) return 0;
        int count = 0;
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    boolean select(Item item) {
        if (client.player == null) return false;
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).is(item)) return selectSlot(i);
        return false;
    }

    boolean selectSlot(int slot) {
        if (client.player == null || client.gameMode == null || slot < 0 || slot >= 36
                || !client.player.containerMenu.getCarried().isEmpty()) return false;
        Inventory inventory = client.player.getInventory();
        if (slot < 9) {
            inventory.setSelectedSlot(slot);
            return true;
        }
        if (client.player.containerMenu != client.player.inventoryMenu) return false;
        ItemStack chosen = inventory.getItem(slot).copy();
        AbstractContainerMenu menu = client.player.inventoryMenu;
        int menuSlot = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot candidate = menu.getSlot(i);
            if (candidate.container == inventory && candidate.getContainerSlot() == slot) { menuSlot = i; break; }
        }
        if (menuSlot < 0) return false;
        client.gameMode.handleContainerInput(menu.containerId, menuSlot, inventory.getSelectedSlot(), ContainerInput.SWAP, client.player);
        return ItemStack.matches(inventory.getSelectedItem(), chosen);
    }

    boolean bestTool(BlockState state) {
        if (client.player == null) return false;
        Inventory inventory = client.player.getInventory();
        int bestSlot = -1, durability = -1;
        float speed = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!hasSafeDurability(stack, 1)) continue;
            if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            float candidate = stack.isEmpty() ? 1 : stack.getDestroySpeed(state);
            int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (candidate > speed || candidate == speed && remaining > durability) { speed = candidate; bestSlot = i; durability = remaining; }
        }
        if (state.requiresCorrectToolForDrops() && bestSlot < 0) return false;
        int heldSlot = inventory.getSelectedSlot();
        if (bestSlot >= 0 && bestSlot != heldSlot) {
            ItemStack held = inventory.getItem(heldSlot), chosen = inventory.getItem(bestSlot);
            if (!held.isEmpty() && held.is(chosen.getItem()) && hasSafeDurability(held, 1)
                    && !held.isEnchanted() && !chosen.isEnchanted()
                    && (!state.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(state))
                    && held.getDestroySpeed(state) == speed) {
                // Keep equivalent tools equipped until their safe reserve is reached.
                // Different components remain distinct even when their raw speed ties.
                ItemStack heldProperties = held.copyWithCount(1), chosenProperties = chosen.copyWithCount(1);
                if (heldProperties.isDamageableItem()) heldProperties.setDamageValue(0);
                if (chosenProperties.isDamageableItem()) chosenProperties.setDamageValue(0);
                if (ItemStack.isSameItemSameComponents(heldProperties, chosenProperties)) bestSlot = heldSlot;
            }
        }
        return bestSlot >= 0 && selectSlot(bestSlot);
    }

    boolean hasTool(SelectedToolRequirement required) {
        if (client.player == null) return false;
        Item item = GameCatalog.item(required.item());
        Inventory inventory = client.player.getInventory();
        for (int index = 0; index < 36; index++) {
            ItemStack stack = inventory.getItem(index);
            if (!stack.isEmpty() && stack.is(item) && hasSafeDurability(stack, required.minimumDurability())) return true;
        }
        return false;
    }

    private static boolean hasSafeDurability(ItemStack stack, int minimumDurability) {
        int wear = GameApi.blockBreakWear(stack);
        if (wear < 0) return false;
        if (!stack.isDamageableItem()) return true;
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        return remaining >= minimumDurability && remaining > wear;
    }

    void look(Vec3 point) {
        if (client.player == null) return;
        Vec3 delta = point.subtract(client.player.getEyePosition());
        client.player.setYRot((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
        client.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
    }

    BlockHitResult hit(net.minecraft.core.BlockPos position) {
        if (client.level == null || client.player == null || client.gameMode == null) return null;
        Vec3 eye = client.player.getEyePosition();
        double reach = client.player.blockInteractionRange();
        for (Direction face : Direction.values()) {
            Vec3 aim = Vec3.atCenterOf(position).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.499));
            if (eye.distanceToSqr(aim) > reach * reach) continue;
            BlockHitResult hit = client.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position)) return hit;
        }
        return null;
    }

    private BlockHitResult hitFace(net.minecraft.core.BlockPos position, Direction face) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 aim = Vec3.atCenterOf(position).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.499));
        double reach = client.player.blockInteractionRange();
        if (eye.distanceToSqr(aim) > reach * reach) return null;
        BlockHitResult hit = client.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position) && hit.getDirection() == face ? hit : null;
    }

    boolean mine(net.minecraft.core.BlockPos position) { return mine(position, null); }

    boolean mine(net.minecraft.core.BlockPos position, SelectedToolRequirement requiredTool) {
        if (client.level == null || client.player == null || client.gameMode == null) return false;
        if (supportsPlayer(position)) return false;
        BlockState state = client.level.getBlockState(position);
        if (state.isAir() || state.getDestroySpeed(client.level, position) < 0) return false;
        if (requiredTool == null) {
            if (!bestTool(state)) return false;
        } else {
            Item required = GameCatalog.item(requiredTool.item());
            int slot = -1;
            Inventory inventory = client.player.getInventory();
            for (int index = 0; index < 36; index++) {
                ItemStack candidate = inventory.getItem(index);
                if (candidate.is(required) && hasSafeDurability(candidate, requiredTool.minimumDurability())
                        && (!state.requiresCorrectToolForDrops() || candidate.isCorrectToolForDrops(state))) {
                    slot = index;
                    break;
                }
            }
            if (slot < 0 || !selectSlot(slot)) return false;
        }
        ItemStack held = client.player.getInventory().getSelectedItem();
        if (!hasSafeDurability(held, requiredTool == null ? 1 : requiredTool.minimumDurability())
                || state.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(state)) return false;
        BlockHitResult hit = hit(position);
        if (hit == null) return false;
        look(hit.getLocation());
        if (!position.equals(miningTarget)) {
            if (!client.gameMode.startDestroyBlock(position, hit.getDirection())) return false;
            miningTarget = position.immutable();
        } else {
            client.gameMode.continueDestroyBlock(position, hit.getDirection());
        }
        GameApi.swing(client.player, InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean supportsPlayer(net.minecraft.core.BlockPos position) {
        var box = client.player.getBoundingBox();
        double feet = client.player.getY();
        return position.getY() < feet && position.getY() + 1 >= feet - .05
                && position.getX() < box.maxX && position.getX() + 1 > box.minX
                && position.getZ() < box.maxZ && position.getZ() + 1 > box.minZ;
    }

    boolean use(net.minecraft.core.BlockPos position) {
        if (client.player == null || client.gameMode == null) return false;
        BlockHitResult hit = hit(position);
        if (hit == null) return false;
        look(hit.getLocation());
        InteractionResult result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        return result.consumesAction();
    }

    /** Checks a station placement without changing the selected slot or sending an interaction. */
    boolean canPlaceAt(net.minecraft.core.BlockPos destination) {
        if (client.level == null || client.player == null
                || !client.level.getBlockState(destination).canBeReplaced()
                || client.player.getBoundingBox().intersects(new AABB(destination))) return false;
        for (Direction side : Direction.values()) {
            if (hitFace(destination.relative(side), side.getOpposite()) != null) return true;
        }
        return false;
    }

    boolean place(net.minecraft.core.BlockPos destination, Block block) {
        if (client.level == null || client.player == null || client.gameMode == null) return false;
        if (!client.level.getBlockState(destination).canBeReplaced() || !select(block.asItem())) return false;
        if (client.player.getBoundingBox().intersects(new AABB(destination))) return false;
        for (Direction side : Direction.values()) {
            net.minecraft.core.BlockPos support = destination.relative(side);
            BlockHitResult hit = hitFace(support, side.getOpposite());
            if (hit == null) continue;
            look(hit.getLocation());
            return client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit).consumesAction();
        }
        return false;
    }

    void cancel() {
        if (miningTarget != null && client.gameMode != null) client.gameMode.stopDestroyBlock();
        miningTarget = null;
    }
}
