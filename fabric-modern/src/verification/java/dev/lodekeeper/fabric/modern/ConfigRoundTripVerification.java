package dev.lodekeeper.fabric.modern;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

final class ConfigRoundTripVerification {
    static JsonObject verify(LodekeeperConfig config) throws IOException {
        Map<String, Object> original = new LinkedHashMap<>();
        for (var spec : LodekeeperConfig.specs()) original.put(spec.key(), config.read(spec.key()));
        Map<String, String> preferences = new LinkedHashMap<>();
        for (int i = 0; i < 128; i++)
            preferences.put(String.format(java.util.Locale.ROOT, "%03d", i) + "<".repeat(61), ("<" + (char) 0).repeat(64));
        try {
            config.writeValue("allowBreaking", false);
            config.writeValue("navigationPreferences", preferences);
            config.save();
            long bytes = Files.size(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("lodekeeper.json"));
            LodekeeperConfig reloaded = LodekeeperConfig.load();
            if (bytes <= 65536 || bytes > 256 * 1024 || reloaded.allowBreaking
                    || !preferences.equals(reloaded.read("navigationPreferences")))
                throw new IOException("legal maximum map or disabled breaking did not survive native config round trip");
            for (var spec : LodekeeperConfig.specs()) {
                if (spec.key().equals("allowBreaking") || spec.key().equals("navigationPreferences")) continue;
                if (!original.get(spec.key()).equals(reloaded.read(spec.key())))
                    throw new IOException("native config round trip changed " + spec.key());
            }
            JsonObject receipt = new JsonObject();
            receipt.addProperty("passed", true);
            receipt.addProperty("preferenceEntries", 128);
            receipt.addProperty("serializedBytes", bytes);
            receipt.addProperty("allowBreakingAfterReload", reloaded.allowBreaking);
            receipt.addProperty("allOtherSettingsPreserved", true);
            return receipt;
        } finally {
            for (var entry : original.entrySet()) config.writeValue(entry.getKey(), entry.getValue());
            config.save();
        }
    }
}
