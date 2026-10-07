package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.SelectedToolRequirement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.EquipmentSlot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All actions use vanilla client input and the server's ordinary survival interaction protocol. */
final class PlayerActions {
    private final Minecraft client;
    private final WorldProtection protection;
    private final PlacementProvenance placementProvenance;
    private BackfillController backfill;
    void attachBackfill(BackfillController controller) { backfill = controller; }
    void recordOwnedNavigationBreak(net.minecraft.core.BlockPos position) {
        if (backfill != null) backfill.beforeOwnedBreak(position);
    }
    enum BackfillAttempt { NOT_SENT, SENT_OR_UNCERTAIN }
    enum PlacementAttempt { SENT, WAITING_FOR_PROVENANCE, INVENTORY_TIMEOUT, REJECTED, QUARANTINED }
    private net.minecraft.core.BlockPos miningTarget;
    enum MineFailure {
        NONE, CONTEXT_UNAVAILABLE, PROTECTED_BLOCK, PLAYER_SUPPORT, UNBREAKABLE_BLOCK,
        NO_REACHABLE_OUTLINE_HIT, SAFE_TOOL_UNAVAILABLE, REQUIRED_TOOL_UNAVAILABLE,
        TOOL_SELECTION_FAILED, UNSAFE_HELD_TOOL, NATIVE_BREAK_REFUSED
    }
    private MineFailure mineFailure = MineFailure.NONE;
    MineFailure mineFailure() { return mineFailure; }
    private boolean refuseMining(MineFailure reason) { mineFailure = reason; return false; }

    PlayerActions(Minecraft client) { this(client, null, null); }
    PlayerActions(Minecraft client, WorldProtection protection) { this(client, protection, null); }
    PlayerActions(Minecraft client, WorldProtection protection, PlacementProvenance placementProvenance) {
        this.client = client;
        this.protection = protection;
        this.placementProvenance = placementProvenance;
    }

    private boolean permitsBreak(net.minecraft.core.BlockPos position) {
        if (protection != null && !protection.mayBreak(position)) return false;
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        return owner != null && owner.isCurrent(session) && session.world() == client.level
                && dev.lodekeeper.navigation.kernel.OwnedMutationGuard.executeBreak(owner, position);
    }

    private boolean permitsPlacement(BlockHitResult hit) {
        if (protection != null) protection.sync();
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        return owner != null && owner.isCurrent(session) && session.world() == client.level
                && dev.lodekeeper.navigation.kernel.OwnedMutationGuard.executePlace(owner, hit,
                        InteractionHand.MAIN_HAND);
    }

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

    /** Goal stock includes worn armor and offhand, while ingredients remain storage-only. */
    Map<String, Integer> heldInventory() {
        Map<String, Integer> result = inventory();
        if (client.player == null) return result;
        for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            ItemStack stack = client.player.getItemBySlot(slot);
            if (!stack.isEmpty()) result.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Math::addExact);
        }
        return result;
    }

    int heldCount(Item item) {
        int result = count(item);
        if (client.player == null) return result;
        for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            ItemStack stack = client.player.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.is(item)) result = Math.addExact(result, stack.getCount());
        }
        return result;
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
        OwnedClickReceipts.inventoryClick(client, menu.containerId, menuSlot, inventory.getSelectedSlot(), ContainerInput.SWAP, client.player);
        return ItemStack.matches(inventory.getSelectedItem(), chosen);
    }

    boolean bestTool(BlockState state) {
        return bestTool(state, null);
    }

    private boolean bestTool(BlockState state, Item expectedOutput) {
        if (client.player == null) return refuseMining(MineFailure.CONTEXT_UNAVAILABLE);
        Inventory inventory = client.player.getInventory();
        int bestSlot = -1, durability = -1;
        float speed = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!hasSafeDurability(stack, 1) || !isMiningOutputCompatible(stack, state, expectedOutput)) continue;
            if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            float candidate = stack.isEmpty() ? 1 : stack.getDestroySpeed(state);
            int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (candidate > speed || candidate == speed && remaining > durability) { speed = candidate; bestSlot = i; durability = remaining; }
        }
        if (state.requiresCorrectToolForDrops() && bestSlot < 0) return refuseMining(MineFailure.SAFE_TOOL_UNAVAILABLE);
        int heldSlot = inventory.getSelectedSlot();
        if (bestSlot >= 0 && bestSlot != heldSlot) {
            ItemStack held = inventory.getItem(heldSlot), chosen = inventory.getItem(bestSlot);
            if (!held.isEmpty() && held.is(chosen.getItem()) && hasSafeDurability(held, 1)
                    && isMiningOutputCompatible(held, state, expectedOutput)
                    && isMiningOutputCompatible(chosen, state, expectedOutput)
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
        if (bestSlot < 0) return refuseMining(MineFailure.SAFE_TOOL_UNAVAILABLE);
        if (!selectSlot(bestSlot)) return refuseMining(MineFailure.TOOL_SELECTION_FAILED);
        return true;
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

    void prepareScaffoldHotbar(java.util.List<Item> allowed) {
        if (allowed.isEmpty() || client.player == null || client.gameMode == null) return;
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 9; slot++) if (allowed.contains(inventory.getItem(slot).getItem())) return;
        int source = -1;
        for (int slot = 9; slot < 36; slot++) if (allowed.contains(inventory.getItem(slot).getItem())) { source = slot; break; }
        if (source < 0) return;
        if (client.player.containerMenu != client.player.inventoryMenu || !client.player.containerMenu.getCarried().isEmpty()) return;
        int selected = inventory.getSelectedSlot();
        int destination = -1;
        for (int slot = 0; slot < 9; slot++) if (slot != selected && inventory.getItem(slot).isEmpty()) { destination = slot; break; }
        if (destination < 0) destination = (selected + 1) % 9;
        var menu = client.player.inventoryMenu;
        for (int index = 0; index < menu.slots.size(); index++) {
            var slot = menu.getSlot(index);
            if (slot.container == inventory && slot.getContainerSlot() == source) {
                OwnedClickReceipts.inventoryClick(client, menu.containerId, index, destination, ContainerInput.SWAP, client.player);
                return;
            }
        }
    }

    boolean prepareMiningTool(SelectedToolRequirement tool, BlockState state) {
        return prepareMiningTool(tool, state, null);
    }

    boolean prepareMiningTool(SelectedToolRequirement tool, BlockState state, Item expectedOutput) {
        if (tool == null) return bestTool(state, expectedOutput);
        if (client.player == null) return refuseMining(MineFailure.CONTEXT_UNAVAILABLE);
        int selected = client.player.getInventory().getSelectedSlot();
        for (int pass = 0; pass < 2; pass++) for (int slot = 0; slot < 36; slot++) {
            if (pass == 0 ? slot != selected : slot == selected) continue;
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.is(GameCatalog.item(tool.item())) && hasSafeDurability(stack, tool.minimumDurability())
                    && isMiningOutputCompatible(stack, state, expectedOutput)
                    && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state))) {
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
        if (client.player == null) return null;
        return hitFrom(client, position, client.player.getEyePosition());
    }

    static BlockHitResult hitFrom(Minecraft client, net.minecraft.core.BlockPos position, Vec3 eye) {
        if (client.level == null || client.player == null || client.gameMode == null
                || !Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)
                || position.getY() < client.level.getMinY() || position.getY() >= client.level.getMaxY()) return null;
        double reach = client.player.blockInteractionRange();
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
                if (client.level.getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) return null;
            }
        }
        // Every target must be visible in the current world. Earlier planned breaks are not air.
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
        Vec3 center = Vec3.atCenterOf(position).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(.499));
        Vec3 closest = new Vec3(
                face.getStepX() == 0 ? Math.max(position.getX() + .001, Math.min(position.getX() + .999, eye.x)) : center.x,
                face.getStepY() == 0 ? Math.max(position.getY() + .001, Math.min(position.getY() + .999, eye.y)) : center.y,
                face.getStepZ() == 0 ? Math.max(position.getZ() + .001, Math.min(position.getZ() + .999, eye.z)) : center.z);
        double reach = client.player.blockInteractionRange();
        for (int attempt = 0; attempt < 2; attempt++) {
            Vec3 aim = attempt == 0 ? closest : center;
            if (attempt == 1 && center.equals(closest)) break;
            if (eye.distanceToSqr(aim) > reach * reach) continue;
            BlockHitResult hit = client.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position) && hit.getDirection() == face) return hit;
        }
        return null;
    }

    boolean mine(net.minecraft.core.BlockPos position) { return mine(position, null); }

    boolean mine(net.minecraft.core.BlockPos position, SelectedToolRequirement requiredTool) {
        mineFailure = MineFailure.NONE;
        if (client.level == null || client.player == null || client.gameMode == null) return refuseMining(MineFailure.CONTEXT_UNAVAILABLE);
        if (!permitsBreak(position)) return refuseMining(MineFailure.PROTECTED_BLOCK);
        if (supportsPlayer(position)) return refuseMining(MineFailure.PLAYER_SUPPORT);
        BlockState state = client.level.getBlockState(position);
        if (state.isAir() || state.getDestroySpeed(client.level, position) < 0) return refuseMining(MineFailure.UNBREAKABLE_BLOCK);
        BlockHitResult hit = hit(position);
        if (hit == null) return refuseMining(MineFailure.NO_REACHABLE_OUTLINE_HIT);
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
            if (slot < 0) return refuseMining(MineFailure.REQUIRED_TOOL_UNAVAILABLE);
            if (!selectSlot(slot)) return refuseMining(MineFailure.TOOL_SELECTION_FAILED);
        }
        ItemStack held = client.player.getInventory().getSelectedItem();
        if (!hasSafeDurability(held, requiredTool == null ? 1 : requiredTool.minimumDurability())
                || state.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(state)) return refuseMining(MineFailure.UNSAFE_HELD_TOOL);
        look(hit.getLocation());
        recordOwnedNavigationBreak(position);
        if (!position.equals(miningTarget)) {
            if (!client.gameMode.startDestroyBlock(position, hit.getDirection())) return refuseMining(MineFailure.NATIVE_BREAK_REFUSED);
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
        if (client.player == null || client.gameMode == null || client.player.isShiftKeyDown()
                || GameApi.screen(client) instanceof dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier) return false;
        if (client.level == null || !client.level.hasChunkAt(position)) return false;
        var state = client.level.getBlockState(position);
        boolean defaultVanillaStation = state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE)
                || state.is(Blocks.SMOKER) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.STONECUTTER);
        if (!defaultVanillaStation && !client.player.getMainHandItem().isEmpty()) {
            int empty = -1;
            for (int slot = 0; slot < 36; slot++) {
                if (client.player.getInventory().getItem(slot).isEmpty()) { empty = slot; break; }
            }
            if (empty < 0) throw new IllegalStateException("Station interaction needs an empty inventory slot");
            if (!selectSlot(empty) || !client.player.getMainHandItem().isEmpty()) return false;
        }
        BlockHitResult hit = hit(position);
        if (hit == null) return false;
        look(hit.getLocation());
        return client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit).consumesAction();
    }
    /** Checks a station placement without changing the selected slot or sending an interaction. */
    boolean safePlacementSupport(net.minecraft.core.BlockPos position) {
        var state = client.level.getBlockState(position);
        Block block = state.getBlock();
        return !state.hasBlockEntity() && Block.isShapeFullBlock(state.getCollisionShape(client.level, position))
                && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)
                && block != Blocks.MAGMA_BLOCK && block != Blocks.CACTUS
                && block != Blocks.CRAFTING_TABLE && block != Blocks.CARTOGRAPHY_TABLE
                && block != Blocks.FLETCHING_TABLE && block != Blocks.SMITHING_TABLE
                && block != Blocks.STONECUTTER && block != Blocks.LOOM
                && block != Blocks.ENCHANTING_TABLE && block != Blocks.NOTE_BLOCK
                && block != Blocks.RESPAWN_ANCHOR;
    }

    private BlockHitResult placementHit(net.minecraft.core.BlockPos destination) {
        if (protection != null && !protection.mayPlace(destination)) return null;
        if (client.level == null || client.player == null
                || !client.level.getBlockState(destination).canBeReplaced()
                || client.player.getBoundingBox().intersects(new AABB(destination))) return null;
        for (Direction side : Direction.values()) {
            net.minecraft.core.BlockPos support = destination.relative(side);
            if (!safePlacementSupport(support)) continue;
            BlockHitResult hit = hitFace(support, side.getOpposite());
            if (hit != null) return hit;
        }
        return null;
    }

    boolean canPlaceAt(net.minecraft.core.BlockPos destination) { return placementHit(destination) != null; }

    boolean place(net.minecraft.core.BlockPos destination, Block block) {
        if (client.gameMode == null) return false;
        BlockHitResult hit = placementHit(destination);
        if (hit == null || !select(block.asItem())) return false;
        if (!permitsPlacement(hit)) return false;
        look(hit.getLocation());
        return client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit).consumesAction();
    }
    BackfillAttempt placeBackfill(net.minecraft.core.BlockPos destination, Block block) {
        if (client.player == null || client.level == null || client.gameMode == null)
            return BackfillAttempt.NOT_SENT;
        BlockHitResult hit = placementHit(destination);
        if (hit == null || !select(block.asItem()) || client.player.getMainHandItem().getItem() != block.asItem())
            return BackfillAttempt.NOT_SENT;
        if (!client.level.getBlockState(destination).isAir()
                || !client.level.getBlockState(destination).getFluidState().isEmpty()
                || !permitsPlacement(hit)) return BackfillAttempt.NOT_SENT;
        look(hit.getLocation());
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        return BackfillAttempt.SENT_OR_UNCERTAIN;
    }
    PlacementAttempt placeStation(net.minecraft.core.BlockPos destination, Block block, long jobToken) {
        return placeStation(destination, block, jobToken, false);
    }
    PlacementAttempt placeStation(net.minecraft.core.BlockPos destination, Block block, long jobToken, boolean ordinaryOnly) {
        if (placementProvenance == null || client.gameMode == null) return PlacementAttempt.REJECTED;
        BlockHitResult hit = placementHit(destination);
        if (hit == null) return PlacementAttempt.REJECTED;
        var readiness = placementProvenance.readinessStatus(jobToken, destination, block);
        if (readiness.orElse(null) == PlacementProvenance.ReservationStatus.QUARANTINED)
            return PlacementAttempt.QUARANTINED;
        if (readiness.orElse(null) == PlacementProvenance.ReservationStatus.EXPIRED)
            return PlacementAttempt.INVENTORY_TIMEOUT;
        if (readiness.isEmpty() && !(ordinaryOnly ? selectOrdinary(block.asItem()) : select(block.asItem()))) return PlacementAttempt.REJECTED;
        if (!permitsPlacement(hit)) return PlacementAttempt.REJECTED;
        look(hit.getLocation());
        var reservation = placementProvenance.reservePlacement(jobToken, destination, block);
        if (reservation.status() == PlacementProvenance.ReservationStatus.NOT_READY)
            return PlacementAttempt.WAITING_FOR_PROVENANCE;
        if (reservation.status() == PlacementProvenance.ReservationStatus.QUARANTINED)
            return PlacementAttempt.QUARANTINED;
        if (reservation.status() != PlacementProvenance.ReservationStatus.RESERVED) return PlacementAttempt.REJECTED;
        var ticket = reservation.ticket().orElseThrow();
        try {
            if (!client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                placementProvenance.interactionRejected(ticket);
                return PlacementAttempt.QUARANTINED;
            }
            return PlacementAttempt.SENT;
        } catch (RuntimeException | Error failure) {
            placementProvenance.interactionRejected(ticket);
            throw failure;
        }
    }
    private boolean selectOrdinary(Item item) {
        if (client.player == null) return false;
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getItem(index);
            if (stack.is(item) && !stack.isEnchanted() && !GameApi.hasCustomName(stack)
                    && ItemStack.isSameItemSameComponents(stack, new ItemStack(item)) && selectSlot(index)) return true;
        }
        return false;
    }

    void cancel() {
        if (miningTarget != null && client.gameMode != null) client.gameMode.stopDestroyBlock();
        miningTarget = null;
    }
}
