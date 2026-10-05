package dev.lodekeeper.fabric;

import dev.lodekeeper.core.SelectedToolRequirement;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.HashMap;
import java.util.Map;

/** All actions run on the client thread and use ordinary survival interactions. */
final class PlayerActions {
    private final MinecraftClient client;
    private boolean ownsBreaking;
    PlayerActions(MinecraftClient client) { this.client = client; }
    Map<String, Integer> inventory() {
        Map<String, Integer> result = new HashMap<>();
        if (client.player == null) return result;
        // Storage slots only: equipment and crafting slots are not consumable ingredients.
        for (ItemStack stack : client.player.getInventory().main) {
            if (!stack.isEmpty()) result.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return result;
    }
    int count(Item item) {
        if (client.player == null) return 0;
        int count = 0;
        for (ItemStack stack : client.player.getInventory().main) if (stack.isOf(item)) count += stack.getCount();
        return count;
    }
    boolean select(Item item) {
        if (client.player == null) return false;
        for (int i = 0; i < 36; i++) if (client.player.getInventory().getStack(i).isOf(item)) return selectSlot(i);
        return false;
    }
    boolean selectSlot(int slot) {
        if (client.player == null || client.interactionManager == null || slot < 0 || slot >= 36
                || !client.player.currentScreenHandler.getCursorStack().isEmpty()) return false;
        var inventory = client.player.getInventory();
        if (slot < 9) { inventory.selectedSlot = slot; return true; }
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) return false;
        ItemStack chosen = inventory.getStack(slot).copy();
        client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, slot, inventory.selectedSlot, SlotActionType.SWAP, client.player);
        return ItemStack.areEqual(inventory.getMainHandStack(), chosen);
    }
    boolean bestTool(BlockState state) {
        if (client.player == null) return false;
        int bestSlot = -1; float speed = 0; int durability = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            int remaining = stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
            if (!hasSafeDurability(stack, 1)) continue;
            if (state.isToolRequired() && !stack.isSuitableFor(state)) continue;
            float candidate = stack.isEmpty() ? 1 : stack.getMiningSpeedMultiplier(state);
            if (candidate > speed || candidate == speed && remaining > durability) { speed = candidate; bestSlot = i; durability = remaining; }
        }
        if (state.isToolRequired() && bestSlot < 0) return false;
        return bestSlot >= 0 && selectSlot(bestSlot);
    }
    void look(Vec3d point) {
        if (client.player == null) return;
        Vec3d delta = point.subtract(client.player.getEyePos());
        client.player.setYaw((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
        client.player.setPitch((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
    }
    BlockHitResult hit(BlockPos position) {
        if (client.world == null || client.player == null || client.interactionManager == null) return null;
        Vec3d eye = client.player.getEyePos();
        double reach = GameApi.blockReach(client);
        // Raycast several faces, never issue interactions through an occluding block.
        for (Direction face : Direction.values()) {
            Vec3d aim = Vec3d.ofCenter(position).add(Vec3d.of(face.getVector()).multiply(0.499));
            if (eye.squaredDistanceTo(aim) > reach * reach) continue;
            BlockHitResult hit = client.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position)) return hit;
        }
        return null;
    }
    private BlockHitResult hitFace(BlockPos position, Direction face) {
        Vec3d eye = client.player.getEyePos();
        Vec3d aim = Vec3d.ofCenter(position).add(Vec3d.of(face.getVector()).multiply(.499));
        double reach = GameApi.blockReach(client);
        if (eye.squaredDistanceTo(aim) > reach * reach) return null;
        BlockHitResult hit = client.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position) && hit.getSide() == face ? hit : null;
    }
    static boolean isPlayerSupport(MinecraftClient client, BlockPos position) {
        if (client.player == null) return false;
        var body = client.player.getBoundingBox();
        double feet = client.player.getY();
        return position.getY() < feet && position.getY() + 1 >= feet - .05
            && position.getX() < body.maxX && position.getX() + 1 > body.minX
            && position.getZ() < body.maxZ && position.getZ() + 1 > body.minZ;
    }
    boolean hasTool(SelectedToolRequirement tool) {
        if (client.player == null) return false;
        for (ItemStack stack : client.player.getInventory().main)
            if (!stack.isEmpty() && stack.isOf(GameCatalog.item(tool.item())) && hasSafeDurability(stack, tool.minimumDurability())) return true;
        return false;
    }

    private static boolean hasSafeDurability(ItemStack stack, int minimumDurability) {
        int wear = GameApi.blockBreakWear(stack);
        if (wear < 0) return false;
        if (!stack.isDamageable()) return true;
        int remaining = stack.getMaxDamage() - stack.getDamage();
        return remaining >= minimumDurability && remaining > wear;
    }

    boolean mine(BlockPos position) { return mine(position, null); }
    boolean mine(BlockPos position, SelectedToolRequirement tool) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;
        if (isPlayerSupport(client, position)) return false;
        BlockState state = client.world.getBlockState(position);
        if (state.isAir() || state.getHardness(client.world, position) < 0) return false;
        if (tool == null) { if (!bestTool(state)) return false; }
        else {
            int slot = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isOf(GameCatalog.item(tool.item())) && hasSafeDurability(stack, tool.minimumDurability()) && (!state.isToolRequired() || stack.isSuitableFor(state))) { slot = i; break; }
            }
            if (slot < 0 || !selectSlot(slot)) return false;
        }
        ItemStack held = client.player.getMainHandStack();
        if (!hasSafeDurability(held, tool == null ? 1 : tool.minimumDurability())
                || state.isToolRequired() && !held.isSuitableFor(state)) return false;
        BlockHitResult hit = hit(position);
        if (hit == null) return false;
        look(hit.getPos());
        ownsBreaking = true;
        client.interactionManager.updateBlockBreakingProgress(position, hit.getSide());
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }
    boolean use(BlockPos position) {
        if (client.player == null || client.interactionManager == null) return false;
        BlockHitResult hit = hit(position);
        if (hit == null) return false;
        look(hit.getPos());
        return client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit).isAccepted();
    }
    boolean canPlaceAt(BlockPos destination) {
        if (client.world == null || client.player == null || !client.world.getBlockState(destination).isReplaceable()
                || client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(destination))) return false;
        for (Direction side : Direction.values()) {
            BlockHitResult hit = hitFace(destination.offset(side), side.getOpposite());
            if (hit != null) return true;
        }
        return false;
    }
    boolean place(BlockPos destination, Block block) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;
        if (!client.world.getBlockState(destination).isReplaceable() || !select(block.asItem())) return false;
        if (client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(destination))) return false;
        for (Direction side : Direction.values()) {
            BlockPos support = destination.offset(side);
            BlockHitResult hit = hitFace(support, side.getOpposite());
            if (hit == null || hit.getSide() != side.getOpposite()) continue;
            look(hit.getPos());
            // Avoid opening support containers while placing; executor supplies sneak when needed.
            return client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit).isAccepted();
        }
        return false;
    }
    void cancel() {
        if (ownsBreaking && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        ownsBreaking = false;
    }
}
