package dev.lodekeeper.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small bounded config, independent of optional UI libraries. */
public final class LodekeeperConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lodekeeper.json");
    public String prefix = "!lk ";
    public int searchRadius = 48;
    public int scanBlocksPerTick = 512;
    public int pathNodesPerTick = 128;
    public int pathNodeLimit = 16000;
    public int pathMillisPerTick = 2;
    public int actionTimeoutTicks = 1200;
    public float pauseBelowHealth = 6;
    public boolean allowBreaking = true;
    public boolean allowBuilding = true;
    public boolean allowParkour = false;
    public boolean allowContainers = false;
    public boolean pauseOnScreen = true;
    public boolean autoEat = true;

    public static LodekeeperConfig load() {
        LodekeeperConfig config = new LodekeeperConfig();
        if (Files.exists(PATH)) {
            try {
                if (Files.size(PATH) > 65536) throw new IOException("config exceeds 64 KiB");
                LodekeeperConfig parsed = GSON.fromJson(Files.readString(PATH), LodekeeperConfig.class);
                if (parsed != null) config = parsed;
            } catch (IOException | RuntimeException ex) {
                System.err.println("[Lodekeeper] Config could not be read: " + ex.getMessage());
            }
        }
        config.sanitize();
        return config;
    }
    public void sanitize() {
        if (prefix == null || prefix.isBlank() || prefix.length() > 16 || prefix.startsWith("/")) prefix = "!lk ";
        searchRadius = clamp(searchRadius, 8, 96);
        scanBlocksPerTick = clamp(scanBlocksPerTick, 32, 2048);
        pathNodesPerTick = clamp(pathNodesPerTick, 16, 512);
        pathNodeLimit = clamp(pathNodeLimit, 512, 64000);
        pathMillisPerTick = clamp(pathMillisPerTick, 1, 5);
        actionTimeoutTicks = clamp(actionTimeoutTicks, 100, 6000);
        if (!Float.isFinite(pauseBelowHealth)) pauseBelowHealth = 6;
        pauseBelowHealth = Math.max(1, Math.min(20, pauseBelowHealth));
    }
    public void save() throws IOException {
        sanitize();
        Files.createDirectories(PATH.getParent());
        Path temporary = PATH.resolveSibling("lodekeeper.json.tmp");
        Files.writeString(temporary, GSON.toJson(this));
        try { Files.move(temporary, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
