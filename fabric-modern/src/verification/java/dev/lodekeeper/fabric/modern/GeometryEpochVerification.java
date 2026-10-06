package dev.lodekeeper.fabric.modern;

import com.google.gson.JsonObject;
import dev.lodekeeper.nav.Goal;
import dev.lodekeeper.nav.NavStatus;
import dev.lodekeeper.nav.Planner;
import dev.lodekeeper.nav.StanceProbe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

/** Bounded native geometry epoch checks used only by the runtime verifier. */
final class GeometryEpochVerification {
    private static final int GRID_MIN_X = -10;
    private static final int GRID_MAX_X = 10;
    private static final int GRID_MIN_Z = 16;
    private static final int GRID_MAX_Z = 40;
    private static final int GRID_CELL_COUNT = (GRID_MAX_X - GRID_MIN_X + 1) * (GRID_MAX_Z - GRID_MIN_Z + 1);
    private static final int GRID_WAIT_LIMIT_TICKS = 200;
    private static final BlockPos DYNAMIC_POSITION = new BlockPos(0, 63, 8);

    private enum Phase { WAITING_FOR_GRID, UNCHANGED_SEARCH_TICK, STALE_SEARCH_TICK, CONTEXT_CHECKS, DONE }

    private final JsonObject evidence = new JsonObject();
    private Phase phase = Phase.WAITING_FOR_GRID;
    private GameTerrain dynamicTerrain;
    private Planner dynamicPlanner;
    private BlockState exactDynamicState;
    private int waitTicks;
    private int phaseWaitTicks;
    private long phaseWorldTick;
    private boolean failed;

    static void prepareServerFixture(ServerLevel world) {
        VerificationContentInitializer.geometryBlockFull = true;
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(VerificationContentInitializer.DYNAMIC_COLLISION_ID));
        if (block == Blocks.AIR) throw new IllegalStateException("registered dynamic collision block is unavailable");
        BlockState state = block.defaultBlockState();
        for (int x = -2; x <= 8; x++) for (int z = 7; z <= 9; z++) {
            world.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            for (int y = 64; y <= 66; y++) world.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        world.setBlockAndUpdate(DYNAMIC_POSITION, state);
        world.setBlockAndUpdate(DYNAMIC_POSITION.below(), Blocks.STONE.defaultBlockState());
        for (int x = GRID_MIN_X; x <= GRID_MAX_X; x++) {
            for (int z = GRID_MIN_Z; z <= GRID_MAX_Z; z++) {
                world.setBlockAndUpdate(new BlockPos(x, 63, z), state);
            }
        }
    }

    JsonObject advance(Minecraft client, LodekeeperConfig config) {
        if (phase == Phase.DONE) return evidence.deepCopy();
        try {
            if (phase == Phase.WAITING_FOR_GRID) {
                GridObservation grid = observeGrid(client);
                evidence.addProperty("fixtureGridExpectedCells", GRID_CELL_COUNT);
                evidence.addProperty("fixtureGridLoadedCells", grid.loadedCells);
                evidence.addProperty("fixtureGridDynamicBlockCells", grid.dynamicBlockCells);
                evidence.addProperty("fixtureGridWaitTicks", waitTicks);
                if (grid.loadedCells != GRID_CELL_COUNT || grid.dynamicBlockCells != GRID_CELL_COUNT) {
                    if (++waitTicks > GRID_WAIT_LIMIT_TICKS) {
                        throw new IllegalStateException("dynamic fixture grid was not fully loaded and exact after "
                            + GRID_WAIT_LIMIT_TICKS + " ticks; loaded=" + grid.loadedCells + "/" + GRID_CELL_COUNT
                            + ", dynamic=" + grid.dynamicBlockCells + "/" + GRID_CELL_COUNT);
                    }
                    return null;
                }
                evidence.addProperty("fixtureGridReady", true);
                runInitialDynamicProbe(client, config);
                phaseWorldTick = client.level.getGameTime();
                evidence.addProperty("initialProbeWorldTick", phaseWorldTick);
                phase = Phase.UNCHANGED_SEARCH_TICK;
                return null;
            }
            if (phase == Phase.UNCHANGED_SEARCH_TICK) {
                long currentWorldTick = client.level.getGameTime();
                if (currentWorldTick <= phaseWorldTick) {
                    if (++phaseWaitTicks > GRID_WAIT_LIMIT_TICKS) {
                        throw new IllegalStateException("world time did not advance after the initial dynamic probe");
                    }
                    return null;
                }
                evidence.addProperty("unchangedSearchWorldTick", currentWorldTick);
                evidence.addProperty("unchangedSearchWorldWaitTicks", phaseWaitTicks);
                NavStatus status = dynamicPlanner.advance(1, Long.MAX_VALUE);
                evidence.addProperty("unchangedPlannerStatus", status.name());
                require(status == NavStatus.IN_PROGRESS,
                    "planner became " + status + " before the dynamic geometry changed");
                VerificationContentInitializer.geometryBlockFull = false;
                evidence.addProperty("dynamicCollisionToggledEmpty", true);
                phaseWorldTick = currentWorldTick;
                phaseWaitTicks = 0;
                phase = Phase.STALE_SEARCH_TICK;
                return null;
            }
            if (phase == Phase.STALE_SEARCH_TICK) {
                long currentWorldTick = client.level.getGameTime();
                if (currentWorldTick <= phaseWorldTick) {
                    if (++phaseWaitTicks > GRID_WAIT_LIMIT_TICKS) {
                        throw new IllegalStateException("world time did not advance after the dynamic shape toggle");
                    }
                    return null;
                }
                try {
                    evidence.addProperty("changedShapeWorldTick", currentWorldTick);
                    evidence.addProperty("changedShapeWorldWaitTicks", phaseWaitTicks);
                    NavStatus status = dynamicPlanner.advance(1, Long.MAX_VALUE);
                    StanceProbe probe = probe(dynamicTerrain, 0, 64, 8);
                    BlockState currentState = client.level.getBlockState(DYNAMIC_POSITION);
                    boolean sameNativeState = currentState == exactDynamicState;
                    evidence.addProperty("changedPlannerStatus", status.name());
                    evidence.addProperty("changedPlannerPathNull", dynamicPlanner.getPath() == null);
                    evidence.addProperty("emptyShapeProbeLoaded", probe.loaded);
                    evidence.addProperty("emptyShapeHasGroundSupport", probe.hasGroundSupport());
                    evidence.addProperty("dynamicBlockStateIdentityPreserved", sameNativeState);
                    require(status == NavStatus.STALE && dynamicPlanner.getPath() == null,
                        "shape change did not stale the in-flight planner and clear its path");
                    require(probe.loaded && !probe.hasGroundSupport(),
                        "empty native collision shape still supplied ground support");
                    require(sameNativeState, "dynamic shape toggle replaced the native blockstate object");
                } finally {
                    VerificationContentInitializer.geometryBlockFull = true;
                    dynamicTerrain.revision();
                }
                phase = Phase.CONTEXT_CHECKS;
                return null;
            }
            if (phase == Phase.CONTEXT_CHECKS) {
                runContextChecks(client, config);
                runWatchCapacityCheck(client, config);
                phase = Phase.DONE;
                evidence.addProperty("status", "passed");
                evidence.addProperty("passed", true);
                return evidence.deepCopy();
            }
            throw new IllegalStateException("unknown geometry verification phase " + phase);
        } catch (RuntimeException failure) {
            markFailure(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            throw failure;
        }
    }

    JsonObject evidence() {
        JsonObject result = evidence.deepCopy();
        if (!result.has("status")) result.addProperty("status", failed ? "failed" : "pending");
        return result;
    }

    void markFailure(String reason) {
        if (phase == Phase.DONE) return;
        if (failed) return;
        failed = true;
        VerificationContentInitializer.geometryBlockFull = true;
        evidence.addProperty("status", "failed");
        evidence.addProperty("passed", false);
        evidence.addProperty("failure", reason == null ? "geometry verification failed" : reason);
    }

    private void runInitialDynamicProbe(Minecraft client, LodekeeperConfig config) {
        dynamicTerrain = new GameTerrain(client, config);
        dynamicTerrain.beginSearch();
        long beforeFirstRegistration = dynamicTerrain.revision();
        StanceProbe first = probe(dynamicTerrain, 0, 64, 8);
        long afterFirstRegistration = dynamicTerrain.revision();
        long missesAfterFirstProbe = dynamicTerrain.shapeMisses;
        StanceProbe repeated = probe(dynamicTerrain, 0, 64, 8);
        long repeatedShapeMisses = dynamicTerrain.shapeMisses - missesAfterFirstProbe;
        exactDynamicState = client.level.getBlockState(DYNAMIC_POSITION);
        evidence.addProperty("firstRegistrationDidNotChangeRevision", beforeFirstRegistration == afterFirstRegistration);
        evidence.addProperty("initialProbeLoaded", first.loaded);
        evidence.addProperty("initialProbeBodyClear", first.bodyClear);
        evidence.addProperty("initialProbeFullSupport", first.fullSupport);
        evidence.addProperty("repeatProbeLoaded", repeated.loaded);
        evidence.addProperty("sameTickDynamicShapeMisses", repeatedShapeMisses);
        evidence.addProperty("sameTickDynamicShapeBypassedCache", repeatedShapeMisses > 0);
        require(exactDynamicState.getBlock() == BuiltInRegistries.BLOCK.getValue(
                Identifier.parse(VerificationContentInitializer.DYNAMIC_COLLISION_ID)),
            "dynamic fixture position did not contain the registered block");
        require(first.loaded && first.bodyClear && first.fullSupport,
            "full dynamic shape did not provide a clear supported stance at 0,64,8");
        require(beforeFirstRegistration == afterFirstRegistration,
            "first dynamic shape registration changed the geometry revision");
        require(repeated.loaded && repeatedShapeMisses > 0,
            "same-tick repeat did not bypass the dynamic shape cache");
        dynamicPlanner = new Planner(dynamicTerrain, 0, 64, 8, Goal.exact(6, 64, 8),
            new Planner.Options().maxNodes(64).maxDrop(0));
        evidence.addProperty("initialPlannerStatus", dynamicPlanner.getStatus().name());
        require(dynamicPlanner.getStatus() == NavStatus.IN_PROGRESS,
            "planner did not start from the full dynamic support stance");
    }

    private void runContextChecks(Minecraft client, LodekeeperConfig config) {
        GameTerrain terrain = new GameTerrain(client, config);
        terrain.beginSearch();

        long worldBefore = terrain.revision();
        var originalLevel = client.level;
        long worldNullRevision;
        long worldRestoredRevision;
        try {
            client.level = null;
            worldNullRevision = terrain.revision();
        } finally {
            client.level = originalLevel;
        }
        worldRestoredRevision = terrain.revision();
        boolean worldContextBumped = worldNullRevision > worldBefore && worldRestoredRevision > worldNullRevision;
        evidence.addProperty("worldNullRevision", worldNullRevision);
        evidence.addProperty("worldRestoredRevision", worldRestoredRevision);
        evidence.addProperty("worldContextRevisionBumped", worldContextBumped);
        require(worldContextBumped, "level null-to-restore did not bump revision without a terrain probe");

        long playerBefore = terrain.revision();
        var originalPlayer = client.player;
        long playerNullRevision;
        long playerRestoredRevision;
        try {
            client.player = null;
            playerNullRevision = terrain.revision();
        } finally {
            client.player = originalPlayer;
        }
        playerRestoredRevision = terrain.revision();
        boolean playerContextBumped = playerNullRevision > playerBefore && playerRestoredRevision > playerNullRevision;
        evidence.addProperty("playerNullRevision", playerNullRevision);
        evidence.addProperty("playerRestoredRevision", playerRestoredRevision);
        evidence.addProperty("playerContextRevisionBumped", playerContextBumped);
        require(playerContextBumped, "player null-to-restore did not bump revision without a terrain probe");

        var player = client.player;
        boolean wasSneaking = player.isShiftKeyDown();
        long sneakBefore = terrain.revision();
        long sneakingRevision;
        BotInput temporaryInput = new BotInput();
        try {
            temporaryInput.acquire(client);
            temporaryInput.drive(0, 0, false, !wasSneaking);
            temporaryInput.tick();
            require(player.isShiftKeyDown() != wasSneaking, "temporary native input did not change sneak state");
            sneakingRevision = terrain.revision();
        } finally {
            temporaryInput.release();
        }
        long sneakRestoredRevision = terrain.revision();
        boolean sneakContextBumped = sneakingRevision > sneakBefore && sneakRestoredRevision > sneakingRevision;
        evidence.addProperty("sneakingRevision", sneakingRevision);
        evidence.addProperty("sneakingRestoredRevision", sneakRestoredRevision);
        evidence.addProperty("sneakingContextRevisionBumped", sneakContextBumped);
        require(sneakContextBumped, "sneak toggle and restore did not bump revision without a terrain probe");

        ItemStack oldOffhand = player.getItemBySlot(EquipmentSlot.OFFHAND).copy();
        ItemStack namedA = VerificationApi.namedGeometryStack("geometry A");
        ItemStack namedB = VerificationApi.namedGeometryStack("geometry B");
        boolean sameStackShape = namedA.getItem() == namedB.getItem() && namedA.getCount() == namedB.getCount()
            && namedA.getDamageValue() == namedB.getDamageValue();
        boolean namesDoNotCombine = !ItemStack.isSameItemSameComponents(namedA, namedB);
        long offhandBefore = terrain.revision();
        long namedARevision;
        long namedBRevision;
        long offhandRestoredRevision;
        try {
            player.setItemSlot(EquipmentSlot.OFFHAND, namedA);
            namedARevision = terrain.revision();
            player.setItemSlot(EquipmentSlot.OFFHAND, namedB);
            namedBRevision = terrain.revision();
        } finally {
            player.setItemSlot(EquipmentSlot.OFFHAND, oldOffhand);
        }
        offhandRestoredRevision = terrain.revision();
        boolean offhandContextBumped = namedARevision > offhandBefore && namedBRevision > namedARevision
            && offhandRestoredRevision > namedBRevision;
        evidence.addProperty("namedOffhandSameItemCountDamage", sameStackShape);
        evidence.addProperty("namedOffhandNamesDoNotCombine", namesDoNotCombine);
        evidence.addProperty("namedOffhandARevision", namedARevision);
        evidence.addProperty("namedOffhandBRevision", namedBRevision);
        evidence.addProperty("offhandRestoredRevision", offhandRestoredRevision);
        evidence.addProperty("offhandContextRevisionBumped", offhandContextBumped);
        require(sameStackShape && namesDoNotCombine,
            "named offhand fixture did not preserve item/count/damage while changing components");
        require(offhandContextBumped, "offhand component changes did not bump revision without a terrain probe");

        runScaleCheck(client, terrain);
    }

    private void runScaleCheck(Minecraft client, GameTerrain terrain) {
        ScaleAccess scale;
        try {
            scale = scaleAccess(client.player);
        } catch (ScaleUnavailableException unavailable) {
            if (!VerificationApi.minecraftVersion().startsWith("1.20.")) {
                throw new IllegalStateException("scale attribute is unavailable on "
                    + VerificationApi.minecraftVersion() + ": " + unavailable.getMessage(), unavailable);
            }
            evidence.addProperty("scaleCheckStatus", "skipped");
            evidence.addProperty("scaleCheckSkipReason", "Minecraft 1.20 has no scale attribute: " + unavailable.getMessage());
            return;
        }

        try {
            long beforeValidScale = terrain.revision();
            scale.setBase(scale.originalBase * 0.9);
            long validScaleRevision = terrain.revision();
            StanceProbe validProbe = probe(terrain, 0, 64, 0);
            evidence.addProperty("validScaleRevision", validScaleRevision);
            evidence.addProperty("validScaleProbeLoaded", validProbe.loaded);
            require(validScaleRevision > beforeValidScale && validProbe.loaded,
                "valid 0.9 scale did not bump revision and keep the stance loaded");

            scale.setBase(scale.originalBase * 4.0);
            long oversizedScaleRevision = terrain.revision();
            StanceProbe oversizedProbe = probe(terrain, 0, 64, 0);
            evidence.addProperty("oversizedScaleRevision", oversizedScaleRevision);
            evidence.addProperty("oversizedScaleProbeLoaded", oversizedProbe.loaded);
            require(oversizedScaleRevision > validScaleRevision && !oversizedProbe.loaded,
                "oversized scale did not invalidate the native stance probe");
            evidence.addProperty("scaleCheckStatus", "passed");
        } finally {
            scale.setBase(scale.originalBase);
            terrain.revision();
        }
    }

    private void runWatchCapacityCheck(Minecraft client, LodekeeperConfig config) {
        VerificationContentInitializer.geometryBlockFull = true;
        GameTerrain terrain = new GameTerrain(client, config);
        terrain.beginSearch();
        StanceProbe ordinaryStart = probe(terrain, 0, 64, 0);
        evidence.addProperty("capacityStartProbeLoaded", ordinaryStart.loaded);
        evidence.addProperty("capacityStartProbeSupported", ordinaryStart.hasGroundSupport());
        require(ordinaryStart.loaded && ordinaryStart.hasGroundSupport(),
            "ordinary floor probe failed before dynamic watch capacity check");
        Planner beforeOverflow = new Planner(terrain, 0, 64, 0, Goal.exact(6, 64, 0),
            new Planner.Options().maxNodes(64).maxDrop(0));
        evidence.addProperty("capacityPlannerInitialStatus", beforeOverflow.getStatus().name());
        require(beforeOverflow.getStatus() == NavStatus.IN_PROGRESS,
            "planner was not active before dynamic watch saturation");

        int probeCount = 0;
        int acceptedCount = 0;
        int rejectingIndex = -1;
        for (int x = GRID_MIN_X; x <= GRID_MAX_X && rejectingIndex < 0; x++) {
            for (int z = GRID_MIN_Z; z <= GRID_MAX_Z; z++) {
                StanceProbe probe = probe(terrain, x, 64, z);
                int index = probeCount++;
                if (!probe.loaded) {
                    rejectingIndex = index;
                    break;
                }
                acceptedCount++;
            }
        }
        evidence.addProperty("capacityProbeLimit", GRID_CELL_COUNT);
        evidence.addProperty("capacityProbeCount", probeCount);
        evidence.addProperty("capacityAcceptedProbeCount", acceptedCount);
        evidence.addProperty("capacityRejectingIndex", rejectingIndex);
        boolean overflowRejected = rejectingIndex >= 0 && probeCount <= GRID_CELL_COUNT;
        evidence.addProperty("capacityOverflowRejected", overflowRejected);
        require(overflowRejected, "dynamic geometry watch did not reject within the bounded fixture grid");

        StanceProbe latchedOrdinary = probe(terrain, 0, 64, 0);
        evidence.addProperty("capacityLatchedOrdinaryProbeLoaded", latchedOrdinary.loaded);
        NavStatus beforeOverflowStatus = beforeOverflow.advance(1, Long.MAX_VALUE);
        evidence.addProperty("capacityExistingPlannerStatus", beforeOverflowStatus.name());
        evidence.addProperty("capacityExistingPlannerPathNull", beforeOverflow.getPath() == null);
        Planner afterOverflow = new Planner(terrain, 0, 64, 0, Goal.exact(6, 64, 0),
            new Planner.Options().maxNodes(64).maxDrop(0));
        evidence.addProperty("capacityNewPlannerStatus", afterOverflow.getStatus().name());
        evidence.addProperty("capacityNewPlannerPathNull", afterOverflow.getPath() == null);
        require(!latchedOrdinary.loaded, "ordinary floor probe remained loaded after watch saturation");
        require(beforeOverflowStatus == NavStatus.STALE && beforeOverflow.getPath() == null,
            "planner created before watch saturation did not become stale with no path");
        require(afterOverflow.getStatus() == NavStatus.NO_PATH && afterOverflow.getPath() == null,
            "planner created after watch saturation did not fail closed with no path");

        terrain.beginSearch();
        StanceProbe resetOrdinary = probe(terrain, 0, 64, 0);
        evidence.addProperty("capacityResetOrdinaryProbeLoaded", resetOrdinary.loaded);
        require(resetOrdinary.loaded, "beginSearch did not reset dynamic geometry watch saturation");
        evidence.addProperty("capacityCheckStatus", "passed");
    }

    private GridObservation observeGrid(Minecraft client) {
        if (client.level == null) return new GridObservation(0, 0);
        Block dynamicBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse(VerificationContentInitializer.DYNAMIC_COLLISION_ID));
        int loaded = 0;
        int matches = 0;
        for (int x = GRID_MIN_X; x <= GRID_MAX_X; x++) {
            for (int z = GRID_MIN_Z; z <= GRID_MAX_Z; z++) {
                if (client.level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
                loaded++;
                if (client.level.getBlockState(new BlockPos(x, 63, z)).getBlock() == dynamicBlock) matches++;
            }
        }
        return new GridObservation(loaded, matches);
    }

    private static StanceProbe probe(GameTerrain terrain, int x, int y, int z) {
        StanceProbe result = new StanceProbe();
        terrain.probeStance(x, y, z, result);
        return result;
    }

    private static ScaleAccess scaleAccess(Object player) {
        try {
            Class<?> attributesClass = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes");
            Object scaleAttribute = attributesClass.getField("SCALE").get(null);
            Method getAttribute = findOneArgumentMethod(player.getClass(), "getAttribute", scaleAttribute);
            Object instance = getAttribute.invoke(player, scaleAttribute);
            if (instance == null) throw new IllegalStateException("player has no scale attribute instance");
            Method getBase = instance.getClass().getMethod("getBaseValue");
            Method setBase = instance.getClass().getMethod("setBaseValue", double.class);
            return new ScaleAccess(instance, setBase, ((Number) getBase.invoke(instance)).doubleValue());
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException failure) {
            throw new ScaleUnavailableException(failure.toString());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("could not inspect scale attribute", failure);
        }
    }

    private static Method findOneArgumentMethod(Class<?> type, String name, Object argument) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(argument)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private record GridObservation(int loadedCells, int dynamicBlockCells) {}

    private record ScaleAccess(Object instance, Method setBaseMethod, double originalBase) {
        void setBase(double value) {
            try { setBaseMethod.invoke(instance, value); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("could not set scale base value", failure); }
        }
    }

    private static final class ScaleUnavailableException extends RuntimeException {
        private ScaleUnavailableException(String message) { super(message); }
    }
}
