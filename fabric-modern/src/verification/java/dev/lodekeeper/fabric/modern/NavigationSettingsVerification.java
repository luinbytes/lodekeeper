package dev.lodekeeper.fabric.modern;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class NavigationSettingsVerification {
    private final Map<String, Object> original = new LinkedHashMap<>();
    private final Map<dev.lodekeeper.navigation.kernel.api.Settings.Setting<?>, Object> expected = new LinkedHashMap<>();
    private final Map<dev.lodekeeper.navigation.kernel.api.Settings.Setting<?>, Object> prior = new LinkedHashMap<>();
    private MovementController movement;
    private int drainTicks;
    private String failure;
    private JsonObject receipt;

    JsonObject advance(LodekeeperConfig config) throws IOException {
        if (receipt != null) return receipt;
        var client = net.minecraft.client.Minecraft.getInstance();
        if (movement == null) {
            if (client.player == null || !client.player.onGround()) return null;
            for (var spec : LodekeeperConfig.specs()) original.put(spec.key(), config.read(spec.key()));
            var settings = dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI.getSettings();
            expected.put(settings.allowParkour, true);
            expected.put(settings.allowParkourPlace, false);
            expected.put(settings.allowSprint, false);
            expected.put(settings.allowDiagonalAscend, true);
            expected.put(settings.allowDiagonalDescend, true);
            expected.put(settings.avoidance, true);
            expected.put(settings.mobAvoidanceRadius, 3);
            expected.put(settings.mobSpawnerAvoidanceRadius, 5);
            expected.put(settings.maxFallHeightNoWater, 2);
            expected.put(settings.mineGoalUpdateInterval, 42);
            expected.put(settings.primaryTimeoutMS, 150L);
            expected.put(settings.failureTimeoutMS, 900L);
            expected.put(settings.planAheadPrimaryTimeoutMS, 600L);
            expected.put(settings.planAheadFailureTimeoutMS, 1800L);
            expected.forEach((setting, value) -> prior.put(setting, setting.value));
            var actions = new PlayerActions(client);
            movement = new MovementController(client, config, actions, new BotInput(), new GameTerrain(client, config));
            try {
                config.allowParkour = true;
                config.allowBuilding = true;
                config.allowParkourPlace = false;
                config.allowSprint = false;
                config.allowDiagonalAscend = true;
                config.allowDiagonalDescend = true;
                config.avoidance = true;
                config.mobAvoidanceRadius = 3;
                config.spawnerAvoidanceRadius = 5;
                config.maxFallHeight = 2;
                config.mineGoalUpdateTicks = 42;
                config.pathInitialSearchMillis = 150;
                config.pathInitialFailureMillis = 900;
                config.pathContinuationSearchMillis = 600;
                config.pathContinuationFailureMillis = 1800;
                movement.start(client.player.blockPosition().offset(20, 0, 0), 0);
                for (var entry : expected.entrySet()) {
                    if (!entry.getValue().equals(entry.getKey().value))
                        throw new IOException("native navigation setting did not receive its configured value: " + entry.getKey().getName());
                }
            } catch (IOException | RuntimeException exception) {
                failure = exception.toString();
            } finally {
                try { movement.stop(); }
                catch (RuntimeException stopFailure) {
                    for (var entry : original.entrySet()) config.writeValue(entry.getKey(), entry.getValue());
                    throw stopFailure;
                }
            }
            return null;
        }
        boolean restoreConfig = false;
        try {
            if (!movement.finishCancellation()) {
                if (++drainTicks >= 100) throw new IOException("native navigation settings probe did not drain within 100 ticks");
                return null;
            }
            restoreConfig = true;
            for (var entry : prior.entrySet()) {
                if (!entry.getValue().equals(entry.getKey().value))
                    throw new IOException("native navigation setting lease was not restored: " + entry.getKey().getName());
            }
            if (failure != null) throw new IOException(failure);
            receipt = new JsonObject();
            receipt.addProperty("passed", true);
            receipt.addProperty("settingsChecked", expected.size());
            receipt.addProperty("nativeLeaseRestored", true);
            receipt.addProperty("drainTicks", drainTicks);
            receipt.addProperty("scope", "native launch settings and cancellation lease; no movement or hazard traversal claim");
            return receipt.deepCopy();
        } catch (IOException | RuntimeException exception) {
            restoreConfig = true;
            throw exception;
        } finally {
            if (restoreConfig)
                for (var entry : original.entrySet()) config.writeValue(entry.getKey(), entry.getValue());
        }
    }
}
