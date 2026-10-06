package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ClaimBox;
import dev.lodekeeper.core.SettingSpec;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class NativeSettingsUiAccess implements SettingsUiVerification.Access {
    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final WorldProtection protection;
    private final Path claimsPath;
    private final Supplier<Screen> settingsScreen;
    private final Consumer<String> capture;
    private final Consumer<String> log;
    private final BooleanSupplier engineIdle;

    NativeSettingsUiAccess(MinecraftClient client, LodekeeperConfig config, Supplier<Screen> settingsScreen,
                           WorldProtection protection,
                           Consumer<String> capture, Consumer<String> log, BooleanSupplier engineIdle) {
        this.client = client;
        this.config = config;
        this.protection = protection;
        this.claimsPath = isolatedClaimsPath(client.runDirectory.toPath());
        this.settingsScreen = settingsScreen;
        this.capture = capture;
        this.log = log;
        this.engineIdle = engineIdle;
    }

    @Override public List<SettingsUiVerification.Widget> visibleChildren(Object value) {
        if (!(value instanceof Screen screen)) throw new IllegalStateException("active screen is not a Minecraft Screen");
        List<SettingsUiVerification.Widget> result = new ArrayList<>();
        for (var child : screen.children()) {
            if (!(child instanceof ClickableWidget widget)) continue;
            String message = widget.getMessage().getString();
            String text = widget instanceof TextFieldWidget field ? field.getText() : "";
            result.add(new SettingsUiVerification.Widget(widget, message, text,
                    new SettingsUiVerification.Bounds(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
                    widget instanceof TextFieldWidget, widget.active));
        }
        return result;
    }

    void submitSettingsChatCommand() {
        client.setScreen(new net.minecraft.client.gui.screen.ChatScreen("", false));
        String command = config.prefix + "config";
        for (int codePoint : command.codePoints().toArray()) dispatchCharacter(codePoint);
        dispatchKey(257, 0, 0);
        log.accept("[Lodekeeper UI verification] Submitted config command through the native chat screen Enter key");
    }

    @Override public Object currentScreen() { return client.currentScreen; }
    @Override public void openSettingsScreen() { client.setScreen(settingsScreen.get()); }
    @Override public boolean isClaimsScreen(Object screen) { return screen instanceof ClaimsScreen; }
    @Override public void resizeCurrentScreen(int width, int height) {
        if (client.currentScreen == null) throw new IllegalStateException("no screen to resize");
        client.currentScreen.resize(client, width, height);
    }
    @Override public String nativeEventReceipt() {
        return "Click(x,y,MouseInput(button=0,modifiers=0)) -> ParentElement.mouseClicked(click,false)/mouseReleased(click), "
                + "KeyInput(key,scanCode,modifiers) -> ParentElement.keyPressed(input), "
                + "CharInput(codePoint,modifiers=0) -> ParentElement.charTyped(input), Screen.resize(client,width,height)";
    }
    @Override public int screenWidth() { return client.currentScreen == null ? 0 : client.currentScreen.width; }
    @Override public int screenHeight() { return client.currentScreen == null ? 0 : client.currentScreen.height; }

    @Override public int focusedTextLength() {
        if (client.currentScreen == null) throw new IllegalStateException("no screen for focused text input");
        for (var child : client.currentScreen.children())
            if (child instanceof TextFieldWidget field && field.isFocused())
                return field.getText().codePointCount(0, field.getText().length());
        throw new IllegalStateException("no focused native text field");
    }

    @Override public void dispatchMouseClick(double x, double y) {
        Screen screen = client.currentScreen;
        if (screen == null) throw new IllegalStateException("no screen for mouse click");
        Click click = new Click(x, y, new MouseInput(0, 0));
        screen.mouseClicked(click, false);
        screen.mouseReleased(click);
    }

    @Override public void dispatchKey(int keyCode, int scanCode, int modifiers) {
        Screen screen = client.currentScreen;
        if (screen == null) throw new IllegalStateException("no screen for key input");
        screen.keyPressed(new KeyInput(keyCode, scanCode, modifiers));
    }

    @Override public void dispatchCharacter(int codePoint) {
        Screen screen = client.currentScreen;
        if (screen == null) throw new IllegalStateException("no screen for character input");
        screen.charTyped(new CharInput(codePoint, 0));
    }

    @Override public Object readConfig(String key) { return config.read(key); }
    @Override public void writeConfig(String key, Object value) { config.writeValue(key, value); }
    @Override public void saveConfig() throws IOException { config.save(); }

    @Override public void reloadConfig() {
        LodekeeperConfig disk = LodekeeperConfig.load();
        for (SettingSpec spec : LodekeeperConfig.specs()) config.writeValue(spec.key(), disk.read(spec.key()));
    }

    @Override public List<SettingsUiVerification.Setting> settings() {
        return LodekeeperConfig.specs().stream().map(NativeSettingsUiAccess::setting).toList();
    }

    @Override public Object defaultValue(String key) {
        return LodekeeperConfig.specs().stream().filter(spec -> spec.key().equals(key)).findFirst()
                .orElseThrow().defaultValue();
    }

    @Override public boolean enabled(String key) {
        Map<String, Boolean> values = new java.util.HashMap<>();
        for (SettingSpec spec : LodekeeperConfig.specs()) values.put(spec.key(), Boolean.TRUE.equals(config.read(spec.key())));
        SettingSpec spec = LodekeeperConfig.specs().stream().filter(candidate -> candidate.key().equals(key)).findFirst()
                .orElseThrow();
        while (spec.parentKey() != null) {
            if (!Boolean.TRUE.equals(values.get(spec.parentKey()))) return false;
            String parent = spec.parentKey();
            spec = LodekeeperConfig.specs().stream().filter(candidate -> candidate.key().equals(parent)).findFirst().orElseThrow();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override public Map<String, String> navigationPreferences() {
        return (Map<String, String>) config.read("navigationPreferences");
    }

    @Override public SettingsUiVerification.PlotState plotState() {
        var live = protection.capture();
        ClaimStore.View disk = new ClaimStore(claimsPath).view();
        return new SettingsUiVerification.PlotState(live.locked(), disk.locked(), live.scope(),
                plots(live.claims().all()), plots(disk.claims().all()), corner(true), corner(false));
    }

    @Override public void removePlotClaim(String id) { protection.removeClaim(id); }
    @Override public void clearPlotSelection() { protection.clearSelection(); }

    private SettingsUiVerification.Corner corner(boolean first) {
        int[] position = protection.selectionCoordinates(first);
        return position == null ? null : new SettingsUiVerification.Corner(position[0], position[1], position[2]);
    }

    private static List<SettingsUiVerification.Plot> plots(List<ClaimBox> claims) {
        return claims.stream().map(claim -> new SettingsUiVerification.Plot(claim.id(), claim.name(), claim.scope(),
                claim.minX(), claim.minY(), claim.minZ(), claim.maxX(), claim.maxY(), claim.maxZ(),
                claim.preferredStations())).toList();
    }

    private static Path isolatedClaimsPath(Path runDirectory) {
        try {
            Path run = runDirectory.toRealPath();
            Path configDirectory = FabricLoader.getInstance().getConfigDir().toRealPath();
            if (!configDirectory.startsWith(run) || configDirectory.equals(run)
                    || !"config".equals(configDirectory.getFileName().toString()))
                throw new IOException("verification config is outside the isolated game directory");
            Path claims = configDirectory.resolve("lodekeeper-claims.json").normalize();
            if (!claims.startsWith(configDirectory) || Files.isSymbolicLink(claims))
                throw new IOException("verification claims path is not a regular isolated path");
            return claims;
        } catch (IOException failure) {
            throw new IllegalStateException("plot UI probe requires an isolated claims store", failure);
        }
    }

    @Override public void requestCapture(String label) { capture.accept(label); }
    @Override public void log(String line) { log.accept(line); }
    @Override public boolean engineIdle() { return engineIdle.getAsBoolean(); }

    private static SettingsUiVerification.Setting setting(SettingSpec spec) {
        return new SettingsUiVerification.Setting(spec.key(), spec.label(), spec.category(), spec.help(), spec.type().name(),
                spec.defaultValue(), spec.parentKey());
    }
}
