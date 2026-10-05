package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.nav.StanceProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/** Constrained escape from vanilla's 15/16-height path and farmland surfaces. */
final class SurfaceRecovery {
    private static final int MAX_TICKS = 80;
    private static final double SURFACE_HEIGHT = 15.0 / 16.0;
    private static final double HEIGHT_EPSILON = 1.0 / 64.0;
    private static final double COLLISION_EPSILON = 1.0 / 64.0;

    private final Minecraft client;
    private final GameTerrain terrain;
    private final BotInput input;
    private final StanceProbe stance = new StanceProbe();
    private BlockPos recoveryOrigin;
    private Object budgetWorld;
    private BlockPos destinationSupport;
    private int ticksUsed;
    private boolean active;

    SurfaceRecovery(Minecraft client, GameTerrain terrain, BotInput input) {
        this.client = client;
        this.terrain = terrain;
        this.input = input;
    }

    boolean begin() {
        active = false;
        destinationSupport = null;
        if (client.level == null || client.player == null) return false;
        if (budgetWorld != null && budgetWorld != client.level) resetBudget();
        if (isIntegralFullGround()) {
            resetBudget();
            return false;
        }

        BlockPos surface = supportPosition(client.player.getX(), client.player.getY(), client.player.getZ());
        if (!client.player.onGround() || !loaded(surface)) return false;
        BlockState currentSurface = client.level.getBlockState(surface);
        if (!isPathOrFarmland(currentSurface)
                || Math.abs(client.player.getY() - (surface.getY() + SURFACE_HEIGHT)) > HEIGHT_EPSILON) return false;

        if (budgetWorld == null) budgetWorld = client.level;
        if (recoveryOrigin == null) recoveryOrigin = surface;
        if (ticksUsed >= MAX_TICKS) {
            throw failure("Fractional-surface recovery from " + recoveryOrigin + " exceeded its 80-tick limit");
        }

        BlockPos candidate = findDestination(surface);
        if (candidate == null) {
            throw failure("No safe full-block recovery stance is reachable within two blocks of the fractional surface");
        }
        destinationSupport = candidate;
        active = true;
        return true;
    }

    boolean active() { return active; }

    /** Returns true only after the player reaches and is observed grounded on the destination. */
    boolean tick() {
        if (!active || client.level == null || client.player == null) return false;
        if (++ticksUsed > MAX_TICKS) throw fail("Fractional-surface recovery exceeded its 80-tick limit");
        if (!safeRoute(destinationSupport)) throw fail("Fractional-surface recovery path became unsafe");

        var player = client.player;
        double destinationX = destinationSupport.getX() + .5;
        double destinationY = destinationSupport.getY() + 1.0;
        double destinationZ = destinationSupport.getZ() + .5;
        double dx = destinationX - player.getX();
        double dz = destinationZ - player.getZ();
        double horizontal = Math.hypot(dx, dz);
        player.setSprinting(false);

        if (horizontal <= .12 && isIntegralFullGround()
                && player.onGround()
                && supportPosition(player.getX(), player.getY(), player.getZ()).equals(destinationSupport)) {
            input.idle();
            active = false;
            resetBudget();
            return true;
        }

        if (horizontal > .035) {
            player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90));
            player.setXRot(0);
            input.drive(.22f, 0, false, false);
        } else {
            input.idle();
        }
        return false;
    }

    /** Cancels movement but deliberately preserves the world-scoped 80-tick retry budget. */
    void stop() {
        active = false;
        destinationSupport = null;
        input.idle();
    }

    private BlockPos findDestination(BlockPos surface) {
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int distanceSquared = dx * dx + dz * dz;
            if (distanceSquared == 0 || distanceSquared > 4 || distanceSquared > bestDistance) continue;
            BlockPos candidate = surface.offset(dx, 0, dz);
            if (!validDestination(candidate) || !safeRoute(candidate)) continue;
            if (distanceSquared < bestDistance) {
                best = candidate;
                bestDistance = distanceSquared;
            }
        }
        return best;
    }

    private boolean validDestination(BlockPos support) {
        if (!loaded(support)) return false;
        BlockState state = client.level.getBlockState(support);
        if (hazardous(state) || !state.getFluidState().isEmpty()
                || !Block.isShapeFullBlock(state.getCollisionShape(client.level, support))) return false;
        terrain.probeStance(support.getX(), support.getY() + 1, support.getZ(), stance);
        return stance.loaded && stance.fullSupport && !stance.hazard && stance.bodyClear
                && !stance.water && !stance.climbable;
    }

    private boolean safeRoute(BlockPos support) {
        if (support == null || !validDestination(support) || !validCurrentSupport(support.getY())) return false;
        var player = client.player;
        double startX = player.getX();
        double startY = player.getY();
        double startZ = player.getZ();
        double endY = support.getY() + 1.0;
        double endX = support.getX() + .5;
        double endZ = support.getZ() + .5;
        if (Math.hypot(endX - startX, endZ - startZ) > 2.0 + .001
                || startY < support.getY() + SURFACE_HEIGHT - HEIGHT_EPSILON
                || startY > endY + HEIGHT_EPSILON
                || Math.abs(endY - startY) > 1.0 - SURFACE_HEIGHT + 2 * HEIGHT_EPSILON) return false;

        AABB original = player.getBoundingBox();
        BlockPos currentSupport = supportPosition(startX, startY, startZ);
        // Prove a conservative vertical lift first, then a level horizontal walk. No jump is issued.
        AABB verticalStart = original.move(0, startY - player.getY(), 0);
        AABB verticalEnd = original.move(0, endY - player.getY(), 0);
        if (!bodyClear(union(verticalStart, verticalEnd), currentSupport)) return false;
        double horizontalDistance = Math.hypot(endX - startX, endZ - startZ);
        int samples = Math.max(1, (int) Math.ceil(horizontalDistance / .1));
        AABB previousBody = null;
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double x = startX + (endX - startX) * t;
            double z = startZ + (endZ - startZ) * t;
            AABB body = original.move(x - player.getX(), endY - player.getY(), z - player.getZ());
            if (!supportCoverage(body, support.getY()) || !bodyClear(body, currentSupport)) return false;
            if (previousBody != null) {
                AABB segment = union(previousBody, body);
                if (!supportCoverage(segment, support.getY()) || !bodyClear(segment, currentSupport)) return false;
            }
            previousBody = body;
        }
        return true;
    }

    private boolean validCurrentSupport(int supportY) {
        var player = client.player;
        if (player == null || !player.onGround()) return false;
        AABB body = player.getBoundingBox();
        int minX = (int) Math.floor(body.minX + 1.0e-7);
        int maxX = (int) Math.floor(body.maxX - 1.0e-7);
        int minZ = (int) Math.floor(body.minZ + 1.0e-7);
        int maxZ = (int) Math.floor(body.maxZ - 1.0e-7);
        double highestSupport = Double.NEGATIVE_INFINITY;
        for (int bx = minX; bx <= maxX; bx++) for (int bz = minZ; bz <= maxZ; bz++) {
            BlockPos position = new BlockPos(bx, supportY, bz);
            if (!loaded(position)) return false;
            BlockState state = client.level.getBlockState(position);
            if (hazardous(state) || !state.getFluidState().isEmpty()) return false;
            if (isPathOrFarmland(state)) highestSupport = Math.max(highestSupport, supportY + SURFACE_HEIGHT);
            else if (Block.isShapeFullBlock(state.getCollisionShape(client.level, position))) {
                highestSupport = Math.max(highestSupport, supportY + 1.0);
            } else return false;
        }
        return Double.isFinite(highestSupport) && Math.abs(player.getY() - highestSupport) <= HEIGHT_EPSILON;
    }

    private boolean supportCoverage(AABB body, int supportY) {
        int minX = (int) Math.floor(body.minX + 1.0e-7);
        int maxX = (int) Math.floor(body.maxX - 1.0e-7);
        int minZ = (int) Math.floor(body.minZ + 1.0e-7);
        int maxZ = (int) Math.floor(body.maxZ - 1.0e-7);
        for (int bx = minX; bx <= maxX; bx++) for (int bz = minZ; bz <= maxZ; bz++) {
            BlockPos position = new BlockPos(bx, supportY, bz);
            if (!loaded(position)) return false;
            BlockState state = client.level.getBlockState(position);
            if (hazardous(state) || !state.getFluidState().isEmpty()
                    || !(isPathOrFarmland(state)
                    || Block.isShapeFullBlock(state.getCollisionShape(client.level, position)))) return false;
        }
        return true;
    }

    private boolean bodyClear(AABB body, BlockPos currentSupport) {
        int minX = (int) Math.floor(body.minX + 1.0e-7);
        int maxX = (int) Math.floor(body.maxX - 1.0e-7);
        int minY = (int) Math.floor(body.minY + 1.0e-7);
        int maxY = (int) Math.floor(body.maxY - 1.0e-7);
        int minZ = (int) Math.floor(body.minZ + 1.0e-7);
        int maxZ = (int) Math.floor(body.maxZ - 1.0e-7);
        for (int bx = minX; bx <= maxX; bx++) for (int by = minY; by <= maxY; by++) for (int bz = minZ; bz <= maxZ; bz++) {
            BlockPos position = new BlockPos(bx, by, bz);
            if (!loaded(position)) return false;
            BlockState state = client.level.getBlockState(position);
            if (hazardous(state) || !state.getFluidState().isEmpty()) return false;
            for (AABB local : state.getCollisionShape(client.level, position).toAabbs()) {
                AABB collision = local.move(bx, by, bz);
                if (!collision.intersects(body)) continue;
                if (position.equals(currentSupport) && floorContactOnly(collision, body)) continue;
                return false;
            }
        }
        return true;
    }

    private AABB union(AABB first, AABB second) {
        return new AABB(Math.min(first.minX, second.minX), Math.min(first.minY, second.minY),
                Math.min(first.minZ, second.minZ), Math.max(first.maxX, second.maxX),
                Math.max(first.maxY, second.maxY), Math.max(first.maxZ, second.maxZ));
    }

    private boolean floorContactOnly(AABB collision, AABB body) {
        return collision.maxY >= body.minY - COLLISION_EPSILON
                && collision.maxY <= body.minY + COLLISION_EPSILON
                && collision.minY < body.minY;
    }

    private boolean isIntegralFullGround() {
        var player = client.player;
        if (player == null || !player.onGround()) return false;
        double y = player.getY();
        if (Math.abs(y - Math.rint(y)) > 1.0e-4) return false;
        BlockPos feet = new BlockPos((int) Math.floor(player.getX()), (int) Math.floor(y),
                (int) Math.floor(player.getZ()));
        terrain.probeStance(feet.getX(), feet.getY(), feet.getZ(), stance);
        return stance.loaded && stance.fullSupport && !stance.hazard && stance.bodyClear
                && !stance.water && !stance.climbable;
    }

    private BlockPos supportPosition(double x, double y, double z) {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y - 1.0e-5), (int) Math.floor(z));
    }

    private boolean loaded(BlockPos position) {
        return client.level != null && position.getY() >= client.level.getMinY()
                && position.getY() < client.level.getMaxY()
                && client.level.getChunk(position.getX() >> 4, position.getZ() >> 4,
                ChunkStatus.FULL, false) != null;
    }

    private static boolean isPathOrFarmland(BlockState state) {
        return state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
    }

    private static boolean hazardous(BlockState state) {
        return !state.getFluidState().isEmpty() || state.getFluidState().is(FluidTags.LAVA)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE);
    }

    private void resetBudget() {
        recoveryOrigin = null;
        budgetWorld = client.level;
        ticksUsed = 0;
    }

    private MovementController.NavigationFailure fail(String reason) {
        active = false;
        input.idle();
        if (client.player != null) client.player.setSprinting(false);
        return failure(reason);
    }

    private static MovementController.NavigationFailure failure(String reason) {
        return new MovementController.NavigationFailure(reason);
    }
}
