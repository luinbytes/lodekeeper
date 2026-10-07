package dev.lodekeeper.fabric.modern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import dev.lodekeeper.core.AutomationSettings;
import dev.lodekeeper.core.SettingSpec;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Bounded config shared by local commands and the native settings screen. */
public final class LodekeeperConfig {
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lodekeeper.json");
    private static final AutomationSettings.Codec<LodekeeperConfig> CODEC = AutomationSettings.codec(LodekeeperConfig.class);
    public String prefix;
    public int searchRadius;
    public boolean allowExploration;
    public int explorationAttempts;
    public int explorationDistance;
    public int scanBlocksPerTick;
    public int actionTimeoutTicks;
    public float pauseBelowHealth;
    public boolean allowBreaking;
    public boolean allowBuilding;
    public boolean allowParkour;
    public boolean allowParkourPlace;
    public boolean allowSprint;
    public boolean allowDiagonalAscend;
    public boolean allowDiagonalDescend;
    public int maxFallHeight;
    public boolean allowWaterBucketFall;
    public boolean allowContainers;
    public boolean pauseOnScreen;
    public boolean autoEat;
    public boolean autoEquipArmor;
    public boolean autoDefend;
    public boolean autoUseShield;
    public boolean autoCraftShield;
    public int shieldIronReserve;
    public int shieldPlankReserve;
    public boolean optimizeWoodTools;
    public boolean avoidance;
    public int mobAvoidanceRadius;
    public int spawnerAvoidanceRadius;
    public int mineGoalUpdateTicks;
    public int pathInitialSearchMillis;
    public int pathInitialFailureMillis;
    public int pathContinuationSearchMillis;
    public int pathContinuationFailureMillis;
    public boolean recoverPlacedStations;
    public int stationRecoveryRange;
    public boolean backfill;
    public int backfillPendingLimit;
    public boolean backfillEquivalentStone;
    public boolean showPath;
    public boolean showSearch;
    public boolean showHud;
    public boolean showNextBreak;
    public boolean showNextPlace;
    public boolean showParkour;
    public boolean showClaims;
    public boolean showStations;
    public boolean showBackfill;
    public int visualizationDistance;
    public boolean debugLogging;
    public Map<String, String> navigationPreferences;

    public LodekeeperConfig() { CODEC.resetAll(this); }

    public static List<SettingSpec> specs() { return AutomationSettings.specs(); }
    public Object read(String key) { return CODEC.read(this, key); }
    public void write(String key, String value) { CODEC.write(this, key, value); }
    public void writeValue(String key, Object value) { CODEC.writeValue(this, key, value); }
    public void reset(String key) { CODEC.reset(this, key); }
    public void resetAll() { CODEC.resetAll(this); }

    public static LodekeeperConfig load() {
        LodekeeperConfig config = new LodekeeperConfig();
        if (Files.exists(PATH)) {
            try {
                if (Files.size(PATH) > MAX_CONFIG_BYTES) throw new IOException("config exceeds 256 KiB");
                JsonElement document = GSON.fromJson(Files.readString(PATH), JsonElement.class);
                if (document == null || !document.isJsonObject()) throw new IOException("config root must be an object");
                Map<String, Object> values = new LinkedHashMap<>();
                for (var entry : document.getAsJsonObject().entrySet())
                    values.put(entry.getKey(), GSON.fromJson(entry.getValue(), Object.class));
                CODEC.importValues(config, values, LodekeeperConfig::warn);
            } catch (IOException | RuntimeException ex) {
                warn("Config could not be read: " + ex.getMessage());
            }
        }
        config.sanitize();
        return config;
    }

    public void sanitize() { CODEC.sanitize(this, LodekeeperConfig::warn); }

    public void save() throws IOException {
        sanitize();
        byte[] encoded = GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_CONFIG_BYTES) throw new IOException("config exceeds 256 KiB");
        Files.createDirectories(PATH.getParent());
        Path temporary = PATH.resolveSibling("lodekeeper.json.tmp");
        Files.write(temporary, encoded);
        try { Files.move(temporary, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void warn(String message) { System.err.println("[Lodekeeper] " + message); }
}
