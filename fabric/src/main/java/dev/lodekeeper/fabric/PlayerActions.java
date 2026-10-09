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
    private final WorldProtection protection;
    private final PlacementProvenance placementProvenance;
    private BackfillController backfill;
    void attachBackfill(BackfillController controller) { backfill = controller; }
    void recordOwnedNavigationBreak(BlockPos position) {
        if (backfill != null) backfill.beforeOwnedBreak(position);
    }
    enum BackfillAttempt { NOT_SENT, SENT_OR_UNCERTAIN }
    enum PlacementAttempt { YIELDED, SENT, WAITING_FOR_PROVENANCE, INVENTORY_TIMEOUT, REJECTED, QUARANTINED }
    private boolean ownsBreaking;
    enum MineFailure {
        NONE, CONTEXT_UNAVAILABLE, PROTECTED_BLOCK, PLAYER_SUPPORT, UNBREAKABLE_BLOCK,
        NO_REACHABLE_OUTLINE_HIT, SAFE_TOOL_UNAVAILABLE, REQUIRED_TOOL_UNAVAILABLE,
        TOOL_SELECTION_FAILED, UNSAFE_HELD_TOOL, NATIVE_BREAK_REFUSED
    }
    private MineFailure mineFailure = MineFailure.NONE;
    MineFailure mineFailure() { return mineFailure; }
    private boolean refuseMining(MineFailure reason) { mineFailure = reason; return false; }

    PlayerActions(MinecraftClient client) { this(client, null, null); }
    PlayerActions(MinecraftClient client, WorldProtection protection) { this(client, protection, null); }
    PlayerActions(MinecraftClient client, WorldProtection protection, PlacementProvenance placementProvenance) {
        this.client = client;
        this.protection = protection;
        this.placementProvenance = placementProvenance;
    }

    private boolean permitsBreak(BlockPos position) {
        if (protection != null && !protection.mayBreak(position)) return false;
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        return owner != null && owner.isCurrent(session) && session.world() == client.world
                && dev.lodekeeper.navigation.kernel.OwnedMutationGuard.executeBreak(owner, position);
    }

    private boolean permitsPlacement(BlockHitResult hit) {
        if (protection != null) protection.sync();
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        return owner != null && owner.isCurrent(session) && session.world() == client.world
                && dev.lodekeeper.navigation.kernel.OwnedMutationGuard.executePlace(owner, hit,
                        Hand.MAIN_HAND);
    }
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
        OwnedClickReceipts.inventoryClick(client, client.player.playerScreenHandler.syncId, slot, ClientAccess.selectedSlot(inventory), SlotActionType.SWAP, client.player);
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
        Vec3d center = Vec3d.ofCenter(position).add(Vec3d.of(face.getVector()).multiply(.499));
        return hitFace(position, face, center);
    }

    private BlockHitResult hitFace(BlockPos position, Direction face, Vec3d center) {
        Vec3d eye = client.player.getEyePos();
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
        OwnedClickReceipts.inventoryClick(client, client.player.playerScreenHandler.syncId, source, destination, SlotActionType.SWAP, client.player);
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
        if (!permitsBreak(position)) return refuseMining(MineFailure.PROTECTED_BLOCK);
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
        recordOwnedNavigationBreak(position);
        ownsBreaking = true;
        client.interactionManager.updateBlockBreakingProgress(position, hit.getSide());
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }
    boolean cropMovementInputWitness(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                                     dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session,
                                     Object observedInput, Object installedInput, Object predecessor) {
        if (owner == null || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != owner
                || !owner.isCurrent(session) || session.world() != client.world || client.player == null
                || client.player.input != observedInput || owner.getPrimaryBaritone() == null) return false;
        return GameApi.cropMovementInputWitness(owner, session, client.player, observedInput, installedInput, predecessor);
    }

    boolean cropNativeQuiescent(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner) {
        if (owner == null || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != owner
                || owner.getPrimaryBaritone() == null) return false;
        var bot = owner.getPrimaryBaritone(); var pathing = bot.getPathingBehavior();
        if (pathing.hasPath() || pathing.isPathing() || pathing.getInProgress().isPresent()) return false;
        dev.lodekeeper.navigation.kernel.api.process.IBaritoneProcess[] processes = {
                bot.getCustomGoalProcess(), bot.getMineProcess(), bot.getFollowProcess(), bot.getBuilderProcess(),
                bot.getExploreProcess(), bot.getFarmProcess(), bot.getGetToBlockProcess(), bot.getElytraProcess()
        };
        for (var process : processes) {
            if (process == bot.getFollowProcess() ? bot.getFollowProcess().currentFilter() != null : process.isActive()) return false;
        }
        for (var key : dev.lodekeeper.navigation.kernel.api.utils.input.Input.values())
            if (bot.getInputOverrideHandler().isInputForcedDown(key)) return false;
        return true;
    }

    boolean cropRepairPermitted(BlockPos destination) {
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        return owner != null && owner.isCurrent(session) && session.world() == client.world
                && protection != null && protection.mayPlace(destination) && protection.mayInteractBlock(destination.down())
                && dev.lodekeeper.navigation.kernel.OwnedMutationGuard.safeEquipment(client.player)
                && dev.lodekeeper.navigation.kernel.OwnedMutationGuard.planPlace(owner.capturePolicy(), destination.down(), Direction.UP);
    }

    boolean harvestCrop(BlockPos position, BlockState expected,
                        java.util.function.BooleanSupplier admission, Runnable beforeSend) {
        if (!GameApi.supportsCropHarvest() || !admission.getAsBoolean() || client.world == null || client.player == null
                || client.interactionManager == null || !client.player.getMainHandStack().isEmpty() || !client.world.getBlockState(position).equals(expected)
                || !GameApi.cropInstantBreak(client, position, expected) || !cropRepairPermitted(position)
                || !permitsBreak(position)) return false;
        BlockHitResult hit = hit(position);
        if (hit == null || !admission.getAsBoolean()) return false;
        look(hit.getPos());
        if (!admission.getAsBoolean() || !client.world.getBlockState(position).equals(expected)
                || !cropRepairPermitted(position) || !permitsBreak(position)) return false;
        beforeSend.run();
        GameApi.sendCropBreak(client, position, hit.getSide());
        return true;
    }

    boolean replantCrop(BlockPos destination, dev.lodekeeper.core.ItemId plantingItem,
                        java.util.function.BooleanSupplier admission, Runnable beforeSend) {
        if (!GameApi.supportsCropHarvest() || !admission.getAsBoolean() || client.world == null || client.player == null
                || client.interactionManager == null || !client.world.getBlockState(destination).isAir()
                || !client.world.getBlockState(destination.down()).isOf(Blocks.FARMLAND)
                || !AnimalHarvestAction.ordinary(client.player.getMainHandStack()) || !GameCatalog.id(client.player.getMainHandStack().getItem()).equals(plantingItem)
                || !cropRepairPermitted(destination)) return false;
        BlockPos farmland = destination.down();
        double surfaceY = farmland.getY() + client.world.getBlockState(farmland)
                .getOutlineShape(client.world, farmland, net.minecraft.block.ShapeContext.absent())
                .getMax(Direction.Axis.Y) - .001;
        BlockHitResult hit = hitFace(farmland, Direction.UP,
                new Vec3d(farmland.getX() + .5, surfaceY, farmland.getZ() + .5));
        if (hit == null || !admission.getAsBoolean() || !permitsPlacement(hit)) return false;
        look(hit.getPos());
        if (!admission.getAsBoolean() || !client.world.getBlockState(destination).isAir()
                || !cropRepairPermitted(destination) || !permitsPlacement(hit)) return false;
        beforeSend.run();
        GameApi.sendCropPlant(client, hit);
        return true;
    }

    boolean swapCropHand(int source, int hotbar, java.util.function.BooleanSupplier admission, Runnable beforeSend) {
        if (source < 9 || source >= 36 || hotbar < 0 || hotbar >= 9 || client.player == null || client.interactionManager == null
                || client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler
                || !client.player.currentScreenHandler.getCursorStack().isEmpty() || !admission.getAsBoolean()) return false;
        if (!admission.getAsBoolean()) return false;
        beforeSend.run();
        OwnedClickReceipts.inventoryClick(client, client.player.playerScreenHandler.syncId, source, hotbar, SlotActionType.SWAP, client.player);
        return true;
    }

    boolean use(BlockPos position) {
        if (client.player == null || client.interactionManager == null || client.player.isSneaking()
                || client.currentScreen instanceof dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier) return false;
        if (client.world == null || !client.world.isChunkLoaded(position)) return false;
        var state = client.world.getBlockState(position);
        boolean defaultVanillaStation = state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.SMOKER) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.STONECUTTER);
        if (!defaultVanillaStation && !client.player.getMainHandStack().isEmpty()) {
            int empty = -1;
            for (int slot = 0; slot < 36; slot++) {
                if (client.player.getInventory().getStack(slot).isEmpty()) { empty = slot; break; }
            }
            if (empty < 0) throw new IllegalStateException("Station interaction needs an empty inventory slot");
            if (!selectSlot(empty) || !client.player.getMainHandStack().isEmpty()) return false;
        }
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
        if (protection != null && !protection.mayPlace(destination)) return null;
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
        if (!permitsPlacement(hit)) return false;
        look(hit.getPos());
        return client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit).isAccepted();
    }
    BackfillAttempt placeBackfill(BlockPos destination, Block block) {
        if (client.player == null || client.world == null || client.interactionManager == null)
            return BackfillAttempt.NOT_SENT;
        BlockHitResult hit = placementHit(destination);
        if (hit == null || !select(block.asItem()) || client.player.getMainHandStack().getItem() != block.asItem())
            return BackfillAttempt.NOT_SENT;
        if (!client.world.getBlockState(destination).isAir()
                || !client.world.getBlockState(destination).getFluidState().isEmpty()
                || !permitsPlacement(hit)) return BackfillAttempt.NOT_SENT;
        look(hit.getPos());
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        return BackfillAttempt.SENT_OR_UNCERTAIN;
    }
    static final class StationPlacementHand {
        final long jobToken;
        final BlockPos position;
        final Block block;
        final Object input;
        final dev.lodekeeper.navigation.kernel.OwnedKernelRuntime nativeOwner;
        final dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session nativeSession;
        final java.util.function.BooleanSupplier newEffectAdmission;
        Object player, world, network, connection, menu, playerMenu;
        dev.lodekeeper.core.OwnedStationLedger.Session session;
        dev.lodekeeper.core.OwnedStationLedger.PlacementTicket ticket;
        ItemStack originalStack, selectedBefore;
        int originalSlot, selectedSlot;
        boolean captured, selectionSent, restoreHotbar, rightsLost, sent, draining, settled, abandoned;
        boolean diagnosticCaptured, diagnosticLogged, diagnosticRestored;
        int diagnosticBorrowedSlot = -1;
        String diagnosticFirstLoss, diagnosticLastAdmission = "not_evaluated";
        String diagnosticFailedPredicate, diagnosticCallsite;
        long diagnosticGuardEvaluated, diagnosticGuardValues;
        int diagnosticManualEvaluated, diagnosticManualDown;

        StationPlacementHand(long jobToken, BlockPos position, Block block, Object input,
                             dev.lodekeeper.navigation.kernel.OwnedKernelRuntime nativeOwner,
                             dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session nativeSession,
                             java.util.function.BooleanSupplier newEffectAdmission) {
            this.jobToken = jobToken; this.position = position.toImmutable(); this.block = block;
            this.input = input; this.nativeOwner = nativeOwner; this.nativeSession = nativeSession;
            this.newEffectAdmission = newEffectAdmission;
        }
        void requestDrain() { draining = true; }
        void loseRights() { rightsLost = draining = true; }
    }

    private boolean admitStationHand(StationPlacementHand hand) {
        observeStationPlacementHand(hand, false, "PlayerActions.admitStationHand.observe");
        if (hand.draining || hand.sent || hand.settled || hand.rightsLost || !stationHandAdmission(hand)) {
            hand.requestDrain(); return false;
        }
        return true;
    }

    private boolean selectStationHand(StationPlacementHand hand, boolean ordinaryOnly) {
        if (!admitStationHand(hand)) return false;
        if (hand.captured) return hand.selectionSent;
        if (client.player == null || client.world == null || client.getNetworkHandler() == null
                || client.getNetworkHandler().getConnection() == null || !client.getNetworkHandler().getConnection().isOpen()
                || client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler
                || !client.player.currentScreenHandler.getCursorStack().isEmpty() || client.player.input != hand.input) {
            hand.requestDrain(); return false;
        }
        int original = ClientAccess.selectedSlot(client.player.getInventory());
        if (original < 0 || original > 8) { hand.requestDrain(); return false; }
        var session = placementProvenance.session().orElse(null);
        if (session == null) { hand.requestDrain(); return false; }
        int chosen = -1;
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            if (stack.isOf(hand.block.asItem()) && (!ordinaryOnly || !stack.hasEnchantments()
                    && !GameApi.hasCustomName(stack) && GameApi.canCombine(stack, new ItemStack(hand.block.asItem())))) {
                chosen = index; break;
            }
        }
        if (chosen < 0) return false;
        hand.player = client.player; hand.world = client.world;
        hand.network = client.getNetworkHandler(); hand.connection = client.getNetworkHandler().getConnection();
        hand.menu = client.player.currentScreenHandler; hand.playerMenu = client.player.playerScreenHandler;
        hand.session = session; hand.originalSlot = original;
        hand.selectedSlot = chosen < 9 ? chosen : original;
        hand.diagnosticBorrowedSlot = chosen;
        hand.originalStack = client.player.getInventory().getStack(original).copy();
        hand.selectedBefore = client.player.getInventory().getStack(chosen).copy();
        hand.restoreHotbar = chosen < 9; hand.captured = true;
        if (!admitStationHand(hand)
                || !sameStationHandStack(client.player.getInventory().getStack(chosen), hand.selectedBefore)) {
            hand.requestDrain(); return false;
        }
        // Main-inventory selection retains its existing SWAP; this does not own a layout restoration.
        hand.selectionSent = true;
        return selectSlot(chosen);
    }

    void observeStationPlacementHand(StationPlacementHand hand, boolean ownedAirInput) {
        observeStationPlacementHand(hand, ownedAirInput, "PlayerActions.observeStationPlacementHand");
    }

    void observeStationPlacementHand(StationPlacementHand hand, boolean ownedAirInput, String callsite) {
        StationHandGuardTrace trace = stationHandTrace(hand, callsite, ownedAirInput);
        if (!stationHandGuard(trace, 0, hand.captured) || !stationHandGuard(trace, 1, !hand.settled)) return;
        if (!stationHandGuard(trace, 2, client.player == hand.player)
                || !stationHandGuard(trace, 3, client.world == hand.world)
                || !stationHandGuard(trace, 4, client.getNetworkHandler() == hand.network)
                || !stationHandGuard(trace, 5, client.getNetworkHandler() != null)
                || !stationHandGuard(trace, 6, client.getNetworkHandler().getConnection() == hand.connection)
                || !stationHandGuard(trace, 7, client.getNetworkHandler().getConnection() != null)
                || !stationHandGuard(trace, 8, client.getNetworkHandler().getConnection().isOpen())
                || !stationHandGuard(trace, 9, placementProvenance.session().filter(hand.session::equals).isPresent())
                || !stationHandGuard(trace, 10, dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() == hand.nativeOwner)
                || !stationHandGuard(trace, 11, hand.nativeOwner.isCurrent(hand.nativeSession))
                || !stationHandGuard(trace, 12, hand.nativeSession.world() == client.world)) {
            hand.loseRights(); hand.abandoned = true;
            captureStationHandRightsLoss(hand, trace, firstStationHandFailure(trace, 2, 12));
            return;
        }
        int expectedSlot = hand.selectionSent ? hand.selectedSlot : hand.originalSlot;
        if (!stationHandGuard(trace, 13, client.player.isAlive())
                || !stationHandGuard(trace, 14, client.player.currentScreenHandler == hand.menu)
                || !stationHandGuard(trace, 15, client.player.playerScreenHandler == hand.playerMenu)
                || !stationHandGuard(trace, 16, client.currentScreen == null)
                || !stationHandGuard(trace, 17, client.player.currentScreenHandler.getCursorStack().isEmpty())
                || !stationHandGuard(trace, 18, !client.player.isUsingItem())
                || !stationHandGuard(trace, 19, ClientAccess.selectedSlot(client.player.getInventory()) == expectedSlot)
                || !ownedAirInput && !stationHandGuard(trace, 20, client.player.input == hand.input)
                || !stationHandGuard(trace, 21, !manualStationHandInput(trace))) {
            hand.loseRights();
            captureStationHandRightsLoss(hand, trace, firstStationHandFailure(trace, 13, 21));
        }
        if (!stationHandGuard(trace, 22, hand.selectionSent)) {
            if (!sameStationHandStack(client.player.getInventory().getStack(hand.originalSlot), hand.originalStack, trace, 23)) {
                hand.loseRights();
                captureStationHandRightsLoss(hand, trace, stationHandStackFailure(trace, 23));
            }
            return;
        }
        ItemStack selected = client.player.getInventory().getStack(hand.selectedSlot);
        ItemStack after = hand.selectedBefore.copy(); after.decrement(1);
        if (stationHandGuard(trace, 49, !sameStationHandStack(selected, hand.selectedBefore, trace, 27)
                && (!stationHandGuard(trace, 41, hand.sent) || !sameStationHandStack(selected, after, trace, 31)))
                || stationHandGuard(trace, 39, hand.restoreHotbar) && stationHandGuard(trace, 40, hand.originalSlot != hand.selectedSlot)
                && !sameStationHandStack(client.player.getInventory().getStack(hand.originalSlot), hand.originalStack, trace, 23)) {
            hand.loseRights();
            captureStationHandRightsLoss(hand, trace, trace != null && (trace.values & 1L << 49) != 0
                    ? "selected_changed_without_permitted_debit" : stationHandStackFailure(trace, 23));
        }
    }

    boolean finishStationPlacementHand(StationPlacementHand hand,
                                      dev.lodekeeper.core.OwnedStationLedger.StationRecord record) {
        if (hand.settled) return true;
        if (!hand.captured || !hand.selectionSent) { hand.settled = true; return true; }
        observeStationPlacementHand(hand, false, "PlayerActions.finishStationPlacementHand.observe");
        if (hand.abandoned) return false;
        StationHandGuardTrace trace = stationHandTrace(hand, "PlayerActions.finishStationPlacementHand.stack", false);
        if (trace != null) { trace.record = record; trace.recordArgumentSupplied = true; }
        if (stationHandGuard(trace, 41, hand.sent) && (!stationHandGuard(trace, 43, record != null)
                || !stationHandGuard(trace, 44, record.ticket() == hand.ticket)
                || !stationHandGuard(trace, 45, record.jobToken() == hand.jobToken)
                || !stationHandGuard(trace, 46, record.session().equals(hand.session))
                || !stationHandGuard(trace, 47, record.position().equals(hand.ticket.intent().position()))
                || !stationHandGuard(trace, 48, record.expectedBlockId().equals(hand.ticket.intent().expectedBlockId())))) return false;
        if (!stationHandGuard(trace, 42, placementProvenance.confirmedInventoryReady())) return false;
        ItemStack expected = hand.selectedBefore.copy();
        if (hand.sent) expected.decrement(1);
        if (stationHandGuard(trace, 50, !sameStationHandStack(client.player.getInventory().getStack(hand.selectedSlot), expected, trace, 35))
                || stationHandGuard(trace, 39, hand.restoreHotbar) && stationHandGuard(trace, 40, hand.originalSlot != hand.selectedSlot)
                && !sameStationHandStack(client.player.getInventory().getStack(hand.originalSlot), hand.originalStack, trace, 23)) {
            hand.loseRights();
            captureStationHandRightsLoss(hand, trace, trace != null && (trace.values & 1L << 50) != 0
                    ? stationHandStackFailure(trace, 35) : stationHandStackFailure(trace, 23));
        }
        if (!hand.rightsLost && hand.restoreHotbar && hand.originalSlot != hand.selectedSlot) {
            ClientAccess.selectedSlot(client.player.getInventory(), hand.originalSlot);
            hand.diagnosticRestored = true;
        }
        hand.settled = true;
        return true;
    }

    private static boolean sameStationHandStack(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount() && left.isEmpty() == right.isEmpty()
                && (left.isEmpty() || GameApi.canCombine(left, right));
    }


    private static final class StationHandGuardTrace {
        static final String[] NAMES = {
                "captured", "unsettled", "player_same", "world_same", "network_same", "network_present",
                "connection_same", "connection_present", "connection_open", "provenance_session_equal",
                "native_owner_same", "native_session_current", "native_world_same", "alive", "menu_same",
                "player_menu_same", "screen_absent", "cursor_empty", "not_using_item", "selected_slot_expected",
                "input_same", "manual_idle", "selection_sent", "original_count_equal", "original_empty_equal",
                "original_left_empty", "original_components_equal", "selected_before_count_equal",
                "selected_before_empty_equal", "selected_before_left_empty", "selected_before_components_equal",
                "selected_after_count_equal", "selected_after_empty_equal", "selected_after_left_empty",
                "selected_after_components_equal", "selected_expected_count_equal", "selected_expected_empty_equal",
                "selected_expected_left_empty", "selected_expected_components_equal", "restore_hotbar",
                "original_slot_different", "sent", "confirmed_inventory_ready", "record_present", "record_ticket_same",
                "record_job_equal", "record_session_equal", "record_position_equal", "record_block_equal",
                "selected_changed_without_permitted_debit", "selected_expected_mismatch"
        };
        final String callsite;
        final boolean ownedAirInput;
        long evaluated, values;
        int manualEvaluated, manualDown;
        Object record;
        boolean recordArgumentSupplied;

        StationHandGuardTrace(String callsite, boolean ownedAirInput) {
            this.callsite = callsite; this.ownedAirInput = ownedAirInput;
        }
    }

    private static StationHandGuardTrace stationHandTrace(StationPlacementHand hand, String callsite, boolean ownedAirInput) {
        if (hand.diagnosticCaptured) return null;
        try { return new StationHandGuardTrace(callsite, ownedAirInput); }
        catch (RuntimeException | Error ignored) { return null; }
    }

    private static boolean stationHandGuard(StationHandGuardTrace trace, int predicate, boolean value) {
        if (trace != null) {
            long bit = 1L << predicate;
            trace.evaluated |= bit;
            if (value) trace.values |= bit;
        }
        return value;
    }

    private static boolean stationHandKey(StationHandGuardTrace trace, int bit, boolean down) {
        if (trace != null) {
            trace.manualEvaluated |= bit;
            if (down) trace.manualDown |= bit;
        }
        return down;
    }

    private static String firstStationHandFailure(StationHandGuardTrace trace, int first, int last) {
        if (trace == null) return "trace_unavailable";
        for (int index = first; index <= last; index++) {
            if ((trace.evaluated & 1L << index) != 0 && (trace.values & 1L << index) == 0)
                return StationHandGuardTrace.NAMES[index];
        }
        return "trace_unavailable";
    }

    private static String stationHandStackFailure(StationHandGuardTrace trace, int base) {
        String scalar = firstStationHandFailure(trace, base, base + 1);
        return !scalar.equals("trace_unavailable") ? scalar : firstStationHandFailure(trace, base + 3, base + 3);
    }

    private static boolean sameStationHandStack(ItemStack left, ItemStack right, StationHandGuardTrace trace, int base) {
        return stationHandGuard(trace, base, left.getCount() == right.getCount())
                && stationHandGuard(trace, base + 1, left.isEmpty() == right.isEmpty())
                && (stationHandGuard(trace, base + 2, left.isEmpty())
                || stationHandGuard(trace, base + 3, GameApi.canCombine(left, right)));
    }

    private static boolean stationHandAdmission(StationPlacementHand hand) {
        boolean admitted = hand.newEffectAdmission.getAsBoolean();
        hand.diagnosticLastAdmission = admitted ? "engine_admission_delegate_true" : "engine_admission_delegate_false";
        return admitted;
    }

    private static String stationHandToken(String value, int limit) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), limit));
        for (int index = 0; index < value.length() && index < limit; index++) {
            char character = value.charAt(index);
            result.append(character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9' || ".:_/-@".indexOf(character) >= 0 ? character : '_');
        }
        return result.toString();
    }

    private static String stationHandIdentity(Object value) {
        return value == null ? "null" : stationHandToken(value.getClass().getName(), 96)
                + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String stationHandStackSnapshot(ItemStack source) {
        if (source == null) return "unavailable";
        try {
            return "item:" + stationHandToken(String.valueOf(Registries.ITEM.getId(source.getItem())), 64)
                    + ",count:" + source.getCount() + ",damage:" + source.getDamage()
                    + ",component_types:omitted,component_values:omitted";
        } catch (RuntimeException | Error ignored) { return "snapshot_unavailable"; }
    }

    void captureStationHandRightsLoss(StationPlacementHand hand, String callsite, String failedPredicate) {
        captureStationHandRightsLoss(hand, stationHandTrace(hand, callsite, false), failedPredicate);
    }

    private void captureStationHandRightsLoss(StationPlacementHand hand, StationHandGuardTrace trace, String failedPredicate) {
        if (hand.diagnosticCaptured) return;
        hand.diagnosticCaptured = true;
        hand.diagnosticFailedPredicate = failedPredicate;
        hand.diagnosticCallsite = trace == null ? "trace_unavailable" : trace.callsite;
        hand.diagnosticGuardEvaluated = trace == null ? 0 : trace.evaluated;
        hand.diagnosticGuardValues = trace == null ? 0 : trace.values;
        hand.diagnosticManualEvaluated = trace == null ? 0 : trace.manualEvaluated;
        hand.diagnosticManualDown = trace == null ? 0 : trace.manualDown;
        hand.diagnosticFirstLoss = "schema=1 capture=unavailable evidence=client_only";
        try {
            StringBuilder line = new StringBuilder(6144);
            line.append("schema=1 evidence=client_only callsite=").append(trace == null ? "trace_unavailable" : trace.callsite)
                    .append(" first_failed_predicate=").append(failedPredicate)
                    .append(" tick_kind=player_age tick=").append(client.player == null ? -1 : client.player.age)
                    .append(" job=").append(hand.jobToken).append(" position=")
                    .append(hand.position.getX()).append(',').append(hand.position.getY()).append(',').append(hand.position.getZ())
                    .append(" original_slot=").append(hand.originalSlot).append(" selected_slot=").append(hand.selectedSlot)
                    .append(" borrowed_slot=").append(hand.diagnosticBorrowedSlot)
                    .append(" captured=").append(hand.captured).append(" selection_sent=").append(hand.selectionSent)
                    .append(" sent=").append(hand.sent).append(" restored=").append(hand.diagnosticRestored)
                    .append(" restore_hotbar=").append(hand.restoreHotbar).append(" draining=").append(hand.draining)
                    .append(" rights_lost=").append(hand.rightsLost).append(" abandoned=").append(hand.abandoned)
                    .append(" settled=").append(hand.settled).append(" admission_last=").append(hand.diagnosticLastAdmission)
                    .append(" owned_air_input_argument=").append(trace == null ? "unavailable" : trace.ownedAirInput)
                    .append(" evaluated_guards={");
            if (trace != null) {
                boolean separator = false;
                for (int index = 0; index < StationHandGuardTrace.NAMES.length; index++) {
                    if ((trace.evaluated & 1L << index) == 0) continue;
                    if (separator) line.append(',');
                    line.append(StationHandGuardTrace.NAMES[index]).append(':').append((trace.values & 1L << index) != 0);
                    separator = true;
                }
            }
            line.append("} manual_evaluated_mask=").append(trace == null ? 0 : trace.manualEvaluated)
                    .append(" manual_down_mask=").append(trace == null ? 0 : trace.manualDown);
            hand.diagnosticFirstLoss = line.toString() + " capture=partial";
            line.append(" input_captured=").append(stationHandIdentity(hand.input))
                    .append(" input_snapshot=").append(stationHandIdentity(client.player == null ? null : client.player.input))
                    .append(" player_captured=").append(stationHandIdentity(hand.player))
                    .append(" world_captured=").append(stationHandIdentity(hand.world))
                    .append(" network_captured=").append(stationHandIdentity(hand.network))
                    .append(" connection_captured=").append(stationHandIdentity(hand.connection))
                    .append(" menu_captured=").append(stationHandIdentity(hand.menu))
                    .append(" player_menu_captured=").append(stationHandIdentity(hand.playerMenu))
                    .append(" native_owner_captured=").append(stationHandIdentity(hand.nativeOwner))
                    .append(" native_session_captured=").append(stationHandIdentity(hand.nativeSession))
                    .append(" provenance_session_captured=").append(stationHandIdentity(hand.session))
                    .append(" ticket=").append(stationHandIdentity(hand.ticket))
                    .append(" record_argument=").append(trace == null || !trace.recordArgumentSupplied
                            ? "not_evaluated" : stationHandIdentity(trace.record))
                    .append(" readiness=only_if_evaluated");
            if (hand.ticket != null) {
                var intent = hand.ticket.intent();
                line.append(" ticket_job=").append(intent.jobToken()).append(" ticket_generation=").append(intent.session().generation())
                        .append(" ticket_start_sequence=").append(intent.startNetworkSequence())
                        .append(" ticket_start_count=").append(intent.startingInventoryCount());
            }
            line.append(" saved_original={").append(stationHandStackSnapshot(hand.originalStack))
                    .append("} saved_selected={").append(stationHandStackSnapshot(hand.selectedBefore)).append('}');
            if (client.player != null) {
                var inventory = client.player.getInventory();
                int currentSlot = ClientAccess.selectedSlot(inventory);
                line.append(" current_slot=").append(currentSlot)
                        .append(" current_original={").append(stationHandSlotSnapshot(hand.originalSlot)).append('}')
                        .append(" current_selected={").append(stationHandSlotSnapshot(hand.selectedSlot)).append('}')
                        .append(" current_borrowed={").append(stationHandSlotSnapshot(hand.diagnosticBorrowedSlot)).append('}')
                        .append(" current_held={").append(stationHandSlotSnapshot(currentSlot)).append('}');
            }
            line.append(" component_types=omitted component_values=omitted exact_component_comparisons=only_if_evaluated");
            hand.diagnosticFirstLoss = line.length() <= 8192 ? line.toString() : line.substring(0, 8150) + " line_truncated=true";
        } catch (RuntimeException | Error ignored) { }
    }

    private String stationHandSlotSnapshot(int slot) {
        if (client.player == null || slot < 0 || slot >= 36) return "unavailable";
        return stationHandStackSnapshot(client.player.getInventory().getStack(slot));
    }

    private boolean manualStationHandInput(StationHandGuardTrace trace) {
        var options = client.options;
        return stationHandKey(trace, 1, options.attackKey.isPressed()) || stationHandKey(trace, 2, options.useKey.isPressed())
                || stationHandKey(trace, 4, options.forwardKey.isPressed()) || stationHandKey(trace, 8, options.backKey.isPressed())
                || stationHandKey(trace, 16, options.leftKey.isPressed()) || stationHandKey(trace, 32, options.rightKey.isPressed())
                || stationHandKey(trace, 64, options.jumpKey.isPressed()) || stationHandKey(trace, 128, options.sneakKey.isPressed()) || stationHandKey(trace, 256, options.sprintKey.isPressed());
    }

    PlacementAttempt placeStation(BlockPos destination, Block block, long jobToken) {
        return placeStation(destination, block, jobToken, false);
    }
    PlacementAttempt placeStation(BlockPos destination, Block block, long jobToken, boolean ordinaryOnly) {
        return placeStation(destination, block, jobToken, ordinaryOnly, null);
    }
    PlacementAttempt placeStation(BlockPos destination, Block block, long jobToken, boolean ordinaryOnly, StationPlacementHand hand) {
        if (hand != null && (hand.jobToken != jobToken || !hand.position.equals(destination) || hand.block != block
                || !admitStationHand(hand))) return PlacementAttempt.YIELDED;
        if (placementProvenance == null || client.interactionManager == null) return PlacementAttempt.REJECTED;
        BlockHitResult hit = placementHit(destination);
        if (hit == null) return PlacementAttempt.REJECTED;
        var readiness = placementProvenance.readinessStatus(jobToken, destination, block);
        if (readiness.orElse(null) == PlacementProvenance.ReservationStatus.QUARANTINED)
            return PlacementAttempt.QUARANTINED;
        if (readiness.orElse(null) == PlacementProvenance.ReservationStatus.EXPIRED)
            return PlacementAttempt.INVENTORY_TIMEOUT;
        if (hand != null) {
            if (!selectStationHand(hand, ordinaryOnly))
                return hand.draining ? PlacementAttempt.YIELDED : PlacementAttempt.REJECTED;
        } else if (readiness.isEmpty() && !(ordinaryOnly ? selectOrdinary(block.asItem()) : select(block.asItem()))) return PlacementAttempt.REJECTED;
        if (hand != null && !admitStationHand(hand)) return PlacementAttempt.YIELDED;
        if (!permitsPlacement(hit)) return PlacementAttempt.REJECTED;
        look(hit.getPos());
        var reservation = placementProvenance.reservePlacement(jobToken, destination, block);
        if (reservation.status() == PlacementProvenance.ReservationStatus.NOT_READY)
            return PlacementAttempt.WAITING_FOR_PROVENANCE;
        if (reservation.status() == PlacementProvenance.ReservationStatus.QUARANTINED)
            return PlacementAttempt.QUARANTINED;
        if (reservation.status() != PlacementProvenance.ReservationStatus.RESERVED) return PlacementAttempt.REJECTED;
        var ticket = reservation.ticket().orElseThrow();
        if (hand != null) {
            hand.ticket = ticket;
            if (!admitStationHand(hand)) return PlacementAttempt.YIELDED;
            hit = placementHit(destination);
            if (hit == null || !permitsPlacement(hit)) { hand.requestDrain(); return PlacementAttempt.YIELDED; }
            if (!admitStationHand(hand)) return PlacementAttempt.YIELDED;
            hand.sent = true;
        }
        try {
            if (!client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit).isAccepted()) {
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
            ItemStack stack = client.player.getInventory().getStack(index);
            if (stack.isOf(item) && !stack.hasEnchantments() && !GameApi.hasCustomName(stack)
                    && GameApi.canCombine(stack, new ItemStack(item)) && selectSlot(index)) return true;
        }
        return false;
    }

    void cancel() {
        if (ownsBreaking && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        ownsBreaking = false;
    }
}
