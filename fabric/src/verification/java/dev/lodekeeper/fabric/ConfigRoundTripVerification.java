package dev.lodekeeper.fabric;

import com.google.gson.JsonObject;
import dev.lodekeeper.core.NavigationPreferenceCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

final class ConfigRoundTripVerification {
    static JsonObject verify(LodekeeperConfig config) throws IOException {
        Map<String, Object> original = new LinkedHashMap<>();
        for (var spec : LodekeeperConfig.specs()) original.put(spec.key(), config.read(spec.key()));
        Map<String, String> preferences = nonDefaultPreferences();
        try {
            config.writeValue("allowBreaking", false);
            config.writeValue("navigationPreferences", preferences);
            config.save();
            long bytes = Files.size(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("lodekeeper.json"));
            LodekeeperConfig reloaded = LodekeeperConfig.load();
            if (bytes <= 0 || bytes > 256 * 1024 || reloaded.allowBreaking
                    || !preferences.equals(reloaded.read("navigationPreferences")))
                throw new IOException("complete navigation catalog or disabled breaking did not survive native config round trip");
            for (var spec : LodekeeperConfig.specs()) {
                if (spec.key().equals("allowBreaking") || spec.key().equals("navigationPreferences")) continue;
                if (!original.get(spec.key()).equals(reloaded.read(spec.key())))
                    throw new IOException("native config round trip changed " + spec.key());
            }
            JsonObject receipt = new JsonObject();
            receipt.addProperty("passed", true);
            receipt.addProperty("preferenceEntries", preferences.size());
            receipt.addProperty("serializedBytes", bytes);
            receipt.addProperty("allowBreakingAfterReload", reloaded.allowBreaking);
            receipt.addProperty("allOtherSettingsPreserved", true);
            return receipt;
        } finally {
            for (var entry : original.entrySet()) config.writeValue(entry.getKey(), entry.getValue());
            config.save();
        }
    }

    static Map<String, String> nonDefaultPreferences() {
        Map<String, String> preferences = new LinkedHashMap<>();
        for (var preference : NavigationPreferenceCatalog.entries()) {
            Object value = switch (preference.type()) {
                case BOOLEAN -> !Boolean.TRUE.equals(preference.defaultValue());
                case INTEGER -> (int) (preference.minimum() == ((Number) preference.defaultValue()).doubleValue()
                        ? preference.maximum() : preference.minimum());
                case DECIMAL -> preference.minimum() == ((Number) preference.defaultValue()).doubleValue()
                        ? preference.maximum() : preference.minimum();
            };
            preferences.put(preference.key(), preference.format(value));
        }
        Map<String, String> validated = NavigationPreferenceCatalog.validateOverrides(preferences);
        if (validated.size() != NavigationPreferenceCatalog.entries().size())
            throw new IllegalStateException("native config probe does not cover every navigation option");
        return validated;
    }
}
