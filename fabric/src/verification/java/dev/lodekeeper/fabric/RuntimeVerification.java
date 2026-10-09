package dev.lodekeeper.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import dev.lodekeeper.core.AcquisitionPlanner;
import dev.lodekeeper.core.AcquisitionSource;
import dev.lodekeeper.core.BlockedReason;
import dev.lodekeeper.core.CatalogSnapshot;
import dev.lodekeeper.core.InventorySnapshot;
import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.PlanResult;
import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.nav.LaunchApproach;
import dev.lodekeeper.navigation.kernel.OwnedKernelRuntime;
import dev.lodekeeper.navigation.kernel.OwnedMutationGuard;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.resource.DataConfiguration;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Optional dev-only end-to-end verifier. It is inert unless explicitly enabled with a JVM flag. */
public final class RuntimeVerification implements ClientModInitializer {
    private static final String ENABLE_PROPERTY = "lodekeeper.verify";
    private volatile Object nativePlayerPeer;
    private volatile MinecraftServer nativePlayerPeerServer;
    private volatile Map<String, String> nativePlayerPeerReceipt = Map.of();
    private volatile String nativePlayerPeerFailure;

    private static Object nativePlayerPeerApi(String name, Object... arguments) {
        try {
            for (Method method : VerificationApi.class.getDeclaredMethods())
                if (method.getName().equals(name) && method.getParameterCount() == arguments.length)
                    return method.invoke(null, arguments);
            throw new IllegalStateException("native player fixture unavailable for this artifact");
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw new IllegalStateException("native player fixture failed in " + name + "; "
                    + nativePlayerPeerCauseChain(cause), cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("native player fixture unavailable for this artifact", error);
        }
    }

    private static String nativePlayerPeerCauseChain(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (depth > 0) result.append(" caused by ");
            String type = cause.getClass().getName();
            result.append(type, 0, Math.min(type.length(), 128));
            String message = cause.getMessage();
            if (message != null) {
                result.append(": ").append(message.substring(0, Math.min(message.length(), 512))
                        .replace('\n', ' ').replace('\r', ' '));
                if (message.length() > 512) result.append(" [message truncated]");
            }
            cause = cause.getCause();
        }
        if (cause != null) result.append(" [cause chain truncated]");
        return result.toString();
    }

    private CompletableFuture<Object> prepareNativePlayerPeer(boolean fullRecipient) {
        MinecraftServer server = client.getServer();
        UUID expectedHost = playerId;
        if (server == null || expectedHost == null)
            return CompletableFuture.failedFuture(new IllegalStateException("native fixture requires the active local server player"));
        CompletableFuture<Object> scheduled = new CompletableFuture<>();
        server.execute(() -> {
            Object joined = null;
            try {
                if (nativePlayerPeer != null || nativePlayerPeerFailure != null)
                    throw new IllegalStateException("native fixture peer already exists or failed");
                var player = server.getPlayerManager().getPlayer(expectedHost);
                if (player == null) throw new IllegalStateException("native fixture host is unavailable");
                joined = nativePlayerPeerApi("joinNativePlayerPeer", player, server.getOverworld(), fullRecipient);
                nativePlayerPeerServer = server; nativePlayerPeer = joined;
                nativePlayerPeerReceipt = Map.of("hostUuid", expectedHost.toString());
                observeNativePlayerPeerServer(server);
                if (nativePlayerPeerFailure != null) throw new IllegalStateException(nativePlayerPeerFailure);
                scheduled.complete(joined);
            } catch (RuntimeException error) {
                if (joined != null) {
                    try { nativePlayerPeerApi("closeNativePlayerPeer", joined); }
                    catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                }
                if (nativePlayerPeer == joined) { nativePlayerPeer = null; nativePlayerPeerServer = null; }
                scheduled.completeExceptionally(error);
            }
        });
        return scheduled;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> trackNativePlayerPeer(Object handle) {
        return (Map<String, String>) nativePlayerPeerApi("trackNativePlayerPeer", handle, client);
    }

    private CompletableFuture<Void> moveNativePlayerPeer(double x, double y, double z) {
        Object expected = nativePlayerPeer;
        MinecraftServer server = nativePlayerPeerServer;
        if (expected == null || server == null)
            return CompletableFuture.failedFuture(new IllegalStateException("native fixture peer is unavailable"));
        CompletableFuture<Void> scheduled = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (nativePlayerPeer != expected || nativePlayerPeerServer != server)
                    throw new IllegalStateException("native fixture peer identity changed before movement");
                nativePlayerPeerApi("moveNativePlayerPeer", expected, x, y, z); scheduled.complete(null);
            } catch (RuntimeException error) { scheduled.completeExceptionally(error); }
        });
        return scheduled;
    }

    private CompletableFuture<Void> retireNativePlayerPeer() {
        Object expected = nativePlayerPeer;
        MinecraftServer server = nativePlayerPeerServer;
        if (expected == null || server == null) return nativePlayerPeerFailure == null
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException(nativePlayerPeerFailure));
        CompletableFuture<Void> scheduled = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (nativePlayerPeer != expected || nativePlayerPeerServer != server)
                    throw new IllegalStateException("native fixture peer identity changed before teardown");
                closeNativePlayerPeerServer(server);
                if (nativePlayerPeerFailure != null) throw new IllegalStateException(nativePlayerPeerFailure);
                scheduled.complete(null);
            } catch (RuntimeException error) { scheduled.completeExceptionally(error); }
        });
        return scheduled;
    }

    @SuppressWarnings("unchecked")
    private void observeNativePlayerPeerServer(MinecraftServer server) {
        Object current = nativePlayerPeer;
        if (current == null || nativePlayerPeerServer != server) return;
        try {
            UUID expectedHost = UUID.fromString(nativePlayerPeerReceipt.get("hostUuid"));
            var player = server.getPlayerManager().getPlayer(expectedHost);
            Map<String, String> observed = (Map<String, String>) nativePlayerPeerApi("observeNativePlayerPeer", current, player, server.getTicks());
            Map<String, String> receipt = new LinkedHashMap<>(observed);
            receipt.put("hostUuid", expectedHost.toString()); nativePlayerPeerReceipt = Map.copyOf(receipt);
        } catch (RuntimeException error) {
            nativePlayerPeerFailure = error.toString(); closeNativePlayerPeerServer(server);
        }
    }

    private void closeNativePlayerPeerServer(MinecraftServer server) {
        Object current = nativePlayerPeer;
        if (current == null || nativePlayerPeerServer != server) return;
        try {
            nativePlayerPeerApi("closeNativePlayerPeer", current);
            Map<String, String> receipt = new LinkedHashMap<>(nativePlayerPeerReceipt);
            receipt.put("peerClosed", "true"); nativePlayerPeerReceipt = Map.copyOf(receipt);
        } catch (RuntimeException error) { nativePlayerPeerFailure = error.toString(); }
        finally {
            if (nativePlayerPeer == current) { nativePlayerPeer = null; nativePlayerPeerServer = null; }
        }
    }

    private static final String COOPERATIVE_SCENARIO = System.getProperty("lodekeeper.verify.cooperativeScenario");
    private static final boolean COOPERATIVE_MODE = COOPERATIVE_SCENARIO != null && !"false".equals(COOPERATIVE_SCENARIO);
    private final JsonObject cooperativeEvidence = new JsonObject();
    private final Map<dev.lodekeeper.navigation.kernel.api.Settings.Setting<?>, Object> cooperativeOriginalSettings = new java.util.IdentityHashMap<>();
    private Object cooperativeOriginalInput, cooperativePeerHandle, cooperativeTaskIdentity;
    private CompletableFuture<Object> cooperativePeerJoin;
    private CompletableFuture<Void> cooperativePeerMove, cooperativePeerRetire;
    private int cooperativeCommandTick = -1, cooperativeMoves, cooperativeCompletionFenceTick = -1;
    private long cooperativeCompletionFenceRequest = -1;
    private long cooperativeCompletionFenceServerTick = -1;
    private int cooperativeOriginalSelected;
    private boolean cooperativeMoveAwaitingReceipt;
    private double cooperativeMoveExpectedX;
    private long cooperativeMoveServerFence, cooperativeMoveAckServerTick, cooperativeMoveAckRequest = -1;
    private int cooperativeMoveAckClientTick;
    private boolean cooperativeNativeRouteObserved, cooperativeMovementProfileObserved;

    private static boolean invalidCooperativeScenario() {
        if (!COOPERATIVE_MODE) return false;
        if (!List.of("goto", "follow").contains(COOPERATIVE_SCENARIO) || !BARITONE_MODE
                || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())) return true;
        for (String property : System.getProperties().stringPropertyNames()) {
            if (!property.startsWith("lodekeeper.verify.") || List.of("lodekeeper.verify.baritone",
                    "lodekeeper.verify.cooperativeScenario", "lodekeeper.verify.candidateSha256").contains(property)) continue;
            if (!"false".equals(System.getProperty(property))) return true;
        }
        return false;
    }

    private static final String ANIMAL_SCENARIO = System.getProperty("lodekeeper.verify.animalScenario");
    private static final boolean ANIMAL_MODE = ANIMAL_SCENARIO != null && !"false".equals(ANIMAL_SCENARIO);
    private static final String ANIMAL_NO_SCAFFOLD_PROPERTY = System.getProperty("lodekeeper.verify.animalNoScaffold");
    private static final boolean ANIMAL_NO_SCAFFOLD = "true".equals(ANIMAL_NO_SCAFFOLD_PROPERTY);
    private boolean nativeAnimalApproachObserved, nativeAnimalGroundedPickupObserved;
    private int nativeAnimalShearedAtStop = -1;
    private volatile Object nativeAnimalFixture;
    private volatile Map<String, String> nativeAnimalPublishedReceipt = Map.of();
    private Map<String, String> nativeAnimalInitialReceipt = Map.of();
    private final JsonObject nativeAnimalEvidence = new JsonObject();
    private int nativeAnimalStopTick = -1, nativeAnimalStopSends = -1;
    private Object nativeAnimalOriginalInput, nativeAnimalTaskIdentity;
    private int nativeAnimalOriginalSelectedSlot = -1;
    private long nativeAnimalStopJob;
    private UUID nativeAnimalStopTarget;
    private final Map<dev.lodekeeper.navigation.kernel.api.Settings.Setting<?>, Object> nativeAnimalOriginalSettings = new LinkedHashMap<>();
    private String nativeAnimalClaim;
    private CompletableFuture<Void> nativeAnimalSubmersion;
    private boolean nativeAnimalAirRouteObserved, nativeAnimalAirMovementObserved, nativeAnimalAirReadyObserved;
    private int nativeAnimalPendingSendTick = -1;

    private static Object nativeAnimalApi(String name, Object... arguments) {
        try {
            for (Method method : VerificationApi.class.getDeclaredMethods())
                if (method.getName().equals(name)) return method.invoke(null, arguments);
            throw new IllegalStateException("native animal fixture unavailable for this artifact");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("native animal fixture failed in " + name, failure);
        }
    }
    private static boolean invalidAnimalNoScaffold() {
        return ANIMAL_NO_SCAFFOLD_PROPERTY != null && !"false".equals(ANIMAL_NO_SCAFFOLD_PROPERTY)
                && (!ANIMAL_NO_SCAFFOLD || !"white_wool_inventory".equals(ANIMAL_SCENARIO)
                    || !BARITONE_MODE || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion()));
    }

    private static final String COOKING_ORIGINAL_SLOT = System.getProperty("lodekeeper.verify.cookingOriginalSlot");

    private static boolean invalidAnimalScenario() {
        if (COOKING_ORIGINAL_SLOT != null && (!"cooking".equals(ANIMAL_SCENARIO)
                || !List.of("3", "5").contains(COOKING_ORIGINAL_SLOT) || !BARITONE_MODE
                || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion()))) return true;
        if (!ANIMAL_MODE) return false;
        if (!List.of("beef", "beef_partial", "porkchop", "mutton", "leather", "cooking", "white_wool", "red_wool",
                "white_wool_inventory", "wrong_components", "protected", "stop_after_interaction", "air_pending_attack", "air_pending_transfer").contains(ANIMAL_SCENARIO)
                || !BARITONE_MODE || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())) return true;
        for (String property : System.getProperties().stringPropertyNames()) {
            if (!property.startsWith("lodekeeper.verify.") || List.of("lodekeeper.verify.baritone",
                    "lodekeeper.verify.animalScenario", "lodekeeper.verify.animalNoScaffold", "lodekeeper.verify.cookingOriginalSlot", "lodekeeper.verify.candidateSha256").contains(property)) continue;
            if (!"false".equals(System.getProperty(property))) return true;
        }
        return false;
    }


    private static final String SHIELD_SCENARIO = System.getProperty("lodekeeper.verify.shieldScenario");
    private static final boolean SHIELD_MODE = SHIELD_SCENARIO != null && !"false".equals(SHIELD_SCENARIO);
    private enum ShieldPhase { NONE, RESERVING, RUNNING, MANUAL_WAIT, MANUAL_RELEASE, FINISHED }
    private ShieldPhase shieldPhase = ShieldPhase.NONE;
    private volatile Object shieldFixture;
    private volatile Map<String, String> shieldPublishedReceipt = Map.of();
    private Map<String, String> shieldServerReceipt = Map.of();
    private Map<String, String> shieldInitialReceipt = Map.of(), shieldPreparationAttributes = Map.of();
    private boolean shieldTaskObserved, shieldQueueObserved, shieldOwnedUseObserved, shieldManualPreserved;
    private int shieldManualTick = -1, shieldManualServerTick = -1;
    private Object shieldManualTaskIdentity;
    private long shieldSourceSequence, shieldOffhandSequence;
    private final JsonObject shieldEvidence = new JsonObject();

    private static boolean invalidShieldScenario() {
        if (SHIELD_SCENARIO == null || "false".equals(SHIELD_SCENARIO)) return false;
        if (!List.of("default", "off", "spare", "queued", "iron_short", "planks_short", "worn", "occupied", "manual").contains(SHIELD_SCENARIO)) return true;
        if (!BARITONE_MODE || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())) return true;
        for (String property : System.getProperties().stringPropertyNames()) {
            if (!property.startsWith("lodekeeper.verify.") || List.of("lodekeeper.verify.baritone", "lodekeeper.verify.shieldScenario", "lodekeeper.verify.candidateSha256").contains(property)) continue;
            if (!"false".equals(System.getProperty(property))) return true;
        }
        return false;
    }

    private static Object shieldApi(String name, Object... arguments) {
        try {
            for (Method method : VerificationApi.class.getDeclaredMethods())
                if (method.getName().equals(name)) return method.invoke(null, arguments);
            throw new IllegalStateException("shield native fixture unavailable in this Minecraft profile");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("shield native fixture failed in " + name, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private void observeShieldServer(ServerPlayerEntity player, int serverTick) {
        if (shieldFixture == null) return;
        shieldApi("observeShieldScenario", shieldFixture, player, serverTick);
        shieldPublishedReceipt = (Map<String, String>) shieldApi("shieldScenarioReceipt", shieldFixture, player, serverTick);
    }

    private int shieldCount(String key) { return Integer.parseInt(shieldServerReceipt.getOrDefault(key, "-1")); }
    private boolean shieldReceiptTrue(String key) { return "true".equals(shieldServerReceipt.get(key)); }

    private void startShieldScenario() {
        shieldInitialReceipt = Map.copyOf(shieldServerReceipt);
        activeCase = "shield_" + SHIELD_SCENARIO;
        activeItem = "minecraft:bucket";
        activeCount = 1;
        activeRequiresEmpty = activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        activeTableOpeningsAtStart = serverTableOpenings;
        beginCaseClock();
        if (client.player.playerScreenHandler instanceof OwnedClickReceipts.Receipt receipt) {
            shieldSourceSequence = receipt.lodekeeper$slotSequence(20);
            shieldOffhandSequence = receipt.lodekeeper$slotSequence(45);
        }
        shieldPhase = ShieldPhase.RESERVING;
        state = State.PREPARED_SAFETY;
        sendCommand("!lk maintain iron_ingot 2");
        sendCommand("!lk maintain oak_planks 4");
    }

    private void tickShieldScenario() {
        shieldServerReceipt = shieldPublishedReceipt;
        String status = requireEngine().status();
        if (clientTicks - caseStartedAtTick > 1_200) { fail("shield scenario timed out: " + SHIELD_SCENARIO + "; " + status); return; }
        if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
        if (shieldPhase == ShieldPhase.RESERVING) {
            if (!status.startsWith("idle") || !status.endsWith("0 maintenance queued")
                    || !maintainedReservationObserved("minecraft:iron_ingot", 2)
                    || !maintainedReservationObserved("minecraft:oak_planks", 4)) return;
            if (!shieldServerReceipt.equals(shieldInitialReceipt)) {
                for (String key : List.of("minecraft:iron_ingot", "minecraft:oak_planks", "craftedShields", "craftedBuckets"))
                    if (!shieldInitialReceipt.get(key).equals(shieldServerReceipt.get(key))) { fail("maintain changed shield fixture stock before foreground work"); return; }
            }
            preparedMaintenanceReservationObservedBeforeForeground = true;
            preparedMaintenanceQueueEmptyBeforeForeground = true;
            shieldPhase = ShieldPhase.RUNNING;
            sendCommand("!lk get bucket 1");
            if ("queued".equals(SHIELD_SCENARIO)) sendCommand("!lk get shears 1");
            return;
        }
        if (requireEngine().diagnosticTaskIdentity() != null) shieldTaskObserved = true;
        try {
            Field stepField = findField(AutomationEngine.class, "step"), queueField = findField(AutomationEngine.class, "queue");
            if (stepField == null || queueField == null) throw new IllegalStateException("shield planning receipt fields unavailable");
            Object value = stepField.get(requireEngine());
            if (value instanceof PlanStep step && "true".equals(step.attributes().get("shieldPreparation")))
                shieldPreparationAttributes = Map.copyOf(step.attributes());
            if (queueField.get(requireEngine()) instanceof java.util.Collection<?> queue && !queue.isEmpty()) shieldQueueObserved = true;
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException("could not capture shield plan reservations", exception); }
        boolean owned = ShieldController.isHoldingUse();
        if (owned) shieldOwnedUseObserved = true;
        if ("manual".equals(SHIELD_SCENARIO) && shieldPhase == ShieldPhase.RUNNING && owned && shieldCount("blockedDamageEvents") > 0) {
            client.options.useKey.setPressed(true);
            shieldManualTaskIdentity = requireEngine().diagnosticTaskIdentity();
            shieldManualTick = clientTicks;
            shieldManualServerTick = shieldCount("serverTick");
            shieldPhase = ShieldPhase.MANUAL_WAIT;
            return;
        }
        if (shieldPhase == ShieldPhase.MANUAL_WAIT) {
            if (clientTicks - shieldManualTick > 20) { fail("manual use did not interrupt owned shield hold within twenty ticks"); return; }
            if (!status.startsWith("paused") || shieldCount("serverTick") <= shieldManualServerTick) return;
            shieldManualPreserved = client.options.useKey.isPressed() && !owned && client.player.isUsingItem() && shieldReceiptTrue("usingItem")
                && shieldReceiptTrue("cursorEmpty") && shieldCount("craftedShields") == 0
                && shieldManualTaskIdentity != null && requireEngine().diagnosticTaskIdentity() == shieldManualTaskIdentity;
            if (!shieldManualPreserved) { fail("manual takeover lost native user shield use, key state, or cursor ownership"); return; }
            client.options.useKey.setPressed(false);
            client.interactionManager.stopUsingItem(client.player);
            shieldManualServerTick = shieldCount("serverTick");
            shieldPhase = ShieldPhase.MANUAL_RELEASE;
            return;
        }
        if (shieldPhase == ShieldPhase.MANUAL_RELEASE) {
            if (shieldCount("serverTick") <= shieldManualServerTick || shieldReceiptTrue("usingItem") || owned) return;
            boolean passed = shieldTaskObserved && shieldManualPreserved && baritoneNavigationStopped()
                && maintainedReservationObserved("minecraft:iron_ingot", 2) && maintainedReservationObserved("minecraft:oak_planks", 4)
                && shieldCount("minecraft:iron_ingot") == Integer.parseInt(shieldInitialReceipt.get("initialIron"))
                && shieldCount("minecraft:oak_planks") == Integer.parseInt(shieldInitialReceipt.get("initialPlanks"))
                && shieldCount("craftedBuckets") == 0 && shieldCount("craftedShields") == 0
                && shieldReceiptTrue("cursorEmpty") && shieldCount("blockedDamageEvents") > 0
                && "minecraft:shield".equals(shieldServerReceipt.get("offhandItem")) && "empty".equals(shieldServerReceipt.get("sourceItem"));
            activeCount = 0;
            preparedMaintenanceReservationPresentAtCompletion = passed;
            finishShieldScenario(passed, "native shield blocking yielded to the held manual use key; the user hold survived the pause and its explicit release reached the server");
            return;
        }
        if (status.startsWith("paused")) { fail("shield scenario paused: " + status); return; }
        if (!status.startsWith("idle") || !status.endsWith("0 maintenance queued") || !baritoneNavigationStopped()
                || shieldCount("craftedBuckets") < 1) return;
        boolean craft = List.of("spare", "queued").contains(SHIELD_SCENARIO);
        boolean worn = "worn".equals(SHIELD_SCENARIO), occupied = "occupied".equals(SHIELD_SCENARIO);
        int initialIron = Integer.parseInt(shieldInitialReceipt.get("initialIron")), initialPlanks = Integer.parseInt(shieldInitialReceipt.get("initialPlanks"));
        int queuedCost = "queued".equals(SHIELD_SCENARIO) ? 2 : 0;
        boolean passed = shieldTaskObserved && shieldCount("minecraft:bucket") == 1 && shieldCount("craftedBuckets") == 1
                && shieldCount("craftedShields") == (craft ? 1 : 0)
                && shieldCount("minecraft:iron_ingot") == initialIron - 3 - queuedCost - (craft ? 1 : 0)
                && shieldCount("minecraft:oak_planks") == initialPlanks - (craft ? 6 : 0)
                && shieldCount("minecraft:shield") == (craft || worn || occupied ? 1 : 0)
                && shieldReceiptTrue("cursorEmpty") && shieldReceiptTrue("tablePresent") && !shieldReceiptTrue("usingItem") && !owned
                && maintainedReservationObserved("minecraft:iron_ingot", 2) && maintainedReservationObserved("minecraft:oak_planks", 4)
                && serverTableOpenings > activeTableOpeningsAtStart;
        if (craft) passed &= "4".equals(shieldPreparationAttributes.get("shieldIronFloor"))
                && "9".equals(shieldPreparationAttributes.get("shieldPlankFloor"))
                && Integer.parseInt(shieldPreparationAttributes.getOrDefault("shieldReserved:minecraft:iron_ingot", "0")) >= 5 + queuedCost
                && Integer.parseInt(shieldPreparationAttributes.getOrDefault("shieldReserved:minecraft:oak_planks", "0")) >= 4;
        if ("queued".equals(SHIELD_SCENARIO)) passed &= shieldQueueObserved && shieldCount("minecraft:shears") == 1;
        if (worn) {
            boolean swapReceipts = client.player.playerScreenHandler instanceof OwnedClickReceipts.Receipt receipt
                && receipt.lodekeeper$slotSequence(20) > shieldSourceSequence + 1
                && receipt.lodekeeper$slotSequence(45) > shieldOffhandSequence + 1;
            passed &= swapReceipts && shieldOwnedUseObserved && shieldCount("equipServerTick") >= 0
                && shieldCount("restoreServerTick") > shieldCount("equipServerTick") && shieldCount("nativeUseServerTicks") >= 6
                && shieldCount("blockedDamageEvents") > 0 && Float.parseFloat(shieldServerReceipt.get("lastBaseDamage")) > 0
                && "20.0".equals(shieldServerReceipt.get("minimumHealth")) && !shieldReceiptTrue("zombieAlive")
                && "minecraft:shield".equals(shieldServerReceipt.get("sourceItem")) && shieldCount("sourceCount") == 1
                && shieldCount("sourceDamage") > 200 && shieldCount("sourceDamage") < 236
                && "empty".equals(shieldServerReceipt.get("offhandItem"));
        }
        if (occupied) passed &= !shieldOwnedUseObserved && shieldCount("nativeUseServerTicks") == 0
                && shieldCount("equipServerTick") == -1 && shieldCount("sourceDamage") == 200
                && "minecraft:shield".equals(shieldServerReceipt.get("sourceItem"))
                && "minecraft:torch".equals(shieldServerReceipt.get("offhandItem")) && shieldCount("offhandCount") == 8
                && !shieldReceiptTrue("zombieAlive");
        preparedMaintenanceReservationPresentAtCompletion = passed;
        finishShieldScenario(passed, "server native crafted stats and exact iron/plank costs retained maintained stock and custom keep floors; foreground bucket completed with cursor clear and shield hold released");
    }

    private void recordShieldEvidence() {
        shieldEvidence.addProperty("scenario", SHIELD_SCENARIO);
        shieldEvidence.addProperty("autoUseShield", requireEngine().config.autoUseShield);
        shieldEvidence.addProperty("autoCraftShield", requireEngine().config.autoCraftShield);
        shieldEvidence.addProperty("ironKeepFloor", requireEngine().config.shieldIronReserve);
        shieldEvidence.addProperty("plankKeepFloor", requireEngine().config.shieldPlankReserve);
        shieldEvidence.add("initialServerReceipt", new GsonBuilder().create().toJsonTree(shieldInitialReceipt));
        shieldEvidence.add("finalServerReceipt", new GsonBuilder().create().toJsonTree(shieldServerReceipt));
        shieldEvidence.add("preparationAttributes", new GsonBuilder().create().toJsonTree(shieldPreparationAttributes));
        shieldEvidence.addProperty("foregroundTaskObserved", shieldTaskObserved);
        shieldEvidence.addProperty("queuedTaskObserved", shieldQueueObserved);
        shieldEvidence.addProperty("ownedUseObserved", shieldOwnedUseObserved);
        shieldEvidence.addProperty("manualUsePreserved", shieldManualPreserved);
        shieldEvidence.addProperty("ownedHoldReleased", !ShieldController.isHoldingUse());
        shieldEvidence.addProperty("nativeNavigationStopped", baritoneNavigationStopped());
    }

    private void finishShieldScenario(boolean passed, String detail) {
        recordShieldEvidence();
        shieldEvidence.addProperty("passed", passed);
        if (!passed) { fail("shield native scenario failed its required receipts: " + SHIELD_SCENARIO + "; " + shieldServerReceipt + "; plan=" + shieldPreparationAttributes); return; }
        addResult(true, shieldCount("minecraft:bucket"), detail, capture(activeCase));
        shieldPhase = ShieldPhase.FINISHED;
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private static final boolean BARITONE_MODE = Boolean.getBoolean("lodekeeper.verify.baritone");
    private static final boolean MINING_REQUEST_LIMIT_MODE = Boolean.getBoolean("lodekeeper.verify.miningRequestLimit");
    private static final boolean MINING_ZERO_YIELD_MODE = Boolean.getBoolean("lodekeeper.verify.miningZeroYield");
    private boolean baritoneMiningObserved;

    private static final boolean NEARBY_WOOD_MODE = Boolean.getBoolean("lodekeeper.verify.nearbyWood");
    private static final boolean GEOMETRY_EPOCH_MODE = Boolean.getBoolean("lodekeeper.verify.geometryEpoch");
    private GeometryEpochVerification geometryEpoch;
    private boolean geometryEpochComplete;
    private static final String NEARBY_WOOD_GOAL = System.getProperty("lodekeeper.verify.nearbyWoodGoal", "wood");
    private static final String NEARBY_WOOD_TERRAIN = System.getProperty("lodekeeper.verify.nearbyWoodTerrain", "flat");
    private static final boolean NEARBY_WOOD_LOCAL_DECOY_MODE = NEARBY_WOOD_MODE
            && "local_decoy".equals(NEARBY_WOOD_TERRAIN);
    private static final boolean MEADOW_BENCHMARK = NEARBY_WOOD_MODE
            && "meadow".equals(NEARBY_WOOD_TERRAIN);
    private JsonArray routeBenchmark;
    private static final boolean EXPLORATION_MODE = Boolean.getBoolean("lodekeeper.verify.exploration");
    private static final boolean DIAMOND_BOOTSTRAP_MODE = Boolean.getBoolean("lodekeeper.verify.diamondBoots");
    private static final boolean IRON_PICKAXE_MODE = Boolean.getBoolean("lodekeeper.verify.ironPickaxe");
    private static final boolean IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE =
        Boolean.getBoolean("lodekeeper.verify.ironPickaxeEmptyDistantWood");
    private static final boolean COAL_RECOVERY_MODE = Boolean.getBoolean("lodekeeper.verify.coalRecovery");
    private static final String COAL_START_SURFACE = System.getProperty("lodekeeper.verify.coalStartSurface", "full");
    private static final boolean COAL_RAISED_FULL_DROP_MODE = "raised_full".equals(COAL_START_SURFACE);
    private static final String NAVIGATION_COURSE = System.getProperty("lodekeeper.verify.navigationCourse");
    private static final boolean MIXED_NAVIGATION_COURSE = "mixed".equals(NAVIGATION_COURSE);
    private double coalInitialServerFeetY = Double.NaN;
    private CoalDropStartingPosition coalDropInitialServerPosition;
    private CoalDropEdge coalDropCompletedEdge;
    private volatile CoalDropServerCheckpoint coalDropServerCheckpoint;
    private volatile boolean coalDropCommandStarted;
    private static final boolean BULK_WOOD_MODE = Boolean.getBoolean("lodekeeper.verify.bulkWood");
    private static final boolean WOOD_TOOLS_MODE = Boolean.getBoolean("lodekeeper.verify.woodTools");
    private static final String COOKING_STATION_MODE = System.getProperty("lodekeeper.verify.cookingStation");
    private static final boolean COOKING_MODE = COOKING_STATION_MODE != null;
    private static final boolean STONECUTTING_MODE = Boolean.getBoolean("lodekeeper.verify.stonecutting");
    private static final boolean STONECUTTING_DRAIN_MODE = Boolean.getBoolean("lodekeeper.verify.stonecuttingDrain");
    private static final boolean PROCESSING_MODE = COOKING_MODE || STONECUTTING_MODE;
    private static final String PROCESSING_STATION_MODE = STONECUTTING_MODE ? "stonecutter" : COOKING_STATION_MODE;
    private static final String PREPARED_SAFETY_MODE = System.getProperty("lodekeeper.verify.preparedSafety");
    private static final String STATION_ROOM_TUNNEL_PROPERTY = System.getProperty("lodekeeper.verify.stationRoomTunnel");
    private static final boolean STATION_ROOM_TUNNEL_MODE = "true".equals(STATION_ROOM_TUNNEL_PROPERTY);
    private static final boolean STATION_ROOM_APPROACH_MODE = "approach".equals(STATION_ROOM_TUNNEL_PROPERTY);
    private static final String THREAT_WATER_RETREAT_PROPERTY = System.getProperty("lodekeeper.verify.threatWaterRetreat");
    private static final boolean THREAT_WATER_RETREAT_MODE = Boolean.getBoolean("lodekeeper.verify.threatWaterRetreat");
    private static final String THREAT_CONTACT_PROPERTY = System.getProperty("lodekeeper.verify.threatContact");
    private static final boolean THREAT_CONTACT_MODE = "true".equals(THREAT_CONTACT_PROPERTY);
    private static final String CONTACT_LOW_HEALTH_PROPERTY = System.getProperty("lodekeeper.verify.threatContactLowHealth");
    private static final boolean CONTACT_LOW_HEALTH_MODE = "true".equals(CONTACT_LOW_HEALTH_PROPERTY);
    private static final String CONTACT_MANUAL_INPUT_PROPERTY = System.getProperty("lodekeeper.verify.threatContactManualInput");
    private static final boolean CONTACT_MANUAL_INPUT_MODE = "true".equals(CONTACT_MANUAL_INPUT_PROPERTY);
    private static final String THREAT_CREEPER_CONTACT_PROPERTY = System.getProperty("lodekeeper.verify.threatCreeperContact");
    private static final boolean THREAT_CREEPER_CONTACT_MODE = "true".equals(THREAT_CREEPER_CONTACT_PROPERTY);
    private static final String THREAT_STAIRCASE_PROPERTY = System.getProperty("lodekeeper.verify.threatStaircase");
    private static final boolean THREAT_STAIRCASE_MODE = "true".equals(THREAT_STAIRCASE_PROPERTY);
    private static final int PREPARED_SAFETY_SETUP_TIMEOUT_TICKS = 400;
    private static final int PREPARED_SAFETY_CASE_TIMEOUT_TICKS = 1_200;
    private static final String IRON_PICKAXE_ID = "minecraft:iron_pickaxe";
    private static final String OAK_LOG_ID = "minecraft:oak_log";
    private static final String WOODEN_AXE_ID = "minecraft:wooden_axe";
    private static final String RAW_PORKCHOP_ID = "minecraft:porkchop";
    private static final String COOKED_PORKCHOP_ID = "minecraft:cooked_porkchop";
    private static final String RAW_IRON_ID = "minecraft:raw_iron";
    private static final String IRON_INGOT_ID = "minecraft:iron_ingot";
    private static final int STONECUTTING_DRAIN_COMMAND_TARGET = 144;
    private static final int IRON_PICKAXE_PROBE_TIMEOUT_TICKS = 200;
    private static final int IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT = 4;
    private static final int IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X = 20;
    private static final int IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT = 8;
    private static final int MAX_NEARBY_WOOD_WALK_OBSERVATIONS = 64;
    private static final int MAX_NEARBY_WOOD_LAUNCH_HANDOFF_OBSERVATIONS = 32;
    private static final double FORCED_PREPHYSICS_HANDOFF_SPEED = .015;
    private static final int NEARBY_WOOD_LOCAL_DECOY_TIMEOUT_TICKS = 200;
    private static final Field ENGINE_MOVEMENT_FIELD = findField(AutomationEngine.class, "movement");
    private static final Field MAINTAINED_DEMAND_FIELD = findField(AutomationEngine.class, "maintained");
    private static final Field REQUESTED_BACKFILL_STOCK_FIELD = findField(AutomationEngine.class, "requestedBackfillStock");
    private static final Field ENGINE_TARGET_FIELD = findField(AutomationEngine.class, "target");
    private static final Field ENGINE_GATHER_MINE_TARGET_FIELD = findField(AutomationEngine.class, "gatherMineTarget");
    private static final Field MOVEMENT_PATH_FIELD = findField("dev.lodekeeper.fabric.MovementController", "path");
    private static final Field MOVEMENT_PATH_INDEX_FIELD = findField("dev.lodekeeper.fabric.MovementController", "pathIndex");
    private static final Field MOVEMENT_VALIDATED_PATH_INDEX_FIELD = findField("dev.lodekeeper.fabric.MovementController", "validatedPathIndex");
    private static final Field MOVEMENT_INPUT_FIELD = findField("dev.lodekeeper.fabric.MovementController", "input");
    private static final Field BOT_INPUT_FORWARD_FIELD = findField("dev.lodekeeper.fabric.BotInput", "forward");
    private static final boolean CONFIG_ROUND_TRIP_MODE = Boolean.getBoolean("lodekeeper.verify.configRoundTrip");
    private static final boolean SETTINGS_UI_MODE = Boolean.getBoolean("lodekeeper.verify.settingsUi");
    private static final boolean WORLD_POLICY_MODE = Boolean.getBoolean("lodekeeper.verify.worldPolicy");
    private JsonObject configRoundTripReceipt;
    private NavigationSettingsVerification navigationSettings;
    private JsonObject navigationSettingsReceipt;
    private SettingsUiVerification settingsUiVerification;
    private NativeSettingsUiAccess settingsUiAccess;
    private boolean settingsUiChatEntryConfirmed;
    private String settingsUiReceipt;
    private String settingsUiFinalScreenshot;
    private int settingsUiFinalCaptureTick = -1;
    private final JsonObject settingsUiScreenshots = new JsonObject();
    private WorldPolicyPhase worldPolicyPhase = WorldPolicyPhase.NONE;
    private int worldPolicyPhaseStartedAtTick;
    private int worldPolicyFurnaceOpeningsAtStart;
    private int worldPolicyTableOpeningsAtStart;
    private int worldPolicyLastRecordedServerTick = -1;
    private Path worldPolicyClaimsPath;
    private byte[] worldPolicyOriginalClaims;
    private boolean worldPolicyOriginalClaimsExisted;
    private boolean worldPolicyClaimsBackupTaken;
    private boolean worldPolicyClaimsRestored;
    private boolean worldPolicyStationSetupComplete;
    private boolean worldPolicyTableSetupComplete;
    private boolean worldPolicyBackfillSetupComplete;
    private boolean worldPolicyBackfillSurplusSetupComplete;
    private boolean worldPolicyBackfillSurplusStartReceiptAdded;
    private boolean worldPolicyOriginalBackfill;
    private boolean worldPolicyOriginalEquivalentStone;
    private boolean worldPolicyOriginalBackfillCaptured;
    private boolean worldPolicyConfigRestored;
    private int worldPolicyNoSurplusObservedAtTick = -1;
    private int worldPolicyBackfillSurplusStartedAtTick = -1;
    private int worldPolicyBackfillPlacementStartedAtTick = -1;
    private String worldPolicyBackfillPlayerPositionBeforeRelocation = "unknown";
    private Map<String, String> worldPolicyPlacementBaseline = Map.of();
    private JsonObject worldPolicyEvidence;
    private final JsonArray worldPolicyServerObservations = new JsonArray();
    private boolean resourceInitiallyLoaded;
    private static final int MAX_RUN_TICKS = COOKING_MODE ? 10_000 : MINING_REQUEST_LIMIT_MODE ? 7_200 : 6_000;
    private static final long MAX_RUN_WALL_NANOS = COOKING_MODE ? 500_000_000_000L
        : MINING_REQUEST_LIMIT_MODE ? 360_000_000_000L : 300_000_000_000L;
    private static final int OBSERVE_EVERY_TICKS = 20;
    private static final int FIXTURE_FLOOR_Y = 63;
    private static final int PLAYER_Y = FIXTURE_FLOOR_Y + 1;
    private static final BlockPos WORLD_POLICY_CLAIM_MIN = new BlockPos(3, 64, 1);
    private static final BlockPos WORLD_POLICY_CLAIM_MAX = new BlockPos(8, 66, 7);
    private static final BlockPos WORLD_POLICY_TORCH = new BlockPos(6, 64, 4);
    private static final BlockPos WORLD_POLICY_SUPPORT = WORLD_POLICY_TORCH.down();
    private static final BlockPos WORLD_POLICY_PREFERRED_FURNACE = new BlockPos(4, 64, 5);
    private static final BlockPos WORLD_POLICY_ORDINARY_FURNACE = new BlockPos(1, 64, 1);
    private static final BlockPos WORLD_POLICY_PREFERRED_TABLE = new BlockPos(6, 64, 6);
    private static final BlockPos WORLD_POLICY_ORDINARY_TABLE = new BlockPos(1, 64, 2);
    private static final BlockPos WORLD_POLICY_BACKFILL_TARGET = new BlockPos(1, 64, 3);
    private static final double WORLD_POLICY_BACKFILL_PREPARED_PLAYER_X = 3.5;
    private static final double WORLD_POLICY_BACKFILL_PREPARED_PLAYER_Y = PLAYER_Y;
    private static final double WORLD_POLICY_BACKFILL_PREPARED_PLAYER_Z = 3.5;
    private static final List<WorldPolicyFace> WORLD_POLICY_FACES = List.of(
        new WorldPolicyFace("min_x", new BlockPos(3, 65, 4)),
        new WorldPolicyFace("max_x", new BlockPos(8, 65, 4)),
        new WorldPolicyFace("min_y", new BlockPos(5, 64, 4)),
        new WorldPolicyFace("max_y", new BlockPos(5, 66, 4)),
        new WorldPolicyFace("min_z", new BlockPos(5, 65, 1)),
        new WorldPolicyFace("max_z", new BlockPos(5, 65, 7)));
    private static final List<WorldPolicyPlacementProbe> WORLD_POLICY_PLACEMENT_PROBES = List.of(
        new WorldPolicyPlacementProbe("min_x", new BlockPos(2, 65, 4), Direction.EAST, new BlockPos(3, 65, 4)),
        new WorldPolicyPlacementProbe("max_x", new BlockPos(9, 65, 4), Direction.WEST, new BlockPos(8, 65, 4)),
        new WorldPolicyPlacementProbe("min_y", new BlockPos(5, 63, 4), Direction.UP, new BlockPos(5, 64, 4)),
        new WorldPolicyPlacementProbe("max_y", new BlockPos(5, 67, 4), Direction.DOWN, new BlockPos(5, 66, 4)),
        new WorldPolicyPlacementProbe("min_z", new BlockPos(5, 65, 0), Direction.SOUTH, new BlockPos(5, 65, 1)),
        new WorldPolicyPlacementProbe("max_z", new BlockPos(5, 65, 8), Direction.NORTH, new BlockPos(5, 65, 7)),
        new WorldPolicyPlacementProbe("inside", new BlockPos(5, 64, 5), Direction.UP, new BlockPos(5, 65, 5)));
    private static final WorldPolicyPlacementProbe WORLD_POLICY_OUTSIDE_PLACEMENT_PROBE =
        new WorldPolicyPlacementProbe("outside_control", new BlockPos(1, 63, 3), Direction.UP, WORLD_POLICY_BACKFILL_TARGET);
    private static final BlockPos NEARBY_WOOD_LOCAL_VISIBLE_LOG = new BlockPos(3, PLAYER_Y, 0);
    private static final BlockPos NEARBY_WOOD_LOCAL_DECOY_LOG = new BlockPos(-6, PLAYER_Y, -6);
    private static final List<BlockPos> NEARBY_WOOD_LOCAL_DECOY_SHELL = List.of(
        NEARBY_WOOD_LOCAL_DECOY_LOG.down(), NEARBY_WOOD_LOCAL_DECOY_LOG.up(),
        NEARBY_WOOD_LOCAL_DECOY_LOG.north(), NEARBY_WOOD_LOCAL_DECOY_LOG.south(),
        NEARBY_WOOD_LOCAL_DECOY_LOG.east(), NEARBY_WOOD_LOCAL_DECOY_LOG.west());
    private static final BlockPos COAL_RECOVERY_ENCASED_ORE = new BlockPos(6, PLAYER_Y, 2);
    private static final BlockPos COAL_RECOVERY_ACCESSIBLE_ORE = new BlockPos(16, PLAYER_Y, 2);
    private static final List<CoalNavigationCheckpoint> COAL_NAVIGATION_CHECKPOINTS = List.of(
        new CoalNavigationCheckpoint(1, 64 * 16 + 8), new CoalNavigationCheckpoint(2, 65 * 16),
        new CoalNavigationCheckpoint(4, 64 * 16 + 15), new CoalNavigationCheckpoint(5, 64 * 16 + 15),
        new CoalNavigationCheckpoint(6, 64 * 16 + 8), new CoalNavigationCheckpoint(7, 65 * 16),
        new CoalNavigationCheckpoint(8, 65 * 16 + 2), new CoalNavigationCheckpoint(9, 65 * 16 + 6),
        new CoalNavigationCheckpoint(10, 65 * 16 + 14), new CoalNavigationCheckpoint(11, 66 * 16),
        new CoalNavigationCheckpoint(12, 67 * 16), new CoalNavigationCheckpoint(13, 67 * 16),
        new CoalNavigationCheckpoint(14, 66 * 16 + 15), new CoalNavigationCheckpoint(15, 67 * 16),
        new CoalNavigationCheckpoint(16, 68 * 16));

    private enum State { DISABLED, OPENING_WORLD, WAITING_FOR_WORLD, SETTINGS_UI, SETTING_UP, WAITING_FOR_EMPTY_SNAPSHOT, WORLD_POLICY,
        GATHERING_WOOD, CRAFTING_TABLE, CRAFTING_STICKS, CRAFTING_WOOD_PICK, CRAFTING_STONE_PICK, CRAFTING_FURNACE,
        SMELTING_IRON, COOKING, CUSTOM_CONTENT, SETTING_UP_FOOD, WAITING_FOR_FOOD_FIXTURE, GATHERING_FOOD,
        GATHERING_COAL_RECOVERY, NATIVE_ANIMAL, NATIVE_COOPERATIVE, PREPARED_SAFETY, CAPTURING, COMPLETE, FAILED }

    private enum PreparedSafetyPhase { NONE, EQUIPMENT, OFFHAND_FOOD, OFFHAND_INGREDIENTS, THREAT, PURSUIT, STATION_ROOM, AIR, WORKBENCH_SEEDING, WORKBENCH_RECOVERY, HELD_FUEL_SMELTING, HELD_FUEL_STICKS }
    private enum WorldPolicyPhase { NONE, ADD_CLAIM, CLAIM_BREAK, STOP_CLAIM_BREAK, PREPARE_TABLE,
        TABLE_REQUEST, PREPARE_STATION, STATION_REQUEST, PREPARE_BACKFILL, BACKFILL_BREAK,
        BACKFILL_SURPLUS, COMPLETE }
    private record WorldPolicyFace(String name, BlockPos position) { }
    private record WorldPolicyPlacementProbe(String name, BlockPos clicked, Direction face, BlockPos target) { }

    private MinecraftClient client;
    private State state = State.DISABLED;
    private Path verificationRoot;
    private Path evidenceDirectory;
    private UUID playerId;
    private String runId;
    private long startedAtNanos;
    private int clientTicks;
    private int caseStartedAtTick;
    private long caseStartedAtWorldTime;
    private long caseStartedAtNanos;
    private long fixtureReadyServerTick;
    private int readyTicks;
    private CompletableFuture<Long> setupFuture;
    private CompletableFuture<ServerSnapshot> observationFuture;
    private ServerSnapshot latestSnapshot;
    private volatile boolean coalNavigationCourseCommandStarted;
    private volatile float coalNavigationCourseMinimumHealth = 20.0F;
    private int coalNavigationCourseObservedMask;
    private final int[] coalNavigationCourseCheckpointServerTicks = newCoalNavigationCheckpointTicks();
    private Map<BlockPos, BlockState> coalNavigationExpectedStates = Map.of();
    private JsonObject coalNavigationFenceGeometryEvidence;
    private JsonArray coalNavigationRouteDiagnostics;
    private double coalNavigationInitialServerFeetX = Double.NaN, coalNavigationInitialServerFeetZ = Double.NaN;
    private boolean coalNavigationStairEdgeCompleted;
    private String coalNavigationStairEdgeMovement = "unobserved";
    private int coalNavigationStairEdgePathIndex = -1;
    private boolean coalNavigationLedgeEdgeCompleted;
    private String coalNavigationLedgeEdgeMovement = "unobserved";
    private int coalNavigationLedgeEdgePathIndex = -1;
    private boolean worldLaunchStarted;
    private int screenshotWritesPending;
    private int captureStartedAtTick;
    private final List<CaseResult> results = new ArrayList<>();
    private String activeCase;
    private String liveRouteScreenshot;
    private int firstRouteTick = -1;
    private volatile long firstMovementMillis = -1;
    private volatile MovementClock movementClock;
    private Object nearbyWoodObservedPath;
    private int nearbyWoodPreviousPathIndex = -1;
    private int nearbyWoodPathGeneration = -1;
    private int nearbyWoodEligibleWalkArrivalCount;
    private int nearbyWoodZeroForwardIntentArrivalCount;
    private int nearbyWoodPositiveForwardArrivalCount;
    private final List<NearbyWoodWalkObservation> nearbyWoodWalkObservations = new ArrayList<>();
    private Object nearbyWoodLaunchObservedPath;
    private int nearbyWoodLaunchPreviousPathIndex = -1;
    private int nearbyWoodLaunchPathGeneration = -1;
    private int nearbyWoodLaunchHandoffCount;
    private int nearbyWoodLaunchJumpHandoffCount;
    private int nearbyWoodLaunchSettledHandoffCount;
    private int nearbyWoodLaunchUnsettledHandoffCount;
    private double nearbyWoodLaunchMaximumHorizontalSpeed;
    private final List<NearbyWoodLaunchHandoffObservation> nearbyWoodLaunchHandoffs = new ArrayList<>();
    private boolean nearbyWoodLaunchObserverRegistered;
    private NearbyWoodTargetObservation nearbyWoodFirstSelectedTarget;
    private NearbyWoodTargetObservation nearbyWoodFirstMineTarget;
    private NearbyWoodServerRemovalObservation nearbyWoodFirstServerLogRemoval;
    private boolean nearbyWoodLocalFixtureReadyAtCommandStart;
    private boolean nearbyWoodLocalActiveTaskCaptureAttempted;
    private String nearbyWoodLocalActiveTaskScreenshot;
    private String activeItem;
    private int activeCount;
    private boolean activeRequiresEmpty;
    private boolean activeStartedEmpty;
    private int activeFoodLevelAtStart;
    private int activeBreadCountAtStart;
    private int activeTableOpeningsAtStart;
    private int activeFurnaceOpeningsAtStart;
    private int activeSmokerOpeningsAtStart;
    private int activeBlastFurnaceOpeningsAtStart;
    private int activeStonecutterOpeningsAtStart;
    private boolean stonecuttingDrainStopInjected, stonecuttingDrainStopAttempted;
    private int stonecuttingDrainInputCountAtStop;
    private int stonecuttingDrainStopClientTick = -1, stonecuttingDrainStopServerTick = -1;
    private long observationRequestSequence, latestObservationRequestSequence;
    private long stonecuttingDrainRequiredObservationSequence = -1;
    private GameCatalog ironPickaxeProbeCatalog;
    private JsonObject ironPickaxePlannerProbeEvidence;
    private Map<String, Integer> ironPickaxeFirstVerifiedServerStock;
    private int ironPickaxeFirstVerifiedServerStockTick = -1;
    private int ironPickaxeProbeStartedAtTick = -1;
    private boolean ironPickaxeProbeStarted, ironPickaxeProbeFinished;
    private volatile int serverStonecutterOpenings;
    private Map<String, Integer> activeInitialResources = Map.of();
    private int foodBreadCountBeforeSetup;
    private static final boolean HELD_FUEL_MODE = "held-fuel".equals(PREPARED_SAFETY_MODE);
    private static final Map<String, Integer> HELD_FUEL_SETUP_EXPECTED = Map.of("minecraft:raw_iron", 3,
        "minecraft:oak_log", 2, "minecraft:oak_planks", 3, "minecraft:furnace", 1, "minecraft:crafting_table", 1);
    private static final Map<String, Integer> HELD_FUEL_SMELTING_EXPECTED = Map.of("minecraft:iron_ingot", 3,
        "minecraft:oak_log", 2, "minecraft:oak_planks", 1, "minecraft:crafting_table", 1);
    private static final Map<String, Integer> HELD_FUEL_STICKS_EXPECTED = Map.of("minecraft:iron_ingot", 3,
        "minecraft:oak_log", 1, "minecraft:oak_planks", 1, "minecraft:stick", 8, "minecraft:crafting_table", 1);
    private long heldFuelStartedAtNanos = -1;
    private Map<String, Integer> heldFuelSetupInventory = Map.of(), heldFuelSmeltingInventory = Map.of();
    private Map<String, String> heldFuelSetupReceipt = Map.of(), heldFuelSmeltingReceipt = Map.of();
    private String heldFuelSetupScreenshot = "", heldFuelFurnacePosition = "";
    private static final boolean WORKBENCH_MODE = "workbench".equals(PREPARED_SAFETY_MODE) || "workbench-blocked".equals(PREPARED_SAFETY_MODE);
    private static final boolean WORKBENCH_BLOCKED = "workbench-blocked".equals(PREPARED_SAFETY_MODE);
    private VerificationApi.PreparedSafetyWorkbenchFixture preparedSafetyWorkbenchFixture;
    private Map<String, String> workbenchInitialReceipt = Map.of();
    private Map<String, Integer> workbenchGrantedInventory = Map.of();
    private Map<String, Integer> workbenchSeedInventory = Map.of();
    private String workbenchSeedEngineStatus = "";
    private String workbenchSeedScreenshot = "";
    private int workbenchSeedServerTick = -1, workbenchSeedClientTick = -1;
    private final List<WorkbenchStatusObservation> workbenchStatusObservations = new ArrayList<>();
    private int workbenchRecoveryEpisodes;
    private boolean workbenchRecoveryWasActive, workbenchApproachObserved;
    private long workbenchRecoveryStartedNanos = -1, workbenchRecoveryDurationMillis = -1;
    private PreparedSafetyPhase preparedSafetyPhase = PreparedSafetyPhase.NONE;
    private VerificationApi.PreparedSafetyThreatFixture preparedSafetyThreatFixture;
    private Map<String, String> activeInitialThreatReceipt = Map.of();
    private final Map<String, String> staircaseReceipt = new LinkedHashMap<>();
    private final Map<dev.lodekeeper.navigation.kernel.api.Settings.Setting<?>, Object> staircaseOriginalSettings = new LinkedHashMap<>();
    private final Map<String, Object> staircaseOriginalConfig = new LinkedHashMap<>();
    private Object staircaseOriginalInput, staircaseTaskIdentity;
    private java.util.function.IntConsumer staircaseTransport;
    private boolean staircaseRetreatObserved, staircaseArrivalObserved;
    private int staircasePauseTick = -1, staircasePauseServerTick = -1;
    private long staircasePauseObservationSequence = -1;
    private enum ContactLowHealthPhase { WAIT_FIRST_HOP, HEALTH_REQUESTED, ONSET_SENT, LANDED, PAUSED, COMPLETE }
    private ContactLowHealthPhase contactLowHealthPhase = ContactLowHealthPhase.WAIT_FIRST_HOP;
    private java.util.function.IntConsumer contactLowHealthTransport;
    private int contactLowHealthAttackAttempts = -1;
    private boolean contactLowHealthAttackObserverValid = true;
    private BotInput contactLowHealthOwnedInput;
    private Object contactLowHealthTaskIdentity;
    private int contactLowHealthTriggerTick = -1, contactLowHealthOnsetTick = -1, contactLowHealthLandingTick = -1;
    private int contactLowHealthPauseTick = -1, contactLowHealthPauseServerTick = -1;
    private long contactLowHealthPauseObservationSequence = -1;
    private final Map<dev.lodekeeper.navigation.kernel.api.Settings.Setting<?>, Object> contactLowHealthOriginalSettings = new LinkedHashMap<>();
    private final Map<String, Object> contactLowHealthOriginalConfig = new LinkedHashMap<>();
    private final Map<String, String> contactLowHealthReceipt = new LinkedHashMap<>();
    private Object contactOriginalInput;
    private boolean contactManualKeyInjected;
    private int contactManualInputTick;
    private Object contactManualTaskIdentity;
    private int contactManualGuardTick = -1, contactManualGuardServerTick = -1;
    private long contactManualGuardObservationSequence = -1;
    private Map<String, String> contactManualInputReceipt = Map.of();
    private VerificationApi.PreparedSafetyStationRoomFixture preparedSafetyStationRoomFixture;
    private String stationRoomSetupScreenshot;
    private Map<String, String> activeInitialStationRoomReceipt = Map.of();
    private long stationRoomCompletionRequestFence = -1, stationRoomCompletionServerFenceSequence = -1;
    private int stationRoomCompletionServerFenceTick = -1;
    private VerificationApi.PreparedSafetyPursuitFixture preparedSafetyPursuitFixture;
    private Map<String, String> activeInitialPursuitReceipt = Map.of();
    private VerificationApi.PreparedSafetyAirFixture preparedSafetyAirFixture;
    private Map<String, String> activeInitialAirReceipt = Map.of();
    private boolean preparedAirRecoveryObserved;
    private boolean preparedAirRecoveryCompletedBeforeCraft;
    private int preparedAirRecoveryObservedClientTick = -1;
    private int preparedAirRecoveryTransitionClientTick = -1;
    private int preparedAirRecoveryCompletionClientTick = -1;
    private int preparedAirRecoveryCompletionServerTick = -1;
    private long preparedAirRecoveryTransitionObservationSequence = -1;
    private int preparedAirRecoveryTableOpeningsAtTransition = -1;
    private int preparedAirRecoveryBucketCountAtCompletion = -1;
    private String preparedAirRecoveryEndEngineStatus = "";
    private Map<String, String> preparedAirRecoveryCompletionReceipt = Map.of();
    private int preparedSafetySetupStartedAtTick = -1;
    private Map<String, String> activeInitialEquipment = Map.of();
    private boolean activeInitialCursorEmpty;
    private boolean preparedSafetyForegroundStarted;
    private boolean preparedMaintenanceQueueEmptyBeforeForeground;
    private boolean preparedMaintenanceReservationObservedBeforeForeground;
    private boolean preparedMaintenanceReservationPresentAtCompletion;
    private boolean preparedThreatBucketTaskObserved;
    private String failure = "";
    private volatile boolean serverTableOpened, serverFurnaceOpened;
    private volatile int serverTableOpenings;
    private volatile int serverFurnaceOpenings;
    private volatile double serverTableOpenX = Double.NaN;
    private volatile double serverTableOpenZ = Double.NaN;
    private volatile boolean serverSmokerOpened, serverBlastFurnaceOpened;
    private volatile int serverSmokerOpenings, serverBlastFurnaceOpenings;
    private net.minecraft.screen.ScreenHandler lastServerScreenHandler;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        if ((ANIMAL_NO_SCAFFOLD_PROPERTY == null || "false".equals(ANIMAL_NO_SCAFFOLD_PROPERTY))
                && COOKING_ORIGINAL_SLOT == null
                && !SETTINGS_UI_MODE && !WORLD_POLICY_MODE && System.getProperty("lodekeeper.verify.naturalGoal") != null
                && !MINING_REQUEST_LIMIT_MODE && !MINING_ZERO_YIELD_MODE && !THREAT_WATER_RETREAT_MODE
                && (THREAT_CREEPER_CONTACT_PROPERTY == null || "false".equals(THREAT_CREEPER_CONTACT_PROPERTY))
                && (THREAT_STAIRCASE_PROPERTY == null || "false".equals(THREAT_STAIRCASE_PROPERTY))
                && (THREAT_CONTACT_PROPERTY == null || "false".equals(THREAT_CONTACT_PROPERTY)) && STATION_ROOM_TUNNEL_PROPERTY == null
                && !"pursuit".equals(PREPARED_SAFETY_MODE) && !"pursuit-tool".equals(PREPARED_SAFETY_MODE)
                && !"air".equals(PREPARED_SAFETY_MODE) && !SHIELD_MODE && !ANIMAL_MODE && !COOPERATIVE_MODE) {
            NaturalWorldVerification.start(MinecraftClient.getInstance());
            return;
        }
        client = MinecraftClient.getInstance();
        try {
            Path runDirectory = client.runDirectory.toPath().toRealPath();
            verificationRoot = runDirectory.resolve("verification");
            Path ordinarySaves = runDirectory.resolve("saves");
            Files.createDirectories(verificationRoot);
            Path actualVerificationRoot = verificationRoot.toRealPath();
            if (!actualVerificationRoot.startsWith(runDirectory)) {
                throw new IOException("verification output resolves outside the development run directory");
            }
            Path actualSaves = Files.exists(ordinarySaves) ? ordinarySaves.toRealPath() : ordinarySaves.normalize();
            if (actualVerificationRoot.startsWith(actualSaves) || actualSaves.startsWith(actualVerificationRoot)) {
                throw new IOException("verification output overlaps the normal saves directory");
            }
            Path requestedEvidenceDirectory = actualVerificationRoot.resolve("evidence");
            Files.createDirectories(requestedEvidenceDirectory);
            evidenceDirectory = requestedEvidenceDirectory.toRealPath();
            if (!evidenceDirectory.startsWith(actualVerificationRoot)) {
                throw new IOException("evidence output resolves outside the verification directory");
            }
            runId = Instant.now().toString().replace(':', '-').replace('.', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
            startedAtNanos = System.nanoTime();
            state = State.OPENING_WORLD;
            if (invalidCooperativeScenario()) {
                failure = "cooperativeScenario requires exactly goto or follow, baritone=true, an exact 1.21.1 or 26.3 artifact and no other verifier mode";
                state = State.FAILED; writeEvidence("failed"); client.scheduleStop(); return;
            }
            if (invalidAnimalNoScaffold() || invalidAnimalScenario()) {
                failure = "animalScenario needs an admitted focused case, baritone=true, an exact 1.21.1 or 26.3 artifact, and no other verifier mode; animalNoScaffold must be false or true and true requires white_wool_inventory";
                state = State.FAILED; writeEvidence("failed"); client.scheduleStop(); return;
            }
            if (invalidShieldScenario()) {
                failure = "shieldScenario must be exactly false, default, off, spare, queued, iron_short, planks_short, worn, occupied, or manual; active cases require baritone=true on Minecraft 1.21.1 or 26.3 and no other verifier mode";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (invalidStationRoomTunnelMode()) {
                failure = "stationRoomTunnel must be exactly false, true, or approach and requires baritone=true, preparedSafety=station_room, and Minecraft 1.21.1 or 26.3 without naturalGoal";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (THREAT_STAIRCASE_PROPERTY != null && !"false".equals(THREAT_STAIRCASE_PROPERTY)
                    && (!THREAT_STAIRCASE_MODE || !BARITONE_MODE || !"threat".equals(PREPARED_SAFETY_MODE)
                        || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                        || System.getProperty("lodekeeper.verify.naturalGoal") != null
                        || THREAT_CONTACT_MODE || CONTACT_LOW_HEALTH_MODE || CONTACT_MANUAL_INPUT_MODE
                        || THREAT_WATER_RETREAT_MODE || THREAT_CREEPER_CONTACT_MODE)) {
                failure = "threatStaircase must be false or true; true requires baritone=true, preparedSafety=threat, Minecraft 1.21.1 or 26.3, and no other threat or natural mode";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (invalidContactLowHealthMode()) {
                failure = "threatContactLowHealth must be exactly false or true; true requires baritone=true, preparedSafety=threat, threatContact=true on Minecraft 1.21.1 or 26.3, no naturalGoal, manual takeover, water retreat or creeper mode";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (invalidThreatContactMode()) {
                failure = "threatContact must be exactly false or true; true requires baritone=true, preparedSafety=threat, Minecraft 1.21.1 or 26.3, no naturalGoal, and no threatWaterRetreat or threatCreeperContact";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (invalidThreatCreeperContactMode()) {
                failure = "threatCreeperContact must be exactly false or true; true requires baritone=true, preparedSafety=threat, Minecraft 1.21.1 or 26.3, and no naturalGoal, threatWaterRetreat, threatContact, or threatContactManualInput";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (THREAT_WATER_RETREAT_MODE && (!BARITONE_MODE || !"threat".equals(PREPARED_SAFETY_MODE)
                    || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                    || System.getProperty("lodekeeper.verify.naturalGoal") != null)) {
                failure = "threatWaterRetreat requires baritone=true, preparedSafety=threat, and Minecraft 1.21.1 or 26.3 without naturalGoal";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (("pursuit".equals(PREPARED_SAFETY_MODE) || "pursuit-tool".equals(PREPARED_SAFETY_MODE))
                    && (!BARITONE_MODE || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                        || System.getProperty("lodekeeper.verify.naturalGoal") != null)) {
                failure = "preparedSafety=pursuit or pursuit-tool requires baritone=true on Minecraft 1.21.1 or 26.3 without naturalGoal";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (("air".equals(PREPARED_SAFETY_MODE) || WORKBENCH_MODE)
                    && (!BARITONE_MODE || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                        || System.getProperty("lodekeeper.verify.naturalGoal") != null)) {
                failure = "preparedSafety=air, workbench, or workbench-blocked requires baritone=true on Minecraft 1.21.1 or 26.3 without naturalGoal";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (MINING_ZERO_YIELD_MODE && !MINING_REQUEST_LIMIT_MODE
                    || MINING_REQUEST_LIMIT_MODE && (!BARITONE_MODE || !BULK_WOOD_MODE
                        || System.getProperty("lodekeeper.verify.naturalGoal") != null)) {
                failure = "miningRequestLimit requires baritone=true and bulkWood=true without naturalGoal; miningZeroYield requires miningRequestLimit=true";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (GEOMETRY_EPOCH_MODE && !NEARBY_WOOD_MODE) {
                failure = "geometryEpoch requires nearbyWood=true";
                state = State.FAILED;
                writeEvidence("failed");
                client.scheduleStop();
                return;
            }
            if (IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE && !IRON_PICKAXE_MODE) {
                failure = "lodekeeper.verify.ironPickaxeEmptyDistantWood requires lodekeeper.verify.ironPickaxe=true";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (!(NEARBY_WOOD_GOAL.equals("wood") || NEARBY_WOOD_GOAL.equals("crafting_table"))
                    || !NEARBY_WOOD_MODE && !NEARBY_WOOD_GOAL.equals("wood")) {
                failure = "nearbyWoodGoal must be wood or crafting_table and requires nearbyWood=true";
                state = State.FAILED;
                writeEvidence("failed");
                client.scheduleStop();
                return;
            }
            if ((NEARBY_WOOD_MODE && !(NEARBY_WOOD_TERRAIN.equals("flat")
                    || NEARBY_WOOD_TERRAIN.equals("meadow") || NEARBY_WOOD_TERRAIN.equals("local_decoy")))
                    || (NEARBY_WOOD_TERRAIN.equals("local_decoy") && !NEARBY_WOOD_MODE)) {
                failure = "nearbyWoodTerrain must be exactly flat, meadow, or local_decoy; local_decoy requires nearbyWood=true";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (COOKING_MODE && !isSupportedCookingStationMode()) {
                failure = "lodekeeper.verify.cookingStation must be exactly smoker or blast_furnace";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (STONECUTTING_DRAIN_MODE && !STONECUTTING_MODE) {
                failure = "lodekeeper.verify.stonecuttingDrain requires lodekeeper.verify.stonecutting=true";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (PREPARED_SAFETY_MODE != null
                    && !PREPARED_SAFETY_MODE.equals("equipment") && !PREPARED_SAFETY_MODE.equals("offhand")
                    && !PREPARED_SAFETY_MODE.equals("threat") && !PREPARED_SAFETY_MODE.equals("pursuit")
                    && !PREPARED_SAFETY_MODE.equals("pursuit-tool")
                    && !PREPARED_SAFETY_MODE.equals("station_room") && !PREPARED_SAFETY_MODE.equals("air") && !WORKBENCH_MODE && !HELD_FUEL_MODE) {
                failure = "lodekeeper.verify.preparedSafety must be exactly equipment, offhand, threat, pursuit, pursuit-tool, station_room, air, workbench, workbench-blocked, or held-fuel";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if ((!COAL_START_SURFACE.equals("full") && !COAL_START_SURFACE.equals("dirt_path")
                    && !COAL_START_SURFACE.equals("farmland") && !COAL_RAISED_FULL_DROP_MODE)
                    || (!COAL_START_SURFACE.equals("full") && !COAL_RECOVERY_MODE)) {
                state = State.FAILED;
                failure = "coalStartSurface must be full, dirt_path, farmland, or raised_full; dirt_path, farmland, and raised_full require coalRecovery=true";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (NAVIGATION_COURSE != null && !MIXED_NAVIGATION_COURSE) {
                state = State.FAILED;
                failure = "navigationCourse must be exactly mixed when specified";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (MIXED_NAVIGATION_COURSE && !COAL_RECOVERY_MODE) {
                state = State.FAILED;
                failure = "navigationCourse=mixed requires coalRecovery=true";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (MIXED_NAVIGATION_COURSE && !COAL_START_SURFACE.equals("full")) {
                state = State.FAILED;
                failure = "navigationCourse=mixed requires coalStartSurface=full";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if ((MIXED_NAVIGATION_COURSE || COAL_RAISED_FULL_DROP_MODE) && !navigationMovementReflectionAvailable()) {
                state = State.FAILED;
                failure = "coal navigation verifier cannot inspect active validated route movement (expected AutomationEngine.movement and MovementController.path/pathIndex/validatedPathIndex)";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (NEARBY_WOOD_MODE && !BARITONE_MODE && !nearbyWoodWalkObservationReflectionAvailable()) {
                state = State.FAILED;
                failure = "nearbyWood verifier cannot inspect validated route input intent (expected AutomationEngine.movement and MovementController.path/pathIndex/validatedPathIndex/input plus BotInput.forward)";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (NEARBY_WOOD_LOCAL_DECOY_MODE
                    && (ENGINE_TARGET_FIELD == null || ENGINE_GATHER_MINE_TARGET_FIELD == null)) {
                state = State.FAILED;
                failure = "nearbyWood local_decoy verifier cannot inspect AutomationEngine.target and gatherMineTarget";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (selectedFixtureModes() > 1) {
                failure = "lodekeeper.verify.worldPolicy, lodekeeper.verify.exploration, lodekeeper.verify.diamondBoots, lodekeeper.verify.ironPickaxe, lodekeeper.verify.coalRecovery, lodekeeper.verify.bulkWood, lodekeeper.verify.cookingStation, lodekeeper.verify.stonecutting, and lodekeeper.verify.preparedSafety are mutually exclusive; stonecuttingDrain is a stonecutting submode";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            try {
                VerificationContentInitializer.ensureSourceContract(client.runDirectory.toPath());
            } catch (IOException exception) {
                failure = "refusing verifier source contract: " + exception.getMessage();
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            registerContactLowHealthTransport();
        registerStaircaseTransport();
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (!BARITONE_MODE || mc.player == null) return;
                var bot = dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getProvider().getPrimaryBaritone();
                if (bot.getMineProcess().isActive()) baritoneMiningObserved = true;
            });
            ClientTickEvents.END_CLIENT_TICK.register(mc -> observeCoalNavigationMovementAfterEngineTick());
            ClientTickEvents.END_CLIENT_TICK.register(mc -> observeNearbyWoodWalkArrivalAfterEngineTick());
            ClientTickEvents.END_CLIENT_TICK.register(mc -> observeNearbyWoodLocalTargetsAfterEngineTick());
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(this::closeNativePlayerPeerServer);
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                observeNativePlayerPeerServer(server);
                if (playerId == null) return;
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                if (player == null) return;
                if ((THREAT_CONTACT_MODE || THREAT_CREEPER_CONTACT_MODE || THREAT_STAIRCASE_MODE) && preparedSafetyThreatFixture != null) {
                    VerificationApi.observePreparedSafetyThreatTick(preparedSafetyThreatFixture, player, server.getTicks());
                }
                if (preparedSafetyPursuitFixture != null) {
                    VerificationApi.observePreparedSafetyPursuitTick(preparedSafetyPursuitFixture, player, server.getTicks());
                }
                if (preparedSafetyAirFixture != null) {
                    VerificationApi.observePreparedSafetyAirTick(preparedSafetyAirFixture, player, server.getTicks());
                }
                if (preparedSafetyStationRoomFixture != null && stationRoomHistorySupported()
                        && !STATION_ROOM_TUNNEL_MODE && !STATION_ROOM_APPROACH_MODE) {
                    if (!((Object) preparedSafetyStationRoomFixture instanceof Runnable observer))
                        throw new IllegalStateException("normal station-room fixture lacks its native server-tick observer");
                    observer.run();
                }
                if (ANIMAL_MODE && nativeAnimalFixture != null) {
                    @SuppressWarnings("unchecked") Map<String, String> receipt = (Map<String, String>) nativeAnimalApi(
                            "nativeAnimalReceipt", nativeAnimalFixture, player, server.getTicks());
                    nativeAnimalPublishedReceipt = receipt;
                }
                observeShieldServer(player, server.getTicks());
                observeFirstServerMovement(player);
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler != lastServerScreenHandler) {
                    if (handler instanceof net.minecraft.screen.CraftingScreenHandler) {
                        serverTableOpened = true;
                        serverTableOpenings++;
                        serverTableOpenX = player.getX();
                        serverTableOpenZ = player.getZ();
                    }
                    if (handler instanceof net.minecraft.screen.FurnaceScreenHandler) {
                        serverFurnaceOpened = true;
                        serverFurnaceOpenings++;
                    }
                    if (handler instanceof SmokerScreenHandler) {
                        serverSmokerOpened = true;
                        serverSmokerOpenings++;
                    }
                    if (handler.getClass() == net.minecraft.screen.StonecutterScreenHandler.class) serverStonecutterOpenings++;
                    if (handler instanceof BlastFurnaceScreenHandler) {
                        serverBlastFurnaceOpened = true;
                        serverBlastFurnaceOpenings++;
                    }
                    lastServerScreenHandler = handler;
                }
                observeCoalNavigationCheckpoint(player, server.getTicks());
                observeCoalDropServerCheckpoint(player, server.getTicks());
            });
            System.out.println("[Lodekeeper verification] Enabled. World and evidence paths are under " + verificationRoot);
        } catch (Exception exception) {
            state = State.FAILED;
            failure = exception.toString();
            System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
        }
    }

    private void tick(MinecraftClient currentClient) {
        if (nativePlayerPeerFailure != null) { fail("native fixture peer: " + nativePlayerPeerFailure); return; }
        if (state == State.COMPLETE || state == State.FAILED || state == State.DISABLED) return;
        registerNearbyWoodMeadowLaunchObserver();
        if (state == State.FAILED) return;
        clientTicks++;
        if (clientTicks % 100 == 0) System.out.println("[Lodekeeper verification] state=" + state + ", engine=" + requireEngine().status() + ", screen=" + (client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName()) + ", server=" + latestSnapshot);
        if (clientTicks > MAX_RUN_TICKS || System.nanoTime() - startedAtNanos > MAX_RUN_WALL_NANOS) {
            fail(COOKING_MODE ? "verification exceeded the 500-second cooking-mode limit"
                : MINING_REQUEST_LIMIT_MODE ? "verification exceeded the 360-second mining-request-limit limit"
                : "verification exceeded the five-minute limit");
            return;
        }
        if (PREPARED_SAFETY_MODE != null
                && (state == State.SETTING_UP || state == State.WAITING_FOR_EMPTY_SNAPSHOT)
                && clientTicks - preparedSafetySetupStartedAtTick > PREPARED_SAFETY_SETUP_TIMEOUT_TICKS) {
            fail("prepared safety fixture did not become server-ready within "
                + PREPARED_SAFETY_SETUP_TIMEOUT_TICKS + " ticks");
            return;
        }
        try {
            if (state == State.SETTINGS_UI) {
                tickSettingsUi();
                return;
            }
            if (state == State.OPENING_WORLD) {
                // Startup and resource-reload overlays must finish on ordinary client frames.
                if (client.getOverlay() != null) return;
                if (client.world != null) throw new IllegalStateException("start from the title screen; an existing world is active");
                VerificationApi.openCreateWorldScreen(client, client.currentScreen);
                state = State.WAITING_FOR_WORLD;
                return;
            }
            if (state == State.WAITING_FOR_WORLD) {
                if (client.world != null && client.player != null) {
                    if (client.currentScreen != null || ++readyTicks < 40) return;
                    playerId = client.player.getUuid();
                    if (SETTINGS_UI_MODE) {
                        AutomationEngine engine = requireEngine();
                        engine.stop();
                        activeCase = "native_settings_ui";
                        activeItem = null;
                        activeCount = 0;
                        activeRequiresEmpty = false;
                        activeStartedEmpty = false;
                        caseStartedAtTick = clientTicks;
                        caseStartedAtWorldTime = client.world.getTime();
                        caseStartedAtNanos = System.nanoTime();
                        activeTableOpeningsAtStart = serverTableOpenings;
                        state = State.SETTINGS_UI;
                        settingsUiAccess = new NativeSettingsUiAccess(
                                client, engine.config,
                                () -> new AutomationSettingsScreen(engine.config, () -> {}, engine.protection),
                                engine.protection,
                                this::captureSettingsUi, System.out::println,
                                () -> LodekeeperClient.engine != null
                                        && LodekeeperClient.engine.diagnosticTaskIdentity() == null
                                        && LodekeeperClient.engine.status().startsWith("idle"));
                        settingsUiAccess.submitSettingsChatCommand();
                        return;
                    }
                    if (CONFIG_ROUND_TRIP_MODE) {
                        if (navigationSettings == null) navigationSettings = new NavigationSettingsVerification();
                        navigationSettingsReceipt = navigationSettings.advance(requireEngine().config);
                        if (navigationSettingsReceipt == null) return;
                    }
                    configureAutomation();
                    beginFixtureSetup();
                    return;
                }
                if (!worldLaunchStarted && client.getOverlay() == null
                        && client.currentScreen instanceof CreateWorldScreen createScreen) {
                    worldLaunchStarted = true;
                    WorldCreator creator = createScreen.getWorldCreator();
                    // Server startup renders a loading loop. Run it outside the enclosing client tick.
                    client.send(() -> {
                        try {
                            if (client.getOverlay() != null) {
                                worldLaunchStarted = false;
                                return;
                            }
                            if (client.world != null || client.currentScreen != createScreen) {
                                throw new IllegalStateException("world creation screen changed before verifier startup");
                            }
                            startIsolatedFlatWorld(creator);
                        } catch (Exception exception) {
                            fail("isolated world startup failed: " + exception);
                        }
                    });
                }
                return;
            }
            if (state == State.SETTING_UP) {
                if (setupFuture != null && setupFuture.isDone()) {
                    fixtureReadyServerTick = setupFuture.join();
                    setupFuture = null;
                    engineTerrainChanged();
                    state = State.WAITING_FOR_EMPTY_SNAPSHOT;
                    readyTicks = 0;
                    requestObservation();
                    if (IRON_PICKAXE_MODE) startIronPickaxePlannerProbe();
                }
                return;
            }
            if (state == State.SETTING_UP_FOOD) {
                if (setupFuture != null && setupFuture.isDone()) {
                    fixtureReadyServerTick = setupFuture.join();
                    setupFuture = null;
                    engineTerrainChanged();
                    state = State.WAITING_FOR_FOOD_FIXTURE;
                    readyTicks = 0;
                    requestObservation();
                }
                return;
            }
            if (state == State.WAITING_FOR_FOOD_FIXTURE) {
                if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick
                    && latestSnapshot.foodLevel == 7
                    && latestSnapshot.difficulty.equals(Difficulty.NORMAL.name())
                    && latestSnapshot.count(VerificationContentInitializer.BREAD_ID) >= foodBreadCountBeforeSetup + 1) {
                    if (++readyTicks >= 20) startFoodGatherCommand();
                } else {
                    readyTicks = 0;
                }
                return;
            }
            if (state == State.WAITING_FOR_EMPTY_SNAPSHOT) {
                if (COOPERATIVE_MODE) {
                    if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                    if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick
                            && latestSnapshot.inventoryEmpty() && latestSnapshot.equippedItems.isEmpty()
                            && latestSnapshot.serverCursorEmpty && latestSnapshot.health == 20.0F
                            && Math.abs(latestSnapshot.x - 0.5) < 0.001 && Math.abs(latestSnapshot.y - 64) < 0.001
                            && Math.abs(latestSnapshot.z - 0.5) < 0.001 && requireEngine().placementStockReady()) {
                        if (++readyTicks >= 20) startCooperativeCase();
                    } else readyTicks = 0;
                    return;
                }
                if (ANIMAL_MODE) {
                    if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                    if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick
                            && !nativeAnimalPublishedReceipt.isEmpty() && nativeAnimalInt("effects") == 0
                            && nativeAnimalInt("sheared") == 0 && nativeAnimalOriginalSlotReady()
                            && latestSnapshot.health == 20.0F
                            && latestSnapshot.foodLevel == 20 && latestSnapshot.serverCursorEmpty
                            && requireEngine().placementStockReady()) {
                        if (++readyTicks >= 20) startNativeAnimalCase();
                    } else readyTicks = 0;
                    return;
                }

                if (SHIELD_MODE) {
                    shieldServerReceipt = shieldPublishedReceipt;
                    if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                    if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick
                            && !shieldServerReceipt.isEmpty() && shieldReceiptTrue("tablePresent") && shieldReceiptTrue("cursorEmpty")) {
                        if (++readyTicks >= 20) startShieldScenario();
                    }
                    return;
                }

                if (WORLD_POLICY_MODE) {
                    tickWorldPolicyFixture();
                    return;
                }
                if (PREPARED_SAFETY_MODE != null) {
                    if (THREAT_CONTACT_MODE || THREAT_CREEPER_CONTACT_MODE) {
                        client.player.setYaw(-90.0F);
                        client.player.setPitch(0.0F);
                    }
                    if (STATION_ROOM_TUNNEL_MODE) {
                        client.player.setYaw(98.886902F);
                        client.player.setPitch(-38.467983F);
                    } else if (STATION_ROOM_APPROACH_MODE) {
                        client.player.setYaw(0.0F);
                        client.player.setPitch(0.0F);
                    }
                    if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                    if (preparedSafetyFixtureReady()) {
                        int requiredReadyTicks = preparedSafetyPhase == PreparedSafetyPhase.AIR ? 1 : 20;
                        if (++readyTicks >= requiredReadyTicks && (THREAT_WATER_RETREAT_MODE || client.player.getY() > FIXTURE_FLOOR_Y
                                && client.world.getBlockState(new BlockPos(0, FIXTURE_FLOOR_Y, 0)).isOf(Blocks.BEDROCK))) {
                            if (HELD_FUEL_MODE) {
                                startPreparedSafetyHeldFuelCase();
                            } else if (WORKBENCH_MODE) {
                                startPreparedSafetyWorkbenchCase();
                            } else if (preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_INGREDIENTS) {
                                startPreparedSafetyIngredientsCase();
                            } else if (preparedSafetyPhase == PreparedSafetyPhase.THREAT) {
                                startPreparedSafetyThreatCase();
                            } else if (preparedSafetyPhase == PreparedSafetyPhase.PURSUIT) {
                                startPreparedSafetyPursuitCase();
                            } else if (preparedSafetyPhase == PreparedSafetyPhase.STATION_ROOM) {
                                startPreparedSafetyStationRoomCase();
                            } else if (preparedSafetyPhase == PreparedSafetyPhase.AIR) {
                                startPreparedSafetyAirCase();
                            } else {
                                startPreparedSafetyCase();
                            }
                        }
                    } else {
                        readyTicks = 0;
                    }
                    return;
                }
                if (COAL_RECOVERY_MODE) {
                    if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                    boolean startingStockObserved = latestSnapshot != null
                        && latestSnapshot.serverTick >= fixtureReadyServerTick
                        && latestSnapshot.health == 20.0F
                        && latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1))
                        && latestSnapshot.coalRecoveryEncasedOreRemaining == 1
                        && latestSnapshot.coalRecoveryAccessibleOreRemaining == 1
                        && latestSnapshot.coalStartSurfaceRemaining == 9
                        && (!COAL_RAISED_FULL_DROP_MODE || (Math.abs(latestSnapshot.x - 0.5) < 0.0001
                            && Math.abs(latestSnapshot.z - 0.5) < 0.0001))
                        && (!MIXED_NAVIGATION_COURSE || (Math.abs(latestSnapshot.x - 0.25) < 0.0001
                            && Math.abs(latestSnapshot.z - 0.75) < 0.0001))
                        && (!MIXED_NAVIGATION_COURSE || (latestSnapshot.coalNavigationCourseMismatchCount == 0
                            && latestSnapshot.coalNavigationCourseObservedMask == 0))
                        && Math.abs(latestSnapshot.y - coalStartInitialFeetY()) < 0.0001;
                    if (startingStockObserved) {
                        boolean clientAtStartHeight = COAL_RAISED_FULL_DROP_MODE
                            ? Math.abs(client.player.getY() - coalStartInitialFeetY()) < 0.0001
                            : client.player.getY() > PLAYER_Y - 1;
                        if (++readyTicks >= 20 && clientAtStartHeight
                                && client.world.getBlockState(new BlockPos(0, coalStartSurfaceY(), 0)).isOf(coalStartFloor())) {
                            startCoalRecoveryCommand();
                        }
                    } else {
                        readyTicks = 0;
                    }
                    return;
                }
                if (IRON_PICKAXE_MODE) {
                    if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                    tickIronPickaxePlannerProbe();
                    if (state == State.FAILED) return;
                    Map<String, Integer> expectedIronPickaxeStartingStock = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                        ? Map.of() : Map.of("minecraft:crafting_table", 1);
                    boolean startingStockObserved = latestSnapshot != null
                        && latestSnapshot.serverTick >= fixtureReadyServerTick
                        && latestSnapshot.health == 20.0F
                        && latestSnapshot.inventory.equals(expectedIronPickaxeStartingStock);
                    if (startingStockObserved && IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                            && ironPickaxeFirstVerifiedServerStock == null) {
                        ironPickaxeFirstVerifiedServerStock = Map.copyOf(latestSnapshot.inventory);
                        ironPickaxeFirstVerifiedServerStockTick = latestSnapshot.serverTick;
                    }
                    if (startingStockObserved && ironPickaxeProbeFinished) {
                        if (++readyTicks >= 20 && client.player.getY() > PLAYER_Y - 1
                                && client.world.getBlockState(new BlockPos(0, FIXTURE_FLOOR_Y, 0)).isOf(Blocks.BEDROCK)) {
                            startIronPickaxeCommand();
                        }
                    } else {
                        readyTicks = 0;
                    }
                    return;
                }
                if (PROCESSING_MODE) {
                    boolean startingResourcesObserved = latestSnapshot != null
                        && latestSnapshot.serverTick >= fixtureReadyServerTick
                        && latestSnapshot.inventory.equals(cookingProvidedStock())
                        && latestSnapshot.count(cookingOutputId()) == 0;
                    if (startingResourcesObserved) {
                        readyTicks++;
                        if (readyTicks >= 20 && client.player.getY() > PLAYER_Y - 1
                                && client.world.getBlockState(new BlockPos(0, FIXTURE_FLOOR_Y, 0)).isOf(Blocks.BEDROCK)) {
                            startCookingCommand();
                        }
                    } else {
                        readyTicks = 0;
                    }
                    return;
                }
                if (NEARBY_WOOD_LOCAL_DECOY_MODE && latestSnapshot != null
                        && latestSnapshot.serverTick >= fixtureReadyServerTick && latestSnapshot.inventoryEmpty()
                        && !nearbyWoodLocalFixtureReady(latestSnapshot)) {
                    fail("local_decoy fixture proof failed before command: expected empty inventory, full health, spawn at 0.5,64,0.5, visible oak log at 3,64,0, and intact bedrock enclosure around -6,64,-6; "
                        + nearbyWoodLocalNavigationDiagnostics());
                    return;
                }
                if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick && latestSnapshot.inventoryEmpty()) {
                    readyTicks++;
                    boolean fixtureVisible = EXPLORATION_MODE
                        ? client.world.getBlockState(new BlockPos(0,FIXTURE_FLOOR_Y,0)).isOf(Blocks.BEDROCK)
                        : client.world.getBlockState(NEARBY_WOOD_LOCAL_DECOY_MODE ? NEARBY_WOOD_LOCAL_VISIBLE_LOG
                            : new BlockPos(NEARBY_WOOD_MODE ? 20 : 6,PLAYER_Y + (MEADOW_BENCHMARK ? 3 : 0),0)).isOf(Blocks.OAK_LOG);
                    if (readyTicks >= 20 && client.player.getY() > 63 && fixtureVisible) startGatherCommand();
                }
                return;
            }
            if (state == State.NATIVE_COOPERATIVE) { tickCooperativeCase(); return; }
            if (state == State.NATIVE_ANIMAL) { tickNativeAnimalCase(); return; }
            if (state == State.WORLD_POLICY) {
                if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                tickWorldPolicyScenario();
                return;
            }
            observePreparedAirRecoveryLatches();
            maybeInjectStonecuttingDrainStop();
            if (clientTicks % OBSERVE_EVERY_TICKS == 0
                    || (stonecuttingDrainStopInjected && requireEngine().status().startsWith("idle")
                        && latestObservationRequestSequence < stonecuttingDrainRequiredObservationSequence)) {
                requestObservation();
            }
            if (SHIELD_MODE && state == State.PREPARED_SAFETY) tickShieldScenario();
            else evaluateCurrentCase();
            if (state == State.CAPTURING && screenshotWritesPending == 0 && clientTicks - captureStartedAtTick >= 20) finishRun();
            else if (state == State.CAPTURING && clientTicks - captureStartedAtTick >= 100) finishRun();
        } catch (Exception exception) {
            fail(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private void startIsolatedFlatWorld(WorldCreator creator) throws IOException {
        WorldCreator.WorldType flat = creator.getNormalWorldTypes().stream()
            .filter(type -> type.preset().matchesKey(WorldPresets.FLAT)).findFirst()
            .or(() -> creator.getExtendedWorldTypes().stream().filter(type -> type.preset().matchesKey(WorldPresets.FLAT)).findFirst())
            .orElseThrow(() -> new IllegalStateException("The built-in superflat preset is unavailable"));
        creator.setWorldName("Lodekeeper verification " + runId.substring(Math.max(0, runId.length() - 8)));
        creator.setGameMode(WorldCreator.Mode.SURVIVAL);
        creator.setDifficulty(Difficulty.PEACEFUL);
        creator.setCheatsEnabled(false);
        creator.setGenerateStructures(false);
        creator.setBonusChestEnabled(false);
        creator.setSeed("483920105");
        creator.setWorldType(flat);
        creator.update();
        if (!creator.getWorldType().preset().matchesKey(WorldPresets.FLAT)) {
            throw new IllegalStateException("Could not select the superflat preset");
        }

        var holder = creator.getGeneratorOptionsHolder();
        if (holder == null) throw new IllegalStateException("World generator settings are not ready");
        String saveName = "run-" + runId.substring(Math.max(0, runId.length() - 8));
        LevelInfo levelInfo = new LevelInfo("Lodekeeper verification", GameMode.SURVIVAL, false, Difficulty.PEACEFUL,
            false, VerificationApi.gameRules(creator), DataConfiguration.SAFE_MODE);
        Path worldsDirectory = verificationRoot.resolve("worlds");
        Files.createDirectories(worldsDirectory);
        Path actualWorldsDirectory = worldsDirectory.toRealPath();
        if (!actualWorldsDirectory.startsWith(verificationRoot.toRealPath())) {
            throw new IOException("isolated world storage resolves outside the verification directory");
        }
        Path runDirectory = client.runDirectory.toPath().toRealPath();
        Path ordinarySaves = runDirectory.resolve("saves");
        Path actualSaves = Files.exists(ordinarySaves) ? ordinarySaves.toRealPath() : ordinarySaves.normalize();
        if (actualWorldsDirectory.startsWith(actualSaves) || actualSaves.startsWith(actualWorldsDirectory)) {
            throw new IOException("isolated world storage overlaps the normal saves directory");
        }
        client.setScreen(null);
        IntegratedServerLoader loader = new IntegratedServerLoader(client, LevelStorage.create(actualWorldsDirectory));
        VerificationApi.startFlatWorld(loader, saveName, levelInfo, holder.generatorOptions());
    }

    private void configureAutomation() throws IOException {
        AutomationEngine engine = requireEngine();
        engine.stop();
        if (WORLD_POLICY_MODE && !worldPolicyOriginalBackfillCaptured) {
            worldPolicyOriginalBackfill = engine.config.backfill;
            worldPolicyOriginalEquivalentStone = engine.config.backfillEquivalentStone;
            worldPolicyOriginalBackfillCaptured = true;
        }
        if (CONFIG_ROUND_TRIP_MODE) {
            configRoundTripReceipt = ConfigRoundTripVerification.verify(engine.config);
            configRoundTripReceipt.add("nativeNavigationBindings", navigationSettingsReceipt);
        }
        engine.config.searchRadius = BULK_WOOD_MODE ? 96 : 48;
        engine.config.scanBlocksPerTick = 512;
        engine.config.actionTimeoutTicks = 1_200;
        engine.config.pauseBelowHealth = 6.0F;
        engine.config.allowBreaking = true;
        if (PREPARED_SAFETY_MODE != null && !PREPARED_SAFETY_MODE.equals("station_room") && !WORKBENCH_MODE) engine.config.allowBreaking = false;
        engine.config.allowBuilding = !MIXED_NAVIGATION_COURSE && !COAL_RAISED_FULL_DROP_MODE;
        if (WORLD_POLICY_MODE) engine.config.backfill = false;
        engine.config.allowParkour = false;
        if (SHIELD_MODE) {
            engine.config.autoDefend = true;
            engine.config.autoUseShield = !"off".equals(SHIELD_SCENARIO);
            if (!"default".equals(SHIELD_SCENARIO)) engine.config.autoCraftShield = true;
            else if (engine.config.autoCraftShield) throw new IllegalStateException("untouched shield crafting default must be off");
            engine.config.shieldIronReserve = 4;
            engine.config.shieldPlankReserve = 9;
            engine.config.allowBreaking = false;
            engine.config.allowExploration = false;
            engine.config.debugLogging = true;
        }
        engine.config.autoEat = true;
        if (ANIMAL_MODE) {
            engine.config.autoEat = false; engine.config.autoDefend = false; engine.config.autoEquipArmor = false;
            engine.config.allowBreaking = false; engine.config.allowExploration = false;
            engine.config.backfill = false; engine.config.debugLogging = true;
        }

        if (COOPERATIVE_MODE) {
            engine.config.autoEat = false; engine.config.autoDefend = false; engine.config.autoEquipArmor = false;
            engine.config.allowBreaking = false; engine.config.allowBuilding = false; engine.config.allowExploration = false;
            engine.config.backfill = false; engine.config.debugLogging = true;
        }

        if (BULK_WOOD_MODE) engine.config.optimizeWoodTools = WOOD_TOOLS_MODE;
        if (THREAT_WATER_RETREAT_MODE || THREAT_CONTACT_MODE || THREAT_CREEPER_CONTACT_MODE || THREAT_STAIRCASE_MODE) engine.config.debugLogging = true;
        if (STATION_ROOM_APPROACH_MODE) engine.config.debugLogging = true;
        if (MINING_REQUEST_LIMIT_MODE) {
            engine.config.actionTimeoutTicks = 200;
            engine.config.explorationAttempts = 1;
            engine.config.allowExploration = true;
            engine.config.debugLogging = true;
            if (MINING_ZERO_YIELD_MODE) engine.config.explorationDistance = 1;
        }
    }

    private void observeCoalNavigationCheckpoint(ServerPlayerEntity player, int serverTick) {
        if (!MIXED_NAVIGATION_COURSE || !coalNavigationCourseCommandStarted) return;
        coalNavigationCourseMinimumHealth = Math.min(coalNavigationCourseMinimumHealth, player.getHealth());
        if (!player.isOnGround()) return;
        int xCell = (int) Math.floor(player.getX());
        int zCell = (int) Math.floor(player.getZ());
        int feetY16 = (int) Math.round(player.getY() * 16.0);
        if (zCell != 0) return;
        for (int index = 0; index < COAL_NAVIGATION_CHECKPOINTS.size(); index++) {
            CoalNavigationCheckpoint checkpoint = COAL_NAVIGATION_CHECKPOINTS.get(index);
            if (xCell == checkpoint.xCell && feetY16 == checkpoint.feetY16) {
                coalNavigationCourseObservedMask |= 1 << index;
                if (coalNavigationCourseCheckpointServerTicks[index] < 0) {
                    coalNavigationCourseCheckpointServerTicks[index] = serverTick;
                }
            }
        }
    }

    private void observeCoalDropServerCheckpoint(ServerPlayerEntity player, int serverTick) {
        if (!COAL_RAISED_FULL_DROP_MODE || !coalDropCommandStarted
                || coalDropServerCheckpoint != null || !player.isOnGround()) return;
        int feetY16 = (int) Math.round(player.getY() * 16.0);
        int xCell = (int) Math.floor(player.getX());
        int zCell = (int) Math.floor(player.getZ());
        if (feetY16 != PLAYER_Y * 16 || isCoalDropPlatformCell(xCell, zCell)) return;
        coalDropServerCheckpoint = new CoalDropServerCheckpoint(
            player.getX(), player.getY(), player.getZ(), player.isOnGround(), serverTick);
    }

    private static boolean isCoalDropPlatformCell(int x, int z) {
        return x >= -1 && x <= 1 && z >= -1 && z <= 1;
    }

    private void observeCoalNavigationMovementAfterEngineTick() {
        if (state == State.FAILED || state == State.COMPLETE || client.player == null
                || (MIXED_NAVIGATION_COURSE && !coalNavigationCourseCommandStarted)
                || (!MIXED_NAVIGATION_COURSE
                    && (!COAL_RAISED_FULL_DROP_MODE || state != State.GATHERING_COAL_RECOVERY))) return;
        try {
            Object movement = ENGINE_MOVEMENT_FIELD.get(requireEngine());
            dev.lodekeeper.nav.Path path = (dev.lodekeeper.nav.Path) MOVEMENT_PATH_FIELD.get(movement);
            if (path == null) return;
            int pathIndex = MOVEMENT_PATH_INDEX_FIELD.getInt(movement);
            int completedIndex = pathIndex - 1;
            int validatedPathIndex = MOVEMENT_VALIDATED_PATH_INDEX_FIELD.getInt(movement);
            if (completedIndex < 1 || completedIndex >= path.length() || validatedPathIndex != completedIndex
                    || !client.player.isOnGround()) return;

            dev.lodekeeper.nav.Path.Step source = path.step(completedIndex - 1);
            dev.lodekeeper.nav.Path.Step destination = path.step(completedIndex);
            int xCell = client.player.getBlockX();
            int zCell = client.player.getBlockZ();
            int feetY16 = (int) Math.round(client.player.getY() * 16.0);
            if (COAL_RAISED_FULL_DROP_MODE) {
                if (coalDropCompletedEdge == null && xCell == destination.x && zCell == destination.z
                        && feetY16 == destination.feetY16
                        && source.feetY16 == (PLAYER_Y + 1) * 16
                        && destination.feetY16 == PLAYER_Y * 16
                        && destination.movement == dev.lodekeeper.nav.Path.Movement.DROP
                        && isCoalDropPlatformCell(source.x, source.z)
                        && !isCoalDropPlatformCell(destination.x, destination.z)) {
                    coalDropCompletedEdge = new CoalDropEdge(
                        source.x, source.feetY16, source.z,
                        destination.x, destination.feetY16, destination.z,
                        destination.movement.name(), completedIndex, clientTicks);
                }
                return;
            }
            if (xCell != destination.x || zCell != 0 || feetY16 != destination.feetY16) return;

            if (source.x == 11 && source.z == 0 && source.feetY16 == 66 * 16
                    && destination.x == 12 && destination.z == 0 && destination.feetY16 == 67 * 16) {
                coalNavigationStairEdgeCompleted = true;
                coalNavigationStairEdgeMovement = destination.movement.name();
                coalNavigationStairEdgePathIndex = completedIndex;
            }
            if (source.x == 15 && source.z == 0 && source.feetY16 == 67 * 16
                    && destination.x == 16 && destination.z == 0 && destination.feetY16 == 68 * 16) {
                coalNavigationLedgeEdgeCompleted = true;
                coalNavigationLedgeEdgeMovement = destination.movement.name();
                coalNavigationLedgeEdgePathIndex = completedIndex;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            fail("could not inspect completed active navigation edge: " + exception.getMessage());
        }
    }

    private void observeNearbyWoodWalkArrivalAfterEngineTick() {
        if (!NEARBY_WOOD_MODE || BARITONE_MODE) return;
        if (state != State.GATHERING_WOOD || client.player == null) {
            nearbyWoodObservedPath = null;
            nearbyWoodPreviousPathIndex = -1;
            return;
        }
        try {
            Object movement = ENGINE_MOVEMENT_FIELD.get(requireEngine());
            dev.lodekeeper.nav.Path path = (dev.lodekeeper.nav.Path) MOVEMENT_PATH_FIELD.get(movement);
            int pathIndex = MOVEMENT_PATH_INDEX_FIELD.getInt(movement);
            if (path != nearbyWoodObservedPath) {
                nearbyWoodObservedPath = path;
                nearbyWoodPreviousPathIndex = pathIndex;
                nearbyWoodPathGeneration++;
                return;
            }
            int previousPathIndex = nearbyWoodPreviousPathIndex;
            nearbyWoodPreviousPathIndex = pathIndex;
            if (path == null || previousPathIndex < 0 || pathIndex != previousPathIndex + 1) return;

            int reachedPathIndex = pathIndex - 1;
            if (reachedPathIndex <= 0 || reachedPathIndex >= path.length() - 1) return;
            dev.lodekeeper.nav.Path.Step source = path.step(reachedPathIndex - 1);
            dev.lodekeeper.nav.Path.Step reached = path.step(reachedPathIndex);
            dev.lodekeeper.nav.Path.Step outgoing = path.step(reachedPathIndex + 1);
            int incomingX = reached.x - source.x;
            int incomingZ = reached.z - source.z;
            int outgoingX = outgoing.x - reached.x;
            int outgoingZ = outgoing.z - reached.z;
            if (reached.movement != dev.lodekeeper.nav.Path.Movement.WALK
                    || outgoing.movement != dev.lodekeeper.nav.Path.Movement.WALK
                    || reached.actionCount() != 0 || outgoing.actionCount() != 0
                    || source.feetY16 != reached.feetY16 || reached.feetY16 != outgoing.feetY16
                    || !isUnitXZDirection(incomingX, incomingZ)
                    || incomingX != outgoingX || incomingZ != outgoingZ) return;

            int validatedPathIndex = MOVEMENT_VALIDATED_PATH_INDEX_FIELD.getInt(movement);
            Object botInput = MOVEMENT_INPUT_FIELD.get(movement);
            float forwardIntent = BOT_INPUT_FORWARD_FIELD.getFloat(botInput);
            if (!Float.isFinite(forwardIntent)) {
                fail("nearbyWood verifier observed a non-finite BotInput.forward value");
                return;
            }

            nearbyWoodEligibleWalkArrivalCount++;
            if (forwardIntent == 0.0f) nearbyWoodZeroForwardIntentArrivalCount++;
            if (forwardIntent > 0.0f) nearbyWoodPositiveForwardArrivalCount++;
            if (nearbyWoodWalkObservations.size() < MAX_NEARBY_WOOD_WALK_OBSERVATIONS) {
                nearbyWoodWalkObservations.add(new NearbyWoodWalkObservation(
                    nearbyWoodPathGeneration, previousPathIndex, pathIndex, reachedPathIndex,
                    validatedPathIndex, clientTicks, forwardIntent,
                    incomingX, incomingZ, outgoingX, outgoingZ,
                    nearbyWoodWalkStepSnapshot(path, reachedPathIndex - 1),
                    nearbyWoodWalkStepSnapshot(path, reachedPathIndex),
                    nearbyWoodWalkStepSnapshot(path, reachedPathIndex + 1)));
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            fail("could not inspect nearbyWood walk input intent: " + exception.getMessage());
        }
    }

    private void registerNearbyWoodMeadowLaunchObserver() {
        if (!MEADOW_BENCHMARK || BARITONE_MODE || nearbyWoodLaunchObserverRegistered) return;
        if (isNearbyWoodMeadowLaunchHandoffSettled(LaunchApproach.ARRIVAL_RADIUS / 2,
                FORCED_PREPHYSICS_HANDOFF_SPEED, true)) {
            fail("nearbyWood meadow pure handoff probe accepted speed " + FORCED_PREPHYSICS_HANDOFF_SPEED);
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(mc -> observeNearbyWoodMeadowLaunchHandoffAtEngineStart());
        nearbyWoodLaunchObserverRegistered = true;
    }

    private void observeNearbyWoodMeadowLaunchHandoffAtEngineStart() {
        if (!MEADOW_BENCHMARK) return;
        if (state != State.GATHERING_WOOD || client.player == null) {
            resetNearbyWoodMeadowLaunchPathObservation();
            return;
        }
        try {
            Object movement = ENGINE_MOVEMENT_FIELD.get(requireEngine());
            dev.lodekeeper.nav.Path path = (dev.lodekeeper.nav.Path) MOVEMENT_PATH_FIELD.get(movement);
            if (path == null) {
                resetNearbyWoodMeadowLaunchPathObservation();
                return;
            }
            int pathIndex = MOVEMENT_PATH_INDEX_FIELD.getInt(movement);
            if (path != nearbyWoodLaunchObservedPath) {
                nearbyWoodLaunchObservedPath = path;
                nearbyWoodLaunchPreviousPathIndex = pathIndex;
                nearbyWoodLaunchPathGeneration++;
                return;
            }

            int previousPathIndex = nearbyWoodLaunchPreviousPathIndex;
            nearbyWoodLaunchPreviousPathIndex = pathIndex;
            int indexAdvance = pathIndex - previousPathIndex;
            if (previousPathIndex < 0 || indexAdvance <= 0) {
                if (indexAdvance < 0) nearbyWoodLaunchPathGeneration++;
                return;
            }
            if (indexAdvance > 2) {
                nearbyWoodLaunchPathGeneration++;
                return;
            }

            int reachedPathIndex = pathIndex - 1;
            int sourcePathIndex = reachedPathIndex - 1;
            if (sourcePathIndex < 0 || pathIndex >= path.length()) return;
            dev.lodekeeper.nav.Path.Step reached = path.step(reachedPathIndex);
            dev.lodekeeper.nav.Path.Step outgoing = path.step(pathIndex);
            int validatedPathIndex = MOVEMENT_VALIDATED_PATH_INDEX_FIELD.getInt(movement);
            if (validatedPathIndex != reachedPathIndex
                    || reached.movement != dev.lodekeeper.nav.Path.Movement.WALK
                    || !isNearbyWoodStrictLaunchMovement(outgoing.movement)) return;

            double playerX = client.player.getX();
            double playerY = client.player.getY();
            double playerZ = client.player.getZ();
            var velocity = client.player.getVelocity();
            double offsetX = playerX - (reached.x + .5);
            double offsetY = playerY - reached.feetY();
            double offsetZ = playerZ - (reached.z + .5);
            double centerDistanceXZ = Math.hypot(offsetX, offsetZ);
            double horizontalSpeed = Math.hypot(velocity.x, velocity.z);
            boolean grounded = client.player.isOnGround();
            boolean settled = isNearbyWoodMeadowLaunchHandoffSettled(centerDistanceXZ, horizontalSpeed, grounded);

            nearbyWoodLaunchHandoffCount++;
            if (outgoing.movement == dev.lodekeeper.nav.Path.Movement.JUMP) nearbyWoodLaunchJumpHandoffCount++;
            if (settled) nearbyWoodLaunchSettledHandoffCount++;
            else nearbyWoodLaunchUnsettledHandoffCount++;
            if (Double.isFinite(horizontalSpeed)) {
                nearbyWoodLaunchMaximumHorizontalSpeed = Math.max(nearbyWoodLaunchMaximumHorizontalSpeed, horizontalSpeed);
            }
            if (nearbyWoodLaunchHandoffs.size() < MAX_NEARBY_WOOD_LAUNCH_HANDOFF_OBSERVATIONS) {
                nearbyWoodLaunchHandoffs.add(new NearbyWoodLaunchHandoffObservation(
                    nearbyWoodLaunchPathGeneration, previousPathIndex, pathIndex, sourcePathIndex,
                    reachedPathIndex, pathIndex, validatedPathIndex, clientTicks + 1,
                    reached.movement.name(), outgoing.movement.name(), playerX, playerY, playerZ,
                    velocity.x, velocity.z, offsetX, offsetY, offsetZ, centerDistanceXZ,
                    horizontalSpeed, grounded, settled));
            }
            if (!settled) {
                fail("nearbyWood meadow WALK-to-" + outgoing.movement.name()
                    + " handoff was unsettled before launch (centerDistanceXZ=" + centerDistanceXZ
                    + ", horizontalSpeed=" + horizontalSpeed + ", grounded=" + grounded
                    + "; required distance < " + LaunchApproach.ARRIVAL_RADIUS
                    + " and speed < " + LaunchApproach.SETTLED_SPEED + " with grounded=true)");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            fail("could not inspect nearbyWood meadow launch handoff: " + exception.getMessage());
        }
    }

    private void resetNearbyWoodMeadowLaunchPathObservation() {
        nearbyWoodLaunchObservedPath = null;
        nearbyWoodLaunchPreviousPathIndex = -1;
    }

    private static boolean isNearbyWoodStrictLaunchMovement(dev.lodekeeper.nav.Path.Movement movement) {
        return movement == dev.lodekeeper.nav.Path.Movement.JUMP
            || movement == dev.lodekeeper.nav.Path.Movement.DROP
            || movement == dev.lodekeeper.nav.Path.Movement.PARKOUR
            || movement == dev.lodekeeper.nav.Path.Movement.BRIDGE;
    }

    private static boolean isNearbyWoodMeadowLaunchHandoffSettled(double centerDistanceXZ,
                                                                   double horizontalSpeed,
                                                                   boolean grounded) {
        return grounded && LaunchApproach.isSettled(centerDistanceXZ, horizontalSpeed, 0);
    }

    private void observeNearbyWoodLocalTargetsAfterEngineTick() {
        if (!NEARBY_WOOD_LOCAL_DECOY_MODE || state != State.GATHERING_WOOD || client.player == null) return;
        try {
            AutomationEngine engine = requireEngine();
            if (nearbyWoodFirstSelectedTarget == null) {
                BlockPos selectedTarget = (BlockPos) ENGINE_TARGET_FIELD.get(engine);
                if (selectedTarget != null) nearbyWoodFirstSelectedTarget = nearbyWoodTargetObservation(selectedTarget);
            }
            if (nearbyWoodFirstMineTarget == null) {
                BlockPos mineTarget = (BlockPos) ENGINE_GATHER_MINE_TARGET_FIELD.get(engine);
                if (mineTarget != null) nearbyWoodFirstMineTarget = nearbyWoodTargetObservation(mineTarget);
            }
            if (!nearbyWoodLocalActiveTaskCaptureAttempted && nearbyWoodFirstMineTarget != null
                    && clientTicks - nearbyWoodFirstMineTarget.clientTick >= 8) {
                nearbyWoodLocalActiveTaskCaptureAttempted = true;
                nearbyWoodLocalActiveTaskScreenshot = capture(activeCase + "-active-mining");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            fail("could not inspect local_decoy target selection and mining intent: " + exception.getMessage());
        }
    }

    private NearbyWoodTargetObservation nearbyWoodTargetObservation(BlockPos target) {
        return new NearbyWoodTargetObservation(target.getX(), target.getY(), target.getZ(), clientTicks,
            Math.max(0, (System.nanoTime() - caseStartedAtNanos) / 1_000_000L));
    }

    private boolean nearbyWoodLocalFixtureReady(ServerSnapshot snapshot) {
        NearbyWoodLocalFixtureSnapshot fixture = snapshot.nearbyWoodLocalFixture;
        return snapshot.inventoryEmpty() && snapshot.health == 20.0F
            && Math.abs(snapshot.x - 0.5) < 0.0001 && Math.abs(snapshot.y - PLAYER_Y) < 0.0001
            && Math.abs(snapshot.z - 0.5) < 0.0001 && fixture != null
            && fixture.visibleOakLogPresent && fixture.decoyOakLogPresent && fixture.decoyEnclosureIntact();
    }

    private boolean nearbyWoodLocalOutcomeObserved() {
        if (latestSnapshot == null) return false;
        NearbyWoodLocalFixtureSnapshot fixture = latestSnapshot.nearbyWoodLocalFixture;
        return fixture != null && !fixture.visibleOakLogPresent && fixture.decoyOakLogPresent
            && fixture.decoyEnclosureIntact() && latestSnapshot.health == 20.0F
            && latestSnapshot.inventory.equals(Map.of(activeItem, activeCount))
            && nearbyWoodTargetMatches(nearbyWoodFirstSelectedTarget, NEARBY_WOOD_LOCAL_VISIBLE_LOG)
            && nearbyWoodTargetMatches(nearbyWoodFirstMineTarget, NEARBY_WOOD_LOCAL_VISIBLE_LOG);
    }

    private static boolean nearbyWoodTargetMatches(NearbyWoodTargetObservation observed, BlockPos expected) {
        return observed != null && observed.x == expected.getX() && observed.y == expected.getY()
            && observed.z == expected.getZ();
    }

    private String nearbyWoodLocalNavigationDiagnostics() {
        String selected = nearbyWoodTargetDescription(nearbyWoodFirstSelectedTarget);
        String mine = nearbyWoodTargetDescription(nearbyWoodFirstMineTarget);
        String playerPosition = client.player == null ? "unavailable"
            : client.player.getX() + "," + client.player.getY() + "," + client.player.getZ();
        try {
            AutomationEngine engine = requireEngine();
            Object movement = ENGINE_MOVEMENT_FIELD.get(engine);
            dev.lodekeeper.nav.Path path = (dev.lodekeeper.nav.Path) MOVEMENT_PATH_FIELD.get(movement);
            return "selectedTarget=" + selected + ", firstMineTarget=" + mine
                + ", engineStatus=" + engine.status() + ", player=" + playerPosition
                + ", pathIndex=" + MOVEMENT_PATH_INDEX_FIELD.getInt(movement)
                + ", validatedPathIndex=" + MOVEMENT_VALIDATED_PATH_INDEX_FIELD.getInt(movement)
                + ", pathLength=" + (path == null ? 0 : path.length());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return "selectedTarget=" + selected + ", firstMineTarget=" + mine
                + ", player=" + playerPosition + ", navigationMetricsUnavailable=" + exception.getMessage();
        }
    }

    private static String nearbyWoodTargetDescription(NearbyWoodTargetObservation observation) {
        return observation == null ? "null" : observation.x + "," + observation.y + "," + observation.z
            + "@clientTick=" + observation.clientTick + "@elapsedMillis=" + observation.elapsedMillisFromCommand;
    }

    private static boolean isUnitXZDirection(int x, int z) {
        return x >= -1 && x <= 1 && z >= -1 && z <= 1 && (x != 0 || z != 0);
    }

    private static NearbyWoodWalkStepSnapshot nearbyWoodWalkStepSnapshot(
            dev.lodekeeper.nav.Path path, int pathIndex) {
        dev.lodekeeper.nav.Path.Step step = path.step(pathIndex);
        List<NearbyWoodWalkActionSnapshot> actions = new ArrayList<>(step.actionCount());
        for (dev.lodekeeper.nav.Action action : step.actions()) {
            actions.add(new NearbyWoodWalkActionSnapshot(
                action.type.name(), action.x, action.y, action.z, action.token));
        }
        return new NearbyWoodWalkStepSnapshot(pathIndex, step.x, step.feetY16, step.z,
            step.movement.name(), actions);
    }

    private static BlockPos coalRecoveryAccessibleOrePosition() {
        // Interaction candidates extend two cells: x18 forces the earliest valid stance
        // onto x16 at height 68, beyond the jump ledge. x17 could be mined from x15.
        return MIXED_NAVIGATION_COURSE ? new BlockPos(18, PLAYER_Y + 4, 0) : COAL_RECOVERY_ACCESSIBLE_ORE;
    }

    private Map<BlockPos, BlockState> setupMixedCoalNavigationCourse(ServerWorld world) {
        Map<BlockPos, BlockState> expected = new HashMap<>();
        for (int x = -12; x <= 18; x++) {
            for (int z = -6; z <= 6; z++) {
                boolean solidFloor = (x >= -1 && x <= 1 && z >= -1 && z <= 1)
                    || (x >= 1 && x <= 18 && z == 0) || (x == 5 && z == -2);
                setCourseBlock(world, expected, new BlockPos(x, FIXTURE_FLOOR_Y, z),
                    solidFloor ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState());
            }
        }
        for (int x = 1; x <= 16; x++) {
            for (int y = 64; y <= 70; y++) {
                for (int z : new int[]{-1, 1}) {
                    setCourseBlock(world, expected, new BlockPos(x, y, z), Blocks.BEDROCK.getDefaultState());
                }
            }
        }
        setCourseBlock(world, expected, new BlockPos(1, 64, 0),
            Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM));
        setCourseBlock(world, expected, new BlockPos(2, 64, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(3, 64, 0), Blocks.BEDROCK.getDefaultState());
        setCourseBlock(world, expected, new BlockPos(4, 64, 0), Blocks.DIRT_PATH.getDefaultState());
        setCourseBlock(world, expected, new BlockPos(5, 64, 0), Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE, 7));
        setCourseBlock(world, expected, new BlockPos(6, 64, 0),
            Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM));
        setCourseBlock(world, expected, new BlockPos(7, 64, 0), bottomEastStairs());
        for (int x = 8; x <= 10; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 64, 0), Blocks.BEDROCK.getDefaultState());
        }
        setCourseBlock(world, expected, new BlockPos(8, 65, 0), Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 2));
        setCourseBlock(world, expected, new BlockPos(9, 65, 0), Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 4));
        setCourseBlock(world, expected, new BlockPos(10, 65, 0), Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 8));
        setCourseBlock(world, expected, new BlockPos(11, 65, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(12, 66, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(13, 66, 0),
            Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        setCourseBlock(world, expected, new BlockPos(14, 66, 0), Blocks.DIRT_PATH.getDefaultState());
        for (int x = 15; x <= 18; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 66, 0), Blocks.BEDROCK.getDefaultState());
        }
        for (int x = 16; x <= 18; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 67, 0), Blocks.BEDROCK.getDefaultState());
        }

        // Catch randomized native ore drops after the required one-cell jump corridor.
        // A one-cell ledge lets items bounce off and fall 128 blocks to superflat ground.
        for (int x = 17; x <= 21; x++) for (int z = -2; z <= 2; z++) {
            setCourseBlock(world, expected, new BlockPos(x, 67, z), Blocks.BEDROCK.getDefaultState());
        }

        BlockPos water = new BlockPos(5, 64, -2);
        setCourseBlock(world, expected, water, Blocks.WATER.getDefaultState());
        for (BlockPos surround : List.of(water.up(), water.down(), water.north(), water.south(), water.east(), water.west())) {
            setCourseBlock(world, expected, surround, Blocks.BEDROCK.getDefaultState());
        }
        setCourseBlock(world, expected, new BlockPos(-8, 64, 0), Blocks.OAK_FENCE.getDefaultState());
        return expected;
    }

    private static BlockState bottomEastStairs() {
        return Blocks.STONE_BRICK_STAIRS.getDefaultState()
            .with(StairsBlock.FACING, Direction.EAST)
            .with(StairsBlock.HALF, BlockHalf.BOTTOM);
    }

    private static void setCourseBlock(ServerWorld world, Map<BlockPos, BlockState> expected, BlockPos position, BlockState state) {
        world.setBlockState(position, state, 3);
        expected.put(position, state);
    }

    private Map<BlockPos, BlockState> mixedCoalNavigationExpectedStates(Map<BlockPos, BlockState> course) {
        Map<BlockPos, BlockState> expected = new HashMap<>(course);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    expected.put(COAL_RECOVERY_ENCASED_ORE.add(dx, dy, dz), Blocks.BEDROCK.getDefaultState());
                }
            }
        }
        expected.put(COAL_RECOVERY_ENCASED_ORE, Blocks.COAL_ORE.getDefaultState());
        return Map.copyOf(expected);
    }

    private int coalNavigationCourseMismatchCount(ServerWorld world) {
        int mismatches = 0;
        for (Map.Entry<BlockPos, BlockState> entry : coalNavigationExpectedStates.entrySet()) {
            if (!world.getBlockState(entry.getKey()).equals(entry.getValue())) mismatches++;
        }
        return mismatches;
    }

    private boolean nativeAnimalInputsReleased() {
        var options = client.options;
        return !options.attackKey.isPressed() && !options.useKey.isPressed()
                && !options.forwardKey.isPressed() && !options.backKey.isPressed()
                && !options.leftKey.isPressed() && !options.rightKey.isPressed()
                && !options.jumpKey.isPressed() && !options.sneakKey.isPressed() && !options.sprintKey.isPressed()
                && !client.player.isUsingItem();
    }
    private boolean nativeAnimalSettingsRestored() {
        return !nativeAnimalOriginalSettings.isEmpty() && nativeAnimalOriginalSettings.entrySet().stream()
                .allMatch(entry -> java.util.Objects.equals(entry.getValue(), entry.getKey().value));
    }
    private int nativeAnimalInt(String key) {
        return Integer.parseInt(nativeAnimalPublishedReceipt.getOrDefault(key, "-1"));
    }
    private static JsonObject nativeAnimalJson(Map<String, String> values) {
        JsonObject json = new JsonObject(); values.forEach(json::addProperty); return json;
    }
    private void nativeAnimalDiagnostic(JsonObject evidence, String name, java.util.function.Supplier<?> observation) {
        try {
            Object value = observation.get();
            evidence.addProperty(name + "Available", value != null);
            if (value == null) evidence.add(name, com.google.gson.JsonNull.INSTANCE);
            else if (value instanceof Boolean flag) evidence.addProperty(name, flag);
            else if (value instanceof Number number) evidence.addProperty(name, number);
            else if (value instanceof com.google.gson.JsonElement element) evidence.add(name, element);
            else evidence.addProperty(name, value.toString());
        } catch (Throwable unavailable) {
            evidence.addProperty(name + "Available", false);
            evidence.add(name, com.google.gson.JsonNull.INSTANCE);
            evidence.addProperty(name + "UnavailableReason", unavailable.getClass().getSimpleName());
        }
    }

    private Integer nativeAnimalDiagnosticInt(Map<String, String> receipt, String name) {
        String value = receipt.get(name);
        return value == null ? null : Integer.valueOf(value);
    }

    private Boolean nativeAnimalDiagnosticBoolean(Map<String, String> receipt, String name) {
        String value = receipt.get(name);
        if (value == null) return null;
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("invalid receipt boolean");
        return Boolean.valueOf(value);
    }

    /** Keeps one latest observation and one failure observation before stop/fixture cleanup changes the measured state. */
    private void recordNativeAnimalReadiness(String observationPoint) {
        if (!ANIMAL_MODE || !List.of("cooking", "air_pending_attack", "air_pending_transfer").contains(ANIMAL_SCENARIO)) return;
        ServerSnapshot snapshot = latestSnapshot;
        Map<String, String> receipt = Map.copyOf(nativeAnimalPublishedReceipt);
        var player = client == null ? null : client.player;
        Object currentInput = player == null ? null : player.input;
        int selectedSlot = player == null ? -1 : ClientAccess.selectedSlot(player.getInventory());
        int furnaceOpenings = serverFurnaceOpenings;
        JsonObject evidence = new JsonObject();
        evidence.addProperty("observationPoint", observationPoint);
        evidence.addProperty("clientTick", clientTicks);
        evidence.addProperty("readyTicks", readyTicks);
        evidence.addProperty("serverSnapshotAvailable", snapshot != null);
        evidence.addProperty("nativeReceiptAvailable", !receipt.isEmpty());
        evidence.addProperty("clientPlayerAvailable", player != null);
        AutomationEngine engine = LodekeeperClient.engine;
        evidence.addProperty("engineAvailable", engine != null);
        String observedEngineStatus = engine == null ? null : engine.status();
        evidence.addProperty("observationSources", "pinned immutable server snapshot and native receipt; separately scheduled, not an atomic server observation");
        evidence.addProperty("damageCounterAuthority", "server sampled animal health losses; not proof of native sends");
        nativeAnimalDiagnostic(evidence, "serverTick", () -> snapshot == null ? null : snapshot.serverTick);
        nativeAnimalDiagnostic(evidence, "nativeReceiptServerTick", () -> nativeAnimalDiagnosticInt(receipt, "serverTick"));
        nativeAnimalDiagnostic(evidence, "engineStatus", () -> engine == null ? null : observedEngineStatus);
        nativeAnimalDiagnostic(evidence, "engineIdle", () -> engine == null ? null : observedEngineStatus.startsWith("idle"));
        nativeAnimalDiagnostic(evidence, "navigationStopped", () -> client == null ? null : baritoneNavigationStopped());
        nativeAnimalDiagnostic(evidence, "controlsAndUseReleased", () -> player == null ? null : nativeAnimalInputsReleased());
        nativeAnimalDiagnostic(evidence, "originalInputIdentity", () -> nativeAnimalOriginalInput == null ? null : System.identityHashCode(nativeAnimalOriginalInput));
        nativeAnimalDiagnostic(evidence, "currentInputIdentity", () -> currentInput == null ? null : System.identityHashCode(currentInput));
        nativeAnimalDiagnostic(evidence, "currentInputClass", () -> currentInput == null ? null : currentInput.getClass().getName());
        nativeAnimalDiagnostic(evidence, "exactOriginalInputRestored", () -> nativeAnimalOriginalInput == null || player == null ? null : currentInput == nativeAnimalOriginalInput);
        nativeAnimalDiagnostic(evidence, "nativeSettingsRestored", () -> nativeAnimalOriginalSettings.isEmpty() ? null : nativeAnimalSettingsRestored());
        evidence.addProperty("nativeSettingsChecked", nativeAnimalOriginalSettings.size());
        nativeAnimalDiagnostic(evidence, "originalSelectedSlot", () -> nativeAnimalOriginalSelectedSlot < 0 ? null : nativeAnimalOriginalSelectedSlot);
        nativeAnimalDiagnostic(evidence, "serverSelectedSlot", () -> nativeAnimalDiagnosticInt(receipt, "selectedSlot"));
        nativeAnimalDiagnostic(evidence, "clientSelectedSlot", () -> player == null ? null : selectedSlot);
        nativeAnimalDiagnostic(evidence, "serverOriginalSelectionRestored", () -> nativeAnimalOriginalSelectedSlot < 0 || nativeAnimalDiagnosticInt(receipt, "selectedSlot") == null ? null : nativeAnimalDiagnosticInt(receipt, "selectedSlot") == nativeAnimalOriginalSelectedSlot);
        nativeAnimalDiagnostic(evidence, "clientOriginalSelectionRestored", () -> nativeAnimalOriginalSelectedSlot < 0 || player == null ? null : selectedSlot == nativeAnimalOriginalSelectedSlot);
        nativeAnimalDiagnostic(evidence, "serverSnapshotCursorEmpty", () -> snapshot == null ? null : snapshot.serverCursorEmpty);
        nativeAnimalDiagnostic(evidence, "nativeReceiptCursorEmpty", () -> nativeAnimalDiagnosticBoolean(receipt, "cursorEmpty"));
        nativeAnimalDiagnostic(evidence, "clientCursorEmpty", () -> player == null ? null : player.currentScreenHandler.getCursorStack().isEmpty());
        nativeAnimalDiagnostic(evidence, "placementStockReady", () -> engine == null ? null : engine.placementStockReady());
        nativeAnimalDiagnostic(evidence, "placementInventoryReadiness", () -> engine == null ? null : engine.placementInventoryReadiness());
        evidence.addProperty("requestedItem", activeItem);
        evidence.addProperty("requestedCount", activeCount);
        nativeAnimalDiagnostic(evidence, "observedStock", () -> snapshot == null || activeItem == null ? null : snapshot.count(activeItem));
        nativeAnimalDiagnostic(evidence, "requestedStockReached", () -> snapshot == null || activeItem == null ? null : snapshot.count(activeItem) >= activeCount);
        nativeAnimalDiagnostic(evidence, "serverInventoryCounts", () -> {
            if (snapshot == null) return null;
            JsonObject counts = new JsonObject(); snapshot.inventory.forEach(counts::addProperty); return counts;
        });
        nativeAnimalDiagnostic(evidence, "serverHealth", () -> snapshot == null ? null : snapshot.health);
        nativeAnimalDiagnostic(evidence, "fullServerHealth", () -> snapshot == null ? null : snapshot.health == 20.0F);
        nativeAnimalDiagnostic(evidence, "clientHealth", () -> player == null ? null : player.getHealth());
        nativeAnimalDiagnostic(evidence, "animalDeaths", () -> nativeAnimalDiagnosticInt(receipt, "dead"));
        nativeAnimalDiagnostic(evidence, "serverSampledHealthLossCounter", () -> nativeAnimalDiagnosticInt(receipt, "effects"));
        evidence.addProperty("furnaceOpenings", furnaceOpenings);
        evidence.addProperty("furnaceOpeningsAtStart", activeFurnaceOpeningsAtStart);
        evidence.addProperty("furnaceOpeningDelta", furnaceOpenings - activeFurnaceOpeningsAtStart);
        evidence.addProperty("newFurnaceOpeningObserved", furnaceOpenings > activeFurnaceOpeningsAtStart);
        try {
            AnimalHarvestAction.Observation action = engine == null ? null : engine.nativeAnimalObservation();
            evidence.addProperty("nativeActorObservationAvailable", action != null);
            if (action != null) {
                evidence.addProperty("nativeActorJobToken", action.jobToken());
                nativeAnimalDiagnostic(evidence, "nativeActorTarget", action::target);
                evidence.addProperty("nativeActorSentEffects", action.sentEffects());
                evidence.addProperty("nativeActorActive", action.active());
                evidence.addProperty("nativeActorPendingEvidence", action.pendingEvidence());
                evidence.addProperty("nativeActorAirObserver", action.airObserver());
            }
        } catch (Throwable unavailable) {
            evidence.addProperty("nativeActorObservationAvailable", false);
            evidence.addProperty("nativeActorObservationUnavailableReason", unavailable.getClass().getSimpleName());
        }
        nativeAnimalEvidence.add(observationPoint.equals("before-stop") ? "beforeStopReadiness" : "latestReadiness", evidence);
    }

    private boolean nativeAnimalOriginalSlotReady() {
        if (COOKING_ORIGINAL_SLOT == null) return true;
        int expected = Integer.parseInt(COOKING_ORIGINAL_SLOT);
        return client.player != null && ClientAccess.selectedSlot(client.player.getInventory()) == expected
                && nativeAnimalInt("selectedSlot") == expected;
    }

    private void startNativeAnimalCase() {
        if (!nativeAnimalOriginalSlotReady()) {
            fail("cooking fixture requires matching initial server/client selected slot"); return;
        }
        AutomationEngine engine = requireEngine();
        if (!engine.status().startsWith("idle") || !baritoneNavigationStopped()) {
            fail("native animal command requires idle production navigation"); return;
        }
        nativeAnimalInitialReceipt = Map.copyOf(nativeAnimalPublishedReceipt);
        nativeAnimalOriginalInput = client.player.input;
        nativeAnimalOriginalSelectedSlot = ClientAccess.selectedSlot(client.player.getInventory());
        for (var setting : dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getSettings().byLowerName.values())
            nativeAnimalOriginalSettings.put(setting, setting.value);
        activeCase = "native_animal_" + ANIMAL_SCENARIO + (ANIMAL_NO_SCAFFOLD ? "_no_scaffold" : "");
        activeItem = "minecraft:" + (List.of("white_wool_inventory", "air_pending_transfer").contains(ANIMAL_SCENARIO) ? "white_wool"
                : "cooking".equals(ANIMAL_SCENARIO) ? "cooked_beef"
                : List.of("beef_partial", "wrong_components", "protected", "stop_after_interaction", "air_pending_attack").contains(ANIMAL_SCENARIO)
                ? "beef" : ANIMAL_SCENARIO);
        activeCount = ANIMAL_SCENARIO.startsWith("beef") ? 8 : ANIMAL_SCENARIO.contains("_wool") ? 4
                : "porkchop".equals(ANIMAL_SCENARIO) || "mutton".equals(ANIMAL_SCENARIO) ? 5
                : "wrong_components".equals(ANIMAL_SCENARIO) ? 3 : "protected".equals(ANIMAL_SCENARIO)
                || "stop_after_interaction".equals(ANIMAL_SCENARIO) || "air_pending_attack".equals(ANIMAL_SCENARIO) || "air_pending_transfer".equals(ANIMAL_SCENARIO) ? 1 : 4;
        activeRequiresEmpty = List.of("beef", "porkchop", "mutton", "leather").contains(ANIMAL_SCENARIO);
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        if ("protected".equals(ANIMAL_SCENARIO)) {
            nativeAnimalClaim = "native-animal-" + runId;
            engine.protection.setCorner(true, 3, 63, -5); engine.protection.setCorner(false, 16, 67, 4);
            String result = engine.protection.createClaim(nativeAnimalClaim, false);
            if (!result.contains("created")) { fail("native animal protection fixture failed: " + result); return; }
        }
        nativeAnimalEvidence.addProperty("scenario", ANIMAL_SCENARIO);
        if (ANIMAL_NO_SCAFFOLD) {
            nativeAnimalEvidence.addProperty("animalNoScaffold", true);
            nativeAnimalEvidence.addProperty("physicalCensusScope", "36 copied main stacks, HEAD/CHEST/LEGS/FEET/OFFHAND and cursor at every END_SERVER_TICK from setup through command completion, real stop and bounded restoration; sampled stock does not certify the absence of transient packets");
        }
        if (COOKING_ORIGINAL_SLOT != null) {
            nativeAnimalEvidence.addProperty("cookingOriginalSlotRequested", Integer.parseInt(COOKING_ORIGINAL_SLOT));
            nativeAnimalEvidence.addProperty("initialServerSelectedSlot", nativeAnimalInt("selectedSlot"));
            nativeAnimalEvidence.addProperty("initialClientSelectedSlot", ClientAccess.selectedSlot(client.player.getInventory()));
            nativeAnimalEvidence.addProperty("originalSlotFixtureAuthority", "before command, genuine server selection and normal native S2C held-slot packet; matching client/server selection required for20 ready ticks");
        }
        nativeAnimalEvidence.addProperty("command", "!lk get " + activeItem + " " + activeCount);
        nativeAnimalEvidence.addProperty("fixtureGrants", ANIMAL_NO_SCAFFOLD
                ? "existing white_wool_inventory pad, four adult NoAI white sheep and nearer wrong-color decoy; ordinary shears main20, ordinary cobblestone64 main30, ordinary stick selected0, no hotbar scaffold or granted wool; command get white_wool4 uses real shears staging, native approach and grounded pickup, followed by real stop after full requested stock; no actor/ACK state grants"
                : "air_pending_attack".equals(ANIMAL_SCENARIO)
                ? "bounded bedrock pad; one adult NoAI invulnerable vanilla cow rejects the real native attack; after exactly one send is retained without damage, server fixture builds a roofed two-block water pool with one exit, teleports the same player to 0.5,64,0.5 and sets air to 170; no granted items, output, damage or actor/receipt state; real AIR movement and refill follow; full starting health and hunger"
                : "air_pending_transfer".equals(ANIMAL_SCENARIO)
                ? "bounded bedrock pad; one ordinary adult NoAI white sheep; one ordinary shears in main slot20 and one ordinary stick in selected slot0 force staging into alternate hotbar1; after the real owned PICKUP click puts shears on the cursor with pendingEvidence=true and zero animal sends, server fixture builds the roofed two-block water pool with one exit, teleports the same player to 0.5,64,0.5 and sets air170; no granted wool, animal interaction, damage or actor/receipt state; AIR owns movement, then real stop requires exact shears return20, unchanged wear0, empty hotbar1 and original selection0"
                : "bounded bedrock pad; adult NoAI vanilla animals; no supplied requested output except 2 ordinary beef for beef_partial or 3 named beef for wrong_components; wool cases have one ordinary shears in hotbar 7 (inventory case uses main slot 20 and requires return there) and one nearer wrong-color sheep; cooking has one furnace and 2 coal; full health and hunger; no fixture mutation after command");
        nativeAnimalEvidence.addProperty("autoEat", false);
        nativeAnimalEvidence.addProperty("allowBreaking", false);
        beginCaseClock(); readyTicks = 0; state = State.NATIVE_ANIMAL;
        sendCommand("!lk get " + activeItem + " " + activeCount);
    }
    private void observeNativeAnimalMovement(AnimalHarvestAction.Observation action) {
        if (!ANIMAL_NO_SCAFFOLD || !action.active() || client.player == null || client.world == null) return;
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        if (owner == null || !owner.isCurrent(session) || session.world() != client.world) return;
        var bot = owner.getPrimaryBaritone();
        if (bot.getPlayerContext().player() != client.player || bot.getPlayerContext().world() != client.world) return;
        var follow = bot.getFollowProcess();
        var following = follow.following();
        var filter = follow.currentFilter();
        if (following == null || !follow.isActive() || filter == null) return;
        var settings = dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getSettings();
        if (settings.allowBreak.value || settings.allowPlace.value || settings.allowParkourPlace.value
                || settings.allowInventory.value || settings.autoTool.value) return;
        for (var entity : following) {
            if (!filter.test(entity)) continue;
            if (!nativeAnimalApproachObserved && settings.followRadius.value == 1
                    && entity instanceof net.minecraft.entity.passive.AnimalEntity target && target.getUuid().equals(action.target())) {
                nativeAnimalApproachObserved = true;
                nativeAnimalEvidence.addProperty("nativeApproachObservedClientTick", clientTicks);
                nativeAnimalEvidence.addProperty("nativeApproachTarget", target.getUuid().toString());
            }
            if (!nativeAnimalGroundedPickupObserved && settings.followRadius.value == 0
                    && entity instanceof net.minecraft.entity.ItemEntity drop && drop.isOnGround() && drop.getStack().isOf(Items.WHITE_WOOL)
                    && AnimalHarvestAction.ordinary(drop.getStack())) {
                nativeAnimalGroundedPickupObserved = true;
                nativeAnimalEvidence.addProperty("groundedOwnedPickupObservedClientTick", clientTicks);
                nativeAnimalEvidence.addProperty("groundedPickupDrop", drop.getUuid().toString());
                nativeAnimalEvidence.addProperty("groundedPickupCount", drop.getStack().getCount());
            }
        }
    }

    private boolean nativeAnimalPhysicalCensusReady() {
        return nativeAnimalInt("physicalCensusSlots") == 41 && nativeAnimalInt("physicalCensusSamples") > 0
                && nativeAnimalInt("physicalCensusMismatchSamples") == 0
                && "true".equals(nativeAnimalPublishedReceipt.get("physicalCensusShearsStaged"))
                && "true".equals(nativeAnimalPublishedReceipt.get("shearsOrdinary"))
                && "true".equals(nativeAnimalPublishedReceipt.get("stagedHotbarEmpty"))
                && nativeAnimalApproachObserved && nativeAnimalGroundedPickupObserved;
    }

    private void tickNativeAnimalCase() {
        if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
        if (clientTicks - caseStartedAtTick > 2_400 || System.nanoTime() - caseStartedAtNanos > 120_000_000_000L) {
            fail("native animal case exceeded 120 seconds; receipt=" + nativeAnimalPublishedReceipt); return;
        }
        AutomationEngine engine = requireEngine();
        if (List.of("air_pending_attack", "air_pending_transfer").contains(ANIMAL_SCENARIO)
                && nativeAnimalStopTick < 0 && engine.status().startsWith("paused")) {
            fail("AIR engine paused before verified recovery and the real stop: " + engine.status()); return;
        }
        if (latestSnapshot == null || nativeAnimalPublishedReceipt.isEmpty()) return;
        boolean refusal = List.of("wrong_components", "protected").contains(ANIMAL_SCENARIO);
        boolean stopping = "stop_after_interaction".equals(ANIMAL_SCENARIO);
        AnimalHarvestAction.Observation action = engine.nativeAnimalObservation();
        if (ANIMAL_NO_SCAFFOLD) {
            observeNativeAnimalMovement(action);
            if (nativeAnimalInt("physicalCensusMismatchSamples") > 0) {
                fail("movement-only animal physical stock census changed unrelated stock or exact borrowed shears: " + nativeAnimalPublishedReceipt); return;
            }
            if (nativeAnimalStopTick >= 0 && (nativeAnimalInt("sheared") != nativeAnimalShearedAtStop
                    || nativeAnimalInt("effects") != 0 || action.sentEffects() != nativeAnimalStopSends
                    || action.active() && (action.jobToken() != nativeAnimalStopJob
                        || !java.util.Objects.equals(action.target(), nativeAnimalStopTarget)))) {
                fail("movement-only completion stop crossed its native animal send or server shear fence"); return;
            }
        }
        if (List.of("air_pending_attack", "air_pending_transfer").contains(ANIMAL_SCENARIO)) { tickNativeAnimalAirCase(engine, action); return; }
        if (stopping && nativeAnimalTaskIdentity == null && action.active()) nativeAnimalTaskIdentity = engine.diagnosticTaskIdentity();
        if (stopping && nativeAnimalStopTick < 0 && nativeAnimalInt("effects") > 0) {
            if (!action.active() || nativeAnimalTaskIdentity == null || engine.diagnosticTaskIdentity() != nativeAnimalTaskIdentity
                    || action.sentEffects() < nativeAnimalInt("effects") || action.sentEffects() - nativeAnimalInt("effects") > 1) {
                fail("stop fixture did not witness the original active animal action and its bounded pending effect"); return;
            }
            nativeAnimalStopTick = clientTicks; nativeAnimalStopSends = action.sentEffects();
            nativeAnimalStopJob = action.jobToken(); nativeAnimalStopTarget = action.target();
            nativeAnimalEvidence.addProperty("stopAfterFirstObservedInteractionClientTick", nativeAnimalStopTick);
            nativeAnimalEvidence.addProperty("effectsObservedAtStop", nativeAnimalInt("effects"));
            nativeAnimalEvidence.addProperty("effectsSentAtStop", nativeAnimalStopSends);
            nativeAnimalEvidence.addProperty("alreadySentPendingEffectsAtStop", nativeAnimalStopSends - nativeAnimalInt("effects"));
            nativeAnimalEvidence.addProperty("originalNativeActionActiveAtStop", true);
            sendCommand("!lk stop");
            return;
        }
        if (stopping && nativeAnimalStopTick >= 0 && (action.jobToken() != nativeAnimalStopJob
                || !java.util.Objects.equals(action.target(), nativeAnimalStopTarget) || action.sentEffects() != nativeAnimalStopSends
                || nativeAnimalInt("effects") > nativeAnimalStopSends)) {
            fail("native animal action sent a new effect or exceeded its pending-effect fence after stop"); return;
        }
        boolean stopped = baritoneNavigationStopped() && nativeAnimalInputsReleased()
                && client.player.input == nativeAnimalOriginalInput && nativeAnimalSettingsRestored()
                && nativeAnimalInt("selectedSlot") == (COOKING_ORIGINAL_SLOT == null ? 0 : nativeAnimalOriginalSelectedSlot)
                && (COOKING_ORIGINAL_SLOT == null || ClientAccess.selectedSlot(client.player.getInventory()) == nativeAnimalOriginalSelectedSlot
                    && nativeAnimalOriginalSelectedSlot == Integer.parseInt(COOKING_ORIGINAL_SLOT))
                && latestSnapshot.serverCursorEmpty
                && "true".equals(nativeAnimalPublishedReceipt.get("cursorEmpty"));
        boolean result = refusal ? engine.status().startsWith("paused") && nativeAnimalInt("effects") == 0
                        && nativeAnimalInt("sheared") == 0 && latestSnapshot.inventory.equals(activeInitialResources)
                        && (!"wrong_components".equals(ANIMAL_SCENARIO) || nativeAnimalInt("ordinaryBeef") == 0)
                : stopping ? nativeAnimalStopTick >= 0 && clientTicks - nativeAnimalStopTick >= 60
                        && engine.status().startsWith("idle") && nativeAnimalStopSends > 0
                        && nativeAnimalInt("effects") == nativeAnimalStopSends
                : latestSnapshot.count(activeItem) >= activeCount && engine.status().startsWith("idle")
                        && (ANIMAL_SCENARIO.contains("_wool") ? nativeAnimalInt("effects") == 0 && nativeAnimalInt("dead") == 0
                            && nativeAnimalInt("wrongColorSheared") == 0
                            && nativeAnimalInt("sheared") > 0 && "true".equals(nativeAnimalPublishedReceipt.get("shearsPresent"))
                            && nativeAnimalInt("shearsDamage") == nativeAnimalInt("sheared")
                        : nativeAnimalInt("dead") > 0)
                        && (!"cooking".equals(ANIMAL_SCENARIO) || serverFurnaceOpenings > activeFurnaceOpeningsAtStart);
        recordNativeAnimalReadiness("readiness");
        if (result && stopped && latestSnapshot.health == 20.0F && engine.placementStockReady()
                && (!ANIMAL_NO_SCAFFOLD || nativeAnimalPhysicalCensusReady())) {
            if (ANIMAL_NO_SCAFFOLD && nativeAnimalStopTick >= 0 && clientTicks - nativeAnimalStopTick < 60) { readyTicks = 0; return; }
            if (++readyTicks < 20) return;
            if (ANIMAL_NO_SCAFFOLD && nativeAnimalStopTick < 0) {
                nativeAnimalStopTick = clientTicks; nativeAnimalStopSends = action.sentEffects();
                nativeAnimalStopJob = action.jobToken(); nativeAnimalStopTarget = action.target();
                nativeAnimalShearedAtStop = nativeAnimalInt("sheared");
                nativeAnimalEvidence.addProperty("stopAfterRequestedStockClientTick", clientTicks);
                nativeAnimalEvidence.addProperty("effectsSentAtCompletionStop", action.sentEffects());
                nativeAnimalEvidence.addProperty("shearedAtCompletionStop", nativeAnimalShearedAtStop);
                nativeAnimalEvidence.addProperty("pendingEvidenceAtCompletionStop", action.pendingEvidence());
                nativeAnimalEvidence.addProperty("originalNativeActionActiveAtCompletionStop", action.active());
                nativeAnimalEvidence.add("completionStopServerReceipt", nativeAnimalJson(nativeAnimalPublishedReceipt));
                readyTicks = 0; sendCommand("!lk stop"); return;
            }
            recordNativeAnimalReadiness("completion");
            nativeAnimalEvidence.addProperty("restorationObserved", true);
            nativeAnimalEvidence.addProperty("originalInputRestored", client.player.input == nativeAnimalOriginalInput);
            nativeAnimalEvidence.addProperty("nativeSettingsRestored", nativeAnimalSettingsRestored());
            nativeAnimalEvidence.addProperty("nativeSettingsChecked", nativeAnimalOriginalSettings.size());
            if (ANIMAL_NO_SCAFFOLD) {
                nativeAnimalEvidence.addProperty("physicalCensusUnchanged", true);
                nativeAnimalEvidence.addProperty("nativeApproachObserved", nativeAnimalApproachObserved);
                nativeAnimalEvidence.addProperty("groundedOwnedPickupObserved", nativeAnimalGroundedPickupObserved);
                nativeAnimalEvidence.addProperty("completionStopSendFenceObserved", true);
            }
            nativeAnimalEvidence.addProperty("result", ANIMAL_NO_SCAFFOLD ? "requested_wool_physical_census_return_and_completion_stop_observed"
                    : refusal ? "refused_without_effect" : stopping ? "drained_after_interaction" : "requested_server_stock_observed");
            if (nativeAnimalClaim != null) { engine.protection.removeClaim(nativeAnimalClaim); nativeAnimalClaim = null; }
            addResult(true, latestSnapshot.count(activeItem), "native animal server stock, exact target receipts, safe cancellation and hand restoration observed; receipt=" + nativeAnimalPublishedReceipt, capture(activeCase));
            state = State.CAPTURING; captureStartedAtTick = clientTicks;
        } else readyTicks = 0;
    }

    private void tickNativeAnimalAirCase(AutomationEngine engine, AnimalHarvestAction.Observation action) {
        boolean transferCase = "air_pending_transfer".equals(ANIMAL_SCENARIO);
        int expectedSends = transferCase ? 0 : 1;
        if (nativeAnimalPendingSendTick < 0) {
            if (transferCase && action.sentEffects() > 0) { fail("AIR transfer fixture sent an animal interaction before submersion"); return; }
            if (!action.active() || !action.pendingEvidence() || (!transferCase && action.sentEffects() == 0)) return;
            if (transferCase && !client.player.currentScreenHandler.getCursorStack().isOf(Items.SHEARS)) return;
            if (transferCase && (ClientAccess.selectedSlot(client.player.getInventory()) != 1 || !client.player.getInventory().getStack(20).isEmpty())) {
                fail("AIR transfer did not witness the exact main20 pickup and alternate selected hand1"); return;
            }
            if (action.sentEffects() != expectedSends || nativeAnimalInt("effects") != 0) {
                fail("AIR fixture needs its exact retained interaction or hand-transfer click without damage"); return;
            }
            nativeAnimalPendingSendTick = clientTicks;
            nativeAnimalTaskIdentity = engine.diagnosticTaskIdentity();
            nativeAnimalStopJob = action.jobToken(); nativeAnimalStopTarget = action.target(); nativeAnimalStopSends = expectedSends;
            nativeAnimalEvidence.addProperty("pendingInteractionObservedClientTick", clientTicks);
            nativeAnimalEvidence.addProperty("pendingInteractionJob", action.jobToken());
            nativeAnimalEvidence.addProperty("pendingInteractionTarget", action.target().toString());
            nativeAnimalEvidence.addProperty("pendingInteractionSentEffects", action.sentEffects());
            nativeAnimalSubmersion = new CompletableFuture<>();
            var scheduled = nativeAnimalSubmersion;
            var server = requireServer();
            server.execute(() -> {
                try {
                    nativeAnimalApi("submergeNativeAnimal", nativeAnimalFixture, requireServerPlayer(server), server.getOverworld(), server.getTicks());
                    scheduled.complete(null);
                } catch (Throwable failure) { scheduled.completeExceptionally(failure); }
            });
        }
        if ((!transferCase || nativeAnimalStopTick < 0) && !action.active()
                || !transferCase && !action.pendingEvidence() || action.sentEffects() != expectedSends
                || action.jobToken() != nativeAnimalStopJob || !java.util.Objects.equals(action.target(), nativeAnimalStopTarget)
                || (nativeAnimalStopTick < 0 || action.active()) && engine.diagnosticTaskIdentity() != nativeAnimalTaskIdentity
                || nativeAnimalInt("effects") != 0
                || nativeAnimalInt("dead") != 0 || nativeAnimalInt("sheared") != 0
                || nativeAnimalInt("ordinaryBeef") != 0) {
            fail("AIR observer lost its original interaction or sent/credited another effect"); return;
        }
        if (!nativeAnimalSubmersion.isDone()) return;
        nativeAnimalSubmersion.join();
        if (nativeAnimalInt("submersionTick") < 0) return;
        if ("true".equals(nativeAnimalPublishedReceipt.get("headInWater"))
                && nativeAnimalInt("airSupply") <= nativeAnimalInt("maxAirSupply") * 2 / 3) nativeAnimalAirReadyObserved = true;
        boolean airRoute = action.airObserver() && engine.status().startsWith("recovering air")
                && (engine.status().contains(" · swimming") || engine.status().contains(" · escaping"));
        if (airRoute) {
            if (!nativeAnimalAirRouteObserved) {
                nativeAnimalEvidence.addProperty("airRouteObservedClientTick", clientTicks);
                nativeAnimalEvidence.add("airRouteServerReceipt", nativeAnimalJson(nativeAnimalPublishedReceipt));
                nativeAnimalEvidence.addProperty("pendingEvidenceAtAirRoute", action.pendingEvidence());
            }
            nativeAnimalAirRouteObserved = true;
            double dx = Double.parseDouble(nativeAnimalPublishedReceipt.get("playerX")) - 0.5;
            double dy = Double.parseDouble(nativeAnimalPublishedReceipt.get("playerY")) - 64;
            double dz = Double.parseDouble(nativeAnimalPublishedReceipt.get("playerZ")) - 0.5;
            if (dx * dx + dy * dy + dz * dz >= 0.25) nativeAnimalAirMovementObserved = true;
        }
        boolean airRestored = baritoneNavigationStopped() && nativeAnimalInputsReleased()
                && client.player.input == nativeAnimalOriginalInput && nativeAnimalSettingsRestored();
        boolean restored = airRestored && nativeAnimalInt("selectedSlot") == 0 && latestSnapshot.serverCursorEmpty
                && "true".equals(nativeAnimalPublishedReceipt.get("cursorEmpty"));
        boolean breathable = "false".equals(nativeAnimalPublishedReceipt.get("headInWater"))
                && nativeAnimalInt("airSupply") >= nativeAnimalInt("maxAirSupply") * 9 / 10;
        if (nativeAnimalStopTick < 0 && nativeAnimalAirReadyObserved && nativeAnimalAirRouteObserved
                && nativeAnimalAirMovementObserved && !action.airObserver() && breathable && airRestored
                && (transferCase || restored)) {
            nativeAnimalStopTick = clientTicks;
            nativeAnimalEvidence.add("airCompletionServerReceipt", nativeAnimalJson(nativeAnimalPublishedReceipt));
            nativeAnimalEvidence.addProperty("stopAfterAirCompletionClientTick", clientTicks);
            nativeAnimalEvidence.addProperty("effectsSentAtStop", action.sentEffects());
            nativeAnimalEvidence.addProperty("originalNativeActionActiveAtStop", true);
            sendCommand("!lk stop"); return;
        }
        if (nativeAnimalStopTick >= 0 && clientTicks - nativeAnimalStopTick >= 60 && restored && breathable
                && (transferCase ? engine.status().startsWith("idle") && !action.active()
                    && latestSnapshot.count(activeItem) == 0
                    && "true".equals(nativeAnimalPublishedReceipt.get("shearsPresent"))
                    && "true".equals(nativeAnimalPublishedReceipt.get("shearsOrdinary")) && nativeAnimalInt("shearsDamage") == 0
                    && "true".equals(nativeAnimalPublishedReceipt.get("stagedHotbarEmpty"))
                    && latestSnapshot.inventory.equals(activeInitialResources)
                    : engine.status().startsWith("paused") && engine.status().contains("safe stop")
                        && latestSnapshot.inventory.equals(activeInitialResources))
                && latestSnapshot.health == 20.0F
                && engine.placementStockReady()) {
            if (++readyTicks < 20) return;
            nativeAnimalEvidence.addProperty("airReadyObservedWithRetainedInteraction", nativeAnimalAirReadyObserved);
            nativeAnimalEvidence.addProperty("airRouteObservedWithRetainedEvidence", nativeAnimalAirRouteObserved);
            nativeAnimalEvidence.addProperty("airMovementObserved", nativeAnimalAirMovementObserved);
            nativeAnimalEvidence.addProperty("restorationObserved", true);
            nativeAnimalEvidence.addProperty("originalInputRestored", client.player.input == nativeAnimalOriginalInput);
            nativeAnimalEvidence.addProperty("nativeSettingsRestored", nativeAnimalSettingsRestored());
            nativeAnimalEvidence.addProperty("nativeSettingsChecked", nativeAnimalOriginalSettings.size());
            nativeAnimalEvidence.addProperty("borrowedShearsReturnedToMain20", transferCase && "true".equals(nativeAnimalPublishedReceipt.get("shearsPresent")));
            nativeAnimalEvidence.addProperty("result", transferCase ? "air_recovered_transfer_drained_shears_returned_selection_restored_stop_fenced"
                    : "air_recovered_unknown_attack_retained_stop_fenced");
            addResult(true, latestSnapshot.count(activeItem), "AIR escape observed with retained native evidence, exact hand restoration and the stop send fence; receipt=" + nativeAnimalPublishedReceipt, capture(activeCase));
            state = State.CAPTURING; captureStartedAtTick = clientTicks;
        } else readyTicks = 0;
    }

    private static final class CooperativeIdentityValue {
        private final Object value;
        CooperativeIdentityValue(Object value) { this.value = value; }
        @Override public boolean equals(Object other) {
            return other instanceof CooperativeIdentityValue identity && identity.value == value;
        }
        @Override public int hashCode() { return System.identityHashCode(value); }
    }
    private record CooperativeArrayValue(Class<?> componentType, List<Object> values) { }

    private static Object cooperativeSettingSnapshot(Object value) {
        return cooperativeSettingSnapshot(value, 0, new int[] {8192});
    }

    private static Object cooperativeSettingSnapshot(Object value, int depth, int[] remaining) {
        if (depth > 12 || --remaining[0] < 0)
            throw new IllegalStateException("native setting snapshot exceeded its finite content budget");
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof Enum<?>) return value;
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new HashMap<>();
            for (var entry : map.entrySet()) copy.put(cooperativeSettingSnapshot(entry.getKey(), depth + 1, remaining),
                    cooperativeSettingSnapshot(entry.getValue(), depth + 1, remaining));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object member : list) copy.add(cooperativeSettingSnapshot(member, depth + 1, remaining));
            return java.util.Collections.unmodifiableList(copy);
        }
        if (value instanceof java.util.Set<?> set) {
            java.util.Set<Object> copy = new java.util.HashSet<>();
            for (Object member : set) copy.add(cooperativeSettingSnapshot(member, depth + 1, remaining));
            return java.util.Collections.unmodifiableSet(copy);
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            if (length > remaining[0]) throw new IllegalStateException("native setting array exceeds snapshot budget");
            for (int index = 0; index < length; index++)
                copy.add(cooperativeSettingSnapshot(java.lang.reflect.Array.get(value, index), depth + 1, remaining));
            return new CooperativeArrayValue(value.getClass().getComponentType(), java.util.Collections.unmodifiableList(copy));
        }
        return new CooperativeIdentityValue(value);
    }

    private void startCooperativeCase() {
        AutomationEngine engine = requireEngine();
        if (!engine.status().startsWith("idle") || !baritoneNavigationStopped()) {
            fail("cooperative case requires idle production navigation"); return;
        }
        activeCase = "native_cooperative_" + COOPERATIVE_SCENARIO;
        activeItem = null; activeCount = 0; activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        cooperativeEvidence.addProperty("scenario", COOPERATIVE_SCENARIO);
        cooperativeEvidence.addProperty("fixtureAuthority", "isolated native flat pad; follow peer joins as an offline ServerPlayer through normal native connection/player-list/world tracking; no certified chat or product receipt state supplied");
        cooperativeEvidence.addProperty("commandTransport", "normal native client chat intercepted by Lodekeeper");
        cooperativeEvidence.addProperty("afterCommandHostFixtureMutations", "none");
        cooperativeEvidence.addProperty("caseClientTickLimit", 1200);
        cooperativeEvidence.addProperty("caseWallLimitSeconds", 90);
        beginCaseClock(); readyTicks = 0; state = State.NATIVE_COOPERATIVE;
        if ("follow".equals(COOPERATIVE_SCENARIO)) cooperativePeerJoin = prepareNativePlayerPeer(false);
        else issueCooperativeCommand("!lk goto 12 64 0");
    }

    private void issueCooperativeCommand(String command) {
        cooperativeOriginalInput = client.player.input;
        cooperativeOriginalSelected = ClientAccess.selectedSlot(client.player.getInventory());
        for (var setting : dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getSettings().byLowerName.values())
            cooperativeOriginalSettings.put(setting, cooperativeSettingSnapshot(setting.value));
        cooperativeCommandTick = clientTicks;
        cooperativeEvidence.addProperty("command", command);
        cooperativeEvidence.addProperty("commandClientTick", clientTicks);
        cooperativeEvidence.addProperty("initialSelectedSlot", cooperativeOriginalSelected);
        cooperativeEvidence.addProperty("initialServerX", latestSnapshot.x);
        cooperativeEvidence.addProperty("initialServerY", latestSnapshot.y);
        cooperativeEvidence.addProperty("initialServerZ", latestSnapshot.z);
        readyTicks = 0; sendCommand(command);
    }

    private void observeCooperativeNativeRoute() {
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        if (owner == null || !owner.isCurrent(session) || session.world() != client.world) return;
        var bot = owner.getPrimaryBaritone();
        if (bot == null || bot.getPlayerContext().player() != client.player || bot.getPlayerContext().world() != client.world) return;
        var settings = dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getSettings();
        boolean movementOnly = !settings.allowBreak.value && !settings.allowPlace.value && !settings.allowParkourPlace.value
                && !settings.allowInventory.value && !settings.autoTool.value;
        boolean witnessed = false;
        if ("goto".equals(COOPERATIVE_SCENARIO)) {
            witnessed = bot.getCustomGoalProcess().isActive()
                    && (bot.getPathingBehavior().isPathing() || bot.getPathingBehavior().getInProgress().isPresent());
        } else if (cooperativePeerHandle != null && bot.getFollowProcess().isActive()) {
            var filter = bot.getFollowProcess().currentFilter();
            if (filter != null && settings.followRadius.value == 2) {
                String expected = nativePlayerPeerReceipt.get("peerUuid");
                for (var entity : bot.getFollowProcess().following()) {
                    if (entity != null && entity.getUuid().toString().equals(expected) && filter.test(entity)) witnessed = true;
                }
            }
        }
        if (witnessed) {
            cooperativeNativeRouteObserved = true;
            cooperativeMovementProfileObserved |= movementOnly;
        }
    }

    private boolean cooperativeRestored() {
        return client.player != null && client.player.input == cooperativeOriginalInput
                && ClientAccess.selectedSlot(client.player.getInventory()) == cooperativeOriginalSelected
                && !cooperativeOriginalSettings.isEmpty() && cooperativeOriginalSettings.entrySet().stream()
                    .allMatch(entry -> java.util.Objects.equals(entry.getValue(), cooperativeSettingSnapshot(entry.getKey().value)))
                && nativeAnimalInputsReleased() && baritoneNavigationStopped();
    }

    private void tickCooperativeCase() {
        if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
        if (clientTicks - caseStartedAtTick > 1200 || System.nanoTime() - caseStartedAtNanos > 90_000_000_000L) {
            fail("cooperative case exceeded its finite native budget; peer=" + nativePlayerPeerReceipt); return;
        }
        if (latestSnapshot == null || client.player == null) return;
        AutomationEngine engine = requireEngine();
        if (cooperativeCommandTick < 0) {
            if (cooperativePeerJoin == null || !cooperativePeerJoin.isDone()) return;
            cooperativePeerHandle = cooperativePeerJoin.join();
            Map<String, String> tracking = trackNativePlayerPeer(cooperativePeerHandle);
            boolean tracked = "true".equals(tracking.get("peerListed")) && "true".equals(tracking.get("peerTracked"))
                    && "true".equals(tracking.get("contextMatches")) && "true".equals(nativePlayerPeerReceipt.get("peerRegistered"));
            if (!tracked) { readyTicks = 0; return; }
            if (++readyTicks < 20) return;
            cooperativeEvidence.add("initialPeerTracking", nativeAnimalJson(tracking));
            cooperativeEvidence.add("initialPeerServerReceipt", nativeAnimalJson(nativePlayerPeerReceipt));
            issueCooperativeCommand("!lk follow " + tracking.get("peerUuid") + " 20");
            return;
        }
        if (engine.status().startsWith("paused")) { fail("cooperative command paused: " + engine.status()); return; }
        Object identity = engine.diagnosticTaskIdentity();
        if (identity != null && cooperativeTaskIdentity == null) cooperativeTaskIdentity = identity;
        if (identity != null && identity != cooperativeTaskIdentity) { fail("cooperative foreground task identity changed"); return; }
        observeCooperativeNativeRoute();
        if ("follow".equals(COOPERATIVE_SCENARIO) && cooperativePeerRetire == null) {
            if ("true".equals(nativePlayerPeerReceipt.get("ordinaryBreadConservationViolated"))
                    || !"true".equals(nativePlayerPeerReceipt.get("hostOtherStockUnchanged"))
                    || !"true".equals(nativePlayerPeerReceipt.get("peerOtherStockUnchanged"))) {
                fail("follow fixture observed a stock/conservation change"); return;
            }
            if (cooperativePeerMove != null) {
                if (!cooperativePeerMove.isDone()) return;
                cooperativePeerMove.join(); cooperativePeerMove = null;
            }
            if (cooperativeMoveAwaitingReceipt) {
                Map<String, String> receipt = nativePlayerPeerReceipt;
                long tick = Long.parseLong(receipt.get("serverTick"));
                if (tick <= cooperativeMoveServerFence
                        || Double.parseDouble(receipt.get("peerX")) != cooperativeMoveExpectedX
                        || Double.parseDouble(receipt.get("peerY")) != 64.0
                        || Double.parseDouble(receipt.get("peerZ")) != 0.5) return;
                cooperativeMoveAwaitingReceipt = false;
                cooperativeMoveAckServerTick = tick;
                cooperativeMoveAckRequest = observationRequestSequence;
                cooperativeMoveAckClientTick = clientTicks;
                cooperativeEvidence.add("acknowledgedPeerMove" + cooperativeMoves, nativeAnimalJson(receipt));
                cooperativeEvidence.addProperty("peerMoveAckRequestFence" + cooperativeMoves, cooperativeMoveAckRequest);
                cooperativeEvidence.addProperty("peerMoveAckClientTick" + cooperativeMoves, cooperativeMoveAckClientTick);
                requestObservation(); return;
            }
            if (cooperativeMoveAckRequest >= 0) {
                if (latestObservationRequestSequence <= cooperativeMoveAckRequest
                        || latestSnapshot.serverTick < cooperativeMoveAckServerTick
                        || clientTicks <= cooperativeMoveAckClientTick) return;
                cooperativeEvidence.addProperty("hostObservationAfterPeerMove" + cooperativeMoves, latestObservationRequestSequence);
                cooperativeEvidence.addProperty("hostServerTickAfterPeerMove" + cooperativeMoves, latestSnapshot.serverTick);
                cooperativeMoveAckRequest = -1;
            }
            double peerX = Double.parseDouble(nativePlayerPeerReceipt.get("peerX"));
            double peerZ = Double.parseDouble(nativePlayerPeerReceipt.get("peerZ"));
            double distance = Math.hypot(latestSnapshot.x - peerX, latestSnapshot.z - peerZ);
            if (cooperativeNativeRouteObserved && cooperativeMoves < 2 && distance <= 2.5
                    && latestSnapshot.serverTick >= fixtureReadyServerTick) {
                cooperativeMoves++;
                cooperativeEvidence.add("beforePeerMove" + cooperativeMoves, nativeAnimalJson(nativePlayerPeerReceipt));
                cooperativeEvidence.addProperty("hostServerXBeforeMove" + cooperativeMoves, latestSnapshot.x);
                cooperativeMoveExpectedX = 8.5 + cooperativeMoves * 4;
                cooperativeMoveServerFence = Long.parseLong(nativePlayerPeerReceipt.get("serverTick"));
                cooperativeMoveAwaitingReceipt = true;
                cooperativePeerMove = moveNativePlayerPeer(cooperativeMoveExpectedX, 64, 0.5);
                return;
            }
        }
        boolean position = "goto".equals(COOPERATIVE_SCENARIO)
                ? Math.floor(latestSnapshot.x) == 12 && Math.floor(latestSnapshot.z) == 0
                    && Math.abs(latestSnapshot.y - 64) < 0.01
                : cooperativeMoves == 2 && latestSnapshot.x >= 13.5 && Math.abs(latestSnapshot.y - 64) < 0.01
                    && Math.hypot(latestSnapshot.x - 16.5, latestSnapshot.z - 0.5) <= 2.5;
        var terminal = engine.diagnosticTravelCompletion();
        boolean acceptedReceipt = terminal != null && terminal.requestIdentity() == cooperativeTaskIdentity
                && ("goto".equals(COOPERATIVE_SCENARIO)
                    ? terminal.receipt().result() == NativeRun.TravelResult.ARRIVED
                    : terminal.receipt().result() == NativeRun.TravelResult.FOLLOW_EXPIRED
                        && terminal.deadlineNanos() - terminal.acceptedNanos() == 20_000_000_000L
                        && terminal.completedNanos() - terminal.deadlineNanos() >= 0);
        boolean completed = acceptedReceipt && cooperativeTaskIdentity != null && cooperativeNativeRouteObserved && cooperativeMovementProfileObserved
                && engine.status().startsWith("idle") && engine.diagnosticTaskIdentity() == null
                && position && cooperativeRestored() && latestSnapshot.inventory.equals(activeInitialResources)
                && latestSnapshot.equippedItems.equals(activeInitialEquipment) && latestSnapshot.serverCursorEmpty
                && latestSnapshot.health == 20.0F && engine.placementStockReady();
        if (!completed) { readyTicks = 0; return; }
        if ("follow".equals(COOPERATIVE_SCENARIO)) {
            if (cooperativePeerRetire == null) {
                cooperativeEvidence.add("peerServerBeforeTeardown", nativeAnimalJson(nativePlayerPeerReceipt));
                cooperativePeerRetire = retireNativePlayerPeer(); readyTicks = 0; return;
            }
            if (!cooperativePeerRetire.isDone()) return;
            cooperativePeerRetire.join();
            Map<String, String> tracking = trackNativePlayerPeer(cooperativePeerHandle);
            if (!"false".equals(tracking.get("peerListed")) || !"false".equals(tracking.get("peerTracked"))
                    || !"true".equals(tracking.get("contextMatches")) || !"true".equals(nativePlayerPeerReceipt.get("peerClosed"))) {
                readyTicks = 0; return;
            }
            cooperativeEvidence.add("finalPeerTracking", nativeAnimalJson(tracking));
            cooperativeEvidence.addProperty("nativePeerTeardownAcknowledged", true);
        }
        if (cooperativeCompletionFenceRequest < 0) {
            cooperativeCompletionFenceRequest = observationRequestSequence;
            cooperativeCompletionFenceServerTick = latestSnapshot.serverTick;
            cooperativeCompletionFenceTick = clientTicks;
            readyTicks = 0; requestObservation(); return;
        }
        if (latestObservationRequestSequence <= cooperativeCompletionFenceRequest
                || latestSnapshot.serverTick <= cooperativeCompletionFenceServerTick
                || clientTicks <= cooperativeCompletionFenceTick) { readyTicks = 0; return; }
        if (++readyTicks < 20) return;
        cooperativeEvidence.addProperty("nativeRouteObserved", true);
        cooperativeEvidence.addProperty("movementOnlyProfileObserved", true);
        cooperativeEvidence.addProperty("originalInputRestored", client.player.input == cooperativeOriginalInput);
        cooperativeEvidence.addProperty("nativeSettingsRestored", true);
        cooperativeEvidence.addProperty("nativeSettingsRestorationRule", "independent recursive collection/array snapshots; opaque native leaves checked by exact identity only");
        cooperativeEvidence.addProperty("terminalJobToken", terminal.receipt().jobToken());
        cooperativeEvidence.addProperty("terminalResult", terminal.receipt().result().name());
        cooperativeEvidence.addProperty("terminalReason", terminal.receipt().reason());
        cooperativeEvidence.addProperty("terminalArrivedSegments", terminal.receipt().arrivedSegments());
        cooperativeEvidence.addProperty("terminalAcceptedNanos", terminal.acceptedNanos());
        cooperativeEvidence.addProperty("terminalDeadlineNanos", terminal.deadlineNanos());
        cooperativeEvidence.addProperty("terminalCompletedNanos", terminal.completedNanos());
        cooperativeEvidence.addProperty("nativeSettingsChecked", cooperativeOriginalSettings.size());
        cooperativeEvidence.addProperty("originalSelectionRestored", true);
        cooperativeEvidence.addProperty("serverStockAndEquipmentUnchanged", true);
        cooperativeEvidence.addProperty("completionRequestFence", cooperativeCompletionFenceRequest);
        cooperativeEvidence.addProperty("completionServerTickFence", cooperativeCompletionFenceServerTick);
        cooperativeEvidence.addProperty("acceptedObservationRequest", latestObservationRequestSequence);
        cooperativeEvidence.addProperty("acceptedServerTick", latestSnapshot.serverTick);
        cooperativeEvidence.addProperty("finalEngineStatus", engine.status());
        addResult(true, 0, "native cooperative command, server movement, control restoration and fresh post-idle observation confirmed", capture(activeCase));
        state = State.CAPTURING; captureStartedAtTick = clientTicks;
    }

    private void beginFixtureSetup() {
        state = State.SETTING_UP;
        preparedSafetyPhase = PREPARED_SAFETY_MODE == null ? PreparedSafetyPhase.NONE
            : PREPARED_SAFETY_MODE.equals("equipment") ? PreparedSafetyPhase.EQUIPMENT
            : PREPARED_SAFETY_MODE.equals("offhand") ? PreparedSafetyPhase.OFFHAND_FOOD
            : PREPARED_SAFETY_MODE.equals("threat") ? PreparedSafetyPhase.THREAT
            : PREPARED_SAFETY_MODE.equals("pursuit") || PREPARED_SAFETY_MODE.equals("pursuit-tool") ? PreparedSafetyPhase.PURSUIT
            : HELD_FUEL_MODE ? PreparedSafetyPhase.HELD_FUEL_SMELTING
            : WORKBENCH_MODE ? PreparedSafetyPhase.WORKBENCH_SEEDING
            : PREPARED_SAFETY_MODE.equals("air") ? PreparedSafetyPhase.AIR : PreparedSafetyPhase.STATION_ROOM;
        preparedSafetySetupStartedAtTick = clientTicks;
        IntegratedServer server = requireServer();
        setupFuture = new CompletableFuture<>();
        CompletableFuture<Long> scheduled = setupFuture;
        server.execute(() -> {
            try {
                ServerPlayerEntity player = requireServerPlayer(server);
                ServerWorld world = server.getOverworld();
                // A bounded, level pad makes the fixture deterministic while retaining normal survival physics.
                for (int x = -12; x <= (PROCESSING_MODE || BULK_WOOD_MODE ? 100 : EXPLORATION_MODE ? 96 : DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE || NEARBY_WOOD_MODE ? 30 : 18); x++) {
                    for (int z = -6; z <= 6; z++) world.setBlockState(new BlockPos(x, FIXTURE_FLOOR_Y, z), Blocks.BEDROCK.getDefaultState(), 3);
                }
                if (MEADOW_BENCHMARK) {
                    for (int x = -12; x <= 30; x++) for (int z = -6; z <= 6; z++) {
                        int rise = Math.max(0, Math.min(3, x / 6));
                        for (int y = FIXTURE_FLOOR_Y; y < FIXTURE_FLOOR_Y + rise; y++)
                            world.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.getDefaultState(), 3);
                        boolean path = z == 0 && x >= 8 && x <= 10;
                        world.setBlockState(new BlockPos(x, FIXTURE_FLOOR_Y + rise, z),
                            (path ? Blocks.DIRT_PATH : Blocks.GRASS_BLOCK).getDefaultState(), 3);
                        if (!path && x > 2 && x < 18 && Math.floorMod(x + z, 3) == 0)
                            world.setBlockState(new BlockPos(x, PLAYER_Y + rise, z), Blocks.DANDELION.getDefaultState(), 3);
                    }
                }
                if (COAL_RECOVERY_MODE) {
                    coalNavigationCourseCommandStarted = false;
                    coalNavigationCourseObservedMask = 0;
                    coalNavigationExpectedStates = Map.of();
                    coalDropInitialServerPosition = null;
                    coalDropCompletedEdge = null;
                    coalDropServerCheckpoint = null;
                    coalDropCommandStarted = false;
                    for (int index = 0; index < coalNavigationCourseCheckpointServerTicks.length; index++) {
                        coalNavigationCourseCheckpointServerTicks[index] = -1;
                    }
                    Map<BlockPos, BlockState> mixedCourseStates = null;
                    if (MIXED_NAVIGATION_COURSE) {
                        mixedCourseStates = setupMixedCoalNavigationCourse(world);
                    } else {
                        if (COAL_START_SURFACE.equals("farmland")) {
                            world.setBlockState(new BlockPos(-3, FIXTURE_FLOOR_Y, 0), Blocks.WATER.getDefaultState(), 3);
                        }
                        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                            world.setBlockState(new BlockPos(x, coalStartSurfaceY(), z), coalStartFloor().getDefaultState(), 3);
                        }
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                world.setBlockState(COAL_RECOVERY_ENCASED_ORE.add(dx, dy, dz), Blocks.BEDROCK.getDefaultState(), 3);
                            }
                        }
                    }
                    world.setBlockState(COAL_RECOVERY_ENCASED_ORE, Blocks.COAL_ORE.getDefaultState(), 3);
                    world.setBlockState(coalRecoveryAccessibleOrePosition(), Blocks.COAL_ORE.getDefaultState(), 3);
                    coalNavigationExpectedStates = MIXED_NAVIGATION_COURSE
                        ? mixedCoalNavigationExpectedStates(mixedCourseStates) : Map.of();
                } else if (!PROCESSING_MODE && PREPARED_SAFETY_MODE == null && !WORLD_POLICY_MODE && !ANIMAL_MODE && !COOPERATIVE_MODE) {
                    int oakLogStartX = NEARBY_WOOD_LOCAL_DECOY_MODE ? NEARBY_WOOD_LOCAL_VISIBLE_LOG.getX()
                        : IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE ? IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X
                        : BULK_WOOD_MODE ? 6 : EXPLORATION_MODE ? 80 : NEARBY_WOOD_MODE ? 20 : 6;
                    int oakLogCount = NEARBY_WOOD_LOCAL_DECOY_MODE ? 1 : BULK_WOOD_MODE ? 80
                        : IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE ? IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT : 8;
                    for (int index = 0; index < oakLogCount; index++) {
                        world.setBlockState(new BlockPos(oakLogStartX + index, PLAYER_Y + (MEADOW_BENCHMARK ? 3 : 0), 0), Blocks.OAK_LOG.getDefaultState(), 3);
                    }
                    if (NEARBY_WOOD_LOCAL_DECOY_MODE) {
                        for (int x = 2; x <= 4; x++) for (int z = -1; z <= 1; z++) {
                            world.setBlockState(new BlockPos(x, PLAYER_Y + 2, z),
                                Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true), 3);
                        }
                        world.setBlockState(NEARBY_WOOD_LOCAL_DECOY_LOG, Blocks.OAK_LOG.getDefaultState(), 3);
                        for (BlockPos shellPosition : NEARBY_WOOD_LOCAL_DECOY_SHELL) {
                            world.setBlockState(shellPosition, Blocks.BEDROCK.getDefaultState(), 3);
                        }
                    }
                    if (!BULK_WOOD_MODE && !NEARBY_WOOD_LOCAL_DECOY_MODE) {
                        for (int x = 6; x <= (DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE ? 25 : 17); x++) {
                            world.setBlockState(new BlockPos(x, PLAYER_Y, 2), Blocks.STONE.getDefaultState(), 3);
                        }
                        if (DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE) {
                            world.setBlockState(new BlockPos(16, PLAYER_Y, 4), Blocks.COAL_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(17, PLAYER_Y, 4), Blocks.COAL_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(18, PLAYER_Y, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(16, PLAYER_Y + 1, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(17, PLAYER_Y + 1, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                            for (int x = 20; x <= 23; x++) {
                                world.setBlockState(new BlockPos(x, PLAYER_Y, 4), Blocks.DIAMOND_ORE.getDefaultState(), 3);
                            }
                            if (IRON_PICKAXE_MODE) {
                                for (int index = 0; index < IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT; index++) {
                                    world.setBlockState(new BlockPos(16 + index, 20, 4), Blocks.DEEPSLATE.getDefaultState(), 3);
                                }
                            }
                        } else {
                            world.setBlockState(new BlockPos(16, PLAYER_Y, 4), Blocks.COAL_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(17, PLAYER_Y, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                            Block rubyOre = Registries.BLOCK.get(GameApi.identifier(VerificationContentInitializer.RUBY_ORE_ID));
                            if (rubyOre == Blocks.AIR) throw new IllegalStateException("verifier ruby ore was not registered");
                            for (int index = 0; index < 4; index++) {
                                world.setBlockState(new BlockPos(8 + index, PLAYER_Y, 4), rubyOre.getDefaultState(), 3);
                            }
                        }
                    }
                }
                if (WORLD_POLICY_MODE) {
                    for (WorldPolicyFace face : WORLD_POLICY_FACES) {
                        world.setBlockState(face.position(), Blocks.STONE.getDefaultState(), 3);
                    }
                    for (WorldPolicyPlacementProbe probe : WORLD_POLICY_PLACEMENT_PROBES) {
                        if (probe.clicked().getY() != FIXTURE_FLOOR_Y) {
                            world.setBlockState(probe.clicked(), Blocks.BEDROCK.getDefaultState(), 3);
                        }
                    }
                    world.setBlockState(WORLD_POLICY_SUPPORT, Blocks.STONE.getDefaultState(), 3);
                    world.setBlockState(WORLD_POLICY_TORCH, Blocks.TORCH.getDefaultState(), 3);
                    world.setBlockState(WORLD_POLICY_PREFERRED_FURNACE, Blocks.FURNACE.getDefaultState(), 3);
                    world.setBlockState(WORLD_POLICY_ORDINARY_FURNACE, Blocks.FURNACE.getDefaultState(), 3);
                    world.setBlockState(WORLD_POLICY_PREFERRED_TABLE, Blocks.CRAFTING_TABLE.getDefaultState(), 3);
                    world.setBlockState(WORLD_POLICY_ORDINARY_TABLE, Blocks.CRAFTING_TABLE.getDefaultState(), 3);
                }
                clearInventory(player.getInventory());
                if (PROCESSING_MODE) seedCookingInventory(player);
                if (WORLD_POLICY_MODE && (!player.getInventory().insertStack(new ItemStack(Items.STONE_PICKAXE))
                        || !player.getInventory().insertStack(new ItemStack(Items.FURNACE)))) {
                    throw new IllegalStateException("could not seed the world-policy pickaxe and duplicate-placement probe furnace");
                }
                if (COAL_RECOVERY_MODE && !player.getInventory().insertStack(new ItemStack(Items.STONE_PICKAXE))) {
                    throw new IllegalStateException("could not seed the single coal-recovery verifier stone pickaxe");
                }
                if (IRON_PICKAXE_MODE && !IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                        && !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
                    throw new IllegalStateException("could not seed the single iron-pickaxe verifier crafting table");
                }
                player.setHealth(player.getMaxHealth());
                player.getHungerManager().setFoodLevel(20);
                if (ANIMAL_MODE) nativeAnimalFixture = nativeAnimalApi(ANIMAL_NO_SCAFFOLD
                        ? "seedNativeAnimalNoScaffold" : "seedNativeAnimal", player, world, ANIMAL_SCENARIO);
                if (SHIELD_MODE) {
                    server.setDifficulty(Difficulty.NORMAL, true);
                    shieldFixture = shieldApi("seedShieldScenario", player, world, SHIELD_SCENARIO);
                }
                if (PREPARED_SAFETY_MODE != null) {
                    server.setDifficulty(Difficulty.NORMAL, true);
                    if (preparedSafetyPhase == PreparedSafetyPhase.WORKBENCH_SEEDING) {
                        VerificationApi.seedPreparedSafetyWorkbench(player, world);
                    } else if (preparedSafetyPhase == PreparedSafetyPhase.THREAT) {
                        preparedSafetyThreatFixture = VerificationApi.seedPreparedSafetyThreatFixture(player, world);
                    } else if (preparedSafetyPhase == PreparedSafetyPhase.PURSUIT) {
                        preparedSafetyPursuitFixture = VerificationApi.seedPreparedSafetyPursuitFixture(player, world);
                    } else if (preparedSafetyPhase == PreparedSafetyPhase.STATION_ROOM) {
                        preparedSafetyStationRoomFixture = VerificationApi.seedPreparedSafetyStationRoomFixture(player, world);
                    } else if (preparedSafetyPhase == PreparedSafetyPhase.AIR) {
                        preparedSafetyAirFixture = VerificationApi.seedPreparedSafetyAirFixture(player, world);
                    } else {
                        VerificationApi.seedPreparedSafetyFixture(player, PREPARED_SAFETY_MODE);
                    }
                }
                double startFeetY = STATION_ROOM_APPROACH_MODE ? 65.0 : COAL_RAISED_FULL_DROP_MODE ? PLAYER_Y + 1.0 : PLAYER_Y;
                if (!VerificationApi.teleport(player, world,
                        STATION_ROOM_APPROACH_MODE ? 1.5 : STATION_ROOM_TUNNEL_MODE ? 0.367555 : MIXED_NAVIGATION_COURSE ? 0.25 : 0.5, startFeetY,
                        STATION_ROOM_TUNNEL_MODE ? 0.505802 : MIXED_NAVIGATION_COURSE ? 0.75 : 0.5,
                        STATION_ROOM_TUNNEL_MODE ? 98.886902F : THREAT_CONTACT_MODE || THREAT_CREEPER_CONTACT_MODE ? -90.0F : 0.0F, STATION_ROOM_TUNNEL_MODE ? -38.467983F : 0.0F)) {
                    throw new IllegalStateException("could not teleport verifier player to the fixture spawn");
                }
                if (preparedSafetyAirFixture != null) {
                    VerificationApi.initializePreparedSafetyAirFixture(preparedSafetyAirFixture, player, server.getTicks());
                }
                if (GEOMETRY_EPOCH_MODE) GeometryEpochVerification.prepareServerFixture(world);
                player.currentScreenHandler.sendContentUpdates();
                scheduled.complete((long) server.getTicks());
            } catch (Throwable throwable) {
                scheduled.completeExceptionally(throwable);
            }
        });
    }

    private static void clearInventory(PlayerInventory inventory) {
        inventory.clear();
        inventory.markDirty();
    }

    private static boolean isSupportedCookingStationMode() {
        return "smoker".equals(COOKING_STATION_MODE) || "blast_furnace".equals(COOKING_STATION_MODE);
    }

    private static Item cookingRawItem() {
        return STONECUTTING_MODE ? Items.STONE : "smoker".equals(COOKING_STATION_MODE) ? Items.PORKCHOP : Items.RAW_IRON;
    }

    private static Item cookingStationItem() {
        return STONECUTTING_MODE ? Items.STONECUTTER : "smoker".equals(COOKING_STATION_MODE) ? Items.SMOKER : Items.BLAST_FURNACE;
    }

    private static String cookingRawItemId() {
        return STONECUTTING_MODE ? "minecraft:stone" : "smoker".equals(COOKING_STATION_MODE) ? RAW_PORKCHOP_ID : RAW_IRON_ID;
    }

    private static String cookingOutputId() {
        return STONECUTTING_MODE ? "minecraft:stone_slab" : "smoker".equals(COOKING_STATION_MODE) ? COOKED_PORKCHOP_ID : IRON_INGOT_ID;
    }

    private static String cookingRecipeType() {
        if (STONECUTTING_MODE) return "stonecutting";
        return switch (COOKING_STATION_MODE == null ? "" : COOKING_STATION_MODE) {
            case "smoker" -> "smoking";
            case "blast_furnace" -> "blasting";
            default -> "unsupported";
        };
    }

    private static String cookingOutputCommandName() {
        return STONECUTTING_MODE ? "stone_slab" : "smoker".equals(COOKING_STATION_MODE) ? "cooked_porkchop" : "iron_ingot";
    }

    private static Map<String, Integer> cookingProvidedStock() {
        if (STONECUTTING_MODE) return Map.of("minecraft:stone", 128, "minecraft:stonecutter", 1);
        return Map.of(cookingRawItemId(), 128, "minecraft:coal", 9,
            "minecraft:" + PROCESSING_STATION_MODE, 1);
    }

    private static void seedCookingInventory(ServerPlayerEntity player) {
        for (int stack = 0; stack < 2; stack++) {
            if (!player.getInventory().insertStack(new ItemStack(cookingRawItem(), 64))) {
                throw new IllegalStateException("could not seed two 64-item raw cooking stacks");
            }
        }
        if (!STONECUTTING_MODE && !player.getInventory().insertStack(new ItemStack(Items.COAL, 9))) {
            throw new IllegalStateException("could not seed nine verifier coal");
        }
        if (!player.getInventory().insertStack(new ItemStack(cookingStationItem(), 1))) {
            throw new IllegalStateException("could not seed the verifier cooking station item");
        }
    }

    private void startCookingCommand() {
        activeCase = STONECUTTING_DRAIN_MODE ? "native_stonecutter_drain_after_insertion"
            : STONECUTTING_MODE ? "native_stonecutter_144_slabs" : "native_" + COOKING_STATION_MODE + "_72";
        activeItem = cookingOutputId();
        activeCount = STONECUTTING_DRAIN_MODE ? 0 : STONECUTTING_MODE ? 144 : 72;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        beginCaseClock();
        sendCommand("!lk get " + cookingOutputCommandName() + " "
            + (STONECUTTING_DRAIN_MODE ? STONECUTTING_DRAIN_COMMAND_TARGET : activeCount));
        state = State.COOKING;
    }

    private void startIronPickaxePlannerProbe() {
        if (ironPickaxeProbeStarted) return;
        ironPickaxeProbeStarted = true;
        ironPickaxeProbeStartedAtTick = clientTicks;
        ironPickaxeProbeCatalog = new GameCatalog(client);
        ironPickaxePlannerProbeEvidence = new JsonObject();
        ironPickaxePlannerProbeEvidence.addProperty("status", "loading");
        ironPickaxePlannerProbeEvidence.addProperty("planningPreferences", "none");
        ironPickaxePlannerProbeEvidence.addProperty("isGameplayEvidence", false);
        ironPickaxePlannerProbeEvidence.addProperty("inventory", IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
            ? "empty" : "minecraft:crafting_table x1 only");
        try {
            ironPickaxeProbeCatalog.load();
        } catch (RuntimeException exception) {
            completeIronPickaxeProbeUnavailable("catalog load failed: " + exception);
        }
    }

    private void tickIronPickaxePlannerProbe() {
        if (!IRON_PICKAXE_MODE || !ironPickaxeProbeStarted || ironPickaxeProbeFinished) return;
        if (ironPickaxeProbeCatalog != null && ironPickaxeProbeCatalog.ready()) {
            try {
                CatalogSnapshot catalogSnapshot = ironPickaxeProbeCatalog.snapshot();
                Map<ItemId, Integer> plannerInventory = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                    ? Map.of() : Map.of(ItemId.parse("minecraft:crafting_table"), 1);
                InventorySnapshot inventory = new InventorySnapshot(plannerInventory);
                AcquisitionPlanner planner = new AcquisitionPlanner();
                PlanResult ironPickaxe = planner.planFast(catalogSnapshot, inventory,
                    ItemId.parse("minecraft:iron_pickaxe"), 1);
                PlanResult stonePickaxe = planner.planFast(catalogSnapshot, inventory,
                    ItemId.parse("minecraft:stone_pickaxe"), 1);
                ironPickaxePlannerProbeEvidence = ironPickaxePlannerEvidence(catalogSnapshot, inventory, ironPickaxe, stonePickaxe);
                ironPickaxeProbeFinished = true;
                writeIronPickaxeCatalogSnapshot(catalogSnapshot);
                logPlannerProbeResult("iron_pickaxe", ironPickaxe);
                logPlannerProbeResult("stone_pickaxe", stonePickaxe);
            } catch (RuntimeException exception) {
                completeIronPickaxeProbeUnavailable("planner probe failed: " + exception);
            }
        } else if (clientTicks - ironPickaxeProbeStartedAtTick >= IRON_PICKAXE_PROBE_TIMEOUT_TICKS) {
            completeIronPickaxeProbeUnavailable("catalog was not ready within "
                + IRON_PICKAXE_PROBE_TIMEOUT_TICKS + " client ticks");
        }
    }

    private void completeIronPickaxeProbeUnavailable(String reason) {
        if (ironPickaxePlannerProbeEvidence == null) ironPickaxePlannerProbeEvidence = new JsonObject();
        ironPickaxePlannerProbeEvidence.addProperty("status", "unavailable");
        ironPickaxePlannerProbeEvidence.addProperty("failure", reason);
        ironPickaxePlannerProbeEvidence.addProperty("planningPreferences", "none");
        ironPickaxePlannerProbeEvidence.addProperty("isGameplayEvidence", false);
        ironPickaxeProbeFinished = true;
        System.err.println("[Lodekeeper verification] Iron-pickaxe planner probe unavailable: " + reason);
    }

    private JsonObject ironPickaxePlannerEvidence(CatalogSnapshot catalogSnapshot, InventorySnapshot inventory,
                                                   PlanResult ironPickaxe, PlanResult stonePickaxe) {
        JsonObject probe = new JsonObject();
        probe.addProperty("status", "complete");
        probe.addProperty("catalogReady", true);
        probe.addProperty("catalogGeneration", ironPickaxeProbeCatalog.generation());
        probe.addProperty("planningPreferences", "none");
        probe.addProperty("isGameplayEvidence", false);
        JsonObject inventoryItems = new JsonObject();
        inventory.counts().forEach((item, count) -> inventoryItems.addProperty(item.toString(), count));
        probe.add("inventoryCounts", inventoryItems);
        probe.add("ironPickaxe", plannerResultEvidence(ironPickaxe));
        probe.add("stonePickaxe", plannerResultEvidence(stonePickaxe));
        return probe;
    }

    private void writeIronPickaxeCatalogSnapshot(CatalogSnapshot catalogSnapshot) {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject export = new JsonObject();
        export.addProperty("format", "lodekeeper.catalog_snapshot.v1");
        export.addProperty("minecraftVersion", VerificationApi.minecraftVersion());
        export.addProperty("catalogGeneration", ironPickaxeProbeCatalog.generation());
        JsonArray items = new JsonArray();
        ironPickaxeProbeCatalog.items.stream().sorted().forEach(item -> items.add(item.toString()));
        export.add("items", items);
        JsonArray definitions = new JsonArray();
        catalogSnapshot.itemDefinitions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject definition = new JsonObject();
            definition.addProperty("item", entry.getKey().toString());
            definition.addProperty("maximumDurability", entry.getValue().maximumDurability());
            definition.addProperty("fuelBurnTicks", entry.getValue().fuelBurnTicks());
            JsonArray aliases = new JsonArray();
            entry.getValue().aliases().forEach(aliases::add);
            definition.add("aliases", aliases);
            definitions.add(definition);
        });
        export.add("itemDefinitions", definitions);
        JsonArray tags = new JsonArray();
        ironPickaxeProbeCatalog.tags.keySet().stream().sorted().forEach(tag -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("tag", tag.toString());
            JsonArray members = new JsonArray();
            ironPickaxeProbeCatalog.tags.get(tag).stream().sorted().forEach(item -> members.add(item.toString()));
            entry.add("items", members);
            tags.add(entry);
        });
        export.add("tags", tags);
        JsonArray sources = new JsonArray();
        ironPickaxeProbeCatalog.sources.stream().sorted((left, right) -> left.sourceId().compareTo(right.sourceId())).forEach(source -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("sourceId", source.sourceId());
            entry.addProperty("sourceType", source.sourceType());
            entry.addProperty("output", source.output().toString());
            entry.addProperty("outputCount", source.outputCount());
            entry.addProperty("definition", source.toString());
            entry.addProperty("definitionType", source.getClass().getSimpleName());
            entry.add("definitionJson", gson.toJsonTree(source));
            sources.add(entry);
        });
        export.add("sources", sources);
        JsonArray unsupported = new JsonArray();
        ironPickaxeProbeCatalog.unsupported.forEach(unsupported::add);
        export.add("unsupported", unsupported);
        String fileName = "iron-pickaxe-catalog-" + runId + ".json";
        try {
            Files.writeString(evidenceDirectory.resolve(fileName),
                gson.toJson(export), StandardCharsets.UTF_8);
            ironPickaxePlannerProbeEvidence.addProperty("catalogSnapshotFile", fileName);
            ironPickaxePlannerProbeEvidence.addProperty("knownItemCount", ironPickaxeProbeCatalog.items.size());
            ironPickaxePlannerProbeEvidence.addProperty("sourceCount", ironPickaxeProbeCatalog.sources.size());
            ironPickaxePlannerProbeEvidence.addProperty("tagCount", ironPickaxeProbeCatalog.tags.size());
        } catch (IOException exception) {
            ironPickaxePlannerProbeEvidence.addProperty("catalogExportFailure", exception.toString());
        }
    }

    private static JsonObject plannerResultEvidence(PlanResult result) {
        JsonObject plan = new JsonObject();
        plan.addProperty("target", result.target().toString());
        plan.addProperty("requestedCount", result.requestedCount());
        plan.addProperty("success", result.success());
        plan.addProperty("optimal", result.optimal());
        plan.addProperty("expandedNodes", result.expandedNodes());
        plan.addProperty("elapsedNanos", result.elapsedNanos());
        JsonArray blockedReasons = new JsonArray();
        for (BlockedReason reason : result.blockedReasons()) {
            JsonObject item = new JsonObject();
            item.addProperty("code", reason.code().name());
            if (reason.item() != null) item.addProperty("item", reason.item().toString());
            item.addProperty("detail", reason.detail());
            JsonArray path = new JsonArray();
            reason.dependencyPath().forEach(dependency -> path.add(dependency.toString()));
            item.add("dependencyPath", path);
            blockedReasons.add(item);
        }
        plan.add("blockedReasons", blockedReasons);
        JsonArray steps = new JsonArray();
        for (PlanStep step : result.steps()) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", step.kind().name());
            item.addProperty("sourceId", step.sourceId());
            if (step.output() != null) item.addProperty("output", step.output().toString());
            item.addProperty("outputCount", step.outputCount());
            item.addProperty("operationCount", step.operationCount());
            if (step.station() != null) item.addProperty("station", step.station().toString());
            JsonArray requirements = new JsonArray();
            step.requirements().forEach(requirement -> requirements.add(requirement.toString()));
            item.add("requirements", requirements);
            steps.add(item);
        }
        plan.add("steps", steps);
        return plan;
    }

    private static void logPlannerProbeResult(String name, PlanResult result) {
        System.out.println("[Lodekeeper verification] Planner probe " + name + " success=" + result.success()
            + ", expandedNodes=" + result.expandedNodes() + ", elapsedNanos=" + result.elapsedNanos()
            + ", blockedReasons=" + result.blockedReasons());
        result.blockedReasons().forEach(reason -> System.err.println("[Lodekeeper verification] Planner probe "
            + name + " blocked " + reason.code() + " at " + reason.item() + ": " + reason.detail()
            + " path=" + reason.dependencyPath()));
    }

    private void startIronPickaxeCommand() {
        activeCase = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
            ? "iron_pickaxe_from_empty_inventory_with_oak_logs_x"
                + IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + "_through_x"
                + (IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT - 1)
            : "iron_pickaxe_from_crafting_table_only";
        activeItem = IRON_PICKAXE_ID;
        activeCount = 1;
        activeRequiresEmpty = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        beginCaseClock();
        sendCommand("!lk get iron_pickaxe");
        state = State.GATHERING_WOOD;
    }

    private static int routeBenchmarkIntProperty(String name, int fallback, int minimum, int maximum) {
        String value = System.getProperty("lodekeeper.verify." + name, Integer.toString(fallback));
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
    }

    private static String routePathFingerprint(dev.lodekeeper.nav.Path path) {
        if (path == null) return "none";
        StringBuilder encoded = new StringBuilder().append(path.cost).append('/').append(path.placementsReserved);
        for (int index = 0; index < path.length(); index++) {
            dev.lodekeeper.nav.Path.Step step = path.step(index);
            encoded.append('|').append(step.x).append(',').append(step.feetY16).append(',')
                .append(step.z).append(',').append(step.movement);
            for (int actionIndex = 0; actionIndex < step.actionCount(); actionIndex++) {
                dev.lodekeeper.nav.Action action = step.action(actionIndex);
                encoded.append(';').append(action.type).append(',').append(action.x).append(',')
                    .append(action.y).append(',').append(action.z).append(',').append(action.token);
            }
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 provider is unavailable", exception);
        }
    }

    /** Same native terrain, policy and target across builds; timing is evidence, not a flaky pass threshold. */
    private void benchmarkNativeRoute() {
        routeBenchmark = new JsonArray();
        int samples = routeBenchmarkIntProperty("routeBenchmarkSamples", 4, 4, 16);
        int warmups = routeBenchmarkIntProperty("routeBenchmarkWarmups", 1, 1, 4);
        if (warmups > samples - 2) throw new IllegalArgumentException("routeBenchmarkWarmups must leave at least two measured samples");
        var cpuClock = java.lang.management.ManagementFactory.getThreadMXBean();
        boolean cpuClockAvailable = cpuClock.isCurrentThreadCpuTimeSupported() && cpuClock.isThreadCpuTimeEnabled();
        boolean comparePruning = Boolean.getBoolean("lodekeeper.verify.compareTransitionPruning");
        if (comparePruning && (samples - warmups < 10 || (samples & 1) != 0 || (warmups & 1) != 0))
            throw new IllegalArgumentException("Paired pruning benchmark requires even warmups and samples, with at least five measured samples per side");
        for (int sample = 0; sample < samples; sample++) {
            GameTerrain terrain = new GameTerrain(client, requireEngine().config);
            terrain.beginSearch();
            long initialVoxelQueries = terrain.voxelQueries, initialReadMisses = terrain.readMisses;
            long initialShapeMisses = terrain.shapeMisses, initialChunkQueries = terrain.chunkQueries;
            long initialChunkMisses = terrain.chunkMisses;
            var options = new dev.lodekeeper.nav.Planner.Options().maxNodes(16000).maxDrop(3).allowBreaking(true);
            options.pruneDominatedTransitions = !comparePruning || (sample & 1) != 0;
            long startedCpu = cpuClockAvailable ? cpuClock.getCurrentThreadCpuTime() : -1L;
            long started = System.nanoTime();
            dev.lodekeeper.nav.Planner planner = new dev.lodekeeper.nav.Planner(terrain, 0, PLAYER_Y, -3,
                dev.lodekeeper.nav.Goal.exact(20, PLAYER_Y + (MEADOW_BENCHMARK ? 3 : 0), -3),
                options);
            int calls = 0;
            while (planner.getStatus() == dev.lodekeeper.nav.NavStatus.IN_PROGRESS && calls < 10000
                    && System.nanoTime() - started < 5_000_000_000L) {
                planner.advance(128, 2_000_000L);
                calls++;
            }
            long elapsedNanos = System.nanoTime() - started;
            long threadCpuNanos = startedCpu < 0L ? -1L : cpuClock.getCurrentThreadCpuTime() - startedCpu;
            JsonObject row = new JsonObject();
            row.addProperty("warmup", sample < warmups);
            row.addProperty("sampleIndex", sample);
            row.addProperty("transitionPruning", options.pruneDominatedTransitions);
            row.addProperty("elapsedNanos", elapsedNanos);
            row.addProperty("threadCpuNanos", threadCpuNanos);
            row.addProperty("groundedCollectionRequests", planner.getGroundedCollectionRequests());
            row.addProperty("groundedCollectionCalls", planner.getGroundedCollectionCalls());
            row.addProperty("dominatedTransitionsSkipped", planner.getDominatedTransitionsSkipped());
            row.addProperty("voxelQueries", terrain.voxelQueries - initialVoxelQueries);
            row.addProperty("readMisses", terrain.readMisses - initialReadMisses);
            row.addProperty("shapeMisses", terrain.shapeMisses - initialShapeMisses);
            row.addProperty("chunkQueries", terrain.chunkQueries - initialChunkQueries);
            row.addProperty("chunkMisses", terrain.chunkMisses - initialChunkMisses);
            row.addProperty("pathFingerprintSha256", routePathFingerprint(planner.getPath()));
            row.addProperty("advanceCalls", calls);
            row.addProperty("expanded", planner.getExpandedNodes());
            row.addProperty("discovered", planner.getDiscoveredNodes());
            row.addProperty("status", planner.getStatus().name());
            row.addProperty("pathSteps", planner.getPath() == null ? 0 : planner.getPath().length());
            routeBenchmark.add(row);
            if (planner.getStatus() != dev.lodekeeper.nav.NavStatus.FOUND) {
                fail("Native 20-block benchmark route failed: " + row);
                return;
            }
        }
        System.out.println("[Lodekeeper verification] Native route benchmark: " + routeBenchmark);
        try {
            Files.writeString(Path.of("navigation-benchmark.json"), routeBenchmark.toString());
        } catch (IOException exception) { fail("Cannot save native benchmark: " + exception); }
    }

    private void maybeInjectStonecuttingDrainStop() {
        if (!STONECUTTING_DRAIN_MODE || state != State.COOKING || stonecuttingDrainStopAttempted
                || client.player == null || client.player.currentScreenHandler == null
                || client.player.currentScreenHandler.getClass() != net.minecraft.screen.StonecutterScreenHandler.class) return;
        net.minecraft.screen.StonecutterScreenHandler handler =
            (net.minecraft.screen.StonecutterScreenHandler) client.player.currentScreenHandler;
        ItemStack ownedInput = handler.getSlot(0).getStack();
        if (ownedInput.isEmpty() || !ownedInput.isOf(Items.STONE) || !handler.getCursorStack().isEmpty()) return;
        if (ClientAccess.main(client.player.getInventory()).stream().anyMatch(stack -> stack.isOf(Items.STONE_SLAB))) {
            fail("stonecutting drain stop was not injected before the first slab output");
            return;
        }

        stonecuttingDrainStopAttempted = true;
        stonecuttingDrainInputCountAtStop = ownedInput.getCount();
        stonecuttingDrainStopClientTick = clientTicks;
        stonecuttingDrainStopServerTick = latestSnapshot == null ? -1 : latestSnapshot.serverTick;
        stonecuttingDrainRequiredObservationSequence = observationRequestSequence + 1;
        sendCommand("!lk stop");
        stonecuttingDrainStopInjected = true;
        System.out.println("[Lodekeeper verification] Injected !lk stop with " + stonecuttingDrainInputCountAtStop
            + " owned stone in the native stonecutter at client tick " + stonecuttingDrainStopClientTick);
    }

    private void startGatherCommand() {
        if (GEOMETRY_EPOCH_MODE && !geometryEpochComplete) {
            if (geometryEpoch == null) geometryEpoch = new GeometryEpochVerification();
            try {
                JsonObject result = geometryEpoch.advance(client, requireEngine().config);
                if (result == null) return;
                geometryEpochComplete = true;
            } catch (RuntimeException exception) {
                geometryEpoch.markFailure(exception.getMessage());
                fail("Geometry epoch verification failed: " + exception.getMessage());
                return;
            }
        }
        if (NEARBY_WOOD_MODE) {
            if (!BARITONE_MODE && !NEARBY_WOOD_LOCAL_DECOY_MODE && !GEOMETRY_EPOCH_MODE) {
                benchmarkNativeRoute();
                if (state == State.FAILED) return;
            }
            activeCase = NEARBY_WOOD_LOCAL_DECOY_MODE
                ? "nearby_" + NEARBY_WOOD_GOAL + "_local_decoy"
                : "nearby_" + NEARBY_WOOD_GOAL + "_20_blocks" + (MEADOW_BENCHMARK ? "_meadow" : "");
            activeItem = NEARBY_WOOD_GOAL.equals("wood") ? OAK_LOG_ID : "minecraft:crafting_table";
            activeCount = 1;
            activeRequiresEmpty = true;
            activeStartedEmpty = latestSnapshot.inventoryEmpty();
            if (NEARBY_WOOD_LOCAL_DECOY_MODE) {
                nearbyWoodLocalFixtureReadyAtCommandStart = nearbyWoodLocalFixtureReady(latestSnapshot);
            }
            beginCaseClock();
            sendCommand("!lk get " + NEARBY_WOOD_GOAL + " 1");
            state = State.GATHERING_WOOD;
            return;
        }
        if (BULK_WOOD_MODE) {
            activeCase = "bulk_wood_64";
            activeItem = OAK_LOG_ID;
            activeCount = 64;
            activeRequiresEmpty = true;
            activeStartedEmpty = latestSnapshot.inventoryEmpty();
            beginCaseClock();
            sendCommand("!lk get wood 64");
            state = State.GATHERING_WOOD;
            return;
        }
        if (DIAMOND_BOOTSTRAP_MODE) {
            activeCase = "bootstrap_diamond_boots";
            activeItem = "minecraft:diamond_boots";
            activeCount = 1;
            activeRequiresEmpty = true;
            activeStartedEmpty = latestSnapshot.inventoryEmpty();
            beginCaseClock();
            sendCommand("!lk get diamond_boots");
            state = State.GATHERING_WOOD;
            return;
        }
        activeCase = EXPLORATION_MODE ? "explore_to_unloaded_wood_8" : "gather_wood_8";
        if (EXPLORATION_MODE) {
            resourceInitiallyLoaded = client.world.getChunkManager().getChunk(5,0,
                net.minecraft.world.chunk.ChunkStatus.FULL,false) != null;
            if (resourceInitiallyLoaded) { fail("far resource chunk was already loaded at goal start"); return; }
            if (!requireEngine().config.allowExploration) { fail("exploration policy was disabled in verifier config"); return; }
        }
        activeItem = "minecraft:oak_log";
        activeCount = 8;
        activeRequiresEmpty = true;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get wood 8");
        state = State.GATHERING_WOOD;
    }

    private boolean verifyFenceGeometry() {
        GameTerrain terrain = new GameTerrain(client, requireEngine().config);
        terrain.beginSearch();
        dev.lodekeeper.nav.StanceProbe probe = new dev.lodekeeper.nav.StanceProbe();
        dev.lodekeeper.nav.StanceProbe empty = new dev.lodekeeper.nav.StanceProbe().clear();
        BlockPos fence = new BlockPos(-8, 64, 0);
        terrain.probeStance(-8, 65, 0, probe);
        boolean loadedFence = probe.loaded && client.world.getBlockState(fence).isOf(Blocks.OAK_FENCE);
        boolean bodyBlocked = loadedFence && !probe.bodyClear;
        boolean pointBlocked = !terrain.isMotionClear(-7.5, 65, 0.5, -7.5, 65, 0.5, 0.0, empty);
        boolean sweepBlocked = !terrain.isMotionClear(-6.5, 65, 0.5, -7.5, 65, 0.5, 0.0, empty);
        coalNavigationFenceGeometryEvidence = new JsonObject();
        coalNavigationFenceGeometryEvidence.addProperty("block", "minecraft:oak_fence");
        coalNavigationFenceGeometryEvidence.addProperty("position", "-8,64,0");
        coalNavigationFenceGeometryEvidence.addProperty("queryFeetY", 65);
        coalNavigationFenceGeometryEvidence.addProperty("loadedFenceObserved", loadedFence);
        coalNavigationFenceGeometryEvidence.addProperty("belowFeetCollisionBlockedStanceBody", bodyBlocked);
        coalNavigationFenceGeometryEvidence.addProperty("belowFeetCollisionBlockedPoint", pointBlocked);
        coalNavigationFenceGeometryEvidence.addProperty("belowFeetCollisionBlockedSweep", sweepBlocked);
        boolean verified = loadedFence && bodyBlocked && pointBlocked && sweepBlocked;
        coalNavigationFenceGeometryEvidence.addProperty("verified", verified);
        if (!verified) fail("Native below-feet fence geometry preflight failed: " + coalNavigationFenceGeometryEvidence);
        return verified;
    }

    private static Object readTerrainDiagnosticField(GameTerrain terrain, String name) {
        try {
            Field field = GameTerrain.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(terrain);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect native navigation diagnostic " + name, failure);
        }
    }

    private void recordMixedNavigationDiagnostics() {
        GameTerrain terrain = new GameTerrain(client, requireEngine().config);
        terrain.beginSearch();
        int[] heights = {1024, 1032, 1040, 1040, 1039, 1039, 1032, 1040,
            1042, 1046, 1054, 1056, 1072, 1072, 1071, 1072, 1088};
        dev.lodekeeper.nav.StanceProbe previous = new dev.lodekeeper.nav.StanceProbe();
        dev.lodekeeper.nav.StanceProbe current = new dev.lodekeeper.nav.StanceProbe();
        dev.lodekeeper.nav.GroundedStanceBuffer candidates = new dev.lodekeeper.nav.GroundedStanceBuffer();
        coalNavigationRouteDiagnostics = new JsonArray();
        for (int x = 0; x < heights.length; x++) {
            terrain.probeStance16(x, heights[x], 0, current);
            JsonObject row = new JsonObject();
            row.addProperty("x", x);
            row.addProperty("feetY16", heights[x]);
            row.addProperty("loaded", current.loaded);
            row.addProperty("bodyClear", current.bodyClear);
            row.addProperty("supported", current.hasGroundSupport());
            row.addProperty("hazard", current.hazard);
            row.addProperty("breakCount", current.breakCount);
            terrain.collectGroundedStances(x, heights[x], 0, candidates);
            JsonArray candidateArray = new JsonArray();
            for (int i = 0; i < candidates.size(); i++) candidateArray.add(candidates.get(i));
            row.add("candidateFeetY16", candidateArray);
            row.addProperty("candidatesComplete", candidates.isComplete());
            if (x > 0) {
                int[] resolved = (int[]) readTerrainDiagnosticField(terrain, "walkResolvedHeights");
                java.util.Arrays.fill(resolved, Integer.MIN_VALUE);
                boolean clear = terrain.isGroundedWalkClear(x - 0.5, heights[x - 1], 0.5,
                    x + 0.5, heights[x], 0.5, previous, current);
                row.addProperty("incomingGroundedWalkClear", clear);
                if (!clear) {
                    dev.lodekeeper.nav.MotionEventBuffer events =
                        (dev.lodekeeper.nav.MotionEventBuffer) readTerrainDiagnosticField(terrain, "walkEvents");
                    int count = events.size();
                    JsonArray profile = new JsonArray();
                    for (int i = 0; i < count; i++) {
                        JsonObject point = new JsonObject();
                        point.addProperty("t", events.get(i));
                        point.addProperty("resolvedY16", resolved[i]);
                        profile.add(point);
                    }
                    row.add("failedWalkProfile", profile);
                    row.addProperty("shapeIncomplete", (Boolean) readTerrainDiagnosticField(terrain, "shapeIncomplete"));
                }
            }
            if (x == 1) row.addProperty("actualStartGroundedWalkClear",
                terrain.isGroundedWalkClear(client.player.getX(), heights[0], client.player.getZ(),
                    1.5, heights[1], 0.5, previous, current));
            coalNavigationRouteDiagnostics.add(row);
            terrain.probeStance16(x, heights[x], 0, previous);
        }
        System.out.println("[Lodekeeper verification] Native mixed route diagnostics: " + coalNavigationRouteDiagnostics);
    }

    private void startCoalRecoveryCommand() {
        if (MIXED_NAVIGATION_COURSE && !verifyFenceGeometry()) return;
        if (MIXED_NAVIGATION_COURSE) recordMixedNavigationDiagnostics();
        coalInitialServerFeetY = latestSnapshot.y;
        if (COAL_RAISED_FULL_DROP_MODE) {
            coalDropInitialServerPosition = new CoalDropStartingPosition(
                latestSnapshot.x, latestSnapshot.y, latestSnapshot.z,
                latestSnapshot.coalStartSurfaceRemaining, latestSnapshot.serverTick);
        }
        coalNavigationInitialServerFeetX = latestSnapshot.x;
        coalNavigationInitialServerFeetZ = latestSnapshot.z;
        coalNavigationCourseMinimumHealth = latestSnapshot.health;
        coalNavigationCourseObservedMask = 0;
        coalNavigationStairEdgeCompleted = false;
        coalNavigationStairEdgeMovement = "unobserved";
        coalNavigationStairEdgePathIndex = -1;
        coalNavigationLedgeEdgeCompleted = false;
        coalNavigationLedgeEdgeMovement = "unobserved";
        coalNavigationLedgeEdgePathIndex = -1;
        activeCase = MIXED_NAVIGATION_COURSE ? "coal_recovery_mixed_navigation_course"
            : COAL_RAISED_FULL_DROP_MODE ? "coal_recovery_raised_full_drop_reject_encased_nearer_resource"
                : "coal_recovery_" + COAL_START_SURFACE + "_reject_encased_nearer_resource";
        activeItem = "minecraft:coal";
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        beginCaseClock();
        coalNavigationCourseCommandStarted = MIXED_NAVIGATION_COURSE;
        coalDropCommandStarted = COAL_RAISED_FULL_DROP_MODE;
        sendCommand("!lk get coal");
        state = State.GATHERING_COAL_RECOVERY;
    }

    private static Block coalStartFloor() {
        return switch (COAL_START_SURFACE) {
            case "dirt_path" -> Blocks.DIRT_PATH;
            case "farmland" -> Blocks.FARMLAND;
            case "full", "raised_full" -> Blocks.BEDROCK;
            default -> Blocks.BEDROCK;
        };
    }

    private static int coalStartSurfaceY() {
        return COAL_RAISED_FULL_DROP_MODE ? FIXTURE_FLOOR_Y + 1 : FIXTURE_FLOOR_Y;
    }

    private static double coalStartInitialFeetY() {
        if (COAL_RAISED_FULL_DROP_MODE) return PLAYER_Y + 1.0;
        return COAL_START_SURFACE.equals("full") ? PLAYER_Y : PLAYER_Y - 0.0625;
    }

    private static int countCoalStartSurface(ServerWorld world) {
        int count = 0;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (world.getBlockState(new BlockPos(x, coalStartSurfaceY(), z)).isOf(coalStartFloor())) count++;
        }
        return count;
    }

    private boolean coalRecoveryTargetRejected() {
        return LodekeeperClient.engine != null && LodekeeperClient.engine.resourceRejected(COAL_RECOVERY_ENCASED_ORE);
    }

    private boolean coalDropCompletionObserved() {
        if (!COAL_RAISED_FULL_DROP_MODE || coalDropInitialServerPosition == null
                || coalDropCompletedEdge == null || coalDropServerCheckpoint == null) return false;
        CoalDropEdge edge = coalDropCompletedEdge;
        CoalDropServerCheckpoint checkpoint = coalDropServerCheckpoint;
        return Math.abs(coalDropInitialServerPosition.x - 0.5) < 0.0001
            && Math.abs(coalDropInitialServerPosition.feetY - (PLAYER_Y + 1.0)) < 0.0001
            && Math.abs(coalDropInitialServerPosition.z - 0.5) < 0.0001
            && coalDropInitialServerPosition.platformBlockCount == 9
            && "DROP".equals(edge.movement)
            && edge.sourceFeetY16 == (PLAYER_Y + 1) * 16
            && edge.destinationFeetY16 == PLAYER_Y * 16
            && isCoalDropPlatformCell(edge.sourceX, edge.sourceZ)
            && !isCoalDropPlatformCell(edge.destinationX, edge.destinationZ)
            && checkpoint.onGround
            && Math.abs(checkpoint.feetY - PLAYER_Y) < 0.0001
            && !isCoalDropPlatformCell((int) Math.floor(checkpoint.x), (int) Math.floor(checkpoint.z));
    }

    private boolean coalRecoveryOutcomeObserved() {
        return latestSnapshot != null
            && latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:coal", 1))
            && latestSnapshot.health == 20.0F
            && latestSnapshot.coalRecoveryEncasedOreRemaining == 1
            && latestSnapshot.coalRecoveryAccessibleOreRemaining == 0
            && latestSnapshot.coalStartSurfaceRemaining == 9
            && (!MIXED_NAVIGATION_COURSE || (coalNavigationFenceGeometryEvidence != null
                && coalNavigationFenceGeometryEvidence.get("verified").getAsBoolean()
                && latestSnapshot.coalNavigationCourseMismatchCount == 0
                && latestSnapshot.coalNavigationCourseObservedMask == coalNavigationExpectedMask()
                && latestSnapshot.coalNavigationCourseMinimumHealth == 20.0F
                && coalNavigationStairEdgeCompleted && "WALK".equals(coalNavigationStairEdgeMovement)
                && coalNavigationLedgeEdgeCompleted && "JUMP".equals(coalNavigationLedgeEdgeMovement)))
            && (!COAL_RAISED_FULL_DROP_MODE || coalDropCompletionObserved())
            && coalRecoveryTargetRejected()
            && requireEngine().status().startsWith("idle");
    }

    private static int coalNavigationExpectedMask() {
        return (1 << COAL_NAVIGATION_CHECKPOINTS.size()) - 1;
    }

    private static int[] newCoalNavigationCheckpointTicks() {
        int[] ticks = new int[COAL_NAVIGATION_CHECKPOINTS.size()];
        java.util.Arrays.fill(ticks, -1);
        return ticks;
    }

    private List<Integer> coalNavigationCheckpointServerTicksSnapshot() {
        List<Integer> ticks = new ArrayList<>(coalNavigationCourseCheckpointServerTicks.length);
        for (int tick : coalNavigationCourseCheckpointServerTicks) ticks.add(tick);
        return List.copyOf(ticks);
    }

    private void evaluateCurrentCase() {
        if (activeCase == null || state == State.CAPTURING) return;
        if (latestSnapshot == null) return;
        if (state == State.PREPARED_SAFETY) {
            evaluatePreparedSafetyCase();
            return;
        }
        if (NEARBY_WOOD_LOCAL_DECOY_MODE) observeNearbyWoodLocalTargetsAfterEngineTick();
        if (STONECUTTING_DRAIN_MODE && state == State.COOKING && !stonecuttingDrainStopInjected
                && latestSnapshot.count(cookingOutputId()) > 0) {
            fail("stonecutting drain stop was not injected before the first slab output");
            return;
        }
        if ((NEARBY_WOOD_MODE && !NEARBY_WOOD_LOCAL_DECOY_MODE) || IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE) {
            if (liveRouteScreenshot == null && ENGINE_MOVEMENT_FIELD != null
                    && MOVEMENT_PATH_FIELD != null && MOVEMENT_PATH_INDEX_FIELD != null) {
                try {
                    Object movement = ENGINE_MOVEMENT_FIELD.get(requireEngine());
                    dev.lodekeeper.nav.Path route = (dev.lodekeeper.nav.Path) MOVEMENT_PATH_FIELD.get(movement);
                    int nextStep = MOVEMENT_PATH_INDEX_FIELD.getInt(movement);
                    if (route != null && nextStep >= 2 && nextStep < route.length()
                            && requireEngine().status().startsWith("route ")) {
                        if (firstRouteTick < 0) firstRouteTick = clientTicks;
                        if (clientTicks - firstRouteTick >= 8) liveRouteScreenshot = capture(activeCase + "-active-route");
                    }
                } catch (ReflectiveOperationException exception) {
                    fail("Cannot inspect active route for capture: " + exception);
                    return;
                }
            }
        }
        if (BARITONE_MODE && liveRouteScreenshot == null
                && dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getProvider().getPrimaryBaritone().getPathingBehavior().hasPath()) {
            if (firstRouteTick < 0) firstRouteTick = clientTicks;
            if (clientTicks - firstRouteTick >= 8) liveRouteScreenshot = capture(activeCase + "-baritone-route");
        }
        int observed = latestSnapshot.count(activeItem);
        if (NEARBY_WOOD_LOCAL_DECOY_MODE && state == State.GATHERING_WOOD
                && !nearbyWoodTargetMatches(nearbyWoodFirstMineTarget, NEARBY_WOOD_LOCAL_VISIBLE_LOG)
                && clientTicks - caseStartedAtTick >= NEARBY_WOOD_LOCAL_DECOY_TIMEOUT_TICKS) {
            fail("local_decoy did not attempt to mine visible oak log 3,64,0 within "
                + NEARBY_WOOD_LOCAL_DECOY_TIMEOUT_TICKS + " client ticks; "
                + nearbyWoodLocalNavigationDiagnostics());
            return;
        }
        if (NEARBY_WOOD_LOCAL_DECOY_MODE && observed >= activeCount
                && requireEngine().status().startsWith("idle") && !nearbyWoodLocalOutcomeObserved()) {
            fail("local_decoy reached its item target without exact visible-log mining and fixture proof; "
                + nearbyWoodLocalNavigationDiagnostics());
            return;
        }
        if (MEADOW_BENCHMARK && !BARITONE_MODE && state == State.GATHERING_WOOD && observed >= activeCount
                && requireEngine().status().startsWith("idle") && nearbyWoodLaunchJumpHandoffCount == 0) {
            fail("nearbyWood meadow reached its item target without a client-observed WALK-to-JUMP handoff");
            return;
        }
        boolean targetReached = (COAL_RECOVERY_MODE || BULK_WOOD_MODE || PROCESSING_MODE || IRON_PICKAXE_MODE ? observed == activeCount : observed >= activeCount)
            && requireEngine().status().startsWith("idle")
            && (BARITONE_MODE || !MEADOW_BENCHMARK || (nearbyWoodLaunchJumpHandoffCount > 0
                && nearbyWoodLaunchUnsettledHandoffCount == 0))
            && (!NEARBY_WOOD_LOCAL_DECOY_MODE || nearbyWoodLocalOutcomeObserved())
            && (state != State.CRAFTING_WOOD_PICK || serverTableOpened)
            && (state != State.SMELTING_IRON || serverFurnaceOpened)
            && (!PROCESSING_MODE || (state == State.COOKING
                && latestSnapshot.health == 20.0F
                && correctCookingStationMenuOpened()
                && (STONECUTTING_DRAIN_MODE ? stonecuttingDrainOutcomeObserved()
                    : latestSnapshot.count(cookingRawItemId()) == 56
                        && latestSnapshot.count("minecraft:coal") == 0
                        && (!STONECUTTING_MODE || latestSnapshot.inventory.equals(
                            Map.of("minecraft:stone", 56, "minecraft:stone_slab", 144))))))
            && (!DIAMOND_BOOTSTRAP_MODE || (state == State.GATHERING_WOOD
                && serverTableOpened && serverTableOpenings > activeTableOpeningsAtStart
                && serverFurnaceOpened && serverFurnaceOpenings > activeFurnaceOpeningsAtStart
                && latestSnapshot.count(IRON_PICKAXE_ID) >= 1))
            && (!IRON_PICKAXE_MODE || (state == State.GATHERING_WOOD
                && latestSnapshot.health == 20.0F
                && serverTableOpened && serverTableOpenings > activeTableOpeningsAtStart
                && serverFurnaceOpened && serverFurnaceOpenings > activeFurnaceOpeningsAtStart))
            && (!COAL_RECOVERY_MODE || coalRecoveryOutcomeObserved())
            && (!BULK_WOOD_MODE || (latestSnapshot.count(OAK_LOG_ID) == 64
                && (WOOD_TOOLS_MODE
                    ? serverTableOpened && serverTableOpenings > activeTableOpeningsAtStart
                        && latestSnapshot.count(WOODEN_AXE_ID) >= 2
                    : !serverTableOpened && serverTableOpenings == 0
                        && latestSnapshot.count(WOODEN_AXE_ID) == 0)))
            && (state != State.CUSTOM_CONTENT || serverTableOpenings > activeTableOpeningsAtStart)
            && (!BARITONE_MODE || (baritoneMiningObserved
                && !dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive()
                && !dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getProvider().getPrimaryBaritone().getPathingBehavior().hasPath()
                && dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getProvider().getPrimaryBaritone().getPathingBehavior().getInProgress().isEmpty()));
        if (targetReached && EXPLORATION_MODE && state == State.GATHERING_WOOD
                && (requireEngine().explorationAttemptsMade() == 0 || latestSnapshot.x <= 48)) {
            fail("far resource goal completed without observed bounded exploration travel"); return;
        }
        if (targetReached && state == State.GATHERING_FOOD
            && (latestSnapshot.foodLevel <= activeFoodLevelAtStart
                || latestSnapshot.count(VerificationContentInitializer.BREAD_ID) >= activeBreadCountAtStart)) {
            fail("food-use case reached its log target without server-confirmed bread consumption and hunger recovery");
        } else if (targetReached) {
            String screenshot = capture(activeCase);
            String detail = STONECUTTING_DRAIN_MODE
                ? "sent ordinary !lk stop at client tick " + stonecuttingDrainStopClientTick + " with "
                    + stonecuttingDrainInputCountAtStop + " owned stone in the native stonecutter; fresh server tick "
                    + latestSnapshot.serverTick + " observed exact starting stock restored with zero slabs (command target 144, expected output 0)"
                : PROCESSING_MODE
                ? "server inventory reached " + activeCount + " " + cookingOutputId() + " with 56 inputs remaining after opening the native " + PROCESSING_STATION_MODE + " menu"
                : NEARBY_WOOD_LOCAL_DECOY_MODE
                ? "server observed the visible oak log removed, the enclosed decoy and six bedrock faces unchanged, exact inventory, full health, and idle engine"
                : MEADOW_BENCHMARK
                ? "server inventory reached the meadow nearby-wood target after "
                    + nearbyWoodLaunchJumpHandoffCount + " client-observed settled WALK-to-JUMP handoffs"
                : BULK_WOOD_MODE
                ? (WOOD_TOOLS_MODE
                    ? "server inventory reached exactly 64 oak logs after opening the crafting table and acquiring at least two wooden axes"
                    : "server inventory reached exactly 64 oak logs with no wooden axes or crafting table opening")
                : IRON_PICKAXE_MODE
                ? IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                    ? "server inventory reached one iron pickaxe at full health from empty inventory with oak logs at x="
                        + IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + " through x="
                        + (IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT - 1)
                        + " after native crafting-table and furnace menu openings"
                    : "server inventory reached one iron pickaxe at full health after native crafting-table and furnace menu openings"
                : COAL_RECOVERY_MODE
                ? "server inventory reached exactly one coal at full health after rejecting the nearer encased ore and mining the accessible ore"
                : DIAMOND_BOOTSTRAP_MODE
                ? "server inventory reached diamond boots after the integrated server observed crafting table and furnace menus and an iron pickaxe"
                : switch (state) {
                case CUSTOM_CONTENT -> "server inventory reached the custom recipe output after opening the server crafting table";
                case GATHERING_FOOD -> "server inventory reached the log target; one bread was consumed and hunger rose from "
                    + activeFoodLevelAtStart + " to " + latestSnapshot.foodLevel;
                default -> "server inventory reached target and engine returned idle";
            };
            addResult(true, observed, detail, screenshot);
            if (state == State.GATHERING_WOOD && (EXPLORATION_MODE || DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE || BULK_WOOD_MODE || NEARBY_WOOD_MODE)) {
                state = State.CAPTURING; captureStartedAtTick = clientTicks;
            } else if (state == State.GATHERING_WOOD) {
                startCraftingTableCommand();
            } else if (state == State.CRAFTING_TABLE) {
                startCraftingSticksCommand();
            } else if (state == State.CRAFTING_STICKS) {
                startAdditionalCase(State.CRAFTING_WOOD_PICK, "craft_wooden_pickaxe", "minecraft:wooden_pickaxe");
            } else if (state == State.CRAFTING_WOOD_PICK) {
                startAdditionalCase(State.CRAFTING_STONE_PICK, "craft_stone_pickaxe", "minecraft:stone_pickaxe");
            } else if (state == State.CRAFTING_STONE_PICK) {
                startAdditionalCase(State.CRAFTING_FURNACE, "craft_furnace", "minecraft:furnace");
            } else if (state == State.CRAFTING_FURNACE) {
                startAdditionalCase(State.SMELTING_IRON, "smelt_iron_ingot", "minecraft:iron_ingot");
            } else if (state == State.SMELTING_IRON) {
                startCustomContentCase();
            } else if (state == State.CUSTOM_CONTENT) {
                beginFoodFixtureSetup();
            } else {
                state = State.CAPTURING;
                captureStartedAtTick = clientTicks;
            }
        } else if (COAL_RECOVERY_MODE && state == State.GATHERING_COAL_RECOVERY && observed >= activeCount
                && requireEngine().status().startsWith("idle") && !coalRecoveryOutcomeObserved()) {
            fail("coal output was observed without the required encased-target rejection and accessible-ore fixture proof");
        } else if (requireEngine().status().startsWith("paused")) {
            fail(NEARBY_WOOD_LOCAL_DECOY_MODE
                ? "local_decoy automation paused before the visible oak log was mined; " + nearbyWoodLocalNavigationDiagnostics()
                : STONECUTTING_DRAIN_MODE && !stonecuttingDrainStopInjected
                ? "stonecutting drain stop was never injected before automation paused: " + requireEngine().status()
                : "automation paused during " + activeCase + ": " + requireEngine().status());
        } else if (clientTicks - caseStartedAtTick > MAX_RUN_TICKS) {
            addResult(false, observed, "case timed out; engine status=" + requireEngine().status(), null);
            fail(STONECUTTING_DRAIN_MODE && !stonecuttingDrainStopInjected
                ? "stonecutting drain stop was never injected because owned input with an empty cursor was not observed"
                : "case timed out: " + activeCase);
        }
    }

    private void startCraftingTableCommand() {
        activeCase = "craft_crafting_table";
        activeItem = "minecraft:crafting_table";
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get crafting_table 1");
        state = State.CRAFTING_TABLE;
    }

    private void startCraftingSticksCommand() {
        activeCase = "craft_sticks_8";
        activeItem = "minecraft:stick";
        activeCount = 8;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get stick 8");
        state = State.CRAFTING_STICKS;
    }

    private boolean preparedSafetyFixtureReady() {
        if (latestSnapshot == null || latestSnapshot.serverTick < fixtureReadyServerTick
                || (preparedSafetyPhase == PreparedSafetyPhase.AIR
                    ? latestSnapshot.health < 3.0F : latestSnapshot.health != 20.0F)
                || !latestSnapshot.serverCursorEmpty) return false;
        if (preparedSafetyPhase != PreparedSafetyPhase.EQUIPMENT
                && !latestSnapshot.difficulty.equals(Difficulty.NORMAL.name())) return false;
        return switch (preparedSafetyPhase) {
            case EQUIPMENT -> latestSnapshot.storageInventory.equals(Map.of("minecraft:iron_helmet", 1))
                && latestSnapshot.count("minecraft:iron_helmet") == 1 && latestSnapshot.equippedItems.isEmpty();
            case OFFHAND_FOOD -> latestSnapshot.storageInventory.equals(Map.of(
                    "minecraft:cooked_beef", 1, "minecraft:crafting_table", 1, "minecraft:iron_ingot", 3))
                && latestSnapshot.equippedItems.equals(Map.of("offhand", "minecraft:cooked_beef"))
                && latestSnapshot.count("minecraft:cooked_beef") == 2
                && latestSnapshot.foodLevel == 7;
            case OFFHAND_INGREDIENTS -> latestSnapshot.storageInventory.equals(
                Map.of("minecraft:oak_log", 2, "minecraft:crafting_table", 1))
                && latestSnapshot.equippedItems.equals(Map.of("offhand", "minecraft:oak_log"))
                && latestSnapshot.count("minecraft:oak_log") == 10;
            case THREAT -> THREAT_STAIRCASE_MODE ? preparedSafetyStaircaseFixtureReady()
                : THREAT_CREEPER_CONTACT_MODE ? preparedSafetyCreeperContactFixtureReady()
                : THREAT_CONTACT_MODE ? preparedSafetyContactFixtureReady() : latestSnapshot.storageInventory.equals(latestSnapshot.inventory)
                && latestSnapshot.inventory.equals(Map.of("minecraft:crafting_table", 1, "minecraft:diamond_sword", 1,
                    "minecraft:iron_ingot", 3, "minecraft:wooden_pickaxe", 1))
                && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.foodLevel == 20
                && "true".equals(latestSnapshot.preparedSafetyThreatReceipt.get("zombieAlive"))
                && "4.0".equals(latestSnapshot.preparedSafetyThreatReceipt.get("zombieHealth"))
                && "false".equals(latestSnapshot.preparedSafetyThreatReceipt.get("preparedThreatsCleared"))
                && "true".equals(latestSnapshot.preparedSafetyThreatReceipt.get("cowAlive"))
                && "10.0".equals(latestSnapshot.preparedSafetyThreatReceipt.get("cowHealth"))
                && "10.0".equals(latestSnapshot.preparedSafetyThreatReceipt.get("cowInitialHealth"))
                && "0".equals(latestSnapshot.preparedSafetyThreatReceipt.get("diamondSwordDamage"))
                && "0".equals(latestSnapshot.preparedSafetyThreatReceipt.get("woodenPickaxeDamage"))
                && (!THREAT_WATER_RETREAT_MODE || "true".equals(latestSnapshot.preparedSafetyThreatReceipt.get("creeperAlive"))
                    && "false".equals(latestSnapshot.preparedSafetyThreatReceipt.get("creeperRemoved"))
                    && "20.0".equals(latestSnapshot.preparedSafetyThreatReceipt.get("creeperHealth"))
                    && Double.parseDouble(latestSnapshot.preparedSafetyThreatReceipt.getOrDefault("creeperDistanceSquared", "NaN")) < 36
                    && "true".equals(latestSnapshot.preparedSafetyThreatReceipt.get("lowWaterRoofPresent"))
                    && "true".equals(latestSnapshot.preparedSafetyThreatReceipt.get("waterSourceCellsPresent"))
                    && "true".equals(latestSnapshot.preparedSafetyThreatReceipt.get("waterFloorPresent"))
                    && "true".equals(latestSnapshot.preparedSafetyThreatReceipt.get("playerInWater")));
            case PURSUIT -> {
                Map<String, String> receipt = latestSnapshot.preparedSafetyPursuitReceipt;
                boolean toolVariant = "pursuit-tool".equals(PREPARED_SAFETY_MODE);
                double dx = Double.parseDouble(receipt.getOrDefault("cowCurrentX", "NaN")) - latestSnapshot.x;
                double dy = Double.parseDouble(receipt.getOrDefault("cowCurrentY", "NaN")) - latestSnapshot.y;
                double dz = Double.parseDouble(receipt.getOrDefault("cowCurrentZ", "NaN")) - latestSnapshot.z;
                boolean toolReady = !toolVariant || "true".equals(receipt.get("pursuitToolVariant"))
                    && "7".equals(receipt.get("stonePickaxeInitialSlot"))
                    && "7".equals(receipt.get("stonePickaxeCurrentSlot"))
                    && "0".equals(receipt.get("stonePickaxeInitialDamage"))
                    && Integer.parseInt(receipt.getOrDefault("stonePickaxeMaxDamage", "0")) > 0;
                yield latestSnapshot.storageInventory.equals(toolVariant
                        ? Map.of("minecraft:crafting_table", 1, "minecraft:iron_ingot", 3, "minecraft:stone_pickaxe", 1)
                        : Map.of("minecraft:crafting_table", 1, "minecraft:iron_ingot", 3))
                    && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.foodLevel == 7
                    && latestSnapshot.inventory.equals(latestSnapshot.storageInventory)
                    && toolReady
                    && "true".equals(receipt.get("cowAlive"))
                    && "10.0".equals(receipt.get("cowInitialHealth"))
                    && "10.0".equals(receipt.get("cowHealth"))
                    && receipt.get("cowUuid") != null && receipt.get("cowUuid").equals(receipt.get("cowInitialUuid"))
                    && Math.sqrt(dx * dx + dy * dy + dz * dz) < 32.0;
            }
            case STATION_ROOM -> latestSnapshot.inventory.equals(Map.of("minecraft:coal", 1,
                    "minecraft:furnace", 1, "minecraft:raw_iron", 1, "minecraft:stone_pickaxe", 1))
                && latestSnapshot.storageInventory.equals(latestSnapshot.inventory)
                && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.foodLevel == 20
                && latestSnapshot.difficulty.equals(Difficulty.NORMAL.name())
                && Math.abs(latestSnapshot.x - (STATION_ROOM_APPROACH_MODE ? 1.5 : STATION_ROOM_TUNNEL_MODE ? 0.367555 : 0.5)) < 0.001 && Math.abs(latestSnapshot.y - (STATION_ROOM_APPROACH_MODE ? 65.0 : 64.0)) < 0.001
                && Math.abs(latestSnapshot.z - (STATION_ROOM_TUNNEL_MODE ? 0.505802 : 0.5)) < 0.001
                && (STATION_ROOM_APPROACH_MODE ? "0" : STATION_ROOM_TUNNEL_MODE ? "66" : "73").equals(latestSnapshot.preparedSafetyStationRoomReceipt.get("roomStoneCellCandidateCount"))
                && (STATION_ROOM_APPROACH_MODE ? "0" : STATION_ROOM_TUNNEL_MODE ? "66" : "73").equals(latestSnapshot.preparedSafetyStationRoomReceipt.get("roomStoneCellsStillStone"))
                && "0".equals(latestSnapshot.preparedSafetyStationRoomReceipt.get("roomStoneCellsChangedCount"))
                && "0".equals(latestSnapshot.preparedSafetyStationRoomReceipt.get("nearbyFurnaceCount"))
                && "true".equals(latestSnapshot.preparedSafetyStationRoomReceipt.get("playerSupportBedrock"))
                && stationRoomHistorySetupReady(latestSnapshot.preparedSafetyStationRoomReceipt)
                && (!STATION_ROOM_TUNNEL_MODE || stationRoomTunnelSetupReady(latestSnapshot.preparedSafetyStationRoomReceipt))
                && (!STATION_ROOM_APPROACH_MODE || (stationRoomApproachSetupReady(latestSnapshot.preparedSafetyStationRoomReceipt)
                    && Math.abs(client.player.getX() - 1.5) < 0.001 && Math.abs(client.player.getY() - 65.0) < 0.001
                    && Math.abs(client.player.getZ() - 0.5) < 0.001));
            case AIR -> {
                Map<String, String> receipt = latestSnapshot.preparedSafetyAirReceipt;
                int airSupply = Integer.parseInt(receipt.getOrDefault("airSupply", "-1"));
                yield latestSnapshot.inventory.equals(Map.of("minecraft:cooked_beef", 2, "minecraft:iron_ingot", 3))
                    && latestSnapshot.storageInventory.equals(latestSnapshot.inventory)
                    && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.health == 3.0F
                    && latestSnapshot.foodLevel == 20
                    && "200".equals(receipt.get("fixtureAirSupply"))
                    && "3.0".equals(receipt.get("initialHealth"))
                    && "0.0".equals(receipt.get("saturation"))
                    && "20".equals(receipt.get("foodLevel"))
                    && "true".equals(receipt.get("headInWaterAtStart"))
                    && "true".equals(receipt.get("headInWater"))
                    && airSupply >= 160 && airSupply <= 180
                    && "true".equals(receipt.get("waterSourceCellsPresent"))
                    && "true".equals(receipt.get("lowWaterRoofPresent"))
                    && "true".equals(receipt.get("waterBoundaryPresent"))
                    && "true".equals(receipt.get("dryExitPresent"))
                    && "true".equals(receipt.get("craftingTablePresent"))
                    && Double.parseDouble(receipt.getOrDefault("exitDistance", "NaN")) <= 6.0;
            }
            case WORKBENCH_SEEDING -> latestSnapshot.inventory.equals(Map.of("minecraft:oak_planks", 12))
                && latestSnapshot.storageInventory.equals(latestSnapshot.inventory) && latestSnapshot.equippedItems.isEmpty();
            case WORKBENCH_RECOVERY -> latestSnapshot.inventory.equals(workbenchSeedInventory)
                && latestSnapshot.equippedItems.isEmpty()
                && "true".equals(latestSnapshot.preparedSafetyWorkbenchReceipt.get("tablePresent"))
                && "true".equals(latestSnapshot.preparedSafetyWorkbenchReceipt.get("stonePresent"))
                && isSixBlocksFromTable(latestSnapshot.preparedSafetyWorkbenchReceipt)
                && (!WORKBENCH_BLOCKED || "26".equals(latestSnapshot.preparedSafetyWorkbenchReceipt.get("bedrockShellCells")));
            case HELD_FUEL_SMELTING -> latestSnapshot.inventory.equals(HELD_FUEL_SETUP_EXPECTED)
                && latestSnapshot.storageInventory.equals(latestSnapshot.inventory)
                && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.foodLevel == 20
                && "0".equals(latestSnapshot.preparedSafetyHeldFuelReceipt.get("nearbyFurnaceCount"));
            case HELD_FUEL_STICKS -> false;
            case NONE -> false;
        };
    }

    private static boolean isSixBlocksFromTable(Map<String, String> receipt) {
        double distance = Double.parseDouble(receipt.getOrDefault("horizontalDistance", "NaN"));
        return Double.isFinite(distance) && Math.abs(distance - 6.0) <= 0.000001;
    }

    private void startPreparedSafetyHeldFuelCase() {
        String engineStatus = requireEngine().status();
        if (!engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued") || !baritoneNavigationStopped()) {
            fail("held-fuel command requires an idle engine and stopped native navigation: " + engineStatus);
            return;
        }
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.storageInventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        preparedMaintenanceQueueEmptyBeforeForeground = true;
        preparedSafetyForegroundStarted = true;
        beginCaseClock();
        state = State.PREPARED_SAFETY;
        if (preparedSafetyPhase == PreparedSafetyPhase.HELD_FUEL_SMELTING) {
            heldFuelStartedAtNanos = System.nanoTime();
            heldFuelSetupInventory = Map.copyOf(latestSnapshot.inventory);
            heldFuelSetupReceipt = Map.copyOf(latestSnapshot.preparedSafetyHeldFuelReceipt);
            heldFuelSetupScreenshot = capture("prepared_held_fuel_setup");
            activeCase = "prepared_held_fuel_iron_ingots";
            activeItem = "minecraft:iron_ingot";
            activeCount = 3;
            sendCommand("!lk get iron_ingot 3");
            return;
        }
        activeCase = "prepared_held_fuel_retained_log_sticks";
        activeItem = "minecraft:stick";
        activeCount = 8;
        sendCommand("!lk get stick 8");
    }

    private void evaluatePreparedSafetyHeldFuelCase(String engineStatus) {
        if (System.nanoTime() - heldFuelStartedAtNanos > 120_000_000_000L) {
            fail("held-fuel command sequence exceeded 120 seconds; server inventory=" + latestSnapshot.inventory);
            return;
        }
        if (!engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued") || !baritoneNavigationStopped()) return;
        Map<String, String> receipt = latestSnapshot.preparedSafetyHeldFuelReceipt;
        boolean drained = "1".equals(receipt.get("nearbyFurnaceCount"))
            && "0".equals(receipt.get("furnaceInputCount"))
            && "0".equals(receipt.get("furnaceFuelCount"))
            && "0".equals(receipt.get("furnaceOutputCount"));
        boolean safe = latestSnapshot.health == 20.0F && latestSnapshot.foodLevel == 20
            && latestSnapshot.serverCursorEmpty && latestSnapshot.equippedItems.isEmpty()
            && latestSnapshot.storageInventory.equals(latestSnapshot.inventory) && drained;
        if (preparedSafetyPhase == PreparedSafetyPhase.HELD_FUEL_SMELTING) {
            if (latestSnapshot.count("minecraft:iron_ingot") != 3) return;
            heldFuelSmeltingInventory = Map.copyOf(latestSnapshot.inventory);
            heldFuelSmeltingReceipt = Map.copyOf(receipt);
            boolean passed = safe && latestSnapshot.inventory.equals(HELD_FUEL_SMELTING_EXPECTED)
                && serverFurnaceOpenings > activeFurnaceOpeningsAtStart;
            if (!passed) {
                fail("native smelting must deliver exactly three iron ingots, retain both logs and one plank, and drain all furnace slots; inventory="
                    + latestSnapshot.inventory + "; furnace=" + receipt);
                return;
            }
            heldFuelFurnacePosition = receipt.get("nativeFurnacePositions");
            addResult(true, 3, "native furnace smelted three supplied raw iron with two held planks, retained both oak logs and one plank, drained all station slots, and stopped native navigation", capture(activeCase));
            preparedSafetyPhase = PreparedSafetyPhase.HELD_FUEL_STICKS;
            startPreparedSafetyHeldFuelCase();
            return;
        }
        if (latestSnapshot.count("minecraft:stick") < 8) return;
        boolean passed = safe && latestSnapshot.inventory.equals(HELD_FUEL_STICKS_EXPECTED)
            && heldFuelFurnacePosition.equals(receipt.get("nativeFurnacePositions"));
        if (!passed) {
            fail("native sticks must consume one retained log through vanilla planks conversion, deliver exactly eight sticks, retain one log and one plank, and keep the same drained furnace; inventory="
                + latestSnapshot.inventory + "; furnace=" + receipt);
            return;
        }
        addResult(true, 8, "one retained oak log became four planks; two vanilla stick batches consumed four of the five planks and delivered eight sticks, retaining one log and one plank with the same furnace drained and native navigation stopped", capture(activeCase));
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private JsonObject heldFuelEvidence() {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("authority", "prepared_integrated_server_inventory_native_furnace_slots_and_idle_navigation");
        evidence.addProperty("fixtureGrants", "exactly 3 raw iron, 2 oak logs, 3 oak planks, one furnace and one crafting table before the first command, full health and hunger");
        evidence.addProperty("commands", "!lk get iron_ingot 3, then !lk get stick 8 without fixture mutation");
        evidence.addProperty("sequenceWallLimitMillis", 120_000);
        evidence.addProperty("elapsedSequenceMillis", heldFuelStartedAtNanos < 0 ? 0 : (System.nanoTime() - heldFuelStartedAtNanos) / 1_000_000L);
        evidence.addProperty("blockBreakingAllowed", false);
        evidence.addProperty("postCommandFixtureMutations", 0);
        evidence.addProperty("setupScreenshot", heldFuelSetupScreenshot);
        evidence.addProperty("nativeNavigationStopped", client != null && client.player != null && baritoneNavigationStopped());
        JsonObject setupInventory = new JsonObject();
        heldFuelSetupInventory.forEach(setupInventory::addProperty);
        evidence.add("setupServerInventory", setupInventory);
        JsonObject smeltingInventory = new JsonObject();
        heldFuelSmeltingInventory.forEach(smeltingInventory::addProperty);
        evidence.add("smeltingServerInventory", smeltingInventory);
        JsonObject finalInventory = new JsonObject();
        if (latestSnapshot != null) latestSnapshot.inventory.forEach(finalInventory::addProperty);
        evidence.add("finalServerInventory", finalInventory);
        JsonObject setupReceipt = new JsonObject();
        heldFuelSetupReceipt.forEach(setupReceipt::addProperty);
        evidence.add("setupServerReceipt", setupReceipt);
        JsonObject smeltingReceipt = new JsonObject();
        heldFuelSmeltingReceipt.forEach(smeltingReceipt::addProperty);
        evidence.add("smeltingServerReceipt", smeltingReceipt);
        JsonObject finalReceipt = new JsonObject();
        if (latestSnapshot != null) latestSnapshot.preparedSafetyHeldFuelReceipt.forEach(finalReceipt::addProperty);
        evidence.add("finalServerReceipt", finalReceipt);
        evidence.addProperty("vanillaCraftingAccounting", "one log yields four planks; two batches use four planks to make eight sticks; one plank remains");
        return evidence;
    }

    private void startPreparedSafetyWorkbenchCase() {
        String status = requireEngine().status();
        if (!status.startsWith("idle") || !status.endsWith("0 maintenance queued") || !baritoneNavigationStopped()) {
            fail("owned-workbench command requires a stopped engine and native navigation: " + status);
            return;
        }
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.storageInventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        preparedMaintenanceQueueEmptyBeforeForeground = true;
        beginCaseClock();
        state = State.PREPARED_SAFETY;
        if (preparedSafetyPhase == PreparedSafetyPhase.WORKBENCH_SEEDING) {
            workbenchGrantedInventory = Map.copyOf(latestSnapshot.inventory);
            activeCase = "prepared_workbench_ownership_setup";
            activeItem = "minecraft:wooden_pickaxe";
            activeCount = 1;
            requireEngine().config.recoverPlacedStations = false;
            sendCommand("!lk get wooden_pickaxe 1");
            return;
        }
        activeCase = WORKBENCH_BLOCKED ? "prepared_owned_workbench_blocked_cobblestone" : "prepared_owned_workbench_approach_cobblestone";
        activeItem = "minecraft:cobblestone";
        activeCount = 1;
        workbenchInitialReceipt = Map.copyOf(latestSnapshot.preparedSafetyWorkbenchReceipt);
        workbenchStatusObservations.clear();
        workbenchRecoveryEpisodes = 0;
        workbenchRecoveryWasActive = false;
        workbenchApproachObserved = false;
        workbenchRecoveryStartedNanos = -1;
        workbenchRecoveryDurationMillis = -1;
        requireEngine().config.recoverPlacedStations = true;
        sendCommand("!lk get cobblestone 1");
    }

    private void evaluatePreparedSafetyWorkbenchCase(String engineStatus) {
        if (preparedSafetyPhase == PreparedSafetyPhase.WORKBENCH_SEEDING) {
            if (clientTicks - caseStartedAtTick > PREPARED_SAFETY_CASE_TIMEOUT_TICKS) {
                fail("production wooden-pickaxe ownership setup did not complete");
                return;
            }
            if (latestSnapshot == null || latestSnapshot.count("minecraft:wooden_pickaxe") != 1
                    || latestSnapshot.count("minecraft:crafting_table") != 0 || latestSnapshot.count("minecraft:cobblestone") != 0
                    || !latestSnapshot.serverCursorEmpty || serverTableOpenings <= activeTableOpeningsAtStart
                    || !engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued")
                    || !baritoneNavigationStopped()) return;
            workbenchSeedInventory = Map.copyOf(latestSnapshot.inventory);
            workbenchSeedEngineStatus = engineStatus;
            workbenchSeedServerTick = latestSnapshot.serverTick;
            workbenchSeedClientTick = clientTicks;
            workbenchSeedScreenshot = capture("prepared_owned_workbench_setup_complete");
            preparedSafetyPhase = PreparedSafetyPhase.WORKBENCH_RECOVERY;
            state = State.SETTING_UP;
            readyTicks = 0;
            preparedSafetySetupStartedAtTick = clientTicks;
            IntegratedServer server = requireServer();
            setupFuture = new CompletableFuture<>();
            CompletableFuture<Long> scheduled = setupFuture;
            server.execute(() -> {
                try {
                    ServerPlayerEntity player = requireServerPlayer(server);
                    preparedSafetyWorkbenchFixture = VerificationApi.prepareOwnedWorkbenchRecovery(player, server.getOverworld(), WORKBENCH_BLOCKED);
                    scheduled.complete((long) server.getTicks());
                } catch (Throwable throwable) {
                    scheduled.completeExceptionally(throwable);
                }
            });
            return;
        }
        if (engineStatus.startsWith("approaching the recorded owned crafting table")) workbenchApproachObserved = true;
        boolean recoveryActive = engineStatus.contains("owned crafting table")
            || engineStatus.contains("owned crafting-table") || engineStatus.startsWith("collecting the crafting-table drop")
            || engineStatus.startsWith("crafting table gained;")
            || workbenchRecoveryWasActive && engineStatus.startsWith("finishing movement before inventory actions");
        if (workbenchStatusObservations.isEmpty()
                || !workbenchStatusObservations.get(workbenchStatusObservations.size() - 1).status.equals(engineStatus)) {
            workbenchStatusObservations.add(new WorkbenchStatusObservation(clientTicks,
                (System.nanoTime() - caseStartedAtNanos) / 1_000_000L, engineStatus));
        }
        if (recoveryActive && !workbenchRecoveryWasActive) {
            workbenchRecoveryEpisodes++;
            workbenchRecoveryStartedNanos = System.nanoTime();
        }
        if (!recoveryActive && workbenchRecoveryWasActive && workbenchRecoveryStartedNanos >= 0) {
            workbenchRecoveryDurationMillis = (System.nanoTime() - workbenchRecoveryStartedNanos) / 1_000_000L;
        }
        workbenchRecoveryWasActive = recoveryActive;
        if (workbenchRecoveryEpisodes > 1) {
            fail("owned-workbench recovery retried during the same foreground command");
            return;
        }
        if (workbenchRecoveryStartedNanos >= 0 && recoveryActive
                && System.nanoTime() - workbenchRecoveryStartedNanos > 21_000_000_000L) {
            fail("owned-workbench recovery exceeded its twenty-second bound plus one-second observation allowance");
            return;
        }
        if (clientTicks - caseStartedAtTick > PREPARED_SAFETY_CASE_TIMEOUT_TICKS) {
            fail("owned-workbench cobblestone command did not finish after one bounded recovery attempt");
            return;
        }
        if (latestSnapshot == null || !engineStatus.startsWith("idle")
                || !engineStatus.endsWith("0 maintenance queued") || !baritoneNavigationStopped()) return;
        Map<String, String> receipt = latestSnapshot.preparedSafetyWorkbenchReceipt;
        boolean inventoryMatches = latestSnapshot.inventory.entrySet().stream().allMatch(entry ->
            entry.getValue() == activeInitialResources.getOrDefault(entry.getKey(), 0)
                + (entry.getKey().equals("minecraft:cobblestone") ? 1
                    : entry.getKey().equals("minecraft:crafting_table") && !WORKBENCH_BLOCKED ? 1 : 0))
            && activeInitialResources.entrySet().stream().allMatch(entry -> latestSnapshot.count(entry.getKey()) == entry.getValue());
        boolean passed = latestSnapshot.count("minecraft:cobblestone") == 1
            && latestSnapshot.count("minecraft:crafting_table") == (WORKBENCH_BLOCKED ? 0 : 1)
            && inventoryMatches && latestSnapshot.storageInventory.equals(latestSnapshot.inventory)
            && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.serverCursorEmpty
            && latestSnapshot.health == 20.0F && workbenchRecoveryEpisodes == 1 && workbenchApproachObserved
            && workbenchRecoveryDurationMillis >= 0 && workbenchRecoveryDurationMillis <= 21_000
            && "true".equals(workbenchInitialReceipt.get("tablePresent"))
            && "true".equals(workbenchInitialReceipt.get("stonePresent"))
            && isSixBlocksFromTable(workbenchInitialReceipt)
            && "true".equals(receipt.get("stoneAir"))
            && workbenchInitialReceipt.get("tablePosition").equals(receipt.get("tablePosition"))
            && (WORKBENCH_BLOCKED
                ? "true".equals(receipt.get("tablePresent")) && "26".equals(receipt.get("bedrockShellCells"))
                : "true".equals(receipt.get("tableAir")));
        if (!passed) return;
        addResult(true, 1, WORKBENCH_BLOCKED
            ? "one bounded approach to the sealed self-created table ended, then native cobblestone arrived with the table and all 26 bedrock shell cells retained and every Baritone process stopped"
            : "native approach recovered the self-created table from six blocks away, then mined the fixture stone into one cobblestone with the native table gone and every Baritone process stopped", capture(activeCase));
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private JsonObject workbenchEvidence() {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("authority", "prepared_integrated_server_inventory_and_block_states_with_natural_client_tick_engine_status");
        evidence.addProperty("fixtureGrants", "12 ordinary oak planks before the production wooden-pickaxe setup command; no other items; stopped-only six-block teleport, level bedrock support, one nearby stone, and optional 26-cell bedrock table shell before the cobblestone command");
        evidence.addProperty("ownershipSetupCommand", "!lk get wooden_pickaxe 1");
        evidence.addProperty("ownershipSetupExcludedFromCaseTiming", true);
        evidence.addProperty("ownershipSetupServerTick", workbenchSeedServerTick);
        evidence.addProperty("ownershipSetupClientTick", workbenchSeedClientTick);
        evidence.addProperty("ownershipSetupEngineStatus", workbenchSeedEngineStatus);
        evidence.addProperty("ownershipSetupScreenshot", workbenchSeedScreenshot);
        JsonObject grantedInventory = new JsonObject();
        workbenchGrantedInventory.forEach(grantedInventory::addProperty);
        evidence.add("ownershipSetupStartingServerInventory", grantedInventory);
        JsonObject seedInventory = new JsonObject();
        workbenchSeedInventory.forEach(seedInventory::addProperty);
        evidence.add("ownershipSetupServerInventory", seedInventory);
        JsonObject initial = new JsonObject();
        workbenchInitialReceipt.forEach(initial::addProperty);
        evidence.add("initialServerReceipt", initial);
        JsonObject current = new JsonObject();
        if (latestSnapshot != null) latestSnapshot.preparedSafetyWorkbenchReceipt.forEach(current::addProperty);
        evidence.add("finalServerReceipt", current);
        evidence.addProperty("approachObserved", workbenchApproachObserved);
        evidence.addProperty("recoveryEpisodes", workbenchRecoveryEpisodes);
        evidence.addProperty("recoveryDurationMillis", workbenchRecoveryDurationMillis);
        evidence.addProperty("nativeNavigationStopped", client != null && client.player != null && baritoneNavigationStopped());
        evidence.addProperty("postForegroundFixtureMutations", 0);
        JsonArray statuses = new JsonArray();
        for (WorkbenchStatusObservation observation : workbenchStatusObservations) {
            JsonObject status = new JsonObject();
            status.addProperty("clientTick", observation.clientTick);
            status.addProperty("elapsedMillis", observation.elapsedMillis);
            status.addProperty("engineStatus", observation.status);
            statuses.add(status);
        }
        evidence.add("naturalClientTickStatusTransitions", statuses);
        return evidence;
    }

    private record WorkbenchStatusObservation(int clientTick, long elapsedMillis, String status) { }

    private boolean maintainedReservationObserved(String item, int expectedCount) {
        if (MAINTAINED_DEMAND_FIELD == null) throw new IllegalStateException("maintained demand model field is unavailable");
        try {
            Object model = MAINTAINED_DEMAND_FIELD.get(requireEngine());
            Object result = model.getClass().getMethod("reservedCounts").invoke(model);
            if (!(result instanceof Map<?, ?> reserved)) return false;
            return Integer.valueOf(expectedCount).equals(reserved.get(ItemId.parse(item)));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("could not inspect maintained stock reservations", exception);
        }
    }

    private void startPreparedSafetyCase() {
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.storageInventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        preparedSafetyForegroundStarted = false;
        preparedMaintenanceQueueEmptyBeforeForeground = false;
        preparedMaintenanceReservationObservedBeforeForeground = false;
        preparedMaintenanceReservationPresentAtCompletion = false;
        beginCaseClock();
        state = State.PREPARED_SAFETY;
        if (preparedSafetyPhase == PreparedSafetyPhase.EQUIPMENT) {
            activeCase = "prepared_equipment_iron_helmet";
            activeItem = "minecraft:iron_helmet";
            activeCount = 1;
            sendCommand("!lk get iron_helmet 1");
            return;
        }
        activeCase = "prepared_offhand_food_reservation_bucket";
        activeItem = "minecraft:bucket";
        activeCount = 1;
        sendCommand("!lk maintain cooked_beef 1");
    }

    private static boolean invalidContactLowHealthMode() {
        if (CONTACT_LOW_HEALTH_PROPERTY == null || "false".equals(CONTACT_LOW_HEALTH_PROPERTY)) return false;
        return !CONTACT_LOW_HEALTH_MODE || !THREAT_CONTACT_MODE || !BARITONE_MODE || !"threat".equals(PREPARED_SAFETY_MODE)
            || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
            || System.getProperty("lodekeeper.verify.naturalGoal") != null
            || CONTACT_MANUAL_INPUT_PROPERTY != null && !"false".equals(CONTACT_MANUAL_INPUT_PROPERTY)
            || THREAT_WATER_RETREAT_PROPERTY != null && !"false".equals(THREAT_WATER_RETREAT_PROPERTY)
            || THREAT_CREEPER_CONTACT_PROPERTY != null && !"false".equals(THREAT_CREEPER_CONTACT_PROPERTY);
    }

    private void registerStaircaseTransport() {
        if (!THREAT_STAIRCASE_MODE) return;
        try {
            Method method = VerificationApi.class.getDeclaredMethod("registerStaircaseNetworking", java.util.function.Supplier.class, java.util.function.Supplier.class);
            staircaseTransport = (java.util.function.IntConsumer) method.invoke(null,
                (java.util.function.Supplier<VerificationApi.PreparedSafetyThreatFixture>) () -> preparedSafetyThreatFixture,
                (java.util.function.Supplier<java.util.UUID>) () -> playerId);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("staircase verifier transport unavailable for this exact profile", exception);
        }
    }

    private void registerContactLowHealthTransport() {
        if (!CONTACT_LOW_HEALTH_MODE) return;
        try {
            Method method = VerificationApi.class.getDeclaredMethod("registerContactLowHealthNetworking", java.util.function.Supplier.class, java.util.function.Supplier.class, java.util.function.IntConsumer.class);
            contactLowHealthTransport = (java.util.function.IntConsumer) method.invoke(null,
                    (java.util.function.Supplier<VerificationApi.PreparedSafetyThreatFixture>) () -> preparedSafetyThreatFixture,
                    (java.util.function.Supplier<java.util.UUID>) () -> playerId,
                    (java.util.function.IntConsumer) validity -> {
                        if (contactLowHealthAttackAttempts < Integer.MAX_VALUE) contactLowHealthAttackAttempts++;
                        contactLowHealthAttackObserverValid &= validity == 1;
                        contactLowHealthReceipt.put("nativePostOnsetAttackAttempts", Integer.toString(contactLowHealthAttackAttempts));
                        contactLowHealthReceipt.put("nativeAttackObserverIdentityValid", Boolean.toString(contactLowHealthAttackObserverValid));
                    });
            var phase = GameApi.identifier("lodekeeper-verification:contact_low_health_onset");
            ClientTickEvents.START_CLIENT_TICK.addPhaseOrdering(phase, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
            ClientTickEvents.START_CLIENT_TICK.register(phase, mc -> observeContactLowHealthOnset("START_CLIENT_TICK", clientTicks + 1));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("low-health verifier transport unavailable for this exact profile", exception);
        }
    }

    private static boolean invalidThreatContactMode() {
        if (CONTACT_MANUAL_INPUT_PROPERTY != null && !"false".equals(CONTACT_MANUAL_INPUT_PROPERTY)
                && (!CONTACT_MANUAL_INPUT_MODE || !THREAT_CONTACT_MODE)) return true;
        return THREAT_CONTACT_PROPERTY != null && !"false".equals(THREAT_CONTACT_PROPERTY)
            && (!THREAT_CONTACT_MODE || !BARITONE_MODE || !"threat".equals(PREPARED_SAFETY_MODE)
                || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                || System.getProperty("lodekeeper.verify.naturalGoal") != null || THREAT_WATER_RETREAT_MODE
                || THREAT_CREEPER_CONTACT_MODE);
    }

    private static boolean invalidThreatCreeperContactMode() {
        if (THREAT_CREEPER_CONTACT_PROPERTY == null || "false".equals(THREAT_CREEPER_CONTACT_PROPERTY)) return false;
        return !THREAT_CREEPER_CONTACT_MODE || !BARITONE_MODE || !"threat".equals(PREPARED_SAFETY_MODE)
            || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
            || System.getProperty("lodekeeper.verify.naturalGoal") != null
            || THREAT_WATER_RETREAT_PROPERTY != null && !"false".equalsIgnoreCase(THREAT_WATER_RETREAT_PROPERTY)
            || THREAT_CONTACT_PROPERTY != null && !"false".equals(THREAT_CONTACT_PROPERTY)
            || CONTACT_MANUAL_INPUT_PROPERTY != null && !"false".equals(CONTACT_MANUAL_INPUT_PROPERTY);
    }

    private boolean preparedSafetyContactFixtureReady() {
        Map<String, String> receipt = latestSnapshot.preparedSafetyThreatReceipt;
        if (!latestSnapshot.storageInventory.equals(latestSnapshot.inventory)
                || !latestSnapshot.inventory.equals(Map.of("minecraft:crafting_table", 1,
                    "minecraft:diamond_sword", 1, "minecraft:iron_ingot", 3, "minecraft:iron_pickaxe", 1))
                || !latestSnapshot.equippedItems.isEmpty() || latestSnapshot.foodLevel != 20
                || !"true".equals(receipt.get("cowAlive")) || !"10.0".equals(receipt.get("cowHealth"))
                || !"10.0".equals(receipt.get("cowInitialHealth"))
                || !"0".equals(receipt.get("diamondSwordDamage")) || !"0".equals(receipt.get("ironPickaxeDamage"))
                || !"true".equals(receipt.get("contactClockFrozen"))
                || !"-1".equals(receipt.get("contactClockReleaseServerTick"))
                || !"0".equals(receipt.get("contactObservedServerTicks"))
                || !"0".equals(receipt.get("contactAirborneSwordDamageEvents"))
                || !"0".equals(receipt.get("contactGroundedSwordDamageEvents"))
                || !"true".equals(receipt.get("contactPlayerAlive")) || !"0".equals(receipt.get("contactPlayerDeaths"))
                || !"0.5,64.0,0.5".equals(receipt.get("contactPlayerPosition"))
                || !"-90.0".equals(receipt.get("contactPlayerYaw")) || !"0.0".equals(receipt.get("contactPlayerPitch"))
                || !"true".equals(receipt.get("contactCowNoAi"))
                || !"2.5,64.0,1.5".equals(receipt.get("contactCowPosition"))
                || !"false".equals(receipt.get("contactCowBlockCollision"))
                || !contactShellPreserved(receipt)) return false;
        for (int index = 0; index < 2; index++) {
            String prefix = "contactZombie" + index;
            if (!"true".equals(receipt.get(prefix + "Alive")) || !"20.0".equals(receipt.get(prefix + "Health"))
                    || !"true".equals(receipt.get(prefix + "AiEnabled")) || !"true".equals(receipt.get(prefix + "Adult"))
                    || !"true".equals(receipt.get(prefix + "TargetsPlayer")) || !"true".equals(receipt.get(prefix + "Visible"))
                    || !"false".equals(receipt.get(prefix + "OnFire"))
                    || !"false".equals(receipt.get(prefix + "BlockCollision"))
                    || !(index == 0 ? "1.8,64.0,0.45" : "1.8,64.0,1.45").equals(receipt.get(prefix + "Position"))
                    || !(Double.parseDouble(receipt.getOrDefault(prefix + "DistanceSquared", "NaN")) <= 4.0)
                    || !"0".equals(receipt.get(prefix + "PlayerHits")) || !"0".equals(receipt.get(prefix + "ForeignDamage"))) return false;
        }
        return !receipt.get("contactZombie0Uuid").equals(receipt.get("contactZombie1Uuid"));
    }

    private boolean preparedSafetyCreeperContactFixtureReady() {
        Map<String, String> receipt = latestSnapshot.preparedSafetyThreatReceipt;
        return latestSnapshot.storageInventory.equals(latestSnapshot.inventory)
            && latestSnapshot.inventory.equals(Map.of("minecraft:crafting_table", 1, "minecraft:diamond_sword", 1,
                "minecraft:iron_ingot", 3, "minecraft:stone_sword", 1, "minecraft:wooden_pickaxe", 1))
            && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.foodLevel == 20
            && "true".equals(receipt.get("creeperContactClockFrozen"))
            && "-1".equals(receipt.get("creeperContactClockReleaseServerTick"))
            && "true".equals(receipt.get("creeperAiEnabled"))
            && "true".equals(receipt.get("creeperTargetsPlayer"))
            && "true".equals(receipt.get("creeperAlive"))
            && "20.0".equals(receipt.get("creeperHealth"))
            && Double.parseDouble(receipt.getOrDefault("creeperDistanceSquared", "NaN")) <= 4.0
            && "true".equals(receipt.get("creeperContactOpenPlatform"))
            && "true".equals(receipt.get("creeperContactControlShade"))
            && "0".equals(receipt.get("creeperContactObservedServerTicks"))
            && "20.0".equals(receipt.get("creeperContactMinimumPlayerHealth"))
            && "true".equals(receipt.get("zombieAlive"))
            && "4.0".equals(receipt.get("zombieHealth"))
            && "false".equals(receipt.get("zombieAiEnabled"))
            && "20.5,64.0,20.5".equals(receipt.get("zombiePosition"))
            && "true".equals(receipt.get("cowAlive"))
            && "10.0".equals(receipt.get("cowHealth"))
            && "10.0".equals(receipt.get("cowInitialHealth"))
            && "18.5,64.0,20.5".equals(receipt.get("cowPosition"))
            && "0".equals(receipt.get("diamondSwordDamage"))
            && "0".equals(receipt.get("stoneSwordDamage"))
            && "0".equals(receipt.get("woodenPickaxeDamage"))
            && "20.0".equals(receipt.get("creeperContactPlayerHealth"))
            && "true".equals(receipt.get("creeperContactPlayerAlive"))
            && "0".equals(receipt.get("creeperContactPlayerDeaths"));
    }

    private static boolean contactShellPreserved(Map<String, String> receipt) {
        return (CONTACT_LOW_HEALTH_MODE ? "11732" : "5018").equals(receipt.get("contactShellCells"))
            && (CONTACT_LOW_HEALTH_MODE ? "-14..14,63..76,-14..14" : "-14..14,63..68,-14..14").equals(receipt.get("contactShellBounds"))
            && "0".equals(receipt.get("contactShellChangedCells"))
            && (CONTACT_LOW_HEALTH_MODE ? "0..6,64..66,0..1" : "0..6,64..65,0..1").equals(receipt.get("contactPassageBounds"));
    }

    private boolean preparedSafetyContactCompleted(Map<String, String> receipt, int pickaxeWear) {
        int swordWear = Integer.parseInt(receipt.getOrDefault("diamondSwordDamage", "-1"))
            - Integer.parseInt(activeInitialThreatReceipt.getOrDefault("diamondSwordDamage", "-1"));
        int weaponHits = swordWear + pickaxeWear / 2;
        if (latestSnapshot.health <= 0 || !"true".equals(receipt.get("contactPlayerAlive"))
                || !"0".equals(receipt.get("contactPlayerDeaths"))
                || !"false".equals(receipt.get("contactClockFrozen"))
                || Integer.parseInt(receipt.getOrDefault("contactClockReleaseServerTick", "-1")) < 0
                || Integer.parseInt(receipt.getOrDefault("contactNativePlayerHits", "0")) < 1
                || Integer.parseInt(receipt.getOrDefault("contactAirborneSwordDamageEvents", "0")) < 1
                || pickaxeWear < 0 || pickaxeWear > 48 || pickaxeWear % 2 != 0
                || swordWear < 0 || swordWear > 24 || weaponHits < 1 || weaponHits > 24
                || !contactShellPreserved(receipt)) return false;
        int hits = 0;
        for (int index = 0; index < 2; index++) {
            String prefix = "contactZombie" + index;
            int nativeHits = Integer.parseInt(receipt.getOrDefault(prefix + "PlayerHits", "0"));
            if (!activeInitialThreatReceipt.get(prefix + "Uuid").equals(receipt.get(prefix + "Uuid"))
                    || !"false".equals(receipt.get(prefix + "Alive")) || !"0.0".equals(receipt.get(prefix + "Health"))
                    || nativeHits < 1 || !"0".equals(receipt.get(prefix + "ForeignDamage"))
                    || !"player".equals(receipt.get(prefix + "LastDamage"))
                    || !"true".equals(receipt.get(prefix + "LastDamageByPlayer"))) return false;
            hits += nativeHits;
        }
        return hits >= weaponHits && hits <= weaponHits * 2;
    }

    private boolean preparedSafetyCreeperContactCompleted(Map<String, String> receipt, int pickaxeWear) {
        int stoneSwordWear = Integer.parseInt(receipt.getOrDefault("stoneSwordDamage", "-1"))
            - Integer.parseInt(activeInitialThreatReceipt.getOrDefault("stoneSwordDamage", "-1"));
        return preparedThreatBucketTaskObserved && preparedMaintenanceReservationPresentAtCompletion
            && preparedMaintenanceReservationObservedBeforeForeground && preparedMaintenanceQueueEmptyBeforeForeground
            && latestSnapshot.health == 20.0F && "20.0".equals(receipt.get("creeperContactPlayerHealth"))
            && "true".equals(receipt.get("creeperContactPlayerAlive"))
            && "0".equals(receipt.get("creeperContactPlayerDeaths"))
            && "false".equals(receipt.get("creeperContactClockFrozen"))
            && Integer.parseInt(receipt.getOrDefault("creeperContactClockReleaseServerTick", "-1")) >= 0
            && activeInitialThreatReceipt.get("creeperUuid").equals(receipt.get("creeperUuid"))
            && "escaped_alive".equals(receipt.get("creeperContactOutcome"))
            && "true".equals(receipt.get("creeperAlive")) && "false".equals(receipt.get("creeperRemoved"))
            && "20.0".equals(receipt.get("creeperHealth"))
            && Double.parseDouble(receipt.getOrDefault("creeperDistanceSquared", "NaN")) >= 144.0
            && "false".equals(receipt.get("creeperContactPlayerKillObserved"))
            && "false".equals(receipt.get("creeperContactExplosionObserved"))
            && "20.0".equals(receipt.get("creeperContactMinimumPlayerHealth"))
            && Integer.parseInt(receipt.getOrDefault("creeperContactObservedServerTicks", "0")) > 0
            && "true".equals(receipt.get("creeperContactControlShade"))
            && "true".equals(receipt.get("creeperAiEnabled"))
            && activeInitialThreatReceipt.get("zombieUuid").equals(receipt.get("zombieUuid"))
            && "true".equals(receipt.get("zombieAlive")) && "4.0".equals(receipt.get("zombieHealth"))
            && "false".equals(receipt.get("zombieAiEnabled"))
            && activeInitialThreatReceipt.get("cowUuid").equals(receipt.get("cowUuid"))
            && "true".equals(receipt.get("cowAlive"))
            && activeInitialThreatReceipt.get("cowInitialHealth").equals(receipt.get("cowHealth"))
            && activeInitialThreatReceipt.get("diamondSwordDamage").equals(receipt.get("diamondSwordDamage"))
            && "0".equals(receipt.get("diamondSwordDamage")) && pickaxeWear == 0
            && stoneSwordWear == 0;
    }

    private boolean preparedSafetyStaircaseFixtureReady() {
        Map<String, String> receipt = latestSnapshot.preparedSafetyThreatReceipt;
        return latestSnapshot.storageInventory.equals(latestSnapshot.inventory)
            && latestSnapshot.inventory.equals(Map.of("minecraft:crafting_table", 1, "minecraft:diamond_sword", 1,
                "minecraft:iron_ingot", 3, "minecraft:wooden_pickaxe", 1))
            && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.foodLevel == 20
            && latestSnapshot.x == 0.5 && latestSnapshot.y == 64.0 && latestSnapshot.z == 0.5
            && "true".equals(receipt.get("staircaseClockFrozen"))
            && "0".equals(receipt.get("staircaseChangedCells"))
            && "true".equals(receipt.get("creeperAlive")) && "20.0".equals(receipt.get("creeperHealth"))
            && "false".equals(receipt.get("staircaseCreeperAiEnabled"))
            && "0".equals(receipt.get("staircasePlayerDeaths"));
    }

    private void evaluatePreparedSafetyStaircase() {
        String status = requireEngine().status();
        Map<String, String> server = latestSnapshot.preparedSafetyThreatReceipt;
        staircaseReceipt.put("engineStatus", status);
        for (var entry : server.entrySet()) if (entry.getKey().startsWith("staircase"))
            staircaseReceipt.put(entry.getKey(), entry.getValue());
        if (staircaseTaskIdentity == null) staircaseTaskIdentity = requireEngine().diagnosticTaskIdentity();
        if (staircaseTaskIdentity != null && requireEngine().diagnosticTaskIdentity() != staircaseTaskIdentity) {
            fail("staircase retreat lost the original bucket request: " + status);
            return;
        }
        if (status.contains("retreating from live threats 1")) staircaseRetreatObserved = true;
        if (staircasePauseTick < 0) {
            if (status.contains("verifying current threat clearance")) {
                if (!staircaseRetreatObserved || staircaseTaskIdentity == null
                        || !client.player.isOnGround() || Math.abs(client.player.getY() - 68.0) > 0.0625) {
                    fail("staircase arrival lacked the original request, guarded retreat or four-block ascent: " + status);
                    return;
                }
                staircaseArrivalObserved = true;
                staircaseReceipt.put("arrivalClientTick", Integer.toString(clientTicks));
                staircaseReceipt.put("arrivalStatus", status);
                staircaseReceipt.put("arrivalY", Double.toString(client.player.getY()));
                requireEngine().pause("staircase retreat observation");
                status = requireEngine().status();
            }
            if (status.startsWith("paused")) {
                staircasePauseTick = clientTicks;
                staircasePauseServerTick = latestSnapshot.serverTick;
                staircasePauseObservationSequence = observationRequestSequence;
                staircaseReceipt.put("pauseClientTick", Integer.toString(staircasePauseTick));
                staircaseReceipt.put("pauseServerTick", Integer.toString(staircasePauseServerTick));
                staircaseReceipt.put("pauseObservationSequence", Long.toString(staircasePauseObservationSequence));
                staircaseReceipt.put("pauseStatus", status);
                staircaseTransport.accept(1);
            } else {
                if (clientTicks - caseStartedAtTick > PREPARED_SAFETY_CASE_TIMEOUT_TICKS)
                    fail("staircase retreat exceeded the existing case bound: " + status);
                return;
            }
        }
        if (observationFuture == null) requestObservation();
        int pauseFenceTick = Integer.parseInt(server.getOrDefault("staircasePauseFenceServerTick", "-1"));
        if (!server.getOrDefault("staircaseMarkerFailure", "").isEmpty()) {
            fail("staircase connection marker failed: " + server.get("staircaseMarkerFailure"));
            return;
        }
        if (pauseFenceTick < 0 || latestObservationRequestSequence <= staircasePauseObservationSequence
                || latestSnapshot.serverTick <= Math.max(staircasePauseServerTick, pauseFenceTick)) {
            if (clientTicks - staircasePauseTick > 20) fail("staircase pause missed a fresh server observation");
            return;
        }
        boolean settingsRestored = true;
        for (var entry : staircaseOriginalSettings.entrySet())
            settingsRestored &= java.util.Objects.equals(entry.getValue(), entry.getKey().value);
        for (var entry : staircaseOriginalConfig.entrySet())
            settingsRestored &= java.util.Objects.equals(entry.getValue(), requireEngine().config.read(entry.getKey()));
        boolean requestPreserved = staircaseTaskIdentity != null && requireEngine().diagnosticTaskIdentity() == staircaseTaskIdentity;
        boolean stopped = contactLowHealthMovementStopped();
        boolean inputRestored = client.player.input == staircaseOriginalInput;
        staircaseReceipt.put("retreatObserved", Boolean.toString(staircaseRetreatObserved));
        staircaseReceipt.put("arrivalObserved", Boolean.toString(staircaseArrivalObserved));
        staircaseReceipt.put("requestPreserved", Boolean.toString(requestPreserved));
        staircaseReceipt.put("nativeNavigationStopped", Boolean.toString(stopped));
        staircaseReceipt.put("originalInputRestored", Boolean.toString(inputRestored));
        staircaseReceipt.put("settingsRestored", Boolean.toString(settingsRestored));
        staircaseReceipt.put("nativeSettingsChecked", Integer.toString(staircaseOriginalSettings.size()));
        staircaseReceipt.put("observedClientTick", Integer.toString(clientTicks));
        staircaseReceipt.put("observedServerTick", Integer.toString(latestSnapshot.serverTick));
        staircaseReceipt.put("observedRequestSequence", Long.toString(latestObservationRequestSequence));
        boolean preserved = status.startsWith("paused") && requestPreserved && stopped && inputRestored && settingsRestored
            && requireEngine().config.pauseBelowHealth == 6.0F
            && latestSnapshot.inventory.equals(activeInitialResources) && latestSnapshot.storageInventory.equals(activeInitialResources)
            && latestSnapshot.equippedItems.equals(activeInitialEquipment) && latestSnapshot.serverCursorEmpty
            && latestSnapshot.health == 20.0F && "20.0".equals(server.get("staircaseMinimumPlayerHealth"))
            && "0".equals(server.get("staircasePlayerDeaths")) && "true".equals(server.get("staircasePlayerAlive"))
            && "0".equals(server.get("staircaseChangedCells"))
            && "0".equals(server.get("staircaseBlocksMined")) && "0".equals(server.get("staircaseBlockItemUses"))
            && "false".equals(server.get("staircaseClockFrozen"))
            && Integer.parseInt(server.getOrDefault("staircaseReleaseServerTick", "-1")) >= 0
            && pauseFenceTick >= Integer.parseInt(server.getOrDefault("staircaseReleaseServerTick", "-1"))
            && Integer.parseInt(server.getOrDefault("staircaseObservedServerTicks", "0")) > 0
            && server.getOrDefault("staircaseMarkerFailure", "missing").isEmpty()
            && activeInitialThreatReceipt.get("staircaseCreeperPosition").equals(server.get("staircaseCreeperPosition"))
            && activeInitialThreatReceipt.get("staircaseSelectedSlot").equals(server.get("staircaseSelectedSlot"))
            && activeInitialThreatReceipt.get("zombiePosition").equals(server.get("zombiePosition"))
            && activeInitialThreatReceipt.get("cowPosition").equals(server.get("cowPosition"))
            && activeInitialThreatReceipt.get("creeperUuid").equals(server.get("creeperUuid"))
            && "true".equals(server.get("creeperAlive")) && "20.0".equals(server.get("creeperHealth"))
            && activeInitialThreatReceipt.get("zombieUuid").equals(server.get("zombieUuid"))
            && activeInitialThreatReceipt.get("zombieHealth").equals(server.get("zombieHealth"))
            && activeInitialThreatReceipt.get("cowUuid").equals(server.get("cowUuid"))
            && activeInitialThreatReceipt.get("cowHealth").equals(server.get("cowHealth"))
            && activeInitialThreatReceipt.get("diamondSwordDamage").equals(server.get("diamondSwordDamage"))
            && activeInitialThreatReceipt.get("woodenPickaxeDamage").equals(server.get("woodenPickaxeDamage"));
        staircaseReceipt.put("preservedFixtureAndOwnership", Boolean.toString(preserved));
        if (!preserved || !staircaseArrivalObserved || !"31".equals(server.get("staircaseHeightMask"))
                || !"true".equals(server.get("staircaseSupportedEndpoint"))) {
            fail("staircase retreat lacked ascent, supported arrival, preserved fixture or restored ownership: " + status);
            return;
        }
        addResult(true, latestSnapshot.count(activeItem), "ordinary bucket command retreated through four one-block ascents to y 68; a fresh post-arrival pause receipt preserved every fixture block, stock and request, with zero deaths, zero mining and block-item-use counters, empty cursor and restored native settings and inputs", capture(activeCase));
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private void startPreparedSafetyThreatCase() {
        String engineStatus = requireEngine().status();
        if (!engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued")) {
            fail("prepared threat command was not issued from an idle engine: " + engineStatus);
            return;
        }
        activeCase = THREAT_STAIRCASE_MODE ? "threat_retreat_four_block_staircase"
            : CONTACT_LOW_HEALTH_MODE ? "contact_defense_low_health_owned_hop"
            : CONTACT_MANUAL_INPUT_MODE ? "contact_defense_manual_takeover"
            : THREAT_CREEPER_CONTACT_MODE ? "live_creeper_contact_defense_maintained_bucket"
            : THREAT_CONTACT_MODE ? "live_contact_defense_bucket"
            : THREAT_WATER_RETREAT_MODE ? "water_retreat_bucket" : "prepared_threat_sweep_guard_bucket";
        activeItem = "minecraft:bucket";
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.storageInventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        activeInitialThreatReceipt = Map.copyOf(latestSnapshot.preparedSafetyThreatReceipt);
        preparedSafetyForegroundStarted = !THREAT_CREEPER_CONTACT_MODE;
        preparedMaintenanceQueueEmptyBeforeForeground = !THREAT_CREEPER_CONTACT_MODE;
        preparedMaintenanceReservationObservedBeforeForeground = false;
        preparedMaintenanceReservationPresentAtCompletion = false;
        preparedThreatBucketTaskObserved = false;
        activeFoodLevelAtStart = latestSnapshot.foodLevel;
        beginCaseClock();
        state = State.PREPARED_SAFETY;
        if (THREAT_CREEPER_CONTACT_MODE) {
            sendCommand("!lk maintain diamond_sword 1");
            return;
        }
        if (CONTACT_LOW_HEALTH_MODE) {
            var settings = dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getSettings();
            for (var setting : settings.byLowerName.values()) contactLowHealthOriginalSettings.put(setting, setting.value);
            for (var spec : LodekeeperConfig.specs()) contactLowHealthOriginalConfig.put(spec.key(), requireEngine().config.read(spec.key()));
            contactLowHealthReceipt.put("submittedBucketTarget", "1");
            contactLowHealthReceipt.put("threshold", "6.0");
        }
        if (THREAT_STAIRCASE_MODE) {
            staircaseOriginalInput = client.player.input;
            for (var setting : dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getSettings().byLowerName.values())
                staircaseOriginalSettings.put(setting, setting.value);
            for (var spec : LodekeeperConfig.specs()) staircaseOriginalConfig.put(spec.key(), requireEngine().config.read(spec.key()));
            staircaseReceipt.put("command", "!lk get bucket 1");
            staircaseReceipt.put("initialServerTick", Integer.toString(latestSnapshot.serverTick));
            staircaseReceipt.put("initialY", Double.toString(latestSnapshot.y));
        }
        sendCommand("!lk get bucket 1");
        if (THREAT_STAIRCASE_MODE) staircaseTransport.accept(0);
        if (THREAT_CONTACT_MODE) {
            contactOriginalInput = client.player.input;
            releasePreparedThreatFixtureClock();
        }
    }

    private void releasePreparedThreatFixtureClock() {
        var server = client.getServer();
        if (server == null) throw new IllegalStateException("prepared threat clock release requires integrated server");
        server.execute(() -> {
            var player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("prepared threat clock release requires original player");
            VerificationApi.releasePreparedSafetyThreatClock(preparedSafetyThreatFixture, player);
        });
    }

    private static boolean invalidStationRoomTunnelMode() {
        return STATION_ROOM_TUNNEL_PROPERTY != null
            && ((!"true".equals(STATION_ROOM_TUNNEL_PROPERTY) && !"false".equals(STATION_ROOM_TUNNEL_PROPERTY)
                    && !"approach".equals(STATION_ROOM_TUNNEL_PROPERTY))
                || !BARITONE_MODE || !"station_room".equals(PREPARED_SAFETY_MODE)
                || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                || System.getProperty("lodekeeper.verify.naturalGoal") != null);
    }

    private static boolean stationRoomHistorySupported() {
        return List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion());
    }

    private static boolean stationRoomHistorySetupReady(Map<String, String> receipt) {
        if (!stationRoomHistorySupported() || STATION_ROOM_TUNNEL_MODE || STATION_ROOM_APPROACH_MODE) return true;
        try {
            return Integer.parseInt(receipt.get("stationHistoryEndTickObservations")) > 0
                && "true".equals(receipt.get("stationHistoryValid"))
                && "1".equals(receipt.get("stationOrdinaryFurnaceCount"))
                && "1".equals(receipt.get("stationCurrentOrdinaryFurnaceCount"))
                && "".equals(receipt.get("stationPlacedPosition"))
                && "-1".equals(receipt.get("stationFurnaceDebitServerTick"))
                && "-1".equals(receipt.get("stationPlacedServerTick"))
                && "-1".equals(receipt.get("stationRemovedServerTick"))
                && "-1".equals(receipt.get("stationReturnedServerTick"));
        } catch (NumberFormatException | NullPointerException malformed) {
            return false;
        }
    }

    private boolean normalStationRoomHistoryCase() {
        return preparedSafetyPhase == PreparedSafetyPhase.STATION_ROOM && stationRoomHistorySupported()
            && !STATION_ROOM_TUNNEL_MODE && !STATION_ROOM_APPROACH_MODE;
    }

    private void resetStationRoomCompletionFence() {
        stationRoomCompletionRequestFence = stationRoomCompletionServerFenceSequence = -1;
        stationRoomCompletionServerFenceTick = -1;
    }

    private boolean stationRoomCompletionObservationReady() {
        if (stationRoomCompletionRequestFence < 0) {
            stationRoomCompletionRequestFence = observationRequestSequence;
            requestObservation();
            return false;
        }
        if (latestObservationRequestSequence <= stationRoomCompletionRequestFence) {
            requestObservation();
            return false;
        }
        if (stationRoomCompletionServerFenceTick < 0) {
            stationRoomCompletionServerFenceTick = latestSnapshot.serverTick;
            stationRoomCompletionServerFenceSequence = latestObservationRequestSequence;
            requestObservation();
            return false;
        }
        try {
            if (latestObservationRequestSequence > stationRoomCompletionServerFenceSequence
                    && Integer.parseInt(latestSnapshot.preparedSafetyStationRoomReceipt.get("stationHistoryLastEndServerTick"))
                        > stationRoomCompletionServerFenceTick) return true;
        } catch (NumberFormatException | NullPointerException malformed) {
            // Malformed history cannot certify the completion fence.
        }
        requestObservation();
        return false;
    }

    private Map<String, String> stationRoomResultReceipt() {
        if (latestSnapshot == null) return Map.of();
        if (!normalStationRoomHistoryCase()) return latestSnapshot.preparedSafetyStationRoomReceipt;
        Map<String, String> receipt = new LinkedHashMap<>(latestSnapshot.preparedSafetyStationRoomReceipt);
        receipt.put("stationCompletionRequestFence", Long.toString(stationRoomCompletionRequestFence));
        receipt.put("stationCompletionServerFenceSequence", Long.toString(stationRoomCompletionServerFenceSequence));
        receipt.put("stationCompletionServerFenceTick", Integer.toString(stationRoomCompletionServerFenceTick));
        receipt.put("stationCompletionObservationSequence", Long.toString(latestObservationRequestSequence));
        receipt.put("stationCompletionObservationServerTick", Integer.toString(latestSnapshot.serverTick));
        return Map.copyOf(receipt);
    }

    private static boolean stationRoomHistoryCompleted(Map<String, String> initial, Map<String, String> receipt) {
        if (!"true".equals(initial.get("stationHistoryValid")) || !"true".equals(receipt.get("stationHistoryValid"))
                || !"1".equals(receipt.get("stationCurrentOrdinaryFurnaceCount"))
                || !"1".equals(initial.get("stationOrdinaryFurnaceCount"))
                || !"1".equals(receipt.get("stationOrdinaryFurnaceCount"))
                || !"".equals(initial.get("stationPlacedPosition"))
                || !"-1".equals(initial.get("stationFurnaceDebitServerTick"))
                || !"-1".equals(initial.get("stationPlacedServerTick"))
                || !"-1".equals(initial.get("stationRemovedServerTick"))
                || !"-1".equals(initial.get("stationReturnedServerTick"))
                || !"false".equals(initial.get("stationPlacedOnBedrock"))
                || !"true".equals(receipt.get("stationPlacedOnBedrock"))
                || receipt.getOrDefault("stationPlacedPosition", "").isEmpty()) return false;
        try {
            int initialEndObservations = Integer.parseInt(initial.get("stationHistoryEndTickObservations"));
            int finalEndObservations = Integer.parseInt(receipt.get("stationHistoryEndTickObservations"));
            int lastEndTick = Integer.parseInt(receipt.get("stationHistoryLastEndServerTick"));
            int seeded = Integer.parseInt(initial.get("stationSeededAtServerTick"));
            int initialObserved = Integer.parseInt(initial.get("stationObservedAtServerTick"));
            int observed = Integer.parseInt(receipt.get("stationObservedAtServerTick"));
            int debit = Integer.parseInt(receipt.get("stationFurnaceDebitServerTick"));
            int placed = Integer.parseInt(receipt.get("stationPlacedServerTick"));
            int removed = Integer.parseInt(receipt.get("stationRemovedServerTick"));
            int returned = Integer.parseInt(receipt.get("stationReturnedServerTick"));
            return initialEndObservations > 0 && finalEndObservations > initialEndObservations && lastEndTick == observed
                && seeded >= 0 && Integer.toString(seeded).equals(receipt.get("stationSeededAtServerTick"))
                && initialObserved >= seeded && debit > initialObserved && placed >= debit
                && removed > placed && returned >= removed && observed >= returned;
        } catch (NumberFormatException | NullPointerException malformed) {
            return false;
        }
    }

    private static boolean stationRoomTunnelSetupReady(Map<String, String> receipt) {
        return "tunnel".equals(receipt.get("stationRoomSubmode"))
            && "0.367555,64.0,0.505802".equals(receipt.get("playerPosition"))
            && Math.abs(Float.parseFloat(receipt.getOrDefault("playerYaw", "NaN")) - 98.886902F) < 0.0001F
            && Math.abs(Float.parseFloat(receipt.getOrDefault("playerPitch", "NaN")) + 38.467983F) < 0.0001F
            && "true".equals(receipt.get("stonePickaxeHeld"))
            && "true".equals(receipt.get("craftingTablePresent"))
            && "8".equals(receipt.get("tunnelAirCellCount"))
            && "-2,64,0;-2,65,0;-2,66,0;-1,65,0;-1,66,0;0,64,0;0,65,0;0,66,0".equals(receipt.get("tunnelAirPositions"))
            && "361".equals(receipt.get("roomFloorBedrockCellCount"))
            && "true".equals(receipt.get("roomFloorBedrock"));
    }

    private static boolean stationRoomApproachGeometryPreserved(Map<String, String> receipt) {
        return "approach".equals(receipt.get("stationRoomSubmode"))
            && "4683".equals(receipt.get("bedrockShellCellCount"))
            && "0".equals(receipt.get("bedrockShellCellsChangedCount"))
            && "359".equals(receipt.get("roomFloorBedrockCellCount"))
            && "true".equals(receipt.get("approachFloorTablesPresent"))
            && "true".equals(receipt.get("approachCeilingPresent"))
            && "1,65,0".equals(receipt.get("approachStartStance"))
            && "0,64,1".equals(receipt.get("approachValidStance"));
    }

    private static boolean stationRoomApproachSetupReady(Map<String, String> receipt) {
        return stationRoomApproachGeometryPreserved(receipt)
            && "1.5,65.0,0.5".equals(receipt.get("playerPosition"))
            && "1,65,0".equals(receipt.get("playerBlockPosition"))
            && "0.0".equals(receipt.get("playerYaw")) && "0.0".equals(receipt.get("playerPitch"))
            && "true".equals(receipt.get("stonePickaxeHeld"))
            && "8".equals(receipt.get("approachAirCellCount"))
            && "0,64,0;1,65,0;1,66,0;1,64,1;1,65,1;1,66,1;0,64,1;0,65,1".equals(receipt.get("approachAirPositions"));
    }

    private void startPreparedSafetyStationRoomCase() {
        String engineStatus = requireEngine().status();
        if (!engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued")) {
            fail("prepared station-room command was not issued from an idle engine: " + engineStatus);
            return;
        }
        activeCase = STATION_ROOM_APPROACH_MODE ? "prepared_station_room_approach_iron_ingot"
            : STATION_ROOM_TUNNEL_MODE ? "prepared_station_room_tunnel_iron_ingot" : "prepared_station_room_iron_ingot";
        activeItem = IRON_INGOT_ID;
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.storageInventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        activeInitialStationRoomReceipt = Map.copyOf(latestSnapshot.preparedSafetyStationRoomReceipt);
        resetStationRoomCompletionFence();
        if (STATION_ROOM_TUNNEL_MODE || STATION_ROOM_APPROACH_MODE) stationRoomSetupScreenshot = capture(
            STATION_ROOM_APPROACH_MODE ? "prepared-safety-station-room-approach-setup" : "prepared-safety-station-room-tunnel-setup");
        preparedSafetyForegroundStarted = true;
        preparedMaintenanceQueueEmptyBeforeForeground = true;
        preparedMaintenanceReservationObservedBeforeForeground = false;
        preparedMaintenanceReservationPresentAtCompletion = false;
        activeFoodLevelAtStart = latestSnapshot.foodLevel;
        beginCaseClock();
        state = State.PREPARED_SAFETY;
        sendCommand("!lk get iron_ingot 1");
    }

    private void startPreparedSafetyPursuitCase() {
        String engineStatus = requireEngine().status();
        if (!engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued")) {
            fail("prepared pursuit command was not issued from an idle engine: " + engineStatus);
            return;
        }
        boolean toolVariant = "pursuit-tool".equals(PREPARED_SAFETY_MODE);
        activeCase = toolVariant ? "prepared_moving_food_pursuit_tool_bucket" : "prepared_moving_food_pursuit_bucket";
        activeItem = "minecraft:bucket";
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.storageInventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        activeInitialPursuitReceipt = Map.copyOf(latestSnapshot.preparedSafetyPursuitReceipt);
        preparedSafetyForegroundStarted = true;
        preparedMaintenanceQueueEmptyBeforeForeground = true;
        preparedMaintenanceReservationObservedBeforeForeground = false;
        preparedMaintenanceReservationPresentAtCompletion = false;
        activeFoodLevelAtStart = latestSnapshot.foodLevel;
        activeTableOpeningsAtStart = serverTableOpenings;
        beginCaseClock();
        state = State.PREPARED_SAFETY;
        preparedSafetyPursuitFixture.beginObservation();
        sendCommand("!lk get bucket 1");
    }

    private void startPreparedSafetyAirCase() {
        String engineStatus = requireEngine().status();
        if (!engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued")) {
            fail("prepared air-recovery command was not issued from an idle engine: " + engineStatus);
            return;
        }
        Map<String, String> receipt = latestSnapshot.preparedSafetyAirReceipt;
        int airSupply = Integer.parseInt(receipt.getOrDefault("airSupply", "-1"));
        if (airSupply < 160 || airSupply > 180 || !"true".equals(receipt.get("headInWater"))) {
            fail("prepared air-recovery command did not start with the player's head submerged and at least eight seconds of air");
            return;
        }
        activeCase = "prepared_air_recovery_bucket";
        activeItem = "minecraft:bucket";
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.storageInventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        activeInitialAirReceipt = Map.copyOf(receipt);
        preparedAirRecoveryObserved = false;
        preparedAirRecoveryCompletedBeforeCraft = false;
        preparedAirRecoveryObservedClientTick = -1;
        preparedAirRecoveryTransitionClientTick = -1;
        preparedAirRecoveryCompletionClientTick = -1;
        preparedAirRecoveryCompletionServerTick = -1;
        preparedAirRecoveryTransitionObservationSequence = -1;
        preparedAirRecoveryTableOpeningsAtTransition = -1;
        preparedAirRecoveryBucketCountAtCompletion = -1;
        preparedAirRecoveryEndEngineStatus = "";
        preparedAirRecoveryCompletionReceipt = Map.of();
        preparedSafetyForegroundStarted = true;
        preparedMaintenanceQueueEmptyBeforeForeground = true;
        preparedMaintenanceReservationObservedBeforeForeground = false;
        preparedMaintenanceReservationPresentAtCompletion = false;
        activeFoodLevelAtStart = latestSnapshot.foodLevel;
        activeTableOpeningsAtStart = serverTableOpenings;
        beginCaseClock();
        state = State.PREPARED_SAFETY;
        sendCommand("!lk get bucket 1");
    }

    private void observePreparedAirRecoveryLatches() {
        if (preparedSafetyPhase != PreparedSafetyPhase.AIR || state != State.PREPARED_SAFETY
                || !"prepared_air_recovery_bucket".equals(activeCase)) return;
        String engineStatus = requireEngine().status();
        if (engineStatus.startsWith("recovering air")) {
            preparedAirRecoveryObserved = true;
            if (preparedAirRecoveryObservedClientTick < 0) preparedAirRecoveryObservedClientTick = clientTicks;
            return;
        }
        if (!preparedAirRecoveryObserved || preparedAirRecoveryCompletedBeforeCraft) return;
        if (preparedAirRecoveryTransitionClientTick < 0) {
            preparedAirRecoveryTransitionClientTick = clientTicks;
            preparedAirRecoveryTransitionObservationSequence = observationRequestSequence;
            preparedAirRecoveryTableOpeningsAtTransition = serverTableOpenings;
            preparedAirRecoveryEndEngineStatus = engineStatus;
        }
        if (observationFuture == null) requestObservation();
        if (latestSnapshot == null
                || latestObservationRequestSequence <= preparedAirRecoveryTransitionObservationSequence) return;
        Map<String, String> receipt = latestSnapshot.preparedSafetyAirReceipt;
        int airSupply = Integer.parseInt(receipt.getOrDefault("airSupply", "-1"));
        int maximumAirSupply = Integer.parseInt(receipt.getOrDefault("maxAirSupply", "-1"));
        if (latestSnapshot.count("minecraft:bucket") == 0
                && serverTableOpenings == activeTableOpeningsAtStart
                && preparedAirRecoveryTableOpeningsAtTransition == activeTableOpeningsAtStart
                && "false".equals(receipt.get("headInWater"))
                && maximumAirSupply > 0 && airSupply >= maximumAirSupply * 9 / 10) {
            preparedAirRecoveryCompletedBeforeCraft = true;
            preparedAirRecoveryCompletionClientTick = clientTicks;
            preparedAirRecoveryCompletionServerTick = latestSnapshot.serverTick;
            preparedAirRecoveryBucketCountAtCompletion = latestSnapshot.count("minecraft:bucket");
            preparedAirRecoveryCompletionReceipt = Map.copyOf(receipt);
        }
    }

    private boolean baritoneNavigationStopped() {
        if (!BARITONE_MODE) return true;
        var upstream = dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getProvider().getPrimaryBaritone();
        dev.lodekeeper.navigation.kernel.api.process.IBaritoneProcess[] processes = {
                upstream.getMineProcess(), upstream.getFollowProcess(), upstream.getCustomGoalProcess(),
                upstream.getBuilderProcess(), upstream.getExploreProcess(), upstream.getFarmProcess(),
                upstream.getGetToBlockProcess(), upstream.getElytraProcess()
        };
        for (var process : processes) if (process.isActive()) return false;
        for (var key : dev.lodekeeper.navigation.kernel.api.utils.input.Input.values())
            if (upstream.getInputOverrideHandler().isInputForcedDown(key)) return false;
        return upstream.getFollowProcess().currentFilter() == null
                && !upstream.getPathingBehavior().hasPath() && !upstream.getPathingBehavior().isPathing()
                && upstream.getPathingBehavior().getInProgress().isEmpty();
    }

    private boolean contactLowHealthMovementStopped() {
        return baritoneNavigationStopped() && !ShieldController.isHoldingUse()
            && !client.options.attackKey.isPressed() && !client.options.useKey.isPressed()
            && !client.options.forwardKey.isPressed() && !client.options.backKey.isPressed()
            && !client.options.leftKey.isPressed() && !client.options.rightKey.isPressed()
            && !client.options.jumpKey.isPressed() && !client.options.sneakKey.isPressed() && !client.options.sprintKey.isPressed();
    }

    private boolean contactLowHealthSettingsRestored() {
        for (var entry : contactLowHealthOriginalSettings.entrySet())
            if (!java.util.Objects.equals(entry.getValue(), entry.getKey().value)) return false;
        for (var entry : contactLowHealthOriginalConfig.entrySet())
            if (!java.util.Objects.equals(entry.getValue(), requireEngine().config.read(entry.getKey()))) return false;
        return requireEngine().config.pauseBelowHealth == 6.0F;
    }

    private void observeContactLowHealthOnset(String eventPhase, int observedClientTick) {
        if (!CONTACT_LOW_HEALTH_MODE || contactLowHealthPhase != ContactLowHealthPhase.HEALTH_REQUESTED
                || state == State.COMPLETE || state == State.FAILED || state == State.DISABLED || client.player == null) return;
        String status = requireEngine().status();
        float health = client.player.getHealth();
        if (requireEngine().diagnosticTaskIdentity() != contactLowHealthTaskIdentity
                || !playerId.equals(client.player.getUuid()) || status.startsWith("paused")
                || client.player.isOnGround() || client.player.input != contactLowHealthOwnedInput
                || observedClientTick - contactLowHealthTriggerTick > 20
                || !(status.contains("timing a safe airborne defense attack") || status.contains("landing after native defense attack"))) {
            fail("low-health mutation was not observed during that same owned airborne hop: " + status);
            return;
        }
        if (health > 6.0F) return;
        contactLowHealthOnsetTick = observedClientTick;
        contactLowHealthReceipt.put("observedOnsetClientTick", Integer.toString(observedClientTick));
        contactLowHealthReceipt.put("observedOnsetHealth", Float.toString(health));
        contactLowHealthReceipt.put("observedOnsetStatus", status);
        contactLowHealthReceipt.put("observedOnsetEventPhase", eventPhase);
        contactLowHealthReceipt.put("observedOnsetRequestPreserved", "true");
        contactLowHealthAttackAttempts = 0;
        contactLowHealthReceipt.put("nativePostOnsetAttackAttempts", "0");
        contactLowHealthReceipt.put("nativeAttackObserverIdentityValid", "true");
        contactLowHealthPhase = ContactLowHealthPhase.ONSET_SENT;
        contactLowHealthTransport.accept(1);
    }

    private void evaluateContactLowHealth() {
        String status = requireEngine().status();
        Map<String, String> server = latestSnapshot.preparedSafetyThreatReceipt;
        contactLowHealthReceipt.put("phase", contactLowHealthPhase.name());
        contactLowHealthReceipt.put("engineStatus", status);
        for (var entry : server.entrySet()) if (entry.getKey().startsWith("lowHealth")) contactLowHealthReceipt.put(entry.getKey(), entry.getValue());
        if (requireEngine().config.pauseBelowHealth != 6.0F || clientTicks - caseStartedAtTick > PREPARED_SAFETY_CASE_TIMEOUT_TICKS) {
            fail("low-health contact threshold changed or existing case bound expired");
            return;
        }
        if (contactLowHealthAttackAttempts > 0 || !contactLowHealthAttackObserverValid) {
            fail("native client attack attempted after observed low-health onset or observer identity changed");
            return;
        }
        if (!server.getOrDefault("lowHealthMarkerFailure", "").isEmpty()
                || Integer.parseInt(server.getOrDefault("lowHealthPostFenceDamageEvents", "-1")) > 0) {
            fail("low-health native marker failed or player damage was confirmed after observed onset: " + server);
            return;
        }
        if (contactLowHealthPhase == ContactLowHealthPhase.WAIT_FIRST_HOP) {
            if (status.startsWith("paused") || clientTicks - caseStartedAtTick > 100) {
                fail("low-health contact missed the first owned airborne hop: " + status);
                return;
            }
            if (!status.contains("timing a safe airborne defense attack") || client.player.isOnGround()
                    || !(client.player.input instanceof BotInput) || client.player.input == contactOriginalInput) return;
            contactLowHealthTaskIdentity = requireEngine().diagnosticTaskIdentity();
            if (contactLowHealthTaskIdentity == null || client.player.getHealth() <= 6.0F) {
                fail("first owned hop lacked the original bucket request or started at low health");
                return;
            }
            contactLowHealthOwnedInput = (BotInput) client.player.input;
            contactLowHealthTriggerTick = clientTicks;
            contactLowHealthReceipt.put("triggerClientTick", Integer.toString(clientTicks));
            contactLowHealthReceipt.put("triggerHealth", Float.toString(client.player.getHealth()));
            contactLowHealthReceipt.put("triggerStatus", status);
            contactLowHealthReceipt.put("injectedWhileOwnedAirborne", "true");
            contactLowHealthTransport.accept(0);
            contactLowHealthPhase = ContactLowHealthPhase.HEALTH_REQUESTED;
            return;
        }
        if (requireEngine().diagnosticTaskIdentity() != contactLowHealthTaskIdentity) {
            fail("low-health contact lost or replaced the original bucket request: " + status);
            return;
        }
        if (contactLowHealthPhase == ContactLowHealthPhase.HEALTH_REQUESTED) {
            observeContactLowHealthOnset("END_CLIENT_TICK", clientTicks);
            return;
        }
        if (contactLowHealthPhase == ContactLowHealthPhase.ONSET_SENT) {
            if (status.startsWith("paused") || clientTicks - contactLowHealthOnsetTick > 30) {
                fail("low-health owned hop missed confirmed native landing: " + status);
                return;
            }
            if (!status.contains("stopping before retreat")) {
                if (client.player.input != contactLowHealthOwnedInput)
                    fail("low-health owned hop input was lost before its landing receipt: " + status);
                return;
            }
            if (!client.player.isOnGround() || client.player.input != contactOriginalInput
                    || !new GameTerrain(client, requireEngine().config).defenseHopLandingSafe(
                        client.player.getX(), client.player.getY(), client.player.getZ(), GameTerrain.quantizedFeetY16(64.0))) {
                fail("low-health hop-to-retreat transition lacked native landing support or original input restoration");
                return;
            }
            contactLowHealthLandingTick = clientTicks;
            contactLowHealthReceipt.put("landingClientTick", Integer.toString(clientTicks));
            contactLowHealthReceipt.put("nativeLandingSupport", "true");
            contactLowHealthPhase = ContactLowHealthPhase.LANDED;
            return;
        }
        if (contactLowHealthPhase == ContactLowHealthPhase.LANDED) {
            if (!status.startsWith("paused")) {
                if (clientTicks - contactLowHealthLandingTick > 20) fail("low-health landed hop missed the explicit no-route pause");
                return;
            }
            if (!status.contains("No safe dry retreat stance remains within the bounded search")
                    || client.player.input != contactOriginalInput || !contactLowHealthMovementStopped()
                    || !contactLowHealthSettingsRestored()) {
                fail("low-health no-route pause lacked full native cancellation, original input or settings restoration: " + status);
                return;
            }
            contactLowHealthPauseTick = clientTicks;
            contactLowHealthPauseServerTick = latestSnapshot.serverTick;
            contactLowHealthPauseObservationSequence = observationRequestSequence;
            contactLowHealthReceipt.put("pauseClientTick", Integer.toString(clientTicks));
            contactLowHealthReceipt.put("pauseServerTick", Integer.toString(contactLowHealthPauseServerTick));
            contactLowHealthReceipt.put("pauseObservationSequence", Long.toString(contactLowHealthPauseObservationSequence));
            contactLowHealthTransport.accept(2);
            contactLowHealthPhase = ContactLowHealthPhase.PAUSED;
        }
        if (contactLowHealthPhase != ContactLowHealthPhase.PAUSED) return;
        if (!status.startsWith("paused") || !status.contains("No safe dry retreat stance remains within the bounded search")) {
            fail("low-health no-route pause was not retained: " + status);
            return;
        }
        if (observationFuture == null) requestObservation();
        int fenceTick = Integer.parseInt(server.getOrDefault("lowHealthFenceServerTick", "-1"));
        int landingTick = Integer.parseInt(server.getOrDefault("lowHealthLandingServerTick", "-1"));
        int pauseFenceTick = Integer.parseInt(server.getOrDefault("lowHealthPauseFenceServerTick", "-1"));
        if (pauseFenceTick < 0 || latestObservationRequestSequence <= contactLowHealthPauseObservationSequence
                || latestSnapshot.serverTick <= Math.max(contactLowHealthPauseServerTick, Math.max(pauseFenceTick, landingTick))) {
            if (clientTicks - contactLowHealthPauseTick > 20) fail("low-health pause missed a fresh post-pause server observation");
            return;
        }
        boolean passed = contactLowHealthAttackAttempts == 0 && contactLowHealthAttackObserverValid
            && Integer.parseInt(server.getOrDefault("lowHealthMutationServerTick", "-1")) >= 0
            && "6.0".equals(server.get("lowHealthMutationAfter")) && playerId.toString().equals(server.get("lowHealthPlayerUuid"))
            && fenceTick >= Integer.parseInt(server.getOrDefault("lowHealthMutationServerTick", "-1"))
            && landingTick >= fenceTick && pauseFenceTick >= fenceTick
            && server.get("lowHealthFenceDamageEvents").equals(server.get("lowHealthPauseFenceDamageEvents"))
            && "0".equals(server.get("lowHealthPostFenceDamageEvents"))
            && Integer.parseInt(server.getOrDefault("lowHealthFenceDamageEvents", "-1")) >= 0
            && server.getOrDefault("lowHealthMarkerFailure", "missing").isEmpty()
            && client.player.input == contactOriginalInput && contactLowHealthMovementStopped() && contactLowHealthSettingsRestored()
            && requireEngine().diagnosticTaskIdentity() == contactLowHealthTaskIdentity
            && latestSnapshot.inventory.equals(activeInitialResources) && latestSnapshot.storageInventory.equals(activeInitialResources)
            && latestSnapshot.equippedItems.equals(activeInitialEquipment) && latestSnapshot.serverCursorEmpty
            && latestSnapshot.health > 0 && "true".equals(server.get("contactPlayerAlive")) && "0".equals(server.get("contactPlayerDeaths"))
            && "false".equals(server.get("contactClockFrozen")) && Integer.parseInt(server.getOrDefault("contactClockReleaseServerTick", "-1")) >= 0
            && contactShellPreserved(server) && "true".equals(server.get("cowAlive"))
            && activeInitialThreatReceipt.get("cowUuid").equals(server.get("cowUuid"))
            && activeInitialThreatReceipt.get("cowHealth").equals(server.get("cowHealth"));
        for (int index = 0; index < 2; index++) {
            String prefix = "contactZombie" + index;
            passed &= activeInitialThreatReceipt.get(prefix + "Uuid").equals(server.get(prefix + "Uuid"))
                && "true".equals(server.get(prefix + "Alive")) && Float.parseFloat(server.getOrDefault(prefix + "Health", "0")) > 0
                && "0".equals(server.get(prefix + "ForeignDamage"));
        }
        contactLowHealthReceipt.put("observedClientTick", Integer.toString(clientTicks));
        contactLowHealthReceipt.put("observedServerTick", Integer.toString(latestSnapshot.serverTick));
        contactLowHealthReceipt.put("observedRequestSequence", Long.toString(latestObservationRequestSequence));
        contactLowHealthReceipt.put("requestPreserved", Boolean.toString(requireEngine().diagnosticTaskIdentity() == contactLowHealthTaskIdentity));
        contactLowHealthReceipt.put("originalInputRestored", Boolean.toString(client.player.input == contactOriginalInput));
        contactLowHealthReceipt.put("nativeNavigationStopped", Boolean.toString(contactLowHealthMovementStopped()));
        contactLowHealthReceipt.put("settingsRestored", Boolean.toString(contactLowHealthSettingsRestored()));
        contactLowHealthReceipt.put("nativeSettingsChecked", Integer.toString(contactLowHealthOriginalSettings.size()));
        if (!passed) {
            fail("low-health post-pause receipt lacked ordered attack suppression, native landing, exact request, settings, stock, shell, cow or survival: " + server);
            return;
        }
        contactLowHealthPhase = ContactLowHealthPhase.COMPLETE;
        contactLowHealthReceipt.put("phase", contactLowHealthPhase.name());
        activeCount = 0;
        addResult(true, latestSnapshot.count(activeItem), "health set exactly 6 during the first owned hop; observed airborne onset fenced native attacks, landed with support, cancelled all native movement and restored settings, then paused for no route with the original bucket request and a fresh preserved-fixture server receipt", capture(activeCase));
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private void evaluateContactManualInput() {
        String engineStatus = requireEngine().status();
        if (!contactManualKeyInjected) {
            if (engineStatus.startsWith("paused")) {
                fail("contact defense paused before exposing the manual takeover condition: " + engineStatus);
                return;
            }
            if (engineStatus.contains("timing a safe airborne defense attack") && !client.player.isOnGround()
                    && client.player.input instanceof BotInput && client.player.input != contactOriginalInput) {
                contactManualTaskIdentity = requireEngine().diagnosticTaskIdentity();
                if (contactManualTaskIdentity == null) {
                    fail("contact defense had no active bucket request before manual input");
                    return;
                }
                client.options.forwardKey.setPressed(true);
                contactManualKeyInjected = true;
                contactManualInputTick = clientTicks;
            }
            if (clientTicks - caseStartedAtTick > 100)
                fail("contact defense did not expose an owned airborne hop for manual input verification");
            return;
        }
        if (!engineStatus.startsWith("paused")) {
            if (contactManualGuardTick >= 0 || clientTicks - contactManualInputTick > 20) {
                client.options.forwardKey.setPressed(false);
                fail("manual input did not keep the owned defense hop paused within twenty client ticks");
            }
            return;
        }
        if (contactManualGuardTick < 0) {
            boolean keyPreserved = client.options.forwardKey.isPressed();
            boolean originalInputRestored = client.player.input == contactOriginalInput;
            boolean taskPreserved = requireEngine().diagnosticTaskIdentity() == contactManualTaskIdentity;
            boolean nativeStopped = baritoneNavigationStopped();
            contactManualInputReceipt = Map.of(
                    "physicalKeyPreserved", Boolean.toString(keyPreserved),
                    "originalInputRestored", Boolean.toString(originalInputRestored),
                    "requestPreserved", Boolean.toString(taskPreserved),
                    "nativeNavigationStopped", Boolean.toString(nativeStopped),
                    "injectedWhileAirborne", "true",
                    "injectedClientTick", Integer.toString(contactManualInputTick),
                    "guardClientTick", Integer.toString(clientTicks),
                    "submittedBucketTarget", "1");
            client.options.forwardKey.setPressed(false);
            if (!engineStatus.contains("manual input interrupted defense")
                    || !keyPreserved || !originalInputRestored || !taskPreserved || !nativeStopped) {
                fail("manual defense takeover lacked immediate physical key, original input, exact request, or native drain: " + engineStatus);
                return;
            }
            contactManualGuardTick = clientTicks;
            contactManualGuardServerTick = latestSnapshot.serverTick;
            contactManualGuardObservationSequence = observationRequestSequence;
        }
        if (observationFuture == null) requestObservation();
        if (latestObservationRequestSequence <= contactManualGuardObservationSequence
                || latestSnapshot.serverTick <= contactManualGuardServerTick) {
            if (clientTicks - contactManualGuardTick > 20)
                fail("manual defense takeover did not obtain a fresh post-pause server observation");
            return;
        }
        Map<String, String> receipt = latestSnapshot.preparedSafetyThreatReceipt;
        boolean passed = engineStatus.contains("manual input interrupted defense")
                && requireEngine().diagnosticTaskIdentity() == contactManualTaskIdentity
                && client.player.input == contactOriginalInput && baritoneNavigationStopped()
                && latestSnapshot.inventory.equals(activeInitialResources) && latestSnapshot.serverCursorEmpty
                && latestSnapshot.equippedItems.isEmpty() && latestSnapshot.health > 0
                && "true".equals(receipt.get("contactPlayerAlive"))
                && "0".equals(receipt.get("contactPlayerDeaths"))
                && "0".equals(receipt.get("contactShellChangedCells"))
                && activeInitialThreatReceipt.get("cowHealth").equals(receipt.get("cowHealth"));
        Map<String, String> completeReceipt = new LinkedHashMap<>(contactManualInputReceipt);
        completeReceipt.put("observedClientTick", Integer.toString(clientTicks));
        completeReceipt.put("observedServerTick", Integer.toString(latestSnapshot.serverTick));
        completeReceipt.put("guardServerTick", Integer.toString(contactManualGuardServerTick));
        completeReceipt.put("guardObservationSequence", Long.toString(contactManualGuardObservationSequence));
        completeReceipt.put("observedRequestSequence", Long.toString(latestObservationRequestSequence));
        contactManualInputReceipt = Map.copyOf(completeReceipt);
        if (!passed) {
            fail("post-pause server observation lacked preserved exact request, original input, inventory, cursor, shell, cow, survival, or stopped navigation: " + engineStatus);
            return;
        }
        activeCount = 0;
        String detail = "one forward-key press during a native airborne defense hop restored the original player input, preserved the key and exact bucket request, paused automation, and a later server observation confirmed inventory, cursor, shell, cow and survival with no native navigation work";
        addResult(true, latestSnapshot.count(activeItem), detail, capture(activeCase));
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private void evaluatePreparedSafetyCase() {
        if (THREAT_STAIRCASE_MODE) { evaluatePreparedSafetyStaircase(); return; }
        if (CONTACT_LOW_HEALTH_MODE) { evaluateContactLowHealth(); return; }
        if (CONTACT_MANUAL_INPUT_MODE) { evaluateContactManualInput(); return; }
        if (requireEngine().status().startsWith("paused")) {
            fail("prepared safety automation paused during " + activeCase + ": " + requireEngine().status());
            return;
        }
        String engineStatus = requireEngine().status();
        if (HELD_FUEL_MODE) {
            evaluatePreparedSafetyHeldFuelCase(engineStatus);
            return;
        }
        if (WORKBENCH_MODE) {
            evaluatePreparedSafetyWorkbenchCase(engineStatus);
            return;
        }
        if (THREAT_CREEPER_CONTACT_MODE && preparedSafetyForegroundStarted && !engineStatus.startsWith("idle")
                && requireEngine().diagnosticTaskIdentity() != null) preparedThreatBucketTaskObserved = true;
        if (THREAT_CREEPER_CONTACT_MODE && !preparedSafetyForegroundStarted) {
            if (clientTicks - caseStartedAtTick > PREPARED_SAFETY_CASE_TIMEOUT_TICKS) {
                fail("maintained diamond sword was not reserved before the creeper-contact bucket goal");
                return;
            }
            if (!engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued")
                    || !baritoneNavigationStopped() || requireEngine().diagnosticTaskIdentity() != null
                    || !maintainedReservationObserved("minecraft:diamond_sword", 1)) return;
            if (!latestSnapshot.serverCursorEmpty || !latestSnapshot.equippedItems.isEmpty()
                    || !latestSnapshot.inventory.equals(activeInitialResources)) {
                fail("maintaining the diamond sword changed the prepared inventory, equipment, or cursor before the bucket goal");
                return;
            }
            preparedMaintenanceQueueEmptyBeforeForeground = true;
            preparedMaintenanceReservationObservedBeforeForeground = true;
            preparedSafetyForegroundStarted = true;
            sendCommand("!lk get bucket 1");
            releasePreparedThreatFixtureClock();
            return;
        }
        if ((preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_FOOD
                || preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_INGREDIENTS)
                && !preparedSafetyForegroundStarted) {
            if (clientTicks - caseStartedAtTick < 2) return;
            if (!engineStatus.startsWith("idle") || !engineStatus.endsWith("0 maintenance queued")) {
                fail("offhand-maintained stock unexpectedly queued an acquisition before the foreground goal: " + engineStatus);
                return;
            }
            String maintainedItem = preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_FOOD
                ? "minecraft:cooked_beef" : "minecraft:oak_log";
            int maintainedCount = preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_FOOD ? 1 : 8;
            if (!maintainedReservationObserved(maintainedItem, maintainedCount)) {
                fail("maintained demand model did not retain the expected physically held reservation for " + maintainedItem);
                return;
            }
            preparedMaintenanceReservationObservedBeforeForeground = true;
            preparedMaintenanceQueueEmptyBeforeForeground = true;
            preparedSafetyForegroundStarted = true;
            if (preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_INGREDIENTS)
                sendCommand("!lk get wood 8");
            sendCommand(preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_FOOD
                ? "!lk get bucket 1" : "!lk get stick 4");
            return;
        }
        boolean navigationStopped = baritoneNavigationStopped();
        if (!engineStatus.startsWith("idle") || !navigationStopped) {
            if (normalStationRoomHistoryCase()) resetStationRoomCompletionFence();
            if (clientTicks - caseStartedAtTick > PREPARED_SAFETY_CASE_TIMEOUT_TICKS) {
                fail("prepared safety case timed out after " + PREPARED_SAFETY_CASE_TIMEOUT_TICKS + " ticks: " + activeCase);
            }
            return;
        }
        if (normalStationRoomHistoryCase() && !stationRoomCompletionObservationReady()) {
            if (clientTicks - caseStartedAtTick > PREPARED_SAFETY_CASE_TIMEOUT_TICKS)
                fail("prepared station-room completion lacks a fresh post-idle server-tick receipt");
            return;
        }
        if (preparedSafetyPhase == PreparedSafetyPhase.AIR && !preparedAirRecoveryCompletedBeforeCraft) {
            if (clientTicks - caseStartedAtTick > PREPARED_SAFETY_CASE_TIMEOUT_TICKS) {
                fail("prepared air recovery did not produce an ordered breathable-exit receipt before crafting");
            }
            return;
        }
        boolean noMaintenanceQueued = engineStatus.endsWith("0 maintenance queued");

        boolean passed;
        String detail;
        int observed;
        if (preparedSafetyPhase == PreparedSafetyPhase.EQUIPMENT) {
            observed = latestSnapshot.count(activeItem);
            passed = observed == activeCount && latestSnapshot.storageCount(activeItem) == 0
                && "minecraft:iron_helmet".equals(latestSnapshot.equippedItems.get("head"))
                && latestSnapshot.serverCursorEmpty
                && serverTableOpenings == activeTableOpeningsAtStart
                && serverFurnaceOpenings == activeFurnaceOpeningsAtStart;
            detail = passed
                ? "integrated server received the stored iron helmet in the head slot through a completed goal, with storage empty, cursor empty, and no crafting station opened"
                : "equipped armor goal completed without the required server head-slot receipt, empty cursor, and zero recrafting menus";
        } else if (preparedSafetyPhase == PreparedSafetyPhase.THREAT) {
            observed = latestSnapshot.count(activeItem);
            Map<String, String> receipt = latestSnapshot.preparedSafetyThreatReceipt;
            String pickaxeDamageKey = THREAT_CONTACT_MODE ? "ironPickaxeDamage" : "woodenPickaxeDamage";
            int initialPickaxeDamage = Integer.parseInt(activeInitialThreatReceipt.getOrDefault(pickaxeDamageKey, "-1"));
            int finalPickaxeDamage = Integer.parseInt(receipt.getOrDefault(pickaxeDamageKey, "-1"));
            int pickaxeWear = finalPickaxeDamage - initialPickaxeDamage;
            int stoneSwordWear = Integer.parseInt(receipt.getOrDefault("stoneSwordDamage", "-1"))
                - Integer.parseInt(activeInitialThreatReceipt.getOrDefault("stoneSwordDamage", "-1"));
            boolean pickaxeShowsNativeHits = pickaxeWear > 0 && pickaxeWear <= 6 && pickaxeWear % 2 == 0;
            if (THREAT_CREEPER_CONTACT_MODE) {
                preparedMaintenanceReservationPresentAtCompletion = preparedMaintenanceReservationObservedBeforeForeground
                    && maintainedReservationObserved("minecraft:diamond_sword", 1);
            }
            passed = observed == 1 && latestSnapshot.storageCount("minecraft:iron_ingot") == 0
                && latestSnapshot.count("minecraft:iron_ingot") == 0
                && latestSnapshot.serverCursorEmpty && latestSnapshot.equippedItems.isEmpty()
                && latestSnapshot.difficulty.equals(Difficulty.NORMAL.name()) && (THREAT_CONTACT_MODE || latestSnapshot.foodLevel == 20)
                && noMaintenanceQueued && preparedMaintenanceQueueEmptyBeforeForeground
                && activeInitialThreatReceipt.get("zombieUuid").equals(receipt.get("zombieUuid"))
                && activeInitialThreatReceipt.get("cowUuid").equals(receipt.get("cowUuid"))
                && "true".equals(receipt.get("cowAlive"))
                && activeInitialThreatReceipt.get("cowHealth").equals(receipt.get("cowHealth"))
                && (THREAT_CONTACT_MODE || activeInitialThreatReceipt.get("diamondSwordDamage").equals(receipt.get("diamondSwordDamage"))
                    && "0".equals(receipt.get("diamondSwordDamage")))
                && (THREAT_CONTACT_MODE ? preparedSafetyContactCompleted(receipt, pickaxeWear)
                    : THREAT_CREEPER_CONTACT_MODE ? preparedSafetyCreeperContactCompleted(receipt, pickaxeWear)
                    : THREAT_WATER_RETREAT_MODE
                    ? latestSnapshot.health == 20.0F && "true".equals(receipt.get("zombieAlive"))
                        && "4.0".equals(receipt.get("zombieHealth"))
                        && "true".equals(receipt.get("creeperAlive")) && "false".equals(receipt.get("creeperRemoved"))
                        && "20.0".equals(receipt.get("creeperHealth"))
                        && activeInitialThreatReceipt.getOrDefault("creeperUuid", "").equals(receipt.get("creeperUuid"))
                        && Double.parseDouble(receipt.getOrDefault("creeperDistanceSquared", "NaN")) >= 144
                        && "0".equals(receipt.get("woodenPickaxeDamage")) && pickaxeWear == 0
                        && "false".equals(receipt.get("playerInWater"))
                        && "true".equals(receipt.get("playerSupportBedrock"))
                        && "true".equals(receipt.get("playerBodyCellsAir"))
                        && "true".equals(receipt.get("lowWaterRoofPresent"))
                        && "true".equals(receipt.get("waterSourceCellsPresent"))
                        && "true".equals(receipt.get("waterFloorPresent"))
                    : "true".equals(receipt.get("preparedThreatsCleared"))
                        && "false".equals(receipt.get("zombieAlive")) && "0.0".equals(receipt.get("zombieHealth"))
                        && pickaxeShowsNativeHits);
            detail = THREAT_CONTACT_MODE
                ? passed ? "both original full-health live zombies died from native player hits, the player survived native contact damage with zero deaths, the iron pickaxe wore by " + pickaxeWear + ", safe sword wear was bounded, cow stayed untouched, shell stayed intact, and the supplied iron became one bucket with cursor clear and idle stopped navigation"
                    : "live contact defense lacked required native hits, two player-attributed deaths, player survival, protected cow, bounded weapon wear, intact shell, or bucket completion"
                : THREAT_CREEPER_CONTACT_MODE
                ? passed ? "the same full-health normal-AI creeper remained unharmed at least twelve blocks away with its fuse stopped; the server observed no player health loss and zero deaths, the protected diamond sword and shaded control mobs stayed untouched, the stone sword wore by " + stoneSwordWear + ", the maintained reservation remained present, and the observed bucket goal completed with an empty cursor and idle navigation"
                    : "creeper contact lacked an unharmed same-UUID live escape, stopped fuse, persistent full player health, zero deaths, untouched weapons and shaded control mobs, maintained reservation, active bucket goal, or idle bucket completion; observed outcome=" + receipt.getOrDefault("creeperContactOutcome", "missing") + " minimum player health=" + receipt.getOrDefault("creeperContactMinimumPlayerHealth", "missing")
                : THREAT_WATER_RETREAT_MODE
                ? passed ? "the prepared native water/roof fixture ended with the same unharmed creeper at least twelve blocks away, unchanged zombie/cow and weapons, a bucket from supplied iron, dry bedrock support, an empty cursor, and idle cancelled navigation"
                    : "the water-retreat fixture lacked the required native distance, health, untouched-mob/weapon, dry-support, bucket, cursor, or cancellation receipts"
                : passed
                ? "the integrated server killed the prepared zombie with native wooden-pickaxe hits, left the diamond sword untouched and cow at full health, then completed the bucket goal with an empty cursor and idle engine; measured pickaxe wear=" + pickaxeWear + " (" + (pickaxeWear / 2) + " hits at two wear each)"
                : "the prepared threat goal did not produce the required native zombie, cow, weapon-wear, bucket, cursor, and idle receipts";
        } else if (preparedSafetyPhase == PreparedSafetyPhase.PURSUIT) {
            observed = latestSnapshot.count(activeItem);
            Map<String, String> receipt = latestSnapshot.preparedSafetyPursuitReceipt;
            boolean toolVariant = "pursuit-tool".equals(PREPARED_SAFETY_MODE);
            int initialFoodItems = activeInitialResources.getOrDefault("minecraft:beef", 0)
                + activeInitialResources.getOrDefault("minecraft:cooked_beef", 0);
            int finalFoodItems = latestSnapshot.count("minecraft:beef") + latestSnapshot.count("minecraft:cooked_beef");
            int healthDrops = Integer.parseInt(receipt.getOrDefault("cowHealthDropsObserved", "0"));
            double displacement = Double.parseDouble(receipt.getOrDefault("cowMaximumHorizontalDisplacement", "NaN"));
            double maximumDistance = Double.parseDouble(receipt.getOrDefault("maximumDistanceFromPlayerStart", "NaN"));
            boolean foodReceived = latestSnapshot.foodLevel > activeFoodLevelAtStart || finalFoodItems > initialFoodItems;
            boolean sameCow = activeInitialPursuitReceipt.get("cowUuid") != null
                && activeInitialPursuitReceipt.get("cowUuid").equals(receipt.get("cowUuid"))
                && receipt.get("cowUuid").equals(receipt.get("cowInitialUuid"));
            int initialPickaxeDamage = Integer.parseInt(activeInitialPursuitReceipt.getOrDefault("stonePickaxeDamage", "-1"));
            int finalPickaxeDamage = Integer.parseInt(receipt.getOrDefault("stonePickaxeDamage", "-1"));
            boolean toolWearObserved = !toolVariant || "true".equals(receipt.get("pursuitToolVariant"))
                && "7".equals(activeInitialPursuitReceipt.get("stonePickaxeCurrentSlot"))
                && "7".equals(receipt.get("stonePickaxeCurrentSlot"))
                && "0".equals(activeInitialPursuitReceipt.get("stonePickaxeDamage"))
                && "0".equals(receipt.get("stonePickaxeInitialDamage"))
                && finalPickaxeDamage > initialPickaxeDamage
                && finalPickaxeDamage < Integer.parseInt(receipt.getOrDefault("stonePickaxeMaxDamage", "0"))
                && latestSnapshot.count("minecraft:stone_pickaxe") == 1;
            passed = observed == 1 && latestSnapshot.storageCount("minecraft:iron_ingot") == 0
                && latestSnapshot.count("minecraft:iron_ingot") == 0
                && latestSnapshot.health == 20.0F && latestSnapshot.foodLevel >= activeFoodLevelAtStart
                && latestSnapshot.difficulty.equals(Difficulty.NORMAL.name())
                && latestSnapshot.serverCursorEmpty && latestSnapshot.equippedItems.isEmpty()
                && noMaintenanceQueued && preparedMaintenanceQueueEmptyBeforeForeground
                && serverTableOpenings > activeTableOpeningsAtStart && foodReceived
                && sameCow && "false".equals(receipt.get("cowAlive")) && "0.0".equals(receipt.get("cowHealth"))
                && "10.0".equals(receipt.get("cowInitialHealth"))
                && (toolVariant ? healthDrops > 0 && healthDrops < 10 : healthDrops >= 5)
                && toolWearObserved
                && "true".equals(receipt.get("pursuitObservationStarted"))
                && Integer.parseInt(receipt.getOrDefault("pursuitObservedServerTicks", "0")) > 0
                && displacement >= 0.5 && maximumDistance < 32.0;
            detail = passed
                ? toolVariant
                    ? "the integrated server recorded " + healthDrops + " native cow health drops, stone-pickaxe wear from hotbar slot 7, and "
                        + displacement + " blocks of target movement, then received food and crafted the bucket from the three supplied iron ingots with an empty cursor and idle cancelled navigation"
                    : "the integrated server recorded " + healthDrops + " native cow health drops and "
                        + displacement + " blocks of target movement, then received food and crafted the bucket from the three supplied iron ingots with an empty cursor and idle cancelled navigation"
                : toolVariant
                    ? "tool pursuit lacked the same cow death, fewer than ten native health drops, stone-pickaxe wear from hotbar slot 7, target movement within 32 blocks, actual food, supplied-iron bucket, health, cursor, or idle receipts"
                    : "moving-food pursuit lacked the same cow death, five server health drops, target movement within 32 blocks, actual food, supplied-iron bucket, health, cursor, or idle receipts";
        } else if (preparedSafetyPhase == PreparedSafetyPhase.STATION_ROOM) {
            observed = latestSnapshot.count(activeItem);
            Map<String, String> receipt = latestSnapshot.preparedSafetyStationRoomReceipt;
            int changedStoneCells = Integer.parseInt(receipt.getOrDefault("roomStoneCellsChangedCount", "-1"));
            passed = observed == 1 && latestSnapshot.count(RAW_IRON_ID) == 0
                && latestSnapshot.storageCount(RAW_IRON_ID) == 0
                && latestSnapshot.health == 20.0F && latestSnapshot.foodLevel == 20
                && latestSnapshot.difficulty.equals(Difficulty.NORMAL.name())
                && latestSnapshot.serverCursorEmpty && latestSnapshot.equippedItems.isEmpty()
                && noMaintenanceQueued && preparedMaintenanceQueueEmptyBeforeForeground
                && serverFurnaceOpenings > activeFurnaceOpeningsAtStart
                && (STATION_ROOM_APPROACH_MODE ? "0" : STATION_ROOM_TUNNEL_MODE ? "66" : "73").equals(activeInitialStationRoomReceipt.get("roomStoneCellCandidateCount"))
                && (STATION_ROOM_APPROACH_MODE ? "0" : STATION_ROOM_TUNNEL_MODE ? "66" : "73").equals(activeInitialStationRoomReceipt.get("roomStoneCellsStillStone"))
                && "0".equals(activeInitialStationRoomReceipt.get("roomStoneCellsChangedCount"))
                && "0".equals(activeInitialStationRoomReceipt.get("nearbyFurnaceCount"))
                && "true".equals(activeInitialStationRoomReceipt.get("playerSupportBedrock"))
                && "true".equals(receipt.get("playerSupportBedrock"))
                && (STATION_ROOM_TUNNEL_MODE || STATION_ROOM_APPROACH_MODE || !stationRoomHistorySupported()
                    ? "true".equals(receipt.get("stationFloorBedrock")) : stationRoomHistoryCompleted(activeInitialStationRoomReceipt, receipt))
                && (STATION_ROOM_APPROACH_MODE ? "0" : STATION_ROOM_TUNNEL_MODE ? "66" : "73").equals(receipt.get("roomStoneCellCandidateCount"))
                && (STATION_ROOM_TUNNEL_MODE || STATION_ROOM_APPROACH_MODE || !stationRoomHistorySupported()
                    ? Integer.parseInt(receipt.getOrDefault("nearbyFurnaceCount", "0")) >= 1 : "0".equals(receipt.get("nearbyFurnaceCount")))
                && (STATION_ROOM_APPROACH_MODE ? "1.5,65,0.5" : STATION_ROOM_TUNNEL_MODE ? "0.367555,64,0.505802" : "0.5,64,0.5").equals(receipt.get("preparedRoomStartPosition"))
                && (STATION_ROOM_TUNNEL_MODE || STATION_ROOM_APPROACH_MODE ? changedStoneCells == 0 : changedStoneCells >= 1 && changedStoneCells <= 2)
                && (!STATION_ROOM_TUNNEL_MODE || (stationRoomTunnelSetupReady(activeInitialStationRoomReceipt)
                    && latestSnapshot.storageCount(IRON_INGOT_ID) == 1
                    && "true".equals(receipt.get("craftingTablePresent"))
                    && "true".equals(receipt.get("roomFloorBedrock"))
                    && "1".equals(receipt.get("nearbyFurnaceCount"))
                    && "0".equals(receipt.get("furnaceInputCount"))
                    && "0".equals(receipt.get("furnaceFuelCount"))
                    && "0".equals(receipt.get("furnaceOutputCount"))))
                && (!STATION_ROOM_APPROACH_MODE || (stationRoomApproachSetupReady(activeInitialStationRoomReceipt)
                    && stationRoomApproachGeometryPreserved(receipt)
                    && "0,64,1".equals(receipt.get("playerBlockPosition"))
                    && "7".equals(receipt.get("approachAirCellCount"))
                    && "1,65,0;1,66,0;1,64,1;1,65,1;1,66,1;0,64,1;0,65,1".equals(receipt.get("approachAirPositions"))
                    && "1".equals(receipt.get("nearbyFurnaceCount")) && "0,64,0".equals(receipt.get("nearbyFurnacePositions"))
                    && latestSnapshot.storageCount(IRON_INGOT_ID) == 1
                    && "0".equals(receipt.get("furnaceInputCount")) && "0".equals(receipt.get("furnaceFuelCount"))
                    && "0".equals(receipt.get("furnaceOutputCount"))));
            detail = passed
                ? "the integrated server consumed raw iron, opened the nearby placed furnace, and recorded " + changedStoneCells
                    + " changed native stone cell(s), with preserved support and "
                    + (STATION_ROOM_TUNNEL_MODE || STATION_ROOM_APPROACH_MODE || !stationRoomHistorySupported() ? "the furnace floor still bedrock"
                        : "ordered native furnace placement on bedrock, same-position removal and ordinary return")
                : "the prepared station-room goal did not produce the required ingot, raw-iron, furnace-menu, nearby placement, bounded stone-change, floor, health, cursor, and idle receipts";
        } else if (preparedSafetyPhase == PreparedSafetyPhase.AIR) {
            observed = latestSnapshot.count(activeItem);
            Map<String, String> receipt = latestSnapshot.preparedSafetyAirReceipt;
            int initialAir = Integer.parseInt(activeInitialAirReceipt.getOrDefault("airSupply", "-1"));
            int finalAir = Integer.parseInt(receipt.getOrDefault("airSupply", "-1"));
            int maximumAir = Integer.parseInt(receipt.getOrDefault("maxAirSupply", "-1"));
            int minimumAir = Integer.parseInt(receipt.getOrDefault("minimumAirSupply", "-1"));
            float minimumHealth = Float.parseFloat(receipt.getOrDefault("minimumHealth", "NaN"));
            int deaths = Integer.parseInt(receipt.getOrDefault("deathsObserved", "-1"));
            double distance = Double.parseDouble(receipt.getOrDefault("maximumDistanceFromStart", "NaN"));
            passed = observed == 1 && latestSnapshot.storageCount("minecraft:iron_ingot") == 0
                && latestSnapshot.count("minecraft:iron_ingot") == 0
                && latestSnapshot.health >= 6.0F && latestSnapshot.foodLevel >= activeFoodLevelAtStart
                && latestSnapshot.count("minecraft:cooked_beef") < activeInitialResources.getOrDefault("minecraft:cooked_beef", 0)
                && minimumHealth >= 3.0F && deaths == 0 && minimumAir > 0
                && latestSnapshot.serverCursorEmpty && latestSnapshot.equippedItems.isEmpty()
                && latestSnapshot.difficulty.equals(Difficulty.NORMAL.name())
                && noMaintenanceQueued && preparedMaintenanceQueueEmptyBeforeForeground
                && serverTableOpenings > activeTableOpeningsAtStart
                && initialAir >= 160 && initialAir <= 180
                && "true".equals(activeInitialAirReceipt.get("headInWater"))
                && preparedAirRecoveryObserved
                && preparedAirRecoveryCompletedBeforeCraft
                && preparedAirRecoveryObservedClientTick >= caseStartedAtTick
                && preparedAirRecoveryTransitionClientTick > preparedAirRecoveryObservedClientTick
                && preparedAirRecoveryCompletionClientTick >= preparedAirRecoveryTransitionClientTick
                && preparedAirRecoveryCompletionServerTick >= 0
                && preparedAirRecoveryTransitionObservationSequence >= 0
                && preparedAirRecoveryTableOpeningsAtTransition == activeTableOpeningsAtStart
                && preparedAirRecoveryBucketCountAtCompletion == 0
                && "false".equals(preparedAirRecoveryCompletionReceipt.get("headInWater"))
                && Integer.parseInt(preparedAirRecoveryCompletionReceipt.getOrDefault("airSupply", "-1"))
                    >= Integer.parseInt(preparedAirRecoveryCompletionReceipt.getOrDefault("maxAirSupply", "0")) * 9 / 10
                && "true".equals(receipt.get("headInWaterAtStart"))
                && "false".equals(receipt.get("headInWater"))
                && Integer.parseInt(receipt.getOrDefault("firstHeadOutOfWaterServerTick", "-1")) >= 0
                && finalAir >= maximumAir * 9 / 10
                && distance >= 0.5
                && "true".equals(receipt.get("waterSourceCellsPresent"))
                && "true".equals(receipt.get("lowWaterRoofPresent"))
                && "true".equals(receipt.get("waterBoundaryPresent"))
                && "true".equals(receipt.get("dryExitPresent"))
                && "true".equals(receipt.get("craftingTablePresent"))
                && Double.parseDouble(receipt.getOrDefault("exitDistance", "NaN")) <= 6.0;
            detail = passed
                ? "the integrated server observed submerged low-air recovery, native movement to breathable space and air refill, preserved the player's starting health of three without death, then crafted a bucket from the supplied iron at the dry ledge"
                : "air recovery lacked the required low-air submerged start, breathable exit, server movement, refill, preserved health, zero deaths, bucket, crafting-table use, empty cursor, or idle navigation receipt";
        } else if (preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_FOOD) {
            observed = latestSnapshot.count(activeItem);
            preparedMaintenanceReservationPresentAtCompletion = preparedMaintenanceReservationObservedBeforeForeground
                && maintainedReservationObserved("minecraft:cooked_beef", 1);
            passed = observed == activeCount && latestSnapshot.storageCount("minecraft:iron_ingot") == 0
                && latestSnapshot.storageCount("minecraft:cooked_beef") == 0
                && latestSnapshot.count("minecraft:cooked_beef") == 1
                && "minecraft:cooked_beef".equals(latestSnapshot.equippedItems.get("offhand"))
                && latestSnapshot.serverCursorEmpty && noMaintenanceQueued
                && preparedMaintenanceQueueEmptyBeforeForeground
                && preparedMaintenanceReservationPresentAtCompletion
                && latestSnapshot.foodLevel > activeFoodLevelAtStart
                && serverTableOpenings > activeTableOpeningsAtStart;
            detail = passed
                ? "maintained cooked beef remained satisfied by the offhand without queued maintenance work before or after the foreground goal while stored beef was eaten and three stored iron ingots were crafted into a bucket"
                : "offhand maintenance did not preserve the offhand item while allowing stored food and bucket ingredients to be used without queued maintenance work";
        } else {
            observed = latestSnapshot.count(activeItem);
            int remainingLogs = latestSnapshot.storageCount("minecraft:oak_log");
            preparedMaintenanceReservationPresentAtCompletion = preparedMaintenanceReservationObservedBeforeForeground
                && maintainedReservationObserved("minecraft:oak_log", 8);
            passed = observed >= activeCount && remainingLogs < activeInitialResources.getOrDefault("minecraft:oak_log", 0)
                && remainingLogs <= 1
                && latestSnapshot.equippedItems.equals(Map.of("offhand", "minecraft:oak_log"))
                && latestSnapshot.count("minecraft:oak_log") - remainingLogs == 8
                && latestSnapshot.serverCursorEmpty && noMaintenanceQueued
                && preparedMaintenanceQueueEmptyBeforeForeground
                && preparedMaintenanceReservationPresentAtCompletion;
            detail = passed
                ? "an eight-log offhand maintenance goal protected the held logs while the planner used stored oak logs to craft sticks with block breaking disabled and no maintenance work queued"
                : "stored oak logs were not used for the stick goal while the offhand-maintained oak-log target remained satisfied";
        }
        if (!passed && clientTicks - caseStartedAtTick <= PREPARED_SAFETY_CASE_TIMEOUT_TICKS) return;
        if (!passed) {
            fail(detail + "; timed out after " + PREPARED_SAFETY_CASE_TIMEOUT_TICKS
                + " ticks waiting for an authoritative server receipt");
            return;
        }

        addResult(true, observed, detail, capture(activeCase));
        if (preparedSafetyPhase == PreparedSafetyPhase.OFFHAND_FOOD) {
            beginPreparedSafetyIngredientsSetup();
            return;
        }
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private void beginPreparedSafetyIngredientsSetup() {
        requireEngine().unmaintain("all");
        preparedSafetyPhase = PreparedSafetyPhase.OFFHAND_INGREDIENTS;
        state = State.SETTING_UP;
        readyTicks = 0;
        preparedSafetySetupStartedAtTick = clientTicks;
        IntegratedServer server = requireServer();
        setupFuture = new CompletableFuture<>();
        CompletableFuture<Long> scheduled = setupFuture;
        server.execute(() -> {
            try {
                ServerPlayerEntity player = requireServerPlayer(server);
                server.setDifficulty(Difficulty.NORMAL, true);
                clearInventory(player.getInventory());
                VerificationApi.seedPreparedSafetyIngredients(player);
                player.currentScreenHandler.sendContentUpdates();
                scheduled.complete((long) server.getTicks());
            } catch (Throwable throwable) {
                scheduled.completeExceptionally(throwable);
            }
        });
    }

    private void startPreparedSafetyIngredientsCase() {
        activeCase = "prepared_offhand_ingredients_sticks";
        activeItem = "minecraft:stick";
        activeCount = 4;
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.storageInventory);
        activeInitialEquipment = Map.copyOf(latestSnapshot.equippedItems);
        activeInitialCursorEmpty = latestSnapshot.serverCursorEmpty;
        preparedSafetyForegroundStarted = false;
        preparedMaintenanceQueueEmptyBeforeForeground = false;
        preparedMaintenanceReservationObservedBeforeForeground = false;
        preparedMaintenanceReservationPresentAtCompletion = false;
        beginCaseClock();
        state = State.PREPARED_SAFETY;
        sendCommand("!lk maintain oak_log 8");
    }

    private void startAdditionalCase(State next, String name, String item) {
        activeCase = name; activeItem = item; activeCount = 1;
        activeRequiresEmpty = false; activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock(); sendCommand("!lk get " + item + " 1"); state = next;
    }

    private void startCustomContentCase() {
        activeCase = "custom_ruby_gear";
        activeItem = VerificationContentInitializer.RUBY_GEAR_ID;
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get " + activeItem);
        state = State.CUSTOM_CONTENT;
    }

    private void beginFoodFixtureSetup() {
        activeCase = "auto_eat_during_gather";
        activeItem = "minecraft:oak_log";
        activeCount = latestSnapshot.count(activeItem) + 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        foodBreadCountBeforeSetup = latestSnapshot.count(VerificationContentInitializer.BREAD_ID);
        beginCaseClock();

        state = State.SETTING_UP_FOOD;
        IntegratedServer server = requireServer();
        setupFuture = new CompletableFuture<>();
        CompletableFuture<Long> scheduled = setupFuture;
        server.execute(() -> {
            try {
                ServerPlayerEntity player = requireServerPlayer(server);
                ServerWorld world = server.getOverworld();
                server.setDifficulty(Difficulty.NORMAL, true);
                player.getHungerManager().setFoodLevel(7);
                player.getHungerManager().setSaturationLevel(0.0F);
                if (!player.getInventory().insertStack(new ItemStack(Items.BREAD))) {
                    throw new IllegalStateException("could not add the single verifier bread to the inventory");
                }
                BlockPos extraLog = new BlockPos(6, PLAYER_Y, 0);
                if (!world.getBlockState(extraLog).isReplaceable()) {
                    throw new IllegalStateException("the extra oak-log fixture position is occupied");
                }
                world.setBlockState(extraLog, Blocks.OAK_LOG.getDefaultState(), 3);
                player.currentScreenHandler.sendContentUpdates();
                scheduled.complete((long) server.getTicks());
            } catch (Throwable throwable) {
                scheduled.completeExceptionally(throwable);
            }
        });
    }

    private void startFoodGatherCommand() {
        activeFoodLevelAtStart = latestSnapshot.foodLevel;
        activeBreadCountAtStart = latestSnapshot.count(VerificationContentInitializer.BREAD_ID);
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get wood " + activeCount);
        state = State.GATHERING_FOOD;
    }

    private void beginCaseClock() {
        caseStartedAtTick = clientTicks;
        caseStartedAtNanos = System.nanoTime();
        firstMovementMillis = -1;
        movementClock = latestSnapshot == null ? null : new MovementClock(
            caseStartedAtNanos, latestSnapshot.x, latestSnapshot.z);
        caseStartedAtWorldTime = client.world == null ? 0 : client.world.getTime();
        activeFoodLevelAtStart = latestSnapshot == null ? 0 : latestSnapshot.foodLevel;
        activeBreadCountAtStart = latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID);
        activeTableOpeningsAtStart = serverTableOpenings;
        activeFurnaceOpeningsAtStart = serverFurnaceOpenings;
        activeSmokerOpeningsAtStart = serverSmokerOpenings;
        activeBlastFurnaceOpeningsAtStart = serverBlastFurnaceOpenings;
        activeStonecutterOpeningsAtStart = serverStonecutterOpenings;
    }

    private boolean correctCookingStationMenuOpened() {
        if (STONECUTTING_MODE) return serverStonecutterOpenings > activeStonecutterOpeningsAtStart;
        return "smoker".equals(COOKING_STATION_MODE)
            ? serverSmokerOpened && serverSmokerOpenings > activeSmokerOpeningsAtStart
            : serverBlastFurnaceOpened && serverBlastFurnaceOpenings > activeBlastFurnaceOpeningsAtStart;
    }

    private boolean stonecuttingDrainOutcomeObserved() {
        return stonecuttingDrainStopInjected && stonecuttingDrainInputCountAtStop > 0
            && stonecuttingDrainStopClientTick >= caseStartedAtTick
            && stonecuttingDrainRequiredObservationSequence > 0
            && latestObservationRequestSequence >= stonecuttingDrainRequiredObservationSequence
            && latestSnapshot.serverTick > stonecuttingDrainStopServerTick
            && latestSnapshot.inventory.equals(Map.of("minecraft:stone", 128))
            && latestSnapshot.count("minecraft:stone_slab") == 0
            && latestSnapshot.count("minecraft:coal") == 0
            && latestSnapshot.count("minecraft:stonecutter") == 0;
    }

    private void tickWorldPolicyFixture() throws IOException {
        if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
        if (latestSnapshot == null || latestSnapshot.serverTick < fixtureReadyServerTick) return;
        if (!latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:furnace", 1))
                || latestSnapshot.health != 20.0F || !latestSnapshot.serverCursorEmpty
                || !"minecraft:torch".equals(latestSnapshot.worldPolicyServerReceipt.get("torch"))
                || !"minecraft:stone".equals(latestSnapshot.worldPolicyServerReceipt.get("support"))
                || !"minecraft:crafting_table".equals(latestSnapshot.worldPolicyServerReceipt.get("preferred_table"))
                || !"minecraft:crafting_table".equals(latestSnapshot.worldPolicyServerReceipt.get("ordinary_table"))
                || !"2".equals(latestSnapshot.worldPolicyServerReceipt.get("crafting_table_count"))) {
            fail("world-policy fixture did not match its server inventory, torch-support, and station setup receipt");
            return;
        }
        backupWorldPolicyClaims();
        worldPolicyEvidence = new JsonObject();
        worldPolicyEvidence.addProperty("mode", "claims_preferred_stations_and_backfill");
        worldPolicyEvidence.addProperty("evidenceAuthority", "integrated_server_inventory_and_block_states");
        worldPolicyEvidence.addProperty("claimAddCommand", "!lk claim add native_world_policy preferred");
        worldPolicyEvidence.addProperty("breakCommand", "!lk get cobblestone 7");
        worldPolicyEvidence.addProperty("preferredTableCommand", "!lk get iron_pickaxe 1");
        worldPolicyEvidence.addProperty("preferredStationCommand", "!lk get iron_ingot 1");
        worldPolicyEvidence.addProperty("backfillBreakCommand", "!lk get cobblestone 1");
        worldPolicyEvidence.addProperty("backfillMaintainedFloorCommand", "!lk maintain cobblestone 1");
        worldPolicyEvidence.addProperty("manualInputInjectedByVerifier", false);
        worldPolicyEvidence.addProperty("normalConfigPersistedByVerifier", false);
        JsonObject fixture = new JsonObject();
        fixture.add("claimMin", blockPositionJson(WORLD_POLICY_CLAIM_MIN));
        fixture.add("claimMax", blockPositionJson(WORLD_POLICY_CLAIM_MAX));
        fixture.add("protectedFaces", new JsonArray());
        for (WorldPolicyFace face : WORLD_POLICY_FACES) {
            JsonObject item = new JsonObject();
            item.addProperty("face", face.name());
            item.add("position", blockPositionJson(face.position()));
            fixture.getAsJsonArray("protectedFaces").add(item);
        }
        fixture.add("torch", blockPositionJson(WORLD_POLICY_TORCH));
        fixture.add("unclaimedSupport", blockPositionJson(WORLD_POLICY_SUPPORT));
        fixture.add("preferredFurnace", blockPositionJson(WORLD_POLICY_PREFERRED_FURNACE));
        fixture.add("ordinaryFurnace", blockPositionJson(WORLD_POLICY_ORDINARY_FURNACE));
        fixture.add("preferredCraftingTable", blockPositionJson(WORLD_POLICY_PREFERRED_TABLE));
        fixture.add("ordinaryCraftingTable", blockPositionJson(WORLD_POLICY_ORDINARY_TABLE));
        fixture.add("backfillTarget", blockPositionJson(WORLD_POLICY_BACKFILL_TARGET));
        worldPolicyEvidence.add("fixture", fixture);
        AutomationEngine engine = requireEngine();
        engine.protection.setCorner(true, WORLD_POLICY_CLAIM_MIN.getX(), WORLD_POLICY_CLAIM_MIN.getY(), WORLD_POLICY_CLAIM_MIN.getZ());
        engine.protection.setCorner(false, WORLD_POLICY_CLAIM_MAX.getX(), WORLD_POLICY_CLAIM_MAX.getY(), WORLD_POLICY_CLAIM_MAX.getZ());
        sendCommand("!lk claim add native_world_policy preferred");
        worldPolicyPhase = WorldPolicyPhase.ADD_CLAIM;
        worldPolicyPhaseStartedAtTick = clientTicks;
        state = State.WORLD_POLICY;
    }

    private JsonObject verifyWorldPolicyPlacementBoundary() {
        if (latestSnapshot == null || client.player == null || client.currentScreen != null) {
            throw new IllegalStateException("placement boundary probe requires a live player and no screen");
        }
        OwnedKernelRuntime owner = OwnedKernelRuntime.current();
        if (owner == null || !owner.isCurrent(owner.captureSession()) || owner.captureSession().world() != client.world) {
            throw new IllegalStateException("placement boundary probe has no current owned world session");
        }
        PlayerInventory inventory = client.player.getInventory();
        int originalSlot = ClientAccess.selectedSlot(inventory);
        int furnaceSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getStack(slot).isOf(Items.FURNACE)) {
                furnaceSlot = slot;
                break;
            }
        }
        if (furnaceSlot < 0) throw new IllegalStateException("the placement probe has no supplied block item in the hotbar");
        try {
            ClientAccess.selectedSlot(inventory, furnaceSlot);
            if (!client.player.getMainHandStack().isOf(Items.FURNACE)) {
                throw new IllegalStateException("the server-supplied furnace stack was not selected for the placement probe");
            }
            JsonObject evidence = new JsonObject();
            evidence.addProperty("probeKind", "OwnedMutationGuard.executePlace_direct_boundary_probe");
            evidence.addProperty("userFacingBuildCommand", false);
            evidence.addProperty("serverMutationAttemptedByProbe", false);
            evidence.addProperty("heldItem", "minecraft:furnace");
            evidence.addProperty("serverInventoryAtProbe", latestSnapshot.worldPolicyServerReceipt.get("inventory"));
            JsonArray probes = new JsonArray();
            for (WorldPolicyPlacementProbe probe : WORLD_POLICY_PLACEMENT_PROBES) {
                boolean permitted = executeWorldPolicyPlacementProbe(owner, probe);
                probes.add(worldPolicyPlacementProbeJson(probe, permitted, false));
                if (permitted) throw new IllegalStateException("claim placement boundary permitted target " + probe.name());
            }
            boolean outsidePermitted = executeWorldPolicyPlacementProbe(owner, WORLD_POLICY_OUTSIDE_PLACEMENT_PROBE);
            probes.add(worldPolicyPlacementProbeJson(WORLD_POLICY_OUTSIDE_PLACEMENT_PROBE, outsidePermitted, true));
            if (!outsidePermitted) throw new IllegalStateException("unclaimed outside control was denied by the placement boundary");
            evidence.add("probes", probes);
            worldPolicyPlacementBaseline = worldPolicyPlacementServerStates(latestSnapshot.worldPolicyServerReceipt);
            evidence.addProperty("serverTargetAndSupportStatesCaptured",
                worldPolicyPlacementBaseline.size() == WORLD_POLICY_PLACEMENT_PROBES.size() * 2 + 3);
            if (worldPolicyPlacementBaseline.size() != WORLD_POLICY_PLACEMENT_PROBES.size() * 2 + 3) {
                throw new IllegalStateException("integrated-server placement state receipt was incomplete");
            }
            return evidence;
        } finally {
            ClientAccess.selectedSlot(inventory, originalSlot);
        }
    }

    private static boolean executeWorldPolicyPlacementProbe(OwnedKernelRuntime owner, WorldPolicyPlacementProbe probe) {
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(probe.clicked()), probe.face(), probe.clicked(), false);
        return OwnedMutationGuard.executePlace(owner, hit, Hand.MAIN_HAND);
    }

    private JsonObject worldPolicyPlacementProbeJson(WorldPolicyPlacementProbe probe, boolean permitted, boolean expectedPermitted) {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("name", probe.name());
        evidence.add("clickedBlock", blockPositionJson(probe.clicked()));
        evidence.addProperty("face", probe.face().name().toLowerCase(Locale.ROOT));
        evidence.add("intendedTarget", blockPositionJson(probe.target()));
        evidence.addProperty("expectedPermitted", expectedPermitted);
        evidence.addProperty("actualPermitted", permitted);
        evidence.addProperty("serverClickedBlockBefore", latestSnapshot.worldPolicyServerReceipt.get("placement_clicked_" + probe.name()));
        evidence.addProperty("serverTargetBlockBefore", latestSnapshot.worldPolicyServerReceipt.get("placement_target_" + probe.name()));
        return evidence;
    }

    private static Map<String, String> worldPolicyPlacementServerStates(Map<String, String> receipt) {
        Map<String, String> states = new LinkedHashMap<>();
        for (WorldPolicyPlacementProbe probe : WORLD_POLICY_PLACEMENT_PROBES) {
            String clicked = receipt.get("placement_clicked_" + probe.name());
            String target = receipt.get("placement_target_" + probe.name());
            if (clicked != null) states.put("clicked_" + probe.name(), clicked);
            if (target != null) states.put("target_" + probe.name(), target);
        }
        String outsideClick = receipt.get("placement_clicked_outside_control");
        String outsideTarget = receipt.get("placement_target_outside_control");
        if (outsideClick != null) states.put("clicked_outside_control", outsideClick);
        if (outsideTarget != null) states.put("target_outside_control", outsideTarget);
        if (receipt.get("inventory") != null) states.put("inventory", receipt.get("inventory"));
        return Map.copyOf(states);
    }

    private boolean worldPolicyPlacementServerStatesUnchanged(Map<String, String> receipt) {
        return !worldPolicyPlacementBaseline.isEmpty()
            && worldPolicyPlacementBaseline.equals(worldPolicyPlacementServerStates(receipt));
    }

    private void tickWorldPolicyScenario() throws IOException {
        recordWorldPolicySnapshot(latestSnapshot);
        switch (worldPolicyPhase) {
            case NONE -> throw new IllegalStateException("world-policy phase was not initialized");
            case ADD_CLAIM -> {
                WorldProtection.PolicySnapshot policy = requireEngine().protection.capture();
                var claim = policy.scope() == null ? null : policy.claims().forScope(policy.scope()).stream()
                        .filter(candidate -> candidate.name().equals("native_world_policy"))
                        .findFirst().orElse(null);
                if (claim == null) {
                    if (clientTicks - worldPolicyPhaseStartedAtTick > 100) {
                        throw new IllegalStateException("ordinary claim command did not persist the fixture claim");
                    }
                    return;
                }
                boolean exact = claim.minX() == WORLD_POLICY_CLAIM_MIN.getX()
                    && claim.minY() == WORLD_POLICY_CLAIM_MIN.getY() && claim.minZ() == WORLD_POLICY_CLAIM_MIN.getZ()
                    && claim.maxX() == WORLD_POLICY_CLAIM_MAX.getX() && claim.maxY() == WORLD_POLICY_CLAIM_MAX.getY()
                    && claim.maxZ() == WORLD_POLICY_CLAIM_MAX.getZ() && claim.preferredStations();
                if (!exact || policy.scope() == null) throw new IllegalStateException("saved claim bounds or preferred-station flag did not match the fixture");
                JsonObject savedClaim = new JsonObject();
                savedClaim.addProperty("id", claim.id());
                savedClaim.addProperty("name", claim.name());
                savedClaim.addProperty("minX", claim.minX());
                savedClaim.addProperty("minY", claim.minY());
                savedClaim.addProperty("minZ", claim.minZ());
                savedClaim.addProperty("maxX", claim.maxX());
                savedClaim.addProperty("maxY", claim.maxY());
                savedClaim.addProperty("maxZ", claim.maxZ());
                savedClaim.addProperty("preferredStations", claim.preferredStations());
                savedClaim.addProperty("worldId", policy.scope().worldId());
                savedClaim.addProperty("dimension", policy.scope().dimension());
                worldPolicyEvidence.add("savedClaim", savedClaim);
                worldPolicyEvidence.add("placementBoundaryProbe", verifyWorldPolicyPlacementBoundary());
                activeCase = "native_claim_boundary_and_torch_support";
                activeItem = "minecraft:cobblestone";
                activeCount = 7;
                activeRequiresEmpty = false;
                activeStartedEmpty = false;
                activeInitialResources = Map.copyOf(latestSnapshot.inventory);
                beginCaseClock();
                sendCommand("!lk get cobblestone 7");
                worldPolicyPhase = WorldPolicyPhase.CLAIM_BREAK;
                worldPolicyPhaseStartedAtTick = clientTicks;
                worldPolicyEvidence.addProperty("claimBreakSubmitted", true);
            }
            case CLAIM_BREAK -> {
                if (clientTicks - worldPolicyPhaseStartedAtTick < 120) return;
                JsonObject claimReceipt = worldPolicyReceipt("claimBreakFinalReceipt", latestSnapshot);
                boolean blocksIntact = worldPolicyBlocksIntact(latestSnapshot.worldPolicyServerReceipt);
                boolean placementStatesUnchanged = worldPolicyPlacementServerStatesUnchanged(latestSnapshot.worldPolicyServerReceipt);
                boolean inventoryConserved = latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:furnace", 1));
                boolean remainedOutside = !worldPolicyPositionInside(latestSnapshot);
                claimReceipt.addProperty("allSixFaceBlocksRemainStone", blocksIntact);
                claimReceipt.addProperty("placementProbeBlocksAndInventoryUnchanged", placementStatesUnchanged);
                claimReceipt.addProperty("torchRemainsAboveUnclaimedSupport", "minecraft:torch".equals(latestSnapshot.worldPolicyServerReceipt.get("torch"))
                    && "minecraft:stone".equals(latestSnapshot.worldPolicyServerReceipt.get("support")));
                claimReceipt.addProperty("inventoryConserved", inventoryConserved);
                claimReceipt.addProperty("playerRemainedOutsideClaim", remainedOutside);
                claimReceipt.addProperty("serverCursorEmpty", latestSnapshot.serverCursorEmpty);
                claimReceipt.addProperty("playerAlive", latestSnapshot.health > 0.0F);
                claimReceipt.addProperty("manualInputInterventionDetected", client.currentScreen != null);
                worldPolicyEvidence.add("claimBreak", claimReceipt);
                if (!blocksIntact || !placementStatesUnchanged || !inventoryConserved || !remainedOutside || !latestSnapshot.serverCursorEmpty
                        || latestSnapshot.health <= 0.0F || client.currentScreen != null) {
                    throw new IllegalStateException("claim break request changed a protected face/support fixture, inventory, or player boundary state");
                }
                sendCommand("!lk stop");
                worldPolicyPhase = WorldPolicyPhase.STOP_CLAIM_BREAK;
                worldPolicyPhaseStartedAtTick = clientTicks;
            }
            case STOP_CLAIM_BREAK -> {
                if (requireEngine().diagnosticTaskIdentity() != null) {
                    if (clientTicks - worldPolicyPhaseStartedAtTick > 100) throw new IllegalStateException("claim-break request did not stop cleanly");
                    return;
                }
                worldPolicyPhase = WorldPolicyPhase.PREPARE_TABLE;
                worldPolicyPhaseStartedAtTick = clientTicks;
            }
            case PREPARE_TABLE -> {
                if (!worldPolicyTableSetupComplete) {
                    if (setupFuture == null) {
                        IntegratedServer server = requireServer();
                        setupFuture = new CompletableFuture<>();
                        CompletableFuture<Long> scheduled = setupFuture;
                        server.execute(() -> {
                            try {
                                ServerPlayerEntity player = requireServerPlayer(server);
                                clearInventory(player.getInventory());
                                if (!player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                                        || !player.getInventory().insertStack(new ItemStack(Items.STICK, 2))) {
                                    throw new IllegalStateException("could not seed the preferred-table request stock");
                                }
                                player.currentScreenHandler.sendContentUpdates();
                                scheduled.complete((long) server.getTicks());
                            } catch (Throwable throwable) {
                                scheduled.completeExceptionally(throwable);
                            }
                        });
                        return;
                    }
                    if (!setupFuture.isDone()) return;
                    fixtureReadyServerTick = setupFuture.join();
                    setupFuture = null;
                    worldPolicyTableSetupComplete = true;
                }
                if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                if (latestSnapshot == null || latestSnapshot.serverTick < fixtureReadyServerTick
                        || !latestSnapshot.inventory.equals(Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2))) return;
                worldPolicyTableOpeningsAtStart = serverTableOpenings;
                serverTableOpenX = Double.NaN;
                serverTableOpenZ = Double.NaN;
                activeCase = "native_preferred_claim_crafting_table_reuse";
                activeItem = "minecraft:iron_pickaxe";
                activeCount = 1;
                activeRequiresEmpty = false;
                activeStartedEmpty = false;
                activeInitialResources = Map.copyOf(latestSnapshot.inventory);
                beginCaseClock();
                sendCommand("!lk get iron_pickaxe 1");
                worldPolicyPhase = WorldPolicyPhase.TABLE_REQUEST;
                worldPolicyPhaseStartedAtTick = clientTicks;
                worldPolicyEvidence.add("tableStartReceipt", worldPolicyReceipt("tableStartReceipt", latestSnapshot));
            }
            case TABLE_REQUEST -> {
                if (clientTicks - worldPolicyPhaseStartedAtTick > 1_200) throw new IllegalStateException("preferred-table request timed out");
                if (latestSnapshot == null || latestSnapshot.serverTick < fixtureReadyServerTick
                        || latestSnapshot.count("minecraft:iron_pickaxe") < 1) return;
                JsonObject receipt = worldPolicyReceipt("tableFinalReceipt", latestSnapshot);
                double preferredDistance = Math.hypot(serverTableOpenX - (WORLD_POLICY_PREFERRED_TABLE.getX() + 0.5),
                    serverTableOpenZ - (WORLD_POLICY_PREFERRED_TABLE.getZ() + 0.5));
                double ordinaryDistance = Math.hypot(serverTableOpenX - (WORLD_POLICY_ORDINARY_TABLE.getX() + 0.5),
                    serverTableOpenZ - (WORLD_POLICY_ORDINARY_TABLE.getZ() + 0.5));
                boolean openedNearPreferred = serverTableOpenings > worldPolicyTableOpeningsAtStart
                    && Double.isFinite(preferredDistance) && preferredDistance <= 4.5 && ordinaryDistance > 4.5;
                boolean noDuplicate = "2".equals(latestSnapshot.worldPolicyServerReceipt.get("crafting_table_count"))
                    && "minecraft:crafting_table".equals(latestSnapshot.worldPolicyServerReceipt.get("preferred_table"))
                    && "minecraft:crafting_table".equals(latestSnapshot.worldPolicyServerReceipt.get("ordinary_table"))
                    && latestSnapshot.count("minecraft:crafting_table") == 0;
                boolean finalInventoryMatches = latestSnapshot.inventory.equals(Map.of("minecraft:iron_pickaxe", 1));
                receipt.addProperty("serverCraftingTableOpened", serverTableOpenings > worldPolicyTableOpeningsAtStart);
                receipt.addProperty("serverPlayerOpenedNearPreferredTable", openedNearPreferred);
                receipt.addProperty("serverTableOpenX", serverTableOpenX);
                receipt.addProperty("serverTableOpenZ", serverTableOpenZ);
                receipt.addProperty("distanceToPreferredTable", preferredDistance);
                receipt.addProperty("distanceToOrdinaryTable", ordinaryDistance);
                receipt.addProperty("noDuplicateTablePlaced", noDuplicate);
                receipt.addProperty("expectedFinalInventory", finalInventoryMatches);
                worldPolicyEvidence.add("preferredTable", receipt);
                if (!openedNearPreferred || !noDuplicate || !finalInventoryMatches || !latestSnapshot.serverCursorEmpty
                        || latestSnapshot.health <= 0.0F || client.currentScreen != null) {
                    throw new IllegalStateException("preferred crafting-table reuse or server inventory receipt did not match the fixture");
                }
                worldPolicyPhase = WorldPolicyPhase.PREPARE_STATION;
                worldPolicyPhaseStartedAtTick = clientTicks;
            }
            case PREPARE_STATION -> {
                if (!worldPolicyStationSetupComplete) {
                    if (setupFuture == null) {
                        IntegratedServer server = requireServer();
                        setupFuture = new CompletableFuture<>();
                        CompletableFuture<Long> scheduled = setupFuture;
                        server.execute(() -> {
                            try {
                                ServerPlayerEntity player = requireServerPlayer(server);
                                clearInventory(player.getInventory());
                                if (!player.getInventory().insertStack(new ItemStack(Items.RAW_IRON, 1))
                                        || !player.getInventory().insertStack(new ItemStack(Items.COAL, 1))
                                        || !player.getInventory().insertStack(new ItemStack(Items.FURNACE, 1))) {
                                    throw new IllegalStateException("could not seed the preferred-furnace request stock");
                                }
                                player.currentScreenHandler.sendContentUpdates();
                                scheduled.complete((long) server.getTicks());
                            } catch (Throwable throwable) {
                                scheduled.completeExceptionally(throwable);
                            }
                        });
                        return;
                    }
                    if (!setupFuture.isDone()) return;
                    fixtureReadyServerTick = setupFuture.join();
                    setupFuture = null;
                    worldPolicyStationSetupComplete = true;
                }
                if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                if (latestSnapshot == null || latestSnapshot.serverTick < fixtureReadyServerTick
                        || !latestSnapshot.inventory.equals(Map.of("minecraft:raw_iron", 1, "minecraft:coal", 1, "minecraft:furnace", 1))) return;
                worldPolicyFurnaceOpeningsAtStart = serverFurnaceOpenings;
                activeCase = "native_preferred_claim_furnace_reuse";
                activeItem = IRON_INGOT_ID;
                activeCount = 1;
                activeRequiresEmpty = false;
                activeStartedEmpty = false;
                activeInitialResources = Map.copyOf(latestSnapshot.inventory);
                beginCaseClock();
                sendCommand("!lk get iron_ingot 1");
                worldPolicyPhase = WorldPolicyPhase.STATION_REQUEST;
                worldPolicyPhaseStartedAtTick = clientTicks;
                worldPolicyEvidence.add("stationStartReceipt", worldPolicyReceipt("stationStartReceipt", latestSnapshot));
            }
            case STATION_REQUEST -> {
                if (clientTicks - worldPolicyPhaseStartedAtTick > 600) throw new IllegalStateException("preferred-furnace request timed out");
                if (latestSnapshot == null || latestSnapshot.serverTick < fixtureReadyServerTick
                        || latestSnapshot.count(IRON_INGOT_ID) < 1) return;
                JsonObject stationReceipt = worldPolicyReceipt("stationFinalReceipt", latestSnapshot);
                boolean preferredUsed = "true".equals(latestSnapshot.worldPolicyServerReceipt.get("preferred_furnace_lit"))
                        && "false".equals(latestSnapshot.worldPolicyServerReceipt.get("ordinary_furnace_lit"))
                        && "minecraft:furnace".equals(latestSnapshot.worldPolicyServerReceipt.get("preferred_furnace"))
                        && "minecraft:furnace".equals(latestSnapshot.worldPolicyServerReceipt.get("ordinary_furnace"));
                boolean noDuplicate = "2".equals(latestSnapshot.worldPolicyServerReceipt.get("furnace_count"))
                        && latestSnapshot.count("minecraft:furnace") == 1;
                boolean finalInventoryMatches = latestSnapshot.inventory.equals(Map.of("minecraft:iron_ingot", 1, "minecraft:furnace", 1));
                boolean opened = serverFurnaceOpenings > worldPolicyFurnaceOpeningsAtStart;
                stationReceipt.addProperty("serverFurnaceOpened", opened);
                stationReceipt.addProperty("preferredFurnaceWasUsed", preferredUsed);
                stationReceipt.addProperty("noDuplicateFurnacePlaced", noDuplicate);
                stationReceipt.addProperty("expectedFinalInventory", finalInventoryMatches);
                worldPolicyEvidence.add("preferredStation", stationReceipt);
                if (!preferredUsed || !noDuplicate || !opened || latestSnapshot.count(IRON_INGOT_ID) != 1
                        || !finalInventoryMatches || !latestSnapshot.serverCursorEmpty || latestSnapshot.health <= 0.0F) {
                    throw new IllegalStateException("preferred furnace reuse or server inventory receipt did not match the fixture");
                }
                worldPolicyPhase = WorldPolicyPhase.PREPARE_BACKFILL;
                worldPolicyPhaseStartedAtTick = clientTicks;
            }
            case PREPARE_BACKFILL -> tickWorldPolicyBackfillSetup();
            case BACKFILL_BREAK -> tickWorldPolicyBackfillBreak();
            case BACKFILL_SURPLUS -> tickWorldPolicyBackfillSurplus();
            case COMPLETE -> { }
        }
    }

    private void tickWorldPolicyBackfillSetup() {
        AutomationEngine engine = requireEngine();
        engine.config.backfill = true;
        engine.config.backfillEquivalentStone = true;
        if (!worldPolicyBackfillSetupComplete) {
            if (setupFuture == null) {
                IntegratedServer server = requireServer();
                setupFuture = new CompletableFuture<>();
                CompletableFuture<Long> scheduled = setupFuture;
                server.execute(() -> {
                    try {
                        ServerPlayerEntity player = requireServerPlayer(server);
                        clearInventory(player.getInventory());
                        if (!player.getInventory().insertStack(new ItemStack(Items.STONE_PICKAXE))) {
                            throw new IllegalStateException("could not seed the backfill verifier pickaxe");
                        }
                        ServerWorld world = server.getOverworld();
                        if (!world.getBlockState(WORLD_POLICY_BACKFILL_TARGET).isAir()) {
                            throw new IllegalStateException("backfill target is occupied before setup");
                        }
                        world.setBlockState(WORLD_POLICY_BACKFILL_TARGET, Blocks.COBBLESTONE.getDefaultState(), 3);
                        player.currentScreenHandler.sendContentUpdates();
                        scheduled.complete((long) server.getTicks());
                    } catch (Throwable throwable) {
                        scheduled.completeExceptionally(throwable);
                    }
                });
                return;
            }
            if (!setupFuture.isDone()) return;
            fixtureReadyServerTick = setupFuture.join();
            setupFuture = null;
            worldPolicyBackfillSetupComplete = true;
        }
        if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
        if (latestSnapshot == null || latestSnapshot.serverTick < fixtureReadyServerTick
                || !latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1))
                || !"minecraft:cobblestone".equals(latestSnapshot.worldPolicyServerReceipt.get("backfill_target"))) return;
        activeCase = "native_backfill_requested_and_maintained_stock_conservation";
        activeItem = "minecraft:cobblestone";
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        beginCaseClock();
        sendCommand("!lk get cobblestone 1");
        worldPolicyPhase = WorldPolicyPhase.BACKFILL_BREAK;
        worldPolicyPhaseStartedAtTick = clientTicks;
        worldPolicyEvidence.add("backfillBreakStartReceipt", worldPolicyReceipt("backfillBreakStartReceipt", latestSnapshot));
    }

    private void tickWorldPolicyBackfillBreak() {
        if (clientTicks - worldPolicyPhaseStartedAtTick > 1_200) throw new IllegalStateException("backfill source-break request timed out");
        if (worldPolicyNoSurplusObservedAtTick < 0) {
            if (latestSnapshot == null || latestSnapshot.count("minecraft:cobblestone") != 1
                    || !"minecraft:air".equals(latestSnapshot.worldPolicyServerReceipt.get("backfill_target"))
                    || requireEngine().diagnosticTaskIdentity() != null) return;
            worldPolicyNoSurplusObservedAtTick = clientTicks;
            worldPolicyEvidence.add("backfillNoSurplusStartReceipt", worldPolicyReceipt("backfillNoSurplusStartReceipt", latestSnapshot));
            return;
        }
        if (clientTicks - worldPolicyNoSurplusObservedAtTick < 120) return;
        boolean targetRemainedAir = latestSnapshot != null
            && "minecraft:air".equals(latestSnapshot.worldPolicyServerReceipt.get("backfill_target"));
        boolean inventoryConserved = latestSnapshot != null
            && latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 1));
        boolean requestedFloorHeld = requestedBackfillFloorObserved("minecraft:cobblestone", 1);
        JsonObject noSurplus = worldPolicyReceipt("backfillNoSurplusFinalReceipt", latestSnapshot);
        noSurplus.addProperty("noSurplusStoneAvailable", latestSnapshot != null && latestSnapshot.count("minecraft:stone") == 0);
        noSurplus.addProperty("requestedCobblestoneGoalCount", requestedBackfillGoalCount("minecraft:cobblestone"));
        noSurplus.addProperty("effectiveRequestedCobblestoneFloor", requestedBackfillFloorCount("minecraft:cobblestone"));
        noSurplus.addProperty("requestedCobblestoneFloorRetained", requestedFloorHeld);
        noSurplus.addProperty("targetRemainedAir", targetRemainedAir);
        noSurplus.addProperty("inventoryConserved", inventoryConserved);
        noSurplus.addProperty("serverCursorEmpty", latestSnapshot != null && latestSnapshot.serverCursorEmpty);
        noSurplus.addProperty("playerAlive", latestSnapshot != null && latestSnapshot.health > 0.0F);
        worldPolicyEvidence.add("backfillNoSurplus", noSurplus);
        if (!targetRemainedAir || !inventoryConserved || !requestedFloorHeld || !latestSnapshot.serverCursorEmpty
                || latestSnapshot.health <= 0.0F || client.currentScreen != null) {
            throw new IllegalStateException("backfill spent requested cobblestone or did not defer without surplus stock");
        }
        sendCommand("!lk maintain cobblestone 1");
        worldPolicyPhase = WorldPolicyPhase.BACKFILL_SURPLUS;
        worldPolicyPhaseStartedAtTick = clientTicks;
        worldPolicyBackfillSurplusStartedAtTick = clientTicks;
    }

    private void tickWorldPolicyBackfillSurplus() {
        if (!maintainedReservationObserved("minecraft:cobblestone", 1)) {
            if (clientTicks - worldPolicyBackfillSurplusStartedAtTick > 1_200) {
                throw new IllegalStateException("waiting for maintained cobblestone stock reservation before surplus setup");
            }
            return;
        }
        if (requireEngine().diagnosticTaskIdentity() != null) {
            if (clientTicks - worldPolicyBackfillSurplusStartedAtTick > 1_200) {
                throw new IllegalStateException("waiting for the engine to become idle before surplus setup");
            }
            return;
        }
        if (!worldPolicyBackfillSurplusSetupComplete) {
            if (setupFuture == null) {
                IntegratedServer server = requireServer();
                setupFuture = new CompletableFuture<>();
                CompletableFuture<Long> scheduled = setupFuture;
                worldPolicyBackfillPlayerPositionBeforeRelocation = latestSnapshot == null ? "unknown"
                    : latestSnapshot.worldPolicyServerReceipt.getOrDefault("position", "unknown");
                server.execute(() -> {
                    try {
                        ServerPlayerEntity player = requireServerPlayer(server);
                        if (!player.getInventory().insertStack(new ItemStack(Items.STONE))) {
                            throw new IllegalStateException("could not seed one equivalent surplus stone block");
                        }
                        if (!VerificationApi.teleport(player, server.getOverworld(), WORLD_POLICY_BACKFILL_PREPARED_PLAYER_X,
                                WORLD_POLICY_BACKFILL_PREPARED_PLAYER_Y, WORLD_POLICY_BACKFILL_PREPARED_PLAYER_Z,
                                player.getYaw(), player.getPitch())) {
                            throw new IllegalStateException("could not move the verifier clear of its backfill target");
                        }
                        double dx = WORLD_POLICY_BACKFILL_TARGET.getX() + 0.5 - player.getX();
                        double dz = WORLD_POLICY_BACKFILL_TARGET.getZ() + 0.5 - player.getZ();
                        float towardTarget = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        player.setYaw(towardTarget + 180.0F);
                        player.setPitch(0.0F);
                        player.currentScreenHandler.sendContentUpdates();
                        scheduled.complete((long) server.getTicks());
                    } catch (Throwable throwable) {
                        scheduled.completeExceptionally(throwable);
                    }
                });
                return;
            }
            if (!setupFuture.isDone()) {
                if (clientTicks - worldPolicyBackfillSurplusStartedAtTick > 1_200) {
                    throw new IllegalStateException("equivalent-stone fixture stock and player relocation setup did not settle");
                }
                return;
            }
            fixtureReadyServerTick = setupFuture.join();
            setupFuture = null;
            worldPolicyBackfillSurplusSetupComplete = true;
            worldPolicyBackfillPlacementStartedAtTick = clientTicks;
        }
        if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
        if (latestSnapshot == null || latestSnapshot.serverTick < fixtureReadyServerTick) {
            if (clientTicks - worldPolicyBackfillPlacementStartedAtTick > 1_200) {
                throw new IllegalStateException("server did not report the prepared backfill player position");
            }
            return;
        }
        if (!worldPolicyBackfillSurplusStartReceiptAdded) {
            JsonObject setupReceipt = worldPolicyReceipt("backfillSurplusStartReceipt", latestSnapshot);
            setupReceipt.addProperty("fixturePlayerRelocationApplied", true);
            setupReceipt.addProperty("relocationIsNavigationEvidence", false);
            setupReceipt.addProperty("playerPositionBeforeRelocation", worldPolicyBackfillPlayerPositionBeforeRelocation);
            setupReceipt.addProperty("preparedPlayerX", WORLD_POLICY_BACKFILL_PREPARED_PLAYER_X);
            setupReceipt.addProperty("preparedPlayerY", WORLD_POLICY_BACKFILL_PREPARED_PLAYER_Y);
            setupReceipt.addProperty("preparedPlayerZ", WORLD_POLICY_BACKFILL_PREPARED_PLAYER_Z);
            setupReceipt.addProperty("playerAtPreparedPosition", Math.abs(latestSnapshot.x - WORLD_POLICY_BACKFILL_PREPARED_PLAYER_X) < 0.25
                && Math.abs(latestSnapshot.y - WORLD_POLICY_BACKFILL_PREPARED_PLAYER_Y) < 0.25
                && Math.abs(latestSnapshot.z - WORLD_POLICY_BACKFILL_PREPARED_PLAYER_Z) < 0.25);
            boolean targetClearOfPlayer = worldPolicyBackfillTargetClearOfPlayer(latestSnapshot);
            boolean targetWithinPlacementReach = worldPolicyBackfillTargetWithinReach(latestSnapshot);
            setupReceipt.addProperty("targetClearOfPlayer", targetClearOfPlayer);
            setupReceipt.addProperty("targetWithinPlacementReach", targetWithinPlacementReach);
            worldPolicyEvidence.add("backfillSurplusStartReceipt", setupReceipt);
            worldPolicyBackfillSurplusStartReceiptAdded = true;
            if (!setupReceipt.get("playerAtPreparedPosition").getAsBoolean() || !targetClearOfPlayer || !targetWithinPlacementReach) {
                throw new IllegalStateException("prepared backfill player position is not clear of the target or within placement reach");
            }
        }
        if (!"minecraft:stone".equals(latestSnapshot.worldPolicyServerReceipt.get("backfill_target"))) {
            if (clientTicks - worldPolicyBackfillPlacementStartedAtTick > 1_200) {
                throw new IllegalStateException("equivalent-stone backfill placement did not complete after the fixture moved the player clear of the target");
            }
            return;
        }
        boolean finalInventoryMatches = latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 1));
        boolean maintainedFloorHeld = maintainedReservationObserved("minecraft:cobblestone", 1);
        boolean requestedFloorHeld = requestedBackfillFloorObserved("minecraft:cobblestone", 1);
        JsonObject restoration = worldPolicyReceipt("backfillEquivalentStoneFinalReceipt", latestSnapshot);
        restoration.addProperty("restoredWithEquivalentStone", "minecraft:stone".equals(latestSnapshot.worldPolicyServerReceipt.get("backfill_target")));
        restoration.addProperty("requestedCobblestoneGoalCount", requestedBackfillGoalCount("minecraft:cobblestone"));
        restoration.addProperty("effectiveRequestedCobblestoneFloor", requestedBackfillFloorCount("minecraft:cobblestone"));
        restoration.addProperty("requestedCobblestoneFloorRetained", requestedFloorHeld);
        restoration.addProperty("maintainedCobblestoneFloorRetained", maintainedFloorHeld);
        restoration.addProperty("surplusStoneConsumed", latestSnapshot.count("minecraft:stone") == 0);
        restoration.addProperty("inventoryConserved", finalInventoryMatches);
        restoration.addProperty("serverCursorEmpty", latestSnapshot.serverCursorEmpty);
        restoration.addProperty("playerAlive", latestSnapshot.health > 0.0F);
        worldPolicyEvidence.add("backfillEquivalentStone", restoration);
        if (!finalInventoryMatches || !requestedFloorHeld || !maintainedFloorHeld
                || !latestSnapshot.serverCursorEmpty || latestSnapshot.health <= 0.0F || client.currentScreen != null) {
            throw new IllegalStateException("equivalent-stone backfill did not preserve requested and maintained cobblestone floors");
        }
        requireEngine().stop();
        boolean configRestored = restoreWorldPolicyConfig();
        boolean claimsRestored = restoreWorldPolicyClaims();
        worldPolicyEvidence.addProperty("backfillConfigRestored", configRestored);
        worldPolicyEvidence.addProperty("claimConfigRestored", claimsRestored);
        if (!configRestored || !claimsRestored) throw new IllegalStateException("world-policy verifier did not restore isolated configuration");
        worldPolicyEvidence.add("serverObservations", worldPolicyServerObservations);
        addResult(true, latestSnapshot.count("minecraft:cobblestone"),
            "claim break and placement boundaries stayed unchanged; preferred stations were reused; backfill deferred without surplus and used surplus stone while retaining requested and maintained cobblestone",
            capture("world-policy"));
        worldPolicyPhase = WorldPolicyPhase.COMPLETE;
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private boolean requestedBackfillFloorObserved(String item, int expectedCount) {
        return requestedBackfillFloorCount(item) == expectedCount;
    }

    private int requestedBackfillFloorCount(String item) {
        if (latestSnapshot == null) return 0;
        return Math.min(requestedBackfillGoalCount(item), latestSnapshot.count(item));
    }

    private int requestedBackfillGoalCount(String item) {
        if (REQUESTED_BACKFILL_STOCK_FIELD == null) throw new IllegalStateException("requested backfill stock field is unavailable");
        try {
            Object value = REQUESTED_BACKFILL_STOCK_FIELD.get(requireEngine());
            if (!(value instanceof Map<?, ?> floors)) return 0;
            Object goalCount = floors.get(ItemId.parse(item));
            return goalCount instanceof Integer count ? count : 0;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("could not inspect requested backfill stock floors", exception);
        }
    }

    private boolean restoreWorldPolicyConfig() {
        if (!worldPolicyOriginalBackfillCaptured) return true;
        AutomationEngine engine = LodekeeperClient.engine;
        if (engine == null) return false;
        engine.config.backfill = worldPolicyOriginalBackfill;
        engine.config.backfillEquivalentStone = worldPolicyOriginalEquivalentStone;
        worldPolicyConfigRestored = engine.config.backfill == worldPolicyOriginalBackfill
            && engine.config.backfillEquivalentStone == worldPolicyOriginalEquivalentStone;
        return worldPolicyConfigRestored;
    }

    private void backupWorldPolicyClaims() throws IOException {
        Path runDirectory = client.runDirectory.toPath().toRealPath();
        Path configDirectory = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
        if (!configDirectory.equals(runDirectory.resolve("config").normalize())) {
            throw new IOException("world-policy verification requires an isolated run-directory config folder");
        }
        Files.createDirectories(configDirectory);
        if (!configDirectory.toRealPath().equals(configDirectory)) throw new IOException("world-policy config folder resolves through a symlink");
        worldPolicyClaimsPath = configDirectory.resolve("lodekeeper-claims.json");
        if (Files.isSymbolicLink(worldPolicyClaimsPath)) throw new IOException("world-policy claims file must not be a symlink");
        worldPolicyOriginalClaimsExisted = Files.exists(worldPolicyClaimsPath);
        if (worldPolicyOriginalClaimsExisted) {
            if (!Files.isRegularFile(worldPolicyClaimsPath) || Files.size(worldPolicyClaimsPath) > 256 * 1024) {
                throw new IOException("world-policy claims file is not a bounded regular file");
            }
            worldPolicyOriginalClaims = Files.readAllBytes(worldPolicyClaimsPath);
        }
        worldPolicyClaimsBackupTaken = true;
    }

    private boolean restoreWorldPolicyClaims() {
        if (!worldPolicyClaimsBackupTaken || worldPolicyClaimsRestored) return true;
        try {
            if (Files.isSymbolicLink(worldPolicyClaimsPath)) throw new IOException("claims file became a symlink during verification");
            if (worldPolicyOriginalClaimsExisted) {
                Path temporary = Files.createTempFile(worldPolicyClaimsPath.getParent(), "lodekeeper-claims-restore-", ".tmp");
                try {
                    Files.write(temporary, worldPolicyOriginalClaims);
                    Files.move(temporary, worldPolicyClaimsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } else {
                Files.deleteIfExists(worldPolicyClaimsPath);
            }
            worldPolicyClaimsRestored = worldPolicyOriginalClaimsExisted
                ? java.util.Arrays.equals(worldPolicyOriginalClaims, Files.readAllBytes(worldPolicyClaimsPath))
                : !Files.exists(worldPolicyClaimsPath);
            return worldPolicyClaimsRestored;
        } catch (IOException | RuntimeException failure) {
            if (worldPolicyEvidence != null) worldPolicyEvidence.addProperty("claimConfigRestorationError", failure.toString());
            return false;
        }
    }

    private void recordWorldPolicySnapshot(ServerSnapshot snapshot) {
        if (worldPolicyEvidence == null || snapshot == null || snapshot.serverTick == worldPolicyLastRecordedServerTick) return;
        worldPolicyLastRecordedServerTick = snapshot.serverTick;
        JsonObject observation = worldPolicyReceipt("serverObservation", snapshot);
        observation.addProperty("phase", worldPolicyPhase.name().toLowerCase(java.util.Locale.ROOT));
        observation.addProperty("playerInsideClaim", worldPolicyPositionInside(snapshot));
        observation.addProperty("clientScreenOpen", client.currentScreen != null);
        observation.addProperty("engineStatus", requireEngine().status());
        worldPolicyServerObservations.add(observation);
    }

    private JsonObject worldPolicyReceipt(String name, ServerSnapshot snapshot) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("name", name);
        receipt.addProperty("serverTick", snapshot.serverTick);
        receipt.add("inventory", gsonObject(snapshot.inventory));
        receipt.addProperty("cursorEmpty", snapshot.serverCursorEmpty);
        receipt.addProperty("health", snapshot.health);
        receipt.addProperty("playerAlive", snapshot.health > 0.0F);
        JsonObject server = new JsonObject();
        snapshot.worldPolicyServerReceipt.forEach(server::addProperty);
        receipt.add("integratedServer", server);
        return receipt;
    }

    private static JsonObject gsonObject(Map<String, Integer> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }

    private static JsonArray blockPositionJson(BlockPos position) {
        JsonArray result = new JsonArray();
        result.add(position.getX());
        result.add(position.getY());
        result.add(position.getZ());
        return result;
    }

    private static boolean worldPolicyBlocksIntact(Map<String, String> receipt) {
        return WORLD_POLICY_FACES.stream().allMatch(face -> "minecraft:stone".equals(receipt.get("face_" + face.name())))
            && "minecraft:stone".equals(receipt.get("support")) && "minecraft:torch".equals(receipt.get("torch"));
    }

    private static boolean worldPolicyPositionInside(ServerSnapshot snapshot) {
        return snapshot.x >= WORLD_POLICY_CLAIM_MIN.getX() && snapshot.x < WORLD_POLICY_CLAIM_MAX.getX() + 1.0
            && snapshot.y >= WORLD_POLICY_CLAIM_MIN.getY() && snapshot.y < WORLD_POLICY_CLAIM_MAX.getY() + 1.0
            && snapshot.z >= WORLD_POLICY_CLAIM_MIN.getZ() && snapshot.z < WORLD_POLICY_CLAIM_MAX.getZ() + 1.0;
    }

    private static boolean worldPolicyBackfillTargetClearOfPlayer(ServerSnapshot snapshot) {
        double targetX = WORLD_POLICY_BACKFILL_TARGET.getX();
        double targetY = WORLD_POLICY_BACKFILL_TARGET.getY();
        double targetZ = WORLD_POLICY_BACKFILL_TARGET.getZ();
        return snapshot.x + 0.3 <= targetX || snapshot.x - 0.3 >= targetX + 1.0
            || snapshot.y + 1.8 <= targetY || snapshot.y >= targetY + 1.0
            || snapshot.z + 0.3 <= targetZ || snapshot.z - 0.3 >= targetZ + 1.0;
    }

    private static boolean worldPolicyBackfillTargetWithinReach(ServerSnapshot snapshot) {
        double dx = WORLD_POLICY_BACKFILL_TARGET.getX() - Math.floor(snapshot.x);
        double dy = WORLD_POLICY_BACKFILL_TARGET.getY() - Math.floor(snapshot.y);
        double dz = WORLD_POLICY_BACKFILL_TARGET.getZ() - Math.floor(snapshot.z);
        return dx * dx + dy * dy + dz * dz <= 16.0;
    }

    private void sendCommand(String command) {
        ClientPlayNetworkHandler network = client.getNetworkHandler();
        if (network == null) throw new IllegalStateException("Integrated client is not connected");
        client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("[Verifier command] " + command));
        network.sendChatMessage(command);
    }

    private void requestObservation() {
        if (observationFuture != null || client.player == null || !client.isIntegratedServerRunning()) return;
        IntegratedServer server = requireServer();
        CompletableFuture<ServerSnapshot> capture = new CompletableFuture<>();
        long requestSequence = ++observationRequestSequence;
        observationFuture = capture;
        server.execute(() -> {
            try {
                ServerPlayerEntity player = requireServerPlayer(server);
                ServerInventorySnapshot inventory = inventorySnapshot(player);
                ServerWorld world = server.getOverworld();
                ServerSnapshot snapshot = new ServerSnapshot(server.getTicks(), world.getTime(), inventory.counts(),
                    inventory.woodenAxeRemainingDurability(),
                    IRON_PICKAXE_MODE ? countIronPickaxeDeepslate(world) : -1,
                    COAL_RECOVERY_MODE && world.getBlockState(COAL_RECOVERY_ENCASED_ORE).isOf(Blocks.COAL_ORE) ? 1 : COAL_RECOVERY_MODE ? 0 : -1,
                    COAL_RECOVERY_MODE && world.getBlockState(coalRecoveryAccessibleOrePosition()).isOf(Blocks.COAL_ORE) ? 1 : COAL_RECOVERY_MODE ? 0 : -1,
                    COAL_RECOVERY_MODE ? countCoalStartSurface(world) : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseMismatchCount(world) : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseObservedMask : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseMinimumHealth : -1.0F,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCheckpointServerTicksSnapshot() : List.of(),
                    nearbyWoodLocalFixtureSnapshot(world), inventory.storageCounts(),
                    VerificationApi.equippedItems(player), VerificationApi.serverCursorEmpty(player),
                    preparedSafetyThreatFixture == null ? Map.of()
                        : VerificationApi.preparedSafetyThreatReceipt(player, preparedSafetyThreatFixture),
                    preparedSafetyStationRoomFixture == null ? Map.of()
                        : VerificationApi.preparedSafetyStationRoomReceipt(player, world, preparedSafetyStationRoomFixture),
                    preparedSafetyPursuitFixture == null ? Map.of()
                        : VerificationApi.preparedSafetyPursuitReceipt(player, preparedSafetyPursuitFixture),
                    preparedSafetyAirFixture == null ? Map.of()
                        : VerificationApi.preparedSafetyAirReceipt(player, preparedSafetyAirFixture),
                    preparedSafetyWorkbenchFixture == null ? Map.of()
                        : VerificationApi.preparedSafetyWorkbenchReceipt(player, world, preparedSafetyWorkbenchFixture),
                    HELD_FUEL_MODE ? VerificationApi.preparedSafetyHeldFuelReceipt(world) : Map.of(),
                    WORLD_POLICY_MODE ? worldPolicyServerReceipt(world, player, inventory.counts()) : Map.of(),
                    player.getHealth(), player.getHungerManager().getFoodLevel(), world.getDifficulty().name(),
                    player.getX(), player.getY(), player.getZ());
                capture.complete(snapshot);
            } catch (Throwable throwable) {
                capture.completeExceptionally(throwable);
            }
        });
        capture.whenComplete((snapshot, throwable) -> client.execute(() -> {
            if (observationFuture != capture) return;
            observationFuture = null;
            if (throwable != null) {
                fail("server observation failed: " + throwable.getMessage());
                return;
            }
            latestSnapshot = snapshot;
            latestObservationRequestSequence = requestSequence;
            if (WORLD_POLICY_MODE) recordWorldPolicySnapshot(snapshot);
            NearbyWoodLocalFixtureSnapshot nearbyFixture = snapshot.nearbyWoodLocalFixture;
            if (NEARBY_WOOD_LOCAL_DECOY_MODE && nearbyFixture != null
                    && !nearbyFixture.visibleOakLogPresent && nearbyWoodFirstServerLogRemoval == null) {
                nearbyWoodFirstServerLogRemoval = new NearbyWoodServerRemovalObservation(
                    snapshot.serverTick, snapshot.worldTime, clientTicks);
            }
        }));
    }

    private static NearbyWoodLocalFixtureSnapshot nearbyWoodLocalFixtureSnapshot(ServerWorld world) {
        if (!NEARBY_WOOD_LOCAL_DECOY_MODE) return null;
        return new NearbyWoodLocalFixtureSnapshot(
            world.getBlockState(NEARBY_WOOD_LOCAL_VISIBLE_LOG).isOf(Blocks.OAK_LOG),
            world.getBlockState(NEARBY_WOOD_LOCAL_DECOY_LOG).isOf(Blocks.OAK_LOG),
            NEARBY_WOOD_LOCAL_DECOY_SHELL.stream()
                .map(position -> world.getBlockState(position).isOf(Blocks.BEDROCK)).toList());
    }

    private static Map<String, String> worldPolicyServerReceipt(ServerWorld world, ServerPlayerEntity player,
                                                                 Map<String, Integer> inventory) {
        Map<String, String> receipt = new LinkedHashMap<>();
        for (WorldPolicyFace face : WORLD_POLICY_FACES) {
            receipt.put("face_" + face.name(), Registries.BLOCK.getId(world.getBlockState(face.position()).getBlock()).toString());
        }
        receipt.put("torch", Registries.BLOCK.getId(world.getBlockState(WORLD_POLICY_TORCH).getBlock()).toString());
        receipt.put("support", Registries.BLOCK.getId(world.getBlockState(WORLD_POLICY_SUPPORT).getBlock()).toString());
        receipt.put("preferred_furnace", Registries.BLOCK.getId(world.getBlockState(WORLD_POLICY_PREFERRED_FURNACE).getBlock()).toString());
        receipt.put("ordinary_furnace", Registries.BLOCK.getId(world.getBlockState(WORLD_POLICY_ORDINARY_FURNACE).getBlock()).toString());
        receipt.put("preferred_furnace_lit", Boolean.toString(furnaceLit(world.getBlockState(WORLD_POLICY_PREFERRED_FURNACE))));
        receipt.put("ordinary_furnace_lit", Boolean.toString(furnaceLit(world.getBlockState(WORLD_POLICY_ORDINARY_FURNACE))));
        receipt.put("preferred_table", Registries.BLOCK.getId(world.getBlockState(WORLD_POLICY_PREFERRED_TABLE).getBlock()).toString());
        receipt.put("ordinary_table", Registries.BLOCK.getId(world.getBlockState(WORLD_POLICY_ORDINARY_TABLE).getBlock()).toString());
        int furnaceCount = 0;
        for (int x = -12; x <= 18; x++) for (int y = 64; y <= 67; y++) for (int z = -6; z <= 6; z++) {
            if (world.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.FURNACE)) furnaceCount++;
        }
        int craftingTableCount = 0;
        for (int x = -12; x <= 18; x++) for (int y = FIXTURE_FLOOR_Y; y <= 67; y++) for (int z = -6; z <= 8; z++) {
            if (world.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.CRAFTING_TABLE)) craftingTableCount++;
        }
        receipt.put("furnace_count", Integer.toString(furnaceCount));
        receipt.put("crafting_table_count", Integer.toString(craftingTableCount));
        receipt.put("backfill_target", Registries.BLOCK.getId(world.getBlockState(WORLD_POLICY_BACKFILL_TARGET).getBlock()).toString());
        for (WorldPolicyPlacementProbe probe : WORLD_POLICY_PLACEMENT_PROBES) {
            receipt.put("placement_clicked_" + probe.name(), Registries.BLOCK.getId(world.getBlockState(probe.clicked()).getBlock()).toString());
            receipt.put("placement_target_" + probe.name(), Registries.BLOCK.getId(world.getBlockState(probe.target()).getBlock()).toString());
        }
        receipt.put("placement_clicked_outside_control", Registries.BLOCK.getId(
            world.getBlockState(WORLD_POLICY_OUTSIDE_PLACEMENT_PROBE.clicked()).getBlock()).toString());
        receipt.put("placement_target_outside_control", Registries.BLOCK.getId(
            world.getBlockState(WORLD_POLICY_OUTSIDE_PLACEMENT_PROBE.target()).getBlock()).toString());
        receipt.put("inventory", inventory.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(",")));
        receipt.put("cursor_empty", Boolean.toString(VerificationApi.serverCursorEmpty(player)));
        receipt.put("alive", Boolean.toString(player.isAlive()));
        receipt.put("health", Float.toString(player.getHealth()));
        receipt.put("position", player.getX() + "," + player.getY() + "," + player.getZ());
        return Map.copyOf(receipt);
    }

    private static boolean furnaceLit(BlockState state) {
        return state.contains(net.minecraft.block.AbstractFurnaceBlock.LIT)
            && state.get(net.minecraft.block.AbstractFurnaceBlock.LIT);
    }

    private static ServerInventorySnapshot inventorySnapshot(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        Map<String, Integer> storageCounts = new HashMap<>();
        List<Integer> woodenAxes = new ArrayList<>();
        countStacks(ClientAccess.main(inventory), storageCounts, woodenAxes);
        Map<String, Integer> counts = new HashMap<>(storageCounts);
        countStacks(List.of(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD),
            player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST),
            player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS),
            player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET),
            player.getEquippedStack(net.minecraft.entity.EquipmentSlot.OFFHAND)), counts, woodenAxes);
        return new ServerInventorySnapshot(counts, storageCounts, woodenAxes);
    }

    private static int countIronPickaxeDeepslate(ServerWorld world) {
        int remaining = 0;
        for (int index = 0; index < IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT; index++) {
            if (world.getBlockState(new BlockPos(16 + index, 20, 4)).isOf(Blocks.DEEPSLATE)) remaining++;
        }
        return remaining;
    }

    private static void countStacks(Iterable<ItemStack> stacks, Map<String, Integer> counts, List<Integer> woodenAxes) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            if (stack.isOf(Items.WOODEN_AXE)) woodenAxes.add(stack.getMaxDamage() - stack.getDamage());
        }
    }

    private ServerPlayerEntity requireServerPlayer(IntegratedServer server) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Integrated server player is not present");
        return player;
    }

    private IntegratedServer requireServer() {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Isolated integrated server is not running");
        return server;
    }

    private AutomationEngine requireEngine() {
        if (LodekeeperClient.engine == null) throw new IllegalStateException("Lodekeeper client initializer has not completed");
        return LodekeeperClient.engine;
    }

    private void engineTerrainChanged() {
        requireEngine().terrain.changed();
    }

    private void captureSettingsUi(String label) {
        String screenshot = capture("settings-ui-" + label);
        settingsUiScreenshots.add(label, screenshot == null
                ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(screenshot));
    }

    private void tickSettingsUi() {
        if (settingsUiVerification == null) {
            if (!(client.currentScreen instanceof AutomationSettingsScreen)) {
                if (clientTicks - caseStartedAtTick > 20) fail("native config chat command did not open settings within 20 ticks");
                return;
            }
            settingsUiChatEntryConfirmed = true;
            settingsUiVerification = SettingsUiVerification.begin(settingsUiAccess);
            return;
        }
        settingsUiVerification.tick();
        if (!settingsUiVerification.completed()) return;
        settingsUiReceipt = settingsUiVerification.receipt();
        if (!settingsUiVerification.passed() || !settingsUiVerification.restorationComplete()) {
            fail("settings UI verification failed or config restoration was incomplete: " + settingsUiReceipt);
            return;
        }
        if (settingsUiFinalCaptureTick < 0) {
            settingsUiFinalCaptureTick = clientTicks + 2;
            return;
        }
        if (clientTicks < settingsUiFinalCaptureTick) return;
        settingsUiFinalScreenshot = capture("native-settings-ui-final");
        if (settingsUiFinalScreenshot == null) {
            fail("final native settings UI screenshot could not be captured");
            return;
        }
        settingsUiScreenshots.addProperty("final", settingsUiFinalScreenshot);
        addResult(true, 0, "native settings and protected-plots screens accepted mouse/key/character events; plot inputs survived screen resize and ClaimStore reload; "
                + settingsUiReceipt, settingsUiFinalScreenshot);
        state = State.CAPTURING;
        captureStartedAtTick = clientTicks;
    }

    private void addResult(boolean passed, int observed, String detail, String screenshot) {
        long gameTicks = client.world == null ? 0 : Math.max(0, client.world.getTime() - caseStartedAtWorldTime);
        results.add(new CaseResult(activeCase, activeItem, activeCount, observed, activeStartedEmpty,
            activeRequiresEmpty, passed && (!activeRequiresEmpty || activeStartedEmpty), clientTicks - caseStartedAtTick, gameTicks,
            Math.max(0, (System.nanoTime() - caseStartedAtNanos) / 1_000_000L),
            requireEngine().status(), detail, screenshot, latestSnapshot == null ? 0 : latestSnapshot.health,
            latestSnapshot == null ? "unknown" : latestSnapshot.difficulty,
            latestSnapshot != null && serverTableOpenings > activeTableOpeningsAtStart,
            serverFurnaceOpenings > activeFurnaceOpeningsAtStart,
            latestSnapshot == null ? 0 : latestSnapshot.count(IRON_PICKAXE_ID),
            activeFoodLevelAtStart, latestSnapshot == null ? 0 : latestSnapshot.foodLevel,
            activeBreadCountAtStart, latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID),
            latestSnapshot == null ? 0 : latestSnapshot.x, latestSnapshot == null ? 0 : latestSnapshot.y,
            latestSnapshot == null ? 0 : latestSnapshot.z,
            latestSnapshot == null ? Map.of() : latestSnapshot.inventory,
            latestSnapshot == null ? Map.of() : latestSnapshot.storageInventory,
            latestSnapshot == null ? List.of() : latestSnapshot.woodenAxeRemainingDurability,
            clientTicks, latestSnapshot == null ? 0 : latestSnapshot.worldTime,
            PROCESSING_MODE ? PROCESSING_STATION_MODE : "", PROCESSING_MODE ? cookingRecipeType() : "",
            ANIMAL_MODE || PROCESSING_MODE || IRON_PICKAXE_MODE || PREPARED_SAFETY_MODE != null ? activeInitialResources : Map.of(), PROCESSING_MODE && correctCookingStationMenuOpened(),
            PROCESSING_MODE ? activeInitialResources.getOrDefault(cookingRawItemId(), 0) : 0,
            PROCESSING_MODE && latestSnapshot != null ? latestSnapshot.count(cookingRawItemId()) : 0,
            PROCESSING_MODE ? activeInitialResources.getOrDefault("minecraft:coal", 0) : 0,
            latestSnapshot == null ? 0 : latestSnapshot.count("minecraft:coal"),
            PROCESSING_MODE ? activeInitialResources.getOrDefault("minecraft:" + PROCESSING_STATION_MODE, 0) : 0,
            latestSnapshot == null || !PROCESSING_MODE ? 0
                : latestSnapshot.count("minecraft:" + PROCESSING_STATION_MODE),
            latestSnapshot == null ? Map.of() : latestSnapshot.equippedItems,
            activeInitialEquipment, latestSnapshot != null && latestSnapshot.serverCursorEmpty, activeInitialCursorEmpty,
            preparedMaintenanceQueueEmptyBeforeForeground, preparedMaintenanceReservationObservedBeforeForeground,
            preparedMaintenanceReservationPresentAtCompletion, activeInitialThreatReceipt,
            latestSnapshot == null ? Map.of() : latestSnapshot.preparedSafetyThreatReceipt,
            activeInitialStationRoomReceipt,
            stationRoomResultReceipt(),
            activeInitialPursuitReceipt,
            latestSnapshot == null ? Map.of() : latestSnapshot.preparedSafetyPursuitReceipt,
            activeInitialAirReceipt,
            latestSnapshot == null ? Map.of() : latestSnapshot.preparedSafetyAirReceipt,
            preparedAirRecoveryObserved, preparedAirRecoveryCompletedBeforeCraft,
            preparedAirRecoveryObservedClientTick, preparedAirRecoveryTransitionClientTick,
            preparedAirRecoveryCompletionClientTick, preparedAirRecoveryCompletionServerTick,
            preparedAirRecoveryTransitionObservationSequence, preparedAirRecoveryTableOpeningsAtTransition,
            preparedAirRecoveryBucketCountAtCompletion, preparedAirRecoveryEndEngineStatus,
            preparedAirRecoveryCompletionReceipt, baritoneNavigationStopped()));
    }

    private String capture(String name) {
        String fileName = "lodekeeper-" + runId + "-" + name + ".png";
        try {
            screenshotWritesPending++;
            VerificationApi.screenshot(evidenceDirectory.toFile(), fileName, client, text -> {
                System.out.println("[Lodekeeper verification] " + text.getString());
                client.execute(() -> screenshotWritesPending = Math.max(0, screenshotWritesPending - 1));
            });
            return "screenshots/" + fileName;
        } catch (Exception exception) {
            screenshotWritesPending = Math.max(0, screenshotWritesPending - 1);
            System.err.println("[Lodekeeper verification] Screenshot failed for " + name + ": " + exception.getMessage());
            return null;
        }
    }

    private void observeFirstServerMovement(ServerPlayerEntity player) {
        MovementClock clock = movementClock;
        if (!(NEARBY_WOOD_MODE || IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE)
                || clock == null || firstMovementMillis >= 0) return;
        if (Math.abs(player.getX() - clock.x) > 0.1 || Math.abs(player.getZ() - clock.z) > 0.1) {
            firstMovementMillis = Math.max(0, (System.nanoTime() - clock.startedAtNanos) / 1_000_000L);
        }
    }

    private record MovementClock(long startedAtNanos, double x, double z) { }

    private void finishRun() {
        if (nativePlayerPeer != null) { fail("native fixture peer requires acknowledged teardown before completion"); return; }
        if (SETTINGS_UI_MODE && !settingsUiScreenshotsValid()) {
            fail("settings UI screenshots were missing or unreadable");
            return;
        }
        if (STATION_ROOM_TUNNEL_MODE || STATION_ROOM_APPROACH_MODE) {
            try {
                if (stationRoomSetupScreenshot == null || screenshotWritesPending > 0
                        || !Files.isRegularFile(evidenceDirectory.resolve(stationRoomSetupScreenshot))
                        || javax.imageio.ImageIO.read(evidenceDirectory.resolve(stationRoomSetupScreenshot).toFile()) == null) {
                    fail("Station-room setup screenshot was not saved as a readable image");
                    return;
                }
            } catch (java.io.IOException exception) {
                fail("Cannot verify station-room setup screenshot: " + exception.getMessage());
                return;
            }
        }
        if (NEARBY_WOOD_MODE || IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE) {
            if (firstMovementMillis < 0) {
                fail("Server movement timestamp was not recorded");
                return;
            }
            String screenshotKind = NEARBY_WOOD_LOCAL_DECOY_MODE ? "active-task" : "active-route";
            String screenshotFailure = NEARBY_WOOD_LOCAL_DECOY_MODE
                ? "Active-task screenshot was not saved as a readable image" : "Active-route screenshot was not saved";
            try {
                String activeEvidenceScreenshot = NEARBY_WOOD_LOCAL_DECOY_MODE
                    ? nearbyWoodLocalActiveTaskScreenshot : liveRouteScreenshot;
                if (activeEvidenceScreenshot == null
                        || !Files.isRegularFile(evidenceDirectory.resolve(activeEvidenceScreenshot))
                        || Files.size(evidenceDirectory.resolve(activeEvidenceScreenshot)) == 0
                        || javax.imageio.ImageIO.read(evidenceDirectory.resolve(activeEvidenceScreenshot).toFile()) == null) {
                    fail(screenshotFailure);
                    return;
                }
            } catch (java.io.IOException exception) {
                fail("Cannot verify saved " + screenshotKind + " screenshot: " + exception.getMessage());
                return;
            }
        }
        state = State.COMPLETE;
        int expectedCases = COOPERATIVE_MODE || ANIMAL_MODE || SHIELD_MODE || WORLD_POLICY_MODE || SETTINGS_UI_MODE ? 1 : PREPARED_SAFETY_MODE != null
            ? PREPARED_SAFETY_MODE.equals("offhand") || HELD_FUEL_MODE ? 2 : 1
            : NEARBY_WOOD_MODE || EXPLORATION_MODE || DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE || COAL_RECOVERY_MODE || BULK_WOOD_MODE || PROCESSING_MODE ? 1 : 9;
        boolean allPassed = results.size() == expectedCases && results.stream().allMatch(CaseResult::passed);
        writeEvidence(allPassed ? "passed" : "failed");
        System.out.println("[Lodekeeper verification] Finished " + results.size()
                + (SETTINGS_UI_MODE ? " native settings UI cases; evidence: " : " server-observed cases; evidence: ")
                + evidenceDirectory);
        client.scheduleStop();
    }

    private boolean settingsUiScreenshotsValid() {
        try {
            if (settingsUiScreenshots.size() < 4 || settingsUiFinalScreenshot == null
                    || !settingsUiScreenshots.has("actual-plot-editor") || !settingsUiScreenshots.has("final")) return false;
            for (var entry : settingsUiScreenshots.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) return false;
                Path image = evidenceDirectory.resolve(entry.getValue().getAsString()).normalize();
                if (!image.startsWith(evidenceDirectory) || !Files.isRegularFile(image)
                        || Files.size(image) == 0 || javax.imageio.ImageIO.read(image.toFile()) == null) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void fail(String reason) {
        if (state != State.FAILED && state != State.COMPLETE) recordNativeAnimalReadiness("before-stop");
        if (nativeAnimalClaim != null) { requireEngine().protection.removeClaim(nativeAnimalClaim); nativeAnimalClaim = null; }
        if (state == State.FAILED || state == State.COMPLETE) return;
        if (WORLD_POLICY_MODE) {
            boolean restored = restoreWorldPolicyClaims();
            if (worldPolicyEvidence != null) worldPolicyEvidence.addProperty("claimConfigRestored", restored);
            boolean configRestored = restoreWorldPolicyConfig();
            if (worldPolicyEvidence != null) worldPolicyEvidence.addProperty("backfillConfigRestored", configRestored);
        }
        if (settingsUiVerification != null) {
            settingsUiVerification.restore();
            settingsUiReceipt = settingsUiVerification.receipt();
            if (client != null && (client.currentScreen instanceof AutomationSettingsScreen
                    || client.currentScreen instanceof NavigationPreferencesScreen)) client.setScreen(null);
        }
        if (SHIELD_MODE && shieldManualTick >= 0) client.options.useKey.setPressed(false);
        if (SHIELD_MODE) recordShieldEvidence();
        failure = reason;
        if (activeCase != null && results.stream().noneMatch(result -> result.name.equals(activeCase))) {
            int observed = latestSnapshot == null || activeItem == null ? 0 : latestSnapshot.count(activeItem);
            addResult(false, observed, reason, capture("failed-" + activeCase));
        }
        state = State.FAILED;
        try {
            if (LodekeeperClient.engine != null) LodekeeperClient.engine.stop();
            writeEvidence("failed");
        } catch (Exception exception) {
            System.err.println("[Lodekeeper verification] Could not write evidence: " + exception.getMessage());
        }
        System.err.println("[Lodekeeper verification] FAILED: " + reason);
        if (client != null && client.isRunning()) client.scheduleStop();
    }

    private void writeEvidence(String status) {
        try {
            Files.createDirectories(evidenceDirectory);
            String json = toJson(status);
            Path output = evidenceDirectory.resolve("run-" + runId + ".json");
            Files.writeString(output, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Lodekeeper verification] Evidence write failed: " + exception.getMessage());
        }
    }

    private String toJson(String status) {
        StringBuilder json = new StringBuilder(1024);
        json.append("{\n  \"runId\":\"").append(escape(runId)).append("\",\n")
            .append("  \"status\":\"").append(escape(status)).append("\",\n")
            .append("  \"minecraftVersion\":\"").append(escape(VerificationApi.minecraftVersion())).append("\",\n")
            .append("  \"worldKind\":\"isolated_superflat_fixture\",\n")
            .append("  \"preparedWorld\":").append(PREPARED_SAFETY_MODE != null).append(",\n")
            .append("  \"threatWaterRetreat\":").append(THREAT_WATER_RETREAT_MODE).append(",\n")
            .append("  \"threatCreeperContact\":").append(THREAT_CREEPER_CONTACT_MODE).append(",\n")
            .append("  \"stationRoomTunnel\":").append(STATION_ROOM_TUNNEL_MODE).append(",\n")
            .append("  \"stationRoomTunnelProperty\":").append(STATION_ROOM_TUNNEL_PROPERTY == null ? "null" : "\"" + escape(STATION_ROOM_TUNNEL_PROPERTY) + "\"").append(",\n")
            .append("  \"stationRoomSetupScreenshot\":").append(stationRoomSetupScreenshot == null ? "null" : "\"" + escape(stationRoomSetupScreenshot) + "\"").append(",\n")
            .append("  \"preparedSafetyProperty\":").append(PREPARED_SAFETY_MODE == null ? "null" : "\"" + escape(PREPARED_SAFETY_MODE) + "\"").append(",\n")
            .append("  \"evidenceAuthority\":\"")
            .append(SETTINGS_UI_MODE ? "native_screen_child_widgets_native_input_events_and_config_reload"
                : HELD_FUEL_MODE ? "integrated_server_inventory_native_furnace_slots_and_idle_navigation" : WORKBENCH_MODE ? "integrated_server_inventory_and_block_states_with_natural_client_tick_engine_status" : THREAT_CREEPER_CONTACT_MODE
                ? "integrated_server_inventory_entity_damage_source_player_health_stats_and_item_durability" : PREPARED_SAFETY_MODE != null && PREPARED_SAFETY_MODE.equals("threat")
                ? "integrated_server_inventory_entities_and_item_durability"
                : PREPARED_SAFETY_MODE != null && PREPARED_SAFETY_MODE.equals("station_room")
                    ? "integrated_server_inventory_furnace_menu_and_block_states"
                    : PREPARED_SAFETY_MODE != null && (PREPARED_SAFETY_MODE.equals("pursuit")
                            || PREPARED_SAFETY_MODE.equals("pursuit-tool"))
                        ? "integrated_server_inventory_hunger_entity_health_and_motion"
                        : PREPARED_SAFETY_MODE != null && PREPARED_SAFETY_MODE.equals("air")
                            ? "integrated_server_air_health_hunger_position_and_inventory"
                        : "integrated_server_inventory")
            .append("\",\n")
            .append("  \"verificationMode\":\"").append(verificationMode()).append("\",\n")
            .append("  \"shieldScenario\":").append(SHIELD_MODE ? shieldEvidence : "null").append(",\n")
            .append("  \"worldPolicy\":").append(WORLD_POLICY_MODE && worldPolicyEvidence != null ? worldPolicyEvidence : "null").append(",\n")
            .append("  \"settingsUiChatEntryConfirmed\":").append(settingsUiChatEntryConfirmed).append(",\n")
            .append("  \"settingsUiReceipt\":").append(settingsUiReceipt == null ? "null" : "\"" + escape(settingsUiReceipt) + "\"").append(",\n")
            .append("  \"settingsUiScreenshots\":").append(SETTINGS_UI_MODE ? settingsUiScreenshots.toString() : "null").append(",\n")
            .append("  \"settingsUiFinalScreenshot\":").append(settingsUiFinalScreenshot == null ? "null" : "\"" + escape(settingsUiFinalScreenshot) + "\"").append(",\n")
            .append("  \"elapsedMillis\":").append((System.nanoTime() - startedAtNanos) / 1_000_000L).append(",\n")
            .append("  \"clientTicks\":").append(clientTicks).append(",\n")
            .append("  \"failure\":\"").append(escape(failure)).append("\",\n")
            .append("  \"serverTableOpened\":").append(serverTableOpened).append(",\n")
            .append("  \"serverFurnaceOpened\":").append(serverFurnaceOpened).append(",\n")
            .append("  \"serverTableOpenings\":").append(serverTableOpenings).append(",\n")
            .append("  \"serverFurnaceOpenings\":").append(serverFurnaceOpenings).append(",\n");
        if (WORKBENCH_MODE) json.append("  \"preparedWorkbench\":").append(workbenchEvidence()).append(",\n");
        if (HELD_FUEL_MODE) json.append("  \"preparedHeldFuel\":").append(heldFuelEvidence()).append(",\n");
        if (THREAT_CREEPER_CONTACT_MODE) {
            json.append("  \"preparedSafetyFixtureGrants\":\"one unprotected stone sword, one maintained diamond sword, one wooden pickaxe, 3 iron ingots, one crafting table; full-health player; one full-health normal-AI creeper at contact range; distant shaded 4-health NoAI zombie and full-health NoAI cow; open bedrock platform; server clock frozen until ordinary bucket command\",\n");
        } else if ("pursuit".equals(PREPARED_SAFETY_MODE)) {
            json.append("  \"preparedSafetyFixtureGrants\":\"3 iron ingots, one crafting table, food level 7, one normal-AI cow, bounded bedrock pen\",\n");
        } else if ("pursuit-tool".equals(PREPARED_SAFETY_MODE)) {
            json.append("  \"preparedSafetyFixtureGrants\":\"3 iron ingots, one crafting table, one full-durability stone pickaxe in hotbar slot index 7, food level 7, one normal-AI cow, bounded bedrock pen\",\n");
        } else if ("air".equals(PREPARED_SAFETY_MODE)) {
            json.append("  \"preparedSafetyFixtureGrants\":\"3 iron ingots, 2 cooked beef, health 3, food level 20 with zero saturation, air 200 at setup, 50 source water cells in a bedrock-bounded 5x5 pool, 24 bedrock roof blocks with one exit gap, bedrock ledge at 3,66,0, and one placed crafting table at 4,66,0\",\n");
        }
        if (GEOMETRY_EPOCH_MODE) {
            json.append("  \"geometryEpoch\":").append(geometryEpoch == null
                ? "null" : geometryEpoch.evidence().toString()).append(",\n");
        }
        if (COOKING_MODE) {
            json.append("  \"serverSmokerOpened\":").append(serverSmokerOpened).append(",\n")
                .append("  \"serverSmokerOpenings\":").append(serverSmokerOpenings).append(",\n")
                .append("  \"serverBlastFurnaceOpened\":").append(serverBlastFurnaceOpened).append(",\n")
                .append("  \"serverBlastFurnaceOpenings\":").append(serverBlastFurnaceOpenings).append(",\n")
                .append("  \"cookingStationProperty\":\"").append(escape(COOKING_STATION_MODE)).append("\",\n")
                .append("  \"cookingFixtureProvidedStock\":");
            appendStringIntMap(json, isSupportedCookingStationMode() ? cookingProvidedStock() : Map.of());
            json.append(",\n");
        }
        NearbyWoodLocalFixtureSnapshot nearbyWoodLocalFixture = latestSnapshot == null
            ? null : latestSnapshot.nearbyWoodLocalFixture;
        if (NEARBY_WOOD_LOCAL_DECOY_MODE) {
            json.append("  \"nearbyWoodTerrain\":\"local_decoy\",\n")
                .append("  \"nearbyWoodLocalDecoyFixture\":{")
                .append("\"visibleLogPosition\":\"").append(blockPosition(NEARBY_WOOD_LOCAL_VISIBLE_LOG)).append('\"')
                .append(",\"decoyLogPosition\":\"").append(blockPosition(NEARBY_WOOD_LOCAL_DECOY_LOG)).append('\"')
                .append(",\"fixtureReadyAtCommandStart\":").append(nearbyWoodLocalFixtureReadyAtCommandStart)
                .append(",\"nativeRouteBenchmarkSkipped\":true")
                .append(",\"serverProofAuthority\":\"integrated_server_block_state\"")
                .append(",\"firstEngineTargetAuthority\":\"client_engine_selection\"")
                .append(",\"firstEngineTarget\":");
            appendNearbyWoodTargetObservation(json, nearbyWoodFirstSelectedTarget);
            json.append(",\"firstGatherMineTargetAuthority\":\"client_mining_intent\"")
                .append(",\"firstGatherMineTarget\":");
            appendNearbyWoodTargetObservation(json, nearbyWoodFirstMineTarget);
            json.append(",\"firstServerVisibleLogRemovalObservation\":");
            appendNearbyWoodServerRemovalObservation(json, nearbyWoodFirstServerLogRemoval);
            json.append(",\"serverVisibleOakLogPresent\":")
                .append(nearbyWoodLocalFixture == null ? "null" : nearbyWoodLocalFixture.visibleOakLogPresent)
                .append(",\"serverDecoyOakLogPresent\":")
                .append(nearbyWoodLocalFixture == null ? "null" : nearbyWoodLocalFixture.decoyOakLogPresent);
            json.append(",\"serverDecoyShellBedrockFacesPresent\":");
            appendNearbyWoodLocalFixtureShell(json, nearbyWoodLocalFixture);
            json.append("},\n");
        }
        json.append("  \"cases\":[\n");
        for (int index = 0; index < results.size(); index++) {
            CaseResult result = results.get(index);
            json.append("    {\"name\":\"").append(escape(result.name)).append("\",\"item\":\"").append(escape(result.item))
                .append("\",\"expected\":").append(result.expected).append(",\"serverObserved\":").append(result.observed)
                .append(",\"inventoryEmptyAtStart\":").append(result.inventoryEmptyAtStart)
                .append(",\"passed\":").append(result.passed).append(",\"clientTicks\":").append(result.clientTicks)
                .append(",\"worldTicks\":").append(result.worldTicks).append(",\"engineStatus\":\"").append(escape(result.engineStatus))
                .append("\",\"detail\":\"").append(escape(result.detail)).append("\",\"serverHealth\":").append(result.health)
                .append(",\"serverDifficulty\":\"").append(escape(result.difficulty)).append("\",\"serverCraftingTableOpenedDuringCase\":").append(result.tableOpenedDuringCase)
                .append(",\"serverFurnaceOpenedDuringCase\":").append(result.furnaceOpenedDuringCase)
                .append(",\"serverIronPickaxeCount\":").append(result.ironPickaxeCount)
                .append(",\"serverFoodLevelAtStart\":").append(result.foodLevelAtStart).append(",\"serverFoodLevelObserved\":").append(result.foodLevelObserved)
                .append(",\"serverBreadAtStart\":").append(result.breadAtStart).append(",\"serverBreadObserved\":").append(result.breadObserved)
                .append(",\"serverPosition\":[").append(result.x).append(',').append(result.y).append(',').append(result.z).append(']')
                .append(",\"screenshot\":").append(result.screenshot == null ? "null" : "\"" + escape(result.screenshot) + "\"");
            if (PREPARED_SAFETY_MODE != null) {
                json.append(",\"initialStorageInventory\":");
                appendStringIntMap(json, result.initialResources);
                json.append(",\"finalStorageInventory\":");
                appendStringIntMap(json, result.storageInventory);
                json.append(",\"fullServerHeldInventory\":");
                appendStringIntMap(json, result.serverInventory);
                json.append(",\"elapsedMillisFromCommand\":").append(result.elapsedMillis)
                    .append(",\"baritoneCompletionGuardEnabled\":").append(BARITONE_MODE)
                    .append(",\"baritoneNavigationStoppedAtReceipt\":").append(result.navigationStopped);
                json.append(",\"initialEquipmentSlotItems\":");
                appendStringStringMap(json, result.initialEquipment);
                json.append(",\"serverEquipmentSlotItems\":");
                appendStringStringMap(json, result.serverEquipment);
                json.append(",\"initialServerCursorEmpty\":").append(result.initialCursorEmpty)
                    .append(",\"serverCursorEmpty\":").append(result.cursorEmpty)
                    .append(",\"noMaintenanceQueuedBeforeForeground\":").append(result.noMaintenanceQueuedBeforeForeground)
                    .append(",\"maintainedReservationObservedBeforeForeground\":").append(result.maintainedReservationObservedBeforeForeground)
                    .append(",\"maintainedReservationPresentAtCompletion\":").append(result.maintainedReservationPresentAtCompletion)
                    .append(",\"noMaintenanceQueued\":").append(result.engineStatus.endsWith("0 maintenance queued"));
                json.append(",\"initialPreparedThreatReceipt\":");
                appendStringStringMap(json, result.initialThreatReceipt);
                json.append(",\"serverPreparedThreatReceipt\":");
                appendStringStringMap(json, result.serverThreatReceipt);
                json.append(",\"initialPreparedStationRoomReceipt\":");
                appendStringStringMap(json, result.initialStationRoomReceipt);
                json.append(",\"serverPreparedStationRoomReceipt\":");
                appendStringStringMap(json, result.serverStationRoomReceipt);
                json.append(",\"initialPreparedPursuitReceipt\":");
                appendStringStringMap(json, result.initialPursuitReceipt);
                json.append(",\"serverPreparedPursuitReceipt\":");
                appendStringStringMap(json, result.serverPursuitReceipt);
                json.append(",\"initialPreparedAirReceipt\":");
                appendStringStringMap(json, result.initialAirReceipt);
                json.append(",\"serverPreparedAirReceipt\":");
                appendStringStringMap(json, result.serverAirReceipt);
                if ("air".equals(PREPARED_SAFETY_MODE)) {
                    json.append(",\"airRecoveryObserved\":").append(result.airRecoveryObserved)
                        .append(",\"airRecoveryCompletedBeforeCraft\":").append(result.airRecoveryCompletedBeforeCraft)
                        .append(",\"airRecoveryObservedClientTick\":").append(result.airRecoveryObservedClientTick)
                        .append(",\"airRecoveryTransitionClientTick\":").append(result.airRecoveryTransitionClientTick)
                        .append(",\"airRecoveryCompletionClientTick\":").append(result.airRecoveryCompletionClientTick)
                        .append(",\"airRecoveryCompletionServerTick\":").append(result.airRecoveryCompletionServerTick)
                        .append(",\"airRecoveryTransitionObservationSequence\":").append(result.airRecoveryTransitionObservationSequence)
                        .append(",\"airRecoveryTableOpeningsAtTransition\":").append(result.airRecoveryTableOpeningsAtTransition)
                        .append(",\"airRecoveryBucketCountAtCompletion\":").append(result.airRecoveryBucketCountAtCompletion)
                        .append(",\"airRecoveryEndEngineStatus\":\"").append(escape(result.airRecoveryEndEngineStatus)).append("\"")
                        .append(",\"airRecoveryCompletionReceipt\":");
                    appendStringStringMap(json, result.airRecoveryCompletionReceipt);
                }
            }
            if (NEARBY_WOOD_MODE) {
                json.append(",\"elapsedMillisFromCommand\":").append(result.elapsedMillis)
                    .append(",\"liveRouteScreenshot\":").append(liveRouteScreenshot == null ? "null" : "\"" + escape(liveRouteScreenshot) + "\"")
                    .append(",\"firstServerMovementMillisFromCommand\":").append(firstMovementMillis)
                    .append(",\"nativeRouteBenchmark\":").append(routeBenchmark)
                    .append(",\"walkArrivalInputEvidence\":");
                appendNearbyWoodWalkInputEvidence(json);
                if (NEARBY_WOOD_LOCAL_DECOY_MODE) {
                    json.append(",\"activeTaskScreenshot\":")
                        .append(nearbyWoodLocalActiveTaskScreenshot == null ? "null"
                            : "\"" + escape(nearbyWoodLocalActiveTaskScreenshot) + "\"");
                }
            }
            if (BULK_WOOD_MODE) {
                json.append(",\"elapsedMillisFromCommand\":").append(result.elapsedMillis)
                    .append(",\"completionClientTick\":").append(result.completionClientTick)
                    .append(",\"completionWorldTick\":").append(result.completionWorldTick)
                    .append(",\"completionHealth\":").append(result.health)
                    .append(",\"finalOakLogCount\":").append(result.serverInventory.getOrDefault(OAK_LOG_ID, 0))
                    .append(",\"fullServerInventory\":{");
                int inventoryIndex = 0;
                for (Map.Entry<String, Integer> entry : result.serverInventory.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    if (inventoryIndex++ > 0) json.append(',');
                    json.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
                }
                json.append("},\"woodenAxeRemainingDurabilityPerStack\":[");
                for (int axeIndex = 0; axeIndex < result.woodenAxeRemainingDurability.size(); axeIndex++) {
                    if (axeIndex > 0) json.append(',');
                    json.append(result.woodenAxeRemainingDurability.get(axeIndex));
                }
                json.append(']');
            }
                if (IRON_PICKAXE_MODE) {
                    json.append(",\"elapsedMillisFromCommand\":").append(result.elapsedMillis)
                        .append(",\"completionClientTick\":").append(result.completionClientTick)
                        .append(",\"completionWorldTick\":").append(result.completionWorldTick)
                        .append(",\"completionHealth\":").append(result.health)
                        .append(",\"finalDeepslateFixtureBlockCount\":")
                        .append(latestSnapshot == null ? -1 : latestSnapshot.ironPickaxeDeepslateRemaining)
                        .append(",\"deepslateFixtureBlocksMined\":")
                        .append(latestSnapshot == null ? -1 : IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT - latestSnapshot.ironPickaxeDeepslateRemaining);
                    if (IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE) {
                        json.append(",\"requiresEmptyAtStart\":").append(result.requiresEmptyAtStart)
                            .append(",\"firstServerMovementMillisFromCommand\":").append(firstMovementMillis)
                            .append(",\"liveRouteScreenshot\":").append(liveRouteScreenshot == null ? "null" : "\"" + escape(liveRouteScreenshot) + "\"");
                    }
                    json.append(",\"fullServerInventory\":");
                    appendStringIntMap(json, result.serverInventory);
                    json.append(",\"initialResources\":");
                    appendStringIntMap(json, result.initialResources);
                }
            if (PROCESSING_MODE) {
                json.append(",\"elapsedMillisFromCommand\":").append(result.elapsedMillis)
                    .append(",\"completionClientTick\":").append(result.completionClientTick)
                    .append(",\"completionWorldTick\":").append(result.completionWorldTick)
                    .append(",\"completionHealth\":").append(result.health)
                    .append(",\"fullServerInventory\":");
                appendStringIntMap(json, result.serverInventory);
                json.append(",\"requiresEmptyAtStart\":").append(result.requiresEmptyAtStart)
                    .append(STONECUTTING_MODE ? ",\"processingStation\":\"" : ",\"cookingStation\":\"").append(escape(result.cookingStation)).append('\"')
                    .append(STONECUTTING_MODE ? ",\"nativeRecipeType\":\"" : ",\"nativeCookingRecipeType\":\"").append(escape(result.cookingRecipeType)).append('\"')
                    .append(",\"initialResources\":");
                appendStringIntMap(json, result.initialResources);
                json.append(",\"serverCorrectStationMenuOpenedDuringCase\":")
                    .append(result.correctCookingStationMenuOpenedDuringCase)
                    .append(",\"initialRawInputCount\":").append(result.initialRawInputCount)
                    .append(",\"finalRawInputCount\":").append(result.finalRawInputCount)
                    .append(",\"initialCoalCount\":").append(result.initialCoalCount)
                    .append(",\"finalCoalCount\":").append(result.finalCoalCount)
                    .append(",\"initialStationItemCount\":").append(result.initialStationItemCount)
                    .append(",\"finalStationItemCount\":").append(result.finalStationItemCount);
            }
            json.append('}').append(index + 1 == results.size() ? "\n" : ",\n");
        }
        json.append("  ],\n  \"explorationFixture\":").append(EXPLORATION_MODE)
            .append(",\n  \"diamondBootsFixture\":").append(DIAMOND_BOOTSTRAP_MODE)
            .append(",\n  \"ironPickaxeFixture\":").append(IRON_PICKAXE_MODE)
            .append(",\n  \"coalRecoveryFixture\":").append(COAL_RECOVERY_MODE)
            .append(",\n  \"bulkWoodFixtureLogCount\":").append(BULK_WOOD_MODE ? 80 : 0)
            .append(",\n  \"woodToolsFlag\":").append(WOOD_TOOLS_MODE)
            .append(",\n  \"woodToolsEnabled\":").append(BULK_WOOD_MODE && WOOD_TOOLS_MODE);
        json.append(",\n  \"miningRequestLimit\":").append(MINING_REQUEST_LIMIT_MODE)
            .append(",\n  \"miningZeroYield\":").append(MINING_ZERO_YIELD_MODE);
        if (MINING_REQUEST_LIMIT_MODE) {
            AutomationEngine engine = LodekeeperClient.engine;
            json.append(",\n  \"miningRequestCapConfiguration\":");
            if (engine == null) json.append("null");
            else {
                int maximumTicks = Math.max(engine.config.actionTimeoutTicks, engine.config.explorationAttempts * 200);
                json.append("{\"actionTimeoutTicks\":").append(engine.config.actionTimeoutTicks)
                    .append(",\"explorationAttempts\":").append(engine.config.explorationAttempts)
                    .append(",\"allowExploration\":").append(engine.config.allowExploration)
                    .append(",\"explorationDistance\":").append(engine.config.explorationDistance)
                    .append(",\"requestCapTicks\":").append(maximumTicks)
                    .append(",\"requestCapMillis\":").append(maximumTicks * 50L)
                    .append(",\"runWallLimitMillis\":").append(MAX_RUN_WALL_NANOS / 1_000_000L).append('}');
            }
            json.append(",\n  \"miningRequestYieldEvidence\":\"native_debug_log_MINING_REQUEST_YIELD\"");
        }
        if (configRoundTripReceipt != null) json.append(",\n  \"configRoundTripReceipt\":").append(configRoundTripReceipt);
        json.append(",\n  \"threatContact\":").append(THREAT_CONTACT_MODE);
        json.append(",\n  \"threatContactManualInput\":").append(CONTACT_MANUAL_INPUT_MODE);
        json.append(",\n  \"threatStaircase\":").append(THREAT_STAIRCASE_MODE);
        if (THREAT_STAIRCASE_MODE) {
            json.append(",\n  \"staircaseReceipt\":");
            appendStringStringMap(json, staircaseReceipt);
            json.append(",\n  \"fixtureGrants\":\"full-health player, bucket materials and untouched weapons; stationary native threat outside attack reach; bedrock corridor with four one-block ascents and its only admissible stances at y 68; no fixture changes after command submission; verifier pauses only after production retreat arrival to observe preserved request, blocks, inputs and settings\"");
        }
        json.append(",\n  \"threatContactLowHealth\":").append(CONTACT_LOW_HEALTH_MODE);
        if (CONTACT_LOW_HEALTH_MODE) {
            json.append(",\n  \"lowHealthReceipt\":");
            appendStringStringMap(json, contactLowHealthReceipt);
        }
        if (CONTACT_MANUAL_INPUT_MODE) {
            json.append(",\n  \"manualInputReceipt\":");
            appendStringStringMap(json, contactManualInputReceipt);
        }
        if (CONTACT_MANUAL_INPUT_MODE) json.append(",\n  \"verificationInputIntervention\":\"one forward-key press during the owned airborne hop; key cleared only after observing the manual-priority pause\"");
        if (THREAT_CONTACT_MODE) json.append(",\n  \"fixtureGrants\":\"full-health player; untouched diamond sword, iron pickaxe, 3 iron ingots, crafting table; two full-health adult normal-AI zombies targeting player; one NoAI cow; solid bedrock box x/z -14..14, ")
            .append(CONTACT_LOW_HEALTH_MODE ? "y 63..76 with 42-cell passage x 0..6, z 0..1, y 64..66"
                : "y 63..68 with 28-cell passage x 0..6, z 0..1, y 64..65")
            .append("; clock frozen until ordinary bucket command\"");
        if (THREAT_WATER_RETREAT_MODE) json.append(",\n  \"fixtureGrants\":\"stored_weapons_and_bucket_materials_with_bedrock_water_roof_and_NoAI_mobs_and_helmeted_distant_zombie\"");
        if (NEARBY_WOOD_MODE) {
            json.append(",\n  \"nearbyWoodWalkArrivalInputEvidence\":");
            appendNearbyWoodWalkInputEvidence(json);
            if (MEADOW_BENCHMARK) {
                json.append(",\n  \"nearbyWoodMeadowLaunchHandoffEvidence\":");
                appendNearbyWoodMeadowLaunchHandoffEvidence(json);
            }
        }
        if (IRON_PICKAXE_MODE) {
            json.append(",\n  \"ironPickaxeFixtureProvidedStock\":");
            appendStringIntMap(json, IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                ? Map.of() : Map.of("minecraft:crafting_table", 1));
            json.append(",\n  \"ironPickaxeGoalCommand\":\"!lk get iron_pickaxe\"")
                .append(",\n  \"ironPickaxeExpectedOutput\":1")
                .append(",\n  \"ironPickaxeNativeTableOpenings\":").append(serverTableOpenings)
                .append(",\n  \"ironPickaxeNativeFurnaceOpenings\":").append(serverFurnaceOpenings)
                .append(",\n  \"ironPickaxeDeepslateFixtureInitialBlockCount\":4")
                .append(",\n  \"ironPickaxeDeepslateFixturePositions\":[\"16,20,4\",\"17,20,4\",\"18,20,4\",\"19,20,4\"]")
                .append(",\n  \"ironPickaxeDeepslateFixtureRemainingBlockCount\":")
                .append(latestSnapshot == null ? -1 : latestSnapshot.ironPickaxeDeepslateRemaining)
                .append(",\n  \"ironPickaxeDeepslateFixtureMinedBlockCount\":")
                .append(latestSnapshot == null ? -1 : 4 - latestSnapshot.ironPickaxeDeepslateRemaining)
                .append(",\n  \"ironPickaxePlannerProbe\":")
                .append(ironPickaxePlannerProbeEvidence == null ? "null" : ironPickaxePlannerProbeEvidence.toString());
            if (IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE) {
                json.append(",\n  \"ironPickaxeEmptyDistantWoodFixture\":true")
                    .append(",\n  \"ironPickaxeFirstVerifiedServerStock\":");
                if (ironPickaxeFirstVerifiedServerStock == null) json.append("null");
                else appendStringIntMap(json, ironPickaxeFirstVerifiedServerStock);
                json.append(",\n  \"ironPickaxeFirstVerifiedServerStockTick\":");
                if (ironPickaxeFirstVerifiedServerStockTick < 0) json.append("null");
                else json.append(ironPickaxeFirstVerifiedServerStockTick);
                json.append(",\n  \"ironPickaxeFixtureOakLogPositions\":[");
                for (int index = 0; index < IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT; index++) {
                    if (index > 0) json.append(',');
                    json.append('\"').append(IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + index)
                        .append(',').append(PLAYER_Y + (MEADOW_BENCHMARK ? 3 : 0)).append(",0\"");
                }
                json.append(']');
            }
        }
        if (COAL_RECOVERY_MODE) {
            BlockPos accessibleCoal = coalRecoveryAccessibleOrePosition();
            json.append(",\n  \"coalStartSurface\":\"").append(COAL_START_SURFACE).append("\"")
                .append(",\n  \"coalStartSurfaceInitialServerFeetY\":").append(Double.isFinite(coalInitialServerFeetY) ? Double.toString(coalInitialServerFeetY) : "null")
                .append(",\n  \"coalStartSurfaceInitialBlockCount\":9")
                .append(",\n  \"coalStartSurfaceRemainingBlockCount\":").append(latestSnapshot == null ? -1 : latestSnapshot.coalStartSurfaceRemaining);
            json.append(",\n  \"coalRecoveryFixtureProvidedStock\":{\"minecraft:stone_pickaxe\":1}")
                .append(",\n  \"coalRecoveryGoalCommand\":\"!lk get coal\"")
                .append(",\n  \"coalRecoveryExpectedOutput\":1")
                .append(",\n  \"coalRecoveryEncasedOrePosition\":\"6,").append(PLAYER_Y).append(",2\"")
                .append(",\n  \"coalRecoveryAccessibleOrePosition\":\"").append(blockPosition(accessibleCoal)).append("\"")
                .append(",\n  \"coalRecoveryEncasedOreInitialBlockCount\":1")
                .append(",\n  \"coalRecoveryEncasedOreRemainingBlockCount\":")
                .append(latestSnapshot == null ? -1 : latestSnapshot.coalRecoveryEncasedOreRemaining)
                .append(",\n  \"coalRecoveryBlockedTargetRejected\":").append(coalRecoveryTargetRejected())
                .append(",\n  \"coalRecoveryAccessibleOreInitialBlockCount\":1")
                .append(",\n  \"coalRecoveryAccessibleOreRemainingBlockCount\":")
                .append(latestSnapshot == null ? -1 : latestSnapshot.coalRecoveryAccessibleOreRemaining)
                .append(",\n  \"coalRecoveryAccessibleOreMined\":")
                .append(latestSnapshot != null && latestSnapshot.coalRecoveryAccessibleOreRemaining == 0)
                .append(",\n  \"coalRecoveryCompletionHealth\":")
                .append(latestSnapshot == null ? -1.0F : latestSnapshot.health)
                .append(",\n  \"coalRecoveryCompletionInventory\":");
            appendStringIntMap(json, latestSnapshot == null ? Map.of() : latestSnapshot.inventory);
            json.append(",\n  \"coalRecoveryOutcomeVerified\":").append(coalRecoveryOutcomeObserved());
            if (COAL_RAISED_FULL_DROP_MODE) {
                json.append(",\n  \"coalDropDevelopmentCase\":\"raised_full_departure_to_floor\"")
                    .append(",\n  \"coalDropBaseFloorY\":").append(FIXTURE_FLOOR_Y)
                    .append(",\n  \"coalDropBaseFloorBlock\":\"minecraft:bedrock\"")
                    .append(",\n  \"coalDropPlatformY\":").append(coalStartSurfaceY())
                    .append(",\n  \"coalDropPlatformBlock\":\"minecraft:bedrock\"")
                    .append(",\n  \"coalDropPlatformFootprint\":\"x=-1..1,z=-1..1\"")
                    .append(",\n  \"coalDropPlatformInitialBlockCount\":")
                    .append(coalDropInitialServerPosition == null ? -1 : coalDropInitialServerPosition.platformBlockCount)
                    .append(",\n  \"coalDropPlatformRemainingBlockCount\":")
                    .append(latestSnapshot == null ? -1 : latestSnapshot.coalStartSurfaceRemaining)
                    .append(",\n  \"coalDropPlatformPreserved\":")
                    .append(latestSnapshot != null && latestSnapshot.coalStartSurfaceRemaining == 9)
                    .append(",\n  \"coalDropInitialServerPosition\":");
                if (coalDropInitialServerPosition == null) {
                    json.append("null");
                } else {
                    json.append("{\"x\":").append(coalDropInitialServerPosition.x)
                        .append(",\"feetY\":").append(coalDropInitialServerPosition.feetY)
                        .append(",\"z\":").append(coalDropInitialServerPosition.z)
                        .append(",\"platformBlockCount\":").append(coalDropInitialServerPosition.platformBlockCount)
                        .append(",\"serverTick\":").append(coalDropInitialServerPosition.serverTick).append('}');
                }
                json.append(",\n  \"coalDropClientCompletedEdgeObserved\":").append(coalDropCompletedEdge != null)
                    .append(",\n  \"coalDropClientCompletedEdge\":");
                if (coalDropCompletedEdge == null) {
                    json.append("null");
                } else {
                    CoalDropEdge edge = coalDropCompletedEdge;
                    json.append("{\"source\":{\"x\":").append(edge.sourceX)
                        .append(",\"feetY\":").append(edge.sourceFeetY16 / 16.0)
                        .append(",\"z\":").append(edge.sourceZ)
                        .append("},\"destination\":{\"x\":").append(edge.destinationX)
                        .append(",\"feetY\":").append(edge.destinationFeetY16 / 16.0)
                        .append(",\"z\":").append(edge.destinationZ)
                        .append("},\"movement\":\"").append(escape(edge.movement))
                        .append("\",\"pathIndex\":").append(edge.pathIndex)
                        .append(",\"clientTick\":").append(edge.clientTick).append('}');
                }
                json.append(",\n  \"coalDropServerHeightCheckpointObserved\":").append(coalDropServerCheckpoint != null)
                    .append(",\n  \"coalDropServerHeightCheckpoint\":");
                if (coalDropServerCheckpoint == null) {
                    json.append("null");
                } else {
                    json.append("{\"x\":").append(coalDropServerCheckpoint.x)
                        .append(",\"feetY\":").append(coalDropServerCheckpoint.feetY)
                        .append(",\"z\":").append(coalDropServerCheckpoint.z)
                        .append(",\"onGround\":").append(coalDropServerCheckpoint.onGround)
                        .append(",\"serverTick\":").append(coalDropServerCheckpoint.serverTick).append('}');
                }
                json.append(",\n  \"coalDropServerHeightTransitionFrom65To64\":")
                    .append(coalDropInitialServerPosition != null && coalDropServerCheckpoint != null
                        && Math.abs(coalDropInitialServerPosition.feetY - (PLAYER_Y + 1.0)) < 0.0001
                        && Math.abs(coalDropServerCheckpoint.feetY - PLAYER_Y) < 0.0001
                        && coalDropServerCheckpoint.onGround
                        && !isCoalDropPlatformCell((int) Math.floor(coalDropServerCheckpoint.x),
                            (int) Math.floor(coalDropServerCheckpoint.z)))
                    .append(",\n  \"coalDropCompletionCoalCount\":")
                    .append(latestSnapshot == null ? -1 : latestSnapshot.count("minecraft:coal"))
                    .append(",\n  \"coalDropCompletionServerTick\":")
                    .append(latestSnapshot == null ? -1 : latestSnapshot.serverTick)
                    .append(",\n  \"coalDropCompletionHealth\":")
                    .append(latestSnapshot == null ? -1.0F : latestSnapshot.health)
                    .append(",\n  \"coalDropIdleCompletionObserved\":")
                    .append(LodekeeperClient.engine != null && LodekeeperClient.engine.status().startsWith("idle"))
                    .append(",\n  \"coalDropOutcomeVerified\":").append(coalRecoveryOutcomeObserved());
            }
            if (MIXED_NAVIGATION_COURSE) {
                int observedMask = latestSnapshot == null ? coalNavigationCourseObservedMask
                    : latestSnapshot.coalNavigationCourseObservedMask;
                int mismatchCount = latestSnapshot == null ? -1 : latestSnapshot.coalNavigationCourseMismatchCount;
                json.append(",\n  \"navigationCourse\":\"mixed\"")
                    .append(",\n  \"coalNavigationCourseInitialServerFeetX\":").append(Double.isFinite(coalNavigationInitialServerFeetX) ? Double.toString(coalNavigationInitialServerFeetX) : "null")
                    .append(",\n  \"coalNavigationCourseInitialServerFeetZ\":").append(Double.isFinite(coalNavigationInitialServerFeetZ) ? Double.toString(coalNavigationInitialServerFeetZ) : "null")
                    .append(",\n  \"coalNavigationFenceGeometry\":").append(coalNavigationFenceGeometryEvidence == null ? "null" : coalNavigationFenceGeometryEvidence.toString())
                    .append(",\n  \"coalNavigationRouteDiagnostics\":").append(coalNavigationRouteDiagnostics == null ? "null" : coalNavigationRouteDiagnostics.toString())
                    .append(",\n  \"coalNavigationCourseAccessibleOrePosition\":\"").append(blockPosition(accessibleCoal)).append("\"")
                    .append(",\n  \"coalNavigationCourseCheckpointExpectedMask\":").append(coalNavigationExpectedMask())
                    .append(",\n  \"coalNavigationCourseCheckpointObservedMask\":").append(observedMask)
                    .append(",\n  \"coalNavigationCourseAllCheckpointsVisited\":").append(observedMask == coalNavigationExpectedMask())
                    .append(",\n  \"coalNavigationCourseProtectedStateCount\":").append(coalNavigationExpectedStates.size())
                    .append(",\n  \"coalNavigationCourseProtectedStatesPreserved\":")
                    .append(mismatchCount < 0 ? -1 : coalNavigationExpectedStates.size() - mismatchCount)
                    .append(",\n  \"coalNavigationCourseStateMismatches\":").append(mismatchCount)
                    .append(",\n  \"coalNavigationCourseMinimumServerHealth\":")
                    .append(latestSnapshot == null ? coalNavigationCourseMinimumHealth : latestSnapshot.coalNavigationCourseMinimumHealth)
                    .append(",\n  \"coalNavigationCourseAllowBreaking\":true")
                    .append(",\n  \"coalNavigationCourseAllowBuilding\":false")
                    .append(",\n  \"coalNavigationCourseAllowParkour\":false")
                    .append(",\n  \"coalNavigationCourseStairEdgeCompleted\":").append(coalNavigationStairEdgeCompleted)
                    .append(",\n  \"coalNavigationCourseStairEdgeMovement\":\"").append(escape(coalNavigationStairEdgeMovement)).append("\"")
                    .append(",\n  \"coalNavigationCourseStairEdgeCompletedPathIndex\":").append(coalNavigationStairEdgePathIndex)
                    .append(",\n  \"coalNavigationCourseStairEdgeTraversalCount\":").append(coalNavigationStairEdgeCompleted ? 1 : 0)
                    .append(",\n  \"coalNavigationCourseLedgeEdgeCompleted\":").append(coalNavigationLedgeEdgeCompleted)
                    .append(",\n  \"coalNavigationCourseLedgeEdgeMovement\":\"").append(escape(coalNavigationLedgeEdgeMovement)).append("\"")
                    .append(",\n  \"coalNavigationCourseLedgeEdgeCompletedPathIndex\":").append(coalNavigationLedgeEdgePathIndex)
                    .append(",\n  \"coalNavigationCourseLedgeEdgeTraversalCount\":").append(coalNavigationLedgeEdgeCompleted ? 1 : 0)
                    .append(",\n  \"coalNavigationCourseCheckpoints\":[");
                for (int checkpointIndex = 0; checkpointIndex < COAL_NAVIGATION_CHECKPOINTS.size(); checkpointIndex++) {
                    if (checkpointIndex > 0) json.append(',');
                    CoalNavigationCheckpoint checkpoint = COAL_NAVIGATION_CHECKPOINTS.get(checkpointIndex);
                    json.append("{\"xCell\":").append(checkpoint.xCell)
                        .append(",\"zCell\":0,\"feetY16\":").append(checkpoint.feetY16)
                        .append(",\"feetY\":").append(checkpoint.feetY16 / 16.0)
                        .append(",\"firstServerTick\":").append(latestSnapshot == null
                            || latestSnapshot.coalNavigationCourseCheckpointServerTicks.size() <= checkpointIndex
                                ? -1 : latestSnapshot.coalNavigationCourseCheckpointServerTicks.get(checkpointIndex)).append('}');
                }
                json.append("]")
                    .append(",\n  \"coalNavigationCourseStairFlight\":{\"lower\":\"11,65,0;feet=66\",\"upper\":\"12,66,0;feet=67\",\"expectedMethod\":\"walk_up_stairs\"}")
                    .append(",\n  \"coalNavigationCourseOneBlockJumpLedge\":{\"approach\":\"15,66,0;feet=67\",\"landing\":\"16,67,0;feet=68\",\"upperBlocks\":[\"16,67,0\",\"17,67,0\",\"18,67,0\"]}")
                    .append(",\n  \"coalNavigationCourseHydrationWater\":\"5,64,-2\"")
                    .append(",\n  \"coalNavigationCourseWaterBedrockShell\":[\"5,65,-2\",\"5,63,-2\",\"5,64,-3\",\"5,64,-1\",\"6,64,-2\",\"4,64,-2\"]");
            }
        }
        if (STONECUTTING_MODE) {
            json.append(",\n  \"stonecuttingFixture\":true,\n  \"nativeRecipeType\":\"stonecutting\",\n  \"processingStation\":\"stonecutter\",\n  \"serverStonecutterOpenings\":")
                .append(serverStonecutterOpenings).append(",\n  \"stonecuttingFixtureProvidedStock\":");
            appendStringIntMap(json, cookingProvidedStock());
            if (STONECUTTING_DRAIN_MODE) {
                json.append(",\n  \"stonecuttingDrainStopAttempted\":").append(stonecuttingDrainStopAttempted)
                    .append(",\n  \"stonecuttingDrainStopInjected\":").append(stonecuttingDrainStopInjected)
                    .append(",\n  \"stonecuttingDrainOriginalCommandTarget\":").append(STONECUTTING_DRAIN_COMMAND_TARGET)
                    .append(",\n  \"stonecuttingDrainExpectedOutput\":0")
                    .append(",\n  \"stonecuttingDrainInputCountAtStop\":").append(stonecuttingDrainInputCountAtStop)
                    .append(",\n  \"stonecuttingDrainStopClientTick\":").append(stonecuttingDrainStopClientTick)
                    .append(",\n  \"stonecuttingDrainServerObservationTickBeforeStop\":").append(stonecuttingDrainStopServerTick)
                    .append(",\n  \"stonecuttingDrainFreshServerObservationTick\":")
                    .append(latestSnapshot == null ? -1 : latestSnapshot.serverTick)
                    .append(",\n  \"stonecuttingDrainRequiredObservationSequence\":").append(stonecuttingDrainRequiredObservationSequence)
                    .append(",\n  \"stonecuttingDrainLatestObservationSequence\":").append(latestObservationRequestSequence);
            }
        }
        if (COOKING_MODE) json.append(",\n  \"cookingStationFixture\":true,\n  \"cookingRecipeType\":\"")
            .append(cookingRecipeType()).append('\"');
        json.append(",\n  \"resourceInitiallyLoaded\":").append(resourceInitiallyLoaded)
            .append(",\n  \"explorationAttempts\":")
            .append(LodekeeperClient.engine == null ? 0 : LodekeeperClient.engine.explorationAttemptsMade());
        if (ANIMAL_MODE) {
            nativeAnimalEvidence.add("initialServerReceipt", nativeAnimalJson(nativeAnimalInitialReceipt));
            nativeAnimalEvidence.add("finalServerReceipt", nativeAnimalJson(nativeAnimalPublishedReceipt));
            json.append(",\n  \"nativeAnimal\":").append(nativeAnimalEvidence);
        }
        if (COOPERATIVE_MODE) json.append(",\n  \"nativeCooperative\": ").append(cooperativeEvidence);
        if (!nativePlayerPeerReceipt.isEmpty()) json.append(",\n  \"nativePlayerPeerFixture\": ").append(nativeAnimalJson(nativePlayerPeerReceipt));
        return json.append("\n}\n").toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void appendNearbyWoodWalkInputEvidence(StringBuilder json) {
        json.append("{\"evidenceAuthority\":\"client_bot_input_intent\"")
            .append(",\"serverTimingIncluded\":false")
            .append(",\"observationLimit\":").append(MAX_NEARBY_WOOD_WALK_OBSERVATIONS)
            .append(",\"navigationBackend\":\"").append(BARITONE_MODE ? "baritone" : "original").append("\"")
            .append(",\"baritoneMiningObserved\":").append(baritoneMiningObserved)
            .append(",\"eligibleWalkArrivalCount\":").append(nearbyWoodEligibleWalkArrivalCount)
            .append(",\"zeroForwardIntentArrivalCount\":").append(nearbyWoodZeroForwardIntentArrivalCount)
            .append(",\"positiveForwardIntentArrivalCount\":").append(nearbyWoodPositiveForwardArrivalCount)
            .append(",\"recordedObservationCount\":").append(nearbyWoodWalkObservations.size())
            .append(",\"observations\":[");
        for (int index = 0; index < nearbyWoodWalkObservations.size(); index++) {
            NearbyWoodWalkObservation observation = nearbyWoodWalkObservations.get(index);
            if (index > 0) json.append(',');
            json.append("{\"pathGeneration\":").append(observation.pathGeneration)
                .append(",\"previousPathIndex\":").append(observation.previousPathIndex)
                .append(",\"pathIndex\":").append(observation.pathIndex)
                .append(",\"reachedPathIndex\":").append(observation.reachedPathIndex)
                .append(",\"validatedPathIndex\":").append(observation.validatedPathIndex)
                .append(",\"clientTick\":").append(observation.clientTick)
                .append(",\"botInputForwardIntent\":").append(observation.forwardIntent)
                .append(",\"eligible\":true")
                .append(",\"incomingDirectionXZ\":[").append(observation.incomingX).append(',')
                .append(observation.incomingZ).append(']')
                .append(",\"outgoingDirectionXZ\":[").append(observation.outgoingX).append(',')
                .append(observation.outgoingZ).append(']')
                .append(",\"source\":");
            appendNearbyWoodWalkStep(json, observation.source);
            json.append(",\"reached\":");
            appendNearbyWoodWalkStep(json, observation.reached);
            json.append(",\"outgoing\":");
            appendNearbyWoodWalkStep(json, observation.outgoing);
            json.append('}');
        }
        json.append("]}");
    }

    private void appendNearbyWoodMeadowLaunchHandoffEvidence(StringBuilder json) {
        json.append("{\"evidenceAuthority\":\"client_player_position_velocity_and_ground_state\"")
            .append(",\"serverPhysicsTimingIncluded\":false")
            .append(",\"sampleRegistrationPhase\":\"first_end_client_tick\"")
            .append(",\"samplePhase\":\"start_client_tick_after_lodekeeper_engine_callback_before_client_physics\"")
            .append(",\"observerRegistered\":").append(nearbyWoodLaunchObserverRegistered)
            .append(",\"forcedHandoffProbe\":{\"kind\":\"pure_settled_predicate\",\"horizontalSpeed\":")
            .append(FORCED_PREPHYSICS_HANDOFF_SPEED)
            .append(",\"grounded\":true,\"settled\":")
            .append(isNearbyWoodMeadowLaunchHandoffSettled(LaunchApproach.ARRIVAL_RADIUS / 2,
                FORCED_PREPHYSICS_HANDOFF_SPEED, true)).append('}')
            .append(",\"observationLimit\":").append(MAX_NEARBY_WOOD_LAUNCH_HANDOFF_OBSERVATIONS)
            .append(",\"handoffCount\":").append(nearbyWoodLaunchHandoffCount)
            .append(",\"jumpHandoffCount\":").append(nearbyWoodLaunchJumpHandoffCount)
            .append(",\"settledHandoffCount\":").append(nearbyWoodLaunchSettledHandoffCount)
            .append(",\"unsettledHandoffCount\":").append(nearbyWoodLaunchUnsettledHandoffCount)
            .append(",\"maximumHorizontalSpeed\":").append(nearbyWoodLaunchMaximumHorizontalSpeed)
            .append(",\"settledContract\":{\"centerDistanceXZLessThan\":")
            .append(LaunchApproach.ARRIVAL_RADIUS)
            .append(",\"horizontalSpeedLessThan\":").append(LaunchApproach.SETTLED_SPEED)
            .append(",\"grounded\":true}")
            .append(",\"recordedObservationCount\":").append(nearbyWoodLaunchHandoffs.size())
            .append(",\"observations\":[");
        for (int index = 0; index < nearbyWoodLaunchHandoffs.size(); index++) {
            NearbyWoodLaunchHandoffObservation observation = nearbyWoodLaunchHandoffs.get(index);
            if (index > 0) json.append(',');
            json.append("{\"pathGeneration\":").append(observation.pathGeneration)
                .append(",\"previousPathIndex\":").append(observation.previousPathIndex)
                .append(",\"pathIndex\":").append(observation.pathIndex)
                .append(",\"sourcePathIndex\":").append(observation.sourcePathIndex)
                .append(",\"reachedPathIndex\":").append(observation.reachedPathIndex)
                .append(",\"outgoingPathIndex\":").append(observation.outgoingPathIndex)
                .append(",\"validatedPathIndex\":").append(observation.validatedPathIndex)
                .append(",\"clientTick\":").append(observation.clientTick)
                .append(",\"incomingMovement\":\"").append(escape(observation.incomingMovement)).append('"')
                .append(",\"outgoingMovement\":\"").append(escape(observation.outgoingMovement)).append('"')
                .append(",\"playerPosition\":[");
            appendFiniteJsonNumber(json, observation.playerX);
            json.append(',');
            appendFiniteJsonNumber(json, observation.playerY);
            json.append(',');
            appendFiniteJsonNumber(json, observation.playerZ);
            json.append("],\"horizontalVelocity\":[");
            appendFiniteJsonNumber(json, observation.velocityX);
            json.append(',');
            appendFiniteJsonNumber(json, observation.velocityZ);
            json.append("],\"reachedCenterOffset\":[");
            appendFiniteJsonNumber(json, observation.offsetX);
            json.append(',');
            appendFiniteJsonNumber(json, observation.offsetY);
            json.append(',');
            appendFiniteJsonNumber(json, observation.offsetZ);
            json.append("],\"centerDistanceXZ\":");
            appendFiniteJsonNumber(json, observation.centerDistanceXZ);
            json.append(",\"horizontalSpeed\":");
            appendFiniteJsonNumber(json, observation.horizontalSpeed);
            json.append(",\"grounded\":").append(observation.grounded)
                .append(",\"settled\":").append(observation.settled).append('}');
        }
        json.append("]}");
    }

    private static void appendFiniteJsonNumber(StringBuilder json, double value) {
        json.append(Double.isFinite(value) ? Double.toString(value) : "null");
    }

    private static void appendNearbyWoodTargetObservation(StringBuilder json,
                                                          NearbyWoodTargetObservation observation) {
        if (observation == null) {
            json.append("null");
            return;
        }
        json.append("{\"position\":[").append(observation.x).append(',').append(observation.y).append(',')
            .append(observation.z).append("],\"clientTick\":").append(observation.clientTick)
            .append(",\"elapsedMillisFromCommand\":").append(observation.elapsedMillisFromCommand).append('}');
    }

    private static void appendNearbyWoodServerRemovalObservation(StringBuilder json,
                                                                  NearbyWoodServerRemovalObservation observation) {
        if (observation == null) {
            json.append("null");
            return;
        }
        json.append("{\"authority\":\"integrated_server_snapshot\",\"serverTick\":")
            .append(observation.serverTick).append(",\"worldTime\":").append(observation.worldTime)
            .append(",\"clientObservedClientTick\":").append(observation.clientObservedClientTick).append('}');
    }

    private static void appendNearbyWoodLocalFixtureShell(StringBuilder json,
                                                          NearbyWoodLocalFixtureSnapshot fixture) {
        if (fixture == null) {
            json.append("null");
            return;
        }
        List<String> faces = List.of("down", "up", "north", "south", "east", "west");
        json.append('{');
        for (int index = 0; index < faces.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"').append(faces.get(index)).append("\":")
                .append(index < fixture.decoyShellBedrockFacesPresent.size()
                    && fixture.decoyShellBedrockFacesPresent.get(index));
        }
        json.append('}');
    }

    private static void appendNearbyWoodWalkStep(StringBuilder json, NearbyWoodWalkStepSnapshot step) {
        json.append("{\"pathIndex\":").append(step.pathIndex)
            .append(",\"x\":").append(step.x)
            .append(",\"feetY16\":").append(step.feetY16)
            .append(",\"z\":").append(step.z)
            .append(",\"movement\":\"").append(escape(step.movement)).append("\"")
            .append(",\"actions\":[");
        for (int index = 0; index < step.actions.size(); index++) {
            NearbyWoodWalkActionSnapshot action = step.actions.get(index);
            if (index > 0) json.append(',');
            json.append("{\"type\":\"").append(escape(action.type)).append("\"")
                .append(",\"x\":").append(action.x)
                .append(",\"y\":").append(action.y)
                .append(",\"z\":").append(action.z)
                .append(",\"token\":").append(action.token).append('}');
        }
        json.append("]}");
    }

    private static void appendStringIntMap(StringBuilder json, Map<String, Integer> values) {
        json.append('{');
        int index = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (index++ > 0) json.append(',');
            json.append('\"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
        }
        json.append('}');
    }

    private static void appendStringStringMap(StringBuilder json, Map<String, String> values) {
        json.append('{');
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (index++ > 0) json.append(',');
            json.append('"').append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append('"');
        }
        json.append('}');
    }

    private static String blockPosition(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findField(String ownerName, String name) {
        try {
            return findField(Class.forName(ownerName), name);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean navigationMovementReflectionAvailable() {
        return ENGINE_MOVEMENT_FIELD != null && MOVEMENT_PATH_FIELD != null
            && MOVEMENT_PATH_INDEX_FIELD != null && MOVEMENT_VALIDATED_PATH_INDEX_FIELD != null;
    }

    private static boolean nearbyWoodWalkObservationReflectionAvailable() {
        return navigationMovementReflectionAvailable() && MOVEMENT_INPUT_FIELD != null
            && BOT_INPUT_FORWARD_FIELD != null;
    }

    private static String verificationMode() {
        if (COOPERATIVE_MODE) return "native_cooperative_" + COOPERATIVE_SCENARIO;
        if (ANIMAL_MODE) return "native_animal_" + ANIMAL_SCENARIO;
        if (invalidShieldScenario()) return "invalid_shield_scenario";
        if (SHIELD_MODE) return "native_shield_" + SHIELD_SCENARIO;
        if (WORLD_POLICY_MODE) return "native_world_policy";
        if (SETTINGS_UI_MODE) return "native_settings_ui";
        if (invalidStationRoomTunnelMode()) return "invalid_station_room_tunnel";
        if (WORKBENCH_MODE && (!BARITONE_MODE || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                || System.getProperty("lodekeeper.verify.naturalGoal") != null)) return "invalid_prepared_safety_workbench";
        if (("pursuit".equals(PREPARED_SAFETY_MODE) || "pursuit-tool".equals(PREPARED_SAFETY_MODE))
                && (!BARITONE_MODE || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                    || System.getProperty("lodekeeper.verify.naturalGoal") != null)) {
            return "pursuit-tool".equals(PREPARED_SAFETY_MODE)
                ? "invalid_moving_food_pursuit_tool" : "invalid_moving_food_pursuit";
        }
        if ("air".equals(PREPARED_SAFETY_MODE)
                && (!BARITONE_MODE || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                    || System.getProperty("lodekeeper.verify.naturalGoal") != null)) return "invalid_prepared_safety_air";
        if (invalidContactLowHealthMode()) return "invalid_threat_contact_low_health";
        if (invalidThreatContactMode()) return "invalid_threat_contact";
        if (invalidThreatCreeperContactMode()) return "invalid_threat_creeper_contact";
        if (THREAT_WATER_RETREAT_MODE && (!BARITONE_MODE || !"threat".equals(PREPARED_SAFETY_MODE)
                || !List.of("1.21.1", "26.3").contains(VerificationApi.minecraftVersion())
                || System.getProperty("lodekeeper.verify.naturalGoal") != null)) return "invalid_threat_water_retreat";
        if (MINING_ZERO_YIELD_MODE && !MINING_REQUEST_LIMIT_MODE) return "invalid_mining_zero_yield";
        if (MINING_REQUEST_LIMIT_MODE && (!BARITONE_MODE || !BULK_WOOD_MODE
                || System.getProperty("lodekeeper.verify.naturalGoal") != null)) return "invalid_mining_request_limit";
        if (PREPARED_SAFETY_MODE != null
                && !PREPARED_SAFETY_MODE.equals("equipment") && !PREPARED_SAFETY_MODE.equals("offhand")
                && !PREPARED_SAFETY_MODE.equals("threat") && !PREPARED_SAFETY_MODE.equals("pursuit")
                && !PREPARED_SAFETY_MODE.equals("pursuit-tool")
                && !PREPARED_SAFETY_MODE.equals("station_room") && !PREPARED_SAFETY_MODE.equals("air") && !WORKBENCH_MODE && !HELD_FUEL_MODE) {
            return "invalid_prepared_safety_mode";
        }
        if (NEARBY_WOOD_TERRAIN.equals("local_decoy") && !NEARBY_WOOD_MODE) {
            return "invalid_nearby_wood_local_decoy_requires_nearby_wood";
        }
        if (NEARBY_WOOD_MODE && !(NEARBY_WOOD_TERRAIN.equals("flat")
                || NEARBY_WOOD_TERRAIN.equals("meadow") || NEARBY_WOOD_TERRAIN.equals("local_decoy"))) {
            return "invalid_nearby_wood_terrain";
        }
        if (IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE && !IRON_PICKAXE_MODE) {
            return "invalid_iron_pickaxe_empty_distant_wood_requires_iron_pickaxe";
        }
        if (COAL_RAISED_FULL_DROP_MODE && !COAL_RECOVERY_MODE) return "invalid_raised_full_requires_coal_recovery";
        if (selectedFixtureModes() > 1) return "invalid_conflicting_modes";
        if (THREAT_STAIRCASE_MODE) return "prepared_safety_threat_staircase";
        if (STATION_ROOM_APPROACH_MODE) return "prepared_safety_station_room_approach";
        if (STATION_ROOM_TUNNEL_MODE) return "prepared_safety_station_room_tunnel";
        if (PREPARED_SAFETY_MODE != null) return CONTACT_LOW_HEALTH_MODE ? "prepared_safety_low_health_owned_hop" : THREAT_CREEPER_CONTACT_MODE ? "prepared_safety_threat_creeper_contact" : CONTACT_MANUAL_INPUT_MODE ? "prepared_safety_manual_defense_takeover" : THREAT_CONTACT_MODE ? "prepared_safety_live_contact_defense" : THREAT_WATER_RETREAT_MODE
            ? "prepared_safety_threat_water_retreat" : "prepared_safety_" + PREPARED_SAFETY_MODE;
        if (NAVIGATION_COURSE != null && !MIXED_NAVIGATION_COURSE) return "invalid_navigation_course";
        if (MIXED_NAVIGATION_COURSE && !COAL_RECOVERY_MODE) return "invalid_navigation_course_requires_coal_recovery";
        if (MIXED_NAVIGATION_COURSE && !COAL_START_SURFACE.equals("full")) return "invalid_navigation_course_requires_full_surface";
        if (COOKING_MODE && !isSupportedCookingStationMode()) return "invalid_cooking_station";
        if (STONECUTTING_DRAIN_MODE && !STONECUTTING_MODE) return "invalid_stonecutting_drain";
        if (STONECUTTING_DRAIN_MODE) return "stonecutting_drain";
        if (STONECUTTING_MODE) return "stonecutting";
        if (COOKING_MODE) return "cooking_" + COOKING_STATION_MODE;
        if (BULK_WOOD_MODE) return MINING_REQUEST_LIMIT_MODE
            ? MINING_ZERO_YIELD_MODE ? "bulk_wood_mining_zero_yield" : "bulk_wood_mining_request_limit" : "bulk_wood";
        if (NEARBY_WOOD_LOCAL_DECOY_MODE) return "nearby_wood_local_decoy";
        if (NEARBY_WOOD_MODE) return "nearby_wood";
        if (IRON_PICKAXE_MODE) return IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
            ? "iron_pickaxe_empty_distant_wood" : "iron_pickaxe";
        if (COAL_RECOVERY_MODE) return MIXED_NAVIGATION_COURSE ? "coal_recovery_mixed_navigation"
            : COAL_RAISED_FULL_DROP_MODE ? "coal_recovery_raised_full_drop" : "coal_recovery";
        if (DIAMOND_BOOTSTRAP_MODE) return "diamond_boots";
        return EXPLORATION_MODE ? "exploration" : "default";
    }

    private static int selectedFixtureModes() {
        return (COOPERATIVE_MODE ? 1 : 0) + (ANIMAL_MODE ? 1 : 0) + (WORLD_POLICY_MODE ? 1 : 0) + (EXPLORATION_MODE ? 1 : 0) + (DIAMOND_BOOTSTRAP_MODE ? 1 : 0)
            + (NEARBY_WOOD_MODE ? 1 : 0) + (IRON_PICKAXE_MODE ? 1 : 0) + (COAL_RECOVERY_MODE ? 1 : 0) + (BULK_WOOD_MODE ? 1 : 0)
            + (COOKING_MODE ? 1 : 0) + (STONECUTTING_MODE ? 1 : 0) + (PREPARED_SAFETY_MODE != null ? 1 : 0);
    }

    private record NearbyWoodWalkActionSnapshot(String type, int x, int y, int z, int token) { }

    private record NearbyWoodWalkStepSnapshot(int pathIndex, int x, int feetY16, int z,
                                               String movement,
                                               List<NearbyWoodWalkActionSnapshot> actions) {
        private NearbyWoodWalkStepSnapshot {
            actions = List.copyOf(actions);
        }
    }

    private record NearbyWoodWalkObservation(int pathGeneration, int previousPathIndex,
                                              int pathIndex, int reachedPathIndex,
                                              int validatedPathIndex, int clientTick,
                                              float forwardIntent,
                                              int incomingX, int incomingZ,
                                              int outgoingX, int outgoingZ,
                                              NearbyWoodWalkStepSnapshot source,
                                              NearbyWoodWalkStepSnapshot reached,
                                              NearbyWoodWalkStepSnapshot outgoing) { }

    private record NearbyWoodLaunchHandoffObservation(int pathGeneration, int previousPathIndex,
                                                       int pathIndex, int sourcePathIndex,
                                                       int reachedPathIndex, int outgoingPathIndex,
                                                       int validatedPathIndex, int clientTick,
                                                       String incomingMovement, String outgoingMovement,
                                                       double playerX, double playerY, double playerZ,
                                                       double velocityX, double velocityZ,
                                                       double offsetX, double offsetY, double offsetZ,
                                                       double centerDistanceXZ, double horizontalSpeed,
                                                       boolean grounded, boolean settled) { }

    private record NearbyWoodTargetObservation(int x, int y, int z, int clientTick,
                                               long elapsedMillisFromCommand) { }

    private record NearbyWoodServerRemovalObservation(int serverTick, long worldTime,
                                                      int clientObservedClientTick) { }

    private record NearbyWoodLocalFixtureSnapshot(boolean visibleOakLogPresent,
                                                   boolean decoyOakLogPresent,
                                                   List<Boolean> decoyShellBedrockFacesPresent) {
        private NearbyWoodLocalFixtureSnapshot {
            decoyShellBedrockFacesPresent = List.copyOf(decoyShellBedrockFacesPresent);
        }

        boolean decoyEnclosureIntact() {
            return decoyShellBedrockFacesPresent.size() == 6
                && decoyShellBedrockFacesPresent.stream().allMatch(Boolean::booleanValue);
        }
    }

    private record ServerInventorySnapshot(Map<String, Integer> counts, Map<String, Integer> storageCounts,
                                           List<Integer> woodenAxeRemainingDurability) {
        private ServerInventorySnapshot {
            counts = Map.copyOf(counts);
            storageCounts = Map.copyOf(storageCounts);
            woodenAxeRemainingDurability = List.copyOf(woodenAxeRemainingDurability);
        }
    }

    private record CoalDropStartingPosition(double x, double feetY, double z,
                                            int platformBlockCount, int serverTick) { }

    private record CoalDropEdge(int sourceX, int sourceFeetY16, int sourceZ,
                                int destinationX, int destinationFeetY16, int destinationZ,
                                String movement, int pathIndex, int clientTick) { }

    private record CoalDropServerCheckpoint(double x, double feetY, double z, boolean onGround, int serverTick) { }

    private record CoalNavigationCheckpoint(int xCell, int feetY16) { }

    private record ServerSnapshot(int serverTick, long worldTime, Map<String, Integer> inventory,
                                  List<Integer> woodenAxeRemainingDurability, int ironPickaxeDeepslateRemaining,
                                  int coalRecoveryEncasedOreRemaining, int coalRecoveryAccessibleOreRemaining, int coalStartSurfaceRemaining,
                                  int coalNavigationCourseMismatchCount, int coalNavigationCourseObservedMask,
                                  float coalNavigationCourseMinimumHealth,
                                  List<Integer> coalNavigationCourseCheckpointServerTicks,
                                  NearbyWoodLocalFixtureSnapshot nearbyWoodLocalFixture,
                                  Map<String, Integer> storageInventory,
                                  Map<String, String> equippedItems, boolean serverCursorEmpty,
                                  Map<String, String> preparedSafetyThreatReceipt,
                                  Map<String, String> preparedSafetyStationRoomReceipt,
                                  Map<String, String> preparedSafetyPursuitReceipt,
                                  Map<String, String> preparedSafetyAirReceipt,
                                  Map<String, String> preparedSafetyWorkbenchReceipt,
                                  Map<String, String> preparedSafetyHeldFuelReceipt,
                                  Map<String, String> worldPolicyServerReceipt,
                                  float health, int foodLevel,
                                  String difficulty, double x, double y, double z) {
        private ServerSnapshot {
            inventory = Map.copyOf(inventory);
            storageInventory = Map.copyOf(storageInventory);
            woodenAxeRemainingDurability = List.copyOf(woodenAxeRemainingDurability);
            coalNavigationCourseCheckpointServerTicks = List.copyOf(coalNavigationCourseCheckpointServerTicks);
            equippedItems = Map.copyOf(equippedItems);
            preparedSafetyThreatReceipt = Map.copyOf(preparedSafetyThreatReceipt);
            preparedSafetyStationRoomReceipt = Map.copyOf(preparedSafetyStationRoomReceipt);
            preparedSafetyPursuitReceipt = Map.copyOf(preparedSafetyPursuitReceipt);
            preparedSafetyAirReceipt = Map.copyOf(preparedSafetyAirReceipt);
            preparedSafetyWorkbenchReceipt = Map.copyOf(preparedSafetyWorkbenchReceipt);
            preparedSafetyHeldFuelReceipt = Map.copyOf(preparedSafetyHeldFuelReceipt);
            worldPolicyServerReceipt = Map.copyOf(worldPolicyServerReceipt);
        }
        int count(String id) { return inventory.getOrDefault(id, 0); }
        int storageCount(String id) { return storageInventory.getOrDefault(id, 0); }
        int heldCount(String id) { return count(id); }
        boolean inventoryEmpty() { return inventory.isEmpty(); }
    }

    private record CaseResult(String name, String item, int expected, int observed, boolean inventoryEmptyAtStart,
                              boolean requiresEmptyAtStart, boolean passed, int clientTicks, long worldTicks, long elapsedMillis, String engineStatus,
                              String detail, String screenshot, float health, String difficulty, boolean tableOpenedDuringCase,
                              boolean furnaceOpenedDuringCase, int ironPickaxeCount,
                              int foodLevelAtStart, int foodLevelObserved, int breadAtStart, int breadObserved,
                              double x, double y, double z, Map<String, Integer> serverInventory,
                              Map<String, Integer> storageInventory,
                              List<Integer> woodenAxeRemainingDurability, int completionClientTick,
                              long completionWorldTick, String cookingStation, String cookingRecipeType,
                              Map<String, Integer> initialResources, boolean correctCookingStationMenuOpenedDuringCase,
                              int initialRawInputCount, int finalRawInputCount, int initialCoalCount, int finalCoalCount,
                              int initialStationItemCount, int finalStationItemCount,
                              Map<String, String> serverEquipment, Map<String, String> initialEquipment,
                              boolean cursorEmpty, boolean initialCursorEmpty,
                              boolean noMaintenanceQueuedBeforeForeground,
                              boolean maintainedReservationObservedBeforeForeground,
                              boolean maintainedReservationPresentAtCompletion,
                              Map<String, String> initialThreatReceipt,
                              Map<String, String> serverThreatReceipt,
                              Map<String, String> initialStationRoomReceipt,
                              Map<String, String> serverStationRoomReceipt,
                              Map<String, String> initialPursuitReceipt,
                              Map<String, String> serverPursuitReceipt,
                              Map<String, String> initialAirReceipt,
                              Map<String, String> serverAirReceipt,
                              boolean airRecoveryObserved, boolean airRecoveryCompletedBeforeCraft,
                              int airRecoveryObservedClientTick, int airRecoveryTransitionClientTick,
                              int airRecoveryCompletionClientTick, int airRecoveryCompletionServerTick,
                              long airRecoveryTransitionObservationSequence,
                              int airRecoveryTableOpeningsAtTransition, int airRecoveryBucketCountAtCompletion,
                              String airRecoveryEndEngineStatus,
                              Map<String, String> airRecoveryCompletionReceipt,
                              boolean navigationStopped) {
        private CaseResult {
            serverInventory = Map.copyOf(serverInventory);
            storageInventory = Map.copyOf(storageInventory);
            woodenAxeRemainingDurability = List.copyOf(woodenAxeRemainingDurability);
            initialResources = Map.copyOf(initialResources);
            serverEquipment = Map.copyOf(serverEquipment);
            initialEquipment = Map.copyOf(initialEquipment);
            initialThreatReceipt = Map.copyOf(initialThreatReceipt);
            serverThreatReceipt = Map.copyOf(serverThreatReceipt);
            initialStationRoomReceipt = Map.copyOf(initialStationRoomReceipt);
            serverStationRoomReceipt = Map.copyOf(serverStationRoomReceipt);
            initialPursuitReceipt = Map.copyOf(initialPursuitReceipt);
            serverPursuitReceipt = Map.copyOf(serverPursuitReceipt);
            initialAirReceipt = Map.copyOf(initialAirReceipt);
            serverAirReceipt = Map.copyOf(serverAirReceipt);
            airRecoveryCompletionReceipt = Map.copyOf(airRecoveryCompletionReceipt);
        }
    }
}
