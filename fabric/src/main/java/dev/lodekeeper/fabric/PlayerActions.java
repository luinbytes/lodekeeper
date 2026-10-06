package dev.lodekeeper.fabric;

import dev.lodekeeper.core.SelectedToolRequirement;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.entity.EquipmentSlot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All actions run on the client thread and use ordinary survival interactions. */
final class PlayerActions {
    private final MinecraftClient client;
    private boolean ownsBreaking;
    enum MineFailure {
        NONE, CONTEXT_UNAVAILABLE, PLAYER_SUPPORT, UNBREAKABLE_BLOCK,
        NO_REACHABLE_OUTLINE_HIT, SAFE_TOOL_UNAVAILABLE, REQUIRED_TOOL_UNAVAILABLE,
        TOOL_SELECTION_FAILED, UNSAFE_HELD_TOOL, NATIVE_BREAK_REFUSED
    }
    private MineFailure mineFailure = MineFailure.NONE;
    MineFailure mineFailure() { return mineFailure; }
    private boolean refuseMining(MineFailure reason) { mineFailure = reason; return false; }

    PlayerActions(MinecraftClient client) { this.client = client; }
    Map<String, Integer> inventory() {
        Map<String, Integer> result = new HashMap<>();
        if (client.player == null) return result;
        // Storage slots only: equipment and crafting slots are not consumable ingredients.
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) {
            if (!stack.isEmpty()) result.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return result;
    }
    int count(Item item) {
        if (client.player == null) return 0;
        int count = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) if (stack.isOf(item)) count += stack.getCount();
        return count;
    }
    /** Goal stock includes worn armor and offhand, while ingredients remain storage-only. */
    Map<String, Integer> heldInventory() {
        Map<String, Integer> result = inventory();
        if (client.player == null) return result;
        for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            ItemStack stack = client.player.getEquippedStack(slot);
            if (!stack.isEmpty()) result.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Math::addExact);
        }
        return result;
    }

    int heldCount(Item item) {
        int result = count(item);
        if (client.player == null) return result;
        for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            ItemStack stack = client.player.getEquippedStack(slot);
            if (!stack.isEmpty() && stack.isOf(item)) result = Math.addExact(result, stack.getCount());
        }
        return result;
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
        if (slot < 9) { ClientAccess.selectedSlot(inventory, slot); return true; }
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) return false;
        ItemStack chosen = inventory.getStack(slot).copy();
        client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, slot, ClientAccess.selectedSlot(inventory), SlotActionType.SWAP, client.player);
        return ItemStack.areEqual(client.player.getMainHandStack(), chosen);
    }
    boolean bestTool(BlockState state) {
        return bestTool(state, null);
    }

    private boolean bestTool(BlockState state, Item expectedOutput) {
        if (client.player == null) return refuseMining(MineFailure.CONTEXT_UNAVAILABLE);
        int bestSlot = -1; float speed = 0; int durability = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            int remaining = stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
            if (!hasSafeDurability(stack, 1) || !isMiningOutputCompatible(stack, state, expectedOutput)) continue;
            if (state.isToolRequired() && !stack.isSuitableFor(state)) continue;
            float candidate = stack.isEmpty() ? 1 : stack.getMiningSpeedMultiplier(state);
            if (candidate > speed || candidate == speed && remaining > durability) { speed = candidate; bestSlot = i; durability = remaining; }
        }
        if (state.isToolRequired() && bestSlot < 0) return refuseMining(MineFailure.SAFE_TOOL_UNAVAILABLE);
        int heldSlot = ClientAccess.selectedSlot(client.player.getInventory());
        if (bestSlot >= 0 && bestSlot != heldSlot) {
            ItemStack held = client.player.getInventory().getStack(heldSlot);
            ItemStack chosen = client.player.getInventory().getStack(bestSlot);
            if (!held.isEmpty() && held.isOf(chosen.getItem()) && hasSafeDurability(held, 1)
                    && isMiningOutputCompatible(held, state, expectedOutput)
                    && isMiningOutputCompatible(chosen, state, expectedOutput)
                    && !held.hasEnchantments() && !chosen.hasEnchantments()
                    && (!state.isToolRequired() || held.isSuitableFor(state))
                    && held.getMiningSpeedMultiplier(state) == speed) {
                // Keep equivalent tools equipped until their safe reserve is reached.
                // Different components remain distinct even when their raw speed ties.
                ItemStack heldProperties = held.copyWithCount(1), chosenProperties = chosen.copyWithCount(1);
                if (heldProperties.isDamageable()) heldProperties.setDamage(0);
                if (chosenProperties.isDamageable()) chosenProperties.setDamage(0);
                if (GameApi.canCombine(heldProperties, chosenProperties)) bestSlot = heldSlot;
            }
        }
        if (bestSlot < 0) return refuseMining(MineFailure.SAFE_TOOL_UNAVAILABLE);
        if (!selectSlot(bestSlot)) return refuseMining(MineFailure.TOOL_SELECTION_FAILED);
        return true;
    }
    void look(Vec3d point) {
        if (client.player == null) return;
        Vec3d delta = point.subtract(client.player.getEyePos());
        client.player.setYaw((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
        client.player.setPitch((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
    }
    BlockHitResult hit(BlockPos position) {
        if (client.player == null) return null;
        return hitFrom(client, position, client.player.getEyePos());
    }

    static BlockHitResult hitFrom(MinecraftClient client, BlockPos position, Vec3d eye) {
        if (client.world == null || client.player == null || client.interactionManager == null
                || !Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)
                || client.world.isOutOfHeightLimit(position.getY())) return null;
        double reach = GameApi.blockReach(client);
        if (!Double.isFinite(reach) || reach <= 0.0) return null;
        // Check the closest point before scanning chunks, including faces closer than the center.
        double dx = Math.max(position.getX() - eye.x, Math.max(0.0, eye.x - (position.getX() + 1.0)));
        double dy = Math.max(position.getY() - eye.y, Math.max(0.0, eye.y - (position.getY() + 1.0)));
        double dz = Math.max(position.getZ() - eye.z, Math.max(0.0, eye.z - (position.getZ() + 1.0)));
        if (dx * dx + dy * dy + dz * dz > reach * reach) return null;
        int minChunkX = Math.min((int) Math.floor(eye.x) >> 4, position.getX() >> 4);
        int maxChunkX = Math.max((int) Math.floor(eye.x) >> 4, position.getX() >> 4);
        int minChunkZ = Math.min((int) Math.floor(eye.z) >> 4, position.getZ() >> 4);
        int maxChunkZ = Math.max((int) Math.floor(eye.z) >> 4, position.getZ() >> 4);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                WorldRevision.watch(chunkX, chunkZ);
                if (client.world.getChunkManager().getChunk(chunkX, chunkZ, net.minecraft.world.chunk.ChunkStatus.FULL, false) == null) return null;
            }
        }
        // Every target must be visible in the current world. Earlier planned breaks are not air.
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
        Vec3d center = Vec3d.ofCenter(position).add(Vec3d.of(face.getVector()).multiply(.499));
        Vec3d closest = new Vec3d(
                face.getOffsetX() == 0 ? Math.max(position.getX() + .001, Math.min(position.getX() + .999, eye.x)) : center.x,
                face.getOffsetY() == 0 ? Math.max(position.getY() + .001, Math.min(position.getY() + .999, eye.y)) : center.y,
                face.getOffsetZ() == 0 ? Math.max(position.getZ() + .001, Math.min(position.getZ() + .999, eye.z)) : center.z);
        double reach = GameApi.blockReach(client);
        for (int attempt = 0; attempt < 2; attempt++) {
            Vec3d aim = attempt == 0 ? closest : center;
            if (attempt == 1 && center.equals(closest)) break;
            if (eye.squaredDistanceTo(aim) > reach * reach) continue;
            BlockHitResult hit = client.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position) && hit.getSide() == face) return hit;
        }
        return null;
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
        for (ItemStack stack : ClientAccess.main(client.player.getInventory()))
            if (!stack.isEmpty() && stack.isOf(GameCatalog.item(tool.item())) && hasSafeDurability(stack, tool.minimumDurability())) return true;
        return false;
    }

    void prepareScaffoldHotbar(java.util.List<Item> allowed) {
        if (allowed.isEmpty() || client.player == null || client.interactionManager == null) return;
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 9; slot++) if (allowed.contains(inventory.getStack(slot).getItem())) return;
        int source = -1;
        for (int slot = 9; slot < 36; slot++) if (allowed.contains(inventory.getStack(slot).getItem())) { source = slot; break; }
        if (source < 0) return;
        if (client.player.currentScreenHandler != client.player.playerScreenHandler || !client.player.currentScreenHandler.getCursorStack().isEmpty()) return;
        int selected = ClientAccess.selectedSlot(inventory);
        int destination = -1;
        for (int slot = 0; slot < 9; slot++) if (slot != selected && inventory.getStack(slot).isEmpty()) { destination = slot; break; }
        if (destination < 0) destination = (selected + 1) % 9;
        client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, source, destination, SlotActionType.SWAP, client.player);
    }

    boolean prepareMiningTool(SelectedToolRequirement tool, BlockState state) {
        return prepareMiningTool(tool, state, null);
    }

    boolean prepareMiningTool(SelectedToolRequirement tool, BlockState state, Item expectedOutput) {
        if (tool == null) return bestTool(state, expectedOutput);
        if (client.player == null) return refuseMining(MineFailure.CONTEXT_UNAVAILABLE);
        int selected = ClientAccess.selectedSlot(client.player.getInventory());
        for (int pass = 0; pass < 2; pass++) for (int slot = 0; slot < 36; slot++) {
            if (pass == 0 ? slot != selected : slot == selected) continue;
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isOf(GameCatalog.item(tool.item())) && hasSafeDurability(stack, tool.minimumDurability())
                    && isMiningOutputCompatible(stack, state, expectedOutput)
                    && (!state.isToolRequired() || stack.isSuitableFor(state))) {
                return selectSlot(slot) || refuseMining(MineFailure.TOOL_SELECTION_FAILED);
            }
        }
        return refuseMining(MineFailure.REQUIRED_TOOL_UNAVAILABLE);
    }

    static boolean isMiningOutputCompatible(ItemStack stack, BlockState state, Item expectedOutput) {
        return expectedOutput == null || state.getBlock().asItem() == expectedOutput || !GameApi.hasSilkTouch(stack);
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
        mineFailure = MineFailure.NONE;
        if (client.world == null || client.player == null || client.interactionManager == null) return refuseMining(MineFailure.CONTEXT_UNAVAILABLE);
        if (isPlayerSupport(client, position)) return refuseMining(MineFailure.PLAYER_SUPPORT);
        BlockState state = client.world.getBlockState(position);
        if (state.isAir() || state.getHardness(client.world, position) < 0) return refuseMining(MineFailure.UNBREAKABLE_BLOCK);
        BlockHitResult hit = hit(position);
        if (hit == null) return refuseMining(MineFailure.NO_REACHABLE_OUTLINE_HIT);
        if (tool == null) { if (!bestTool(state)) return false; }
        else {
            int slot = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isOf(GameCatalog.item(tool.item())) && hasSafeDurability(stack, tool.minimumDurability()) && (!state.isToolRequired() || stack.isSuitableFor(state))) { slot = i; break; }
            }
            if (slot < 0) return refuseMining(MineFailure.REQUIRED_TOOL_UNAVAILABLE);
            if (!selectSlot(slot)) return refuseMining(MineFailure.TOOL_SELECTION_FAILED);
        }
        ItemStack held = client.player.getMainHandStack();
        if (!hasSafeDurability(held, tool == null ? 1 : tool.minimumDurability())
                || state.isToolRequired() && !held.isSuitableFor(state)) return refuseMining(MineFailure.UNSAFE_HELD_TOOL);
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
    boolean safePlacementSupport(BlockPos position) {
        var state = client.world.getBlockState(position);
        Block block = state.getBlock();
        return !state.hasBlockEntity() && Block.isShapeFullCube(state.getCollisionShape(client.world, position))
                && !state.isIn(BlockTags.LEAVES) && !state.isIn(BlockTags.LOGS)
                && block != Blocks.MAGMA_BLOCK && block != Blocks.CACTUS
                && block != Blocks.CRAFTING_TABLE && block != Blocks.CARTOGRAPHY_TABLE
                && block != Blocks.FLETCHING_TABLE && block != Blocks.SMITHING_TABLE
                && block != Blocks.STONECUTTER && block != Blocks.LOOM
                && block != Blocks.ENCHANTING_TABLE && block != Blocks.NOTE_BLOCK
                && block != Blocks.RESPAWN_ANCHOR;
    }

    private BlockHitResult placementHit(BlockPos destination) {
        if (client.world == null || client.player == null
                || !client.world.getBlockState(destination).isReplaceable()
                || client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(destination))) return null;
        for (Direction side : Direction.values()) {
            BlockPos support = destination.offset(side);
            if (!safePlacementSupport(support)) continue;
            BlockHitResult hit = hitFace(support, side.getOpposite());
            if (hit != null) return hit;
        }
        return null;
    }

    boolean canPlaceAt(BlockPos destination) { return placementHit(destination) != null; }

    boolean place(BlockPos destination, Block block) {
        if (client.interactionManager == null) return false;
        BlockHitResult hit = placementHit(destination);
        if (hit == null || !select(block.asItem())) return false;
        look(hit.getPos());
        return client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit).isAccepted();
    }
    void cancel() {
        if (ownsBreaking && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        ownsBreaking = false;
    }
}
