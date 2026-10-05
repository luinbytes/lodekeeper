package dev.lodekeeper.fabric;

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
        if (client.player == null || client.interactionManager == null) return false;
        var inventory = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isOf(item)) { inventory.selectedSlot = i; return true; }
        }
        // Never swap while another container owns the player's inventory slots.
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) return false;
        for (int i = 9; i < 36; i++) {
            if (inventory.getStack(i).isOf(item)) {
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, i, inventory.selectedSlot, SlotActionType.SWAP, client.player);
                return inventory.getMainHandStack().isOf(item);
            }
        }
        return false;
    }
    boolean bestTool(BlockState state) {
        if (client.player == null) return false;
        ItemStack best = ItemStack.EMPTY;
        float speed = 0;
        for (ItemStack stack : client.player.getInventory().main) {
            if (stack.isEmpty() || stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= 1) continue;
            if (state.isToolRequired() && !stack.isSuitableFor(state)) continue;
            float candidate = stack.getMiningSpeedMultiplier(state);
            if (candidate > speed) { speed = candidate; best = stack; }
        }
        if (state.isToolRequired() && best.isEmpty()) return false;
        return best.isEmpty() || select(best.getItem());
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
        double reach = client.interactionManager.getReachDistance();
        // Raycast several faces, never issue interactions through an occluding block.
        for (Direction face : Direction.values()) {
            Vec3d aim = Vec3d.ofCenter(position).add(Vec3d.of(face.getVector()).multiply(0.499));
            if (eye.squaredDistanceTo(aim) > reach * reach) continue;
            BlockHitResult hit = client.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position)) return hit;
        }
        return null;
    }
    boolean mine(BlockPos position) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;
        BlockState state = client.world.getBlockState(position);
        if (state.isAir() || state.getHardness(client.world, position) < 0 || !bestTool(state)) return false;
        BlockHitResult hit = hit(position);
        if (hit == null) return false;
        look(hit.getPos());
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
    boolean place(BlockPos destination, Block block) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;
        if (!client.world.getBlockState(destination).isReplaceable() || !select(block.asItem())) return false;
        if (client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(destination))) return false;
        for (Direction side : Direction.values()) {
            BlockPos support = destination.offset(side);
            BlockHitResult hit = hit(support);
            if (hit == null || hit.getSide() != side.getOpposite()) continue;
            look(hit.getPos());
            // Avoid opening support containers while placing; executor supplies sneak when needed.
            return client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit).isAccepted();
        }
        return false;
    }
    void cancel() {
        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        if (client.player != null && client.player.isUsingItem() && client.interactionManager != null) client.interactionManager.stopUsingItem(client.player);
    }
}
