package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.ClaimBox;
import dev.lodekeeper.core.SettingSpec;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

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
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final WorldProtection protection;
    private final Path claimsPath;
    private final Supplier<Screen> settingsScreen;
    private final Consumer<String> capture;
    private final Consumer<String> log;
    private final BooleanSupplier engineIdle;

    NativeSettingsUiAccess(Minecraft client, LodekeeperConfig config, Supplier<Screen> settingsScreen,
                           WorldProtection protection,
                           Consumer<String> capture, Consumer<String> log, BooleanSupplier engineIdle) {
        this.client = client;
        this.config = config;
        this.protection = protection;
        this.claimsPath = isolatedClaimsPath(client.gameDirectory.toPath());
        this.settingsScreen = settingsScreen;
        this.capture = capture;
        this.log = log;
        this.engineIdle = engineIdle;
    }

    @Override public List<SettingsUiVerification.Widget> visibleChildren(Object value) {
        if (!(value instanceof Screen screen)) throw new IllegalStateException("active screen is not a Minecraft Screen");
        List<SettingsUiVerification.Widget> result = new ArrayList<>();
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            String message = widget.getMessage().getString();
            String text = widget instanceof EditBox field ? field.getValue() : "";
            result.add(new SettingsUiVerification.Widget(widget, message, text,
                    new SettingsUiVerification.Bounds(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
                    widget instanceof EditBox, widget.active));
        }
        return result;
    }

    void submitSettingsChatCommand() {
        GameApi.setScreen(client, new net.minecraft.client.gui.screens.ChatScreen("", false));
        String command = config.prefix + "config";
        for (int codePoint : command.codePoints().toArray()) dispatchCharacter(codePoint);
        dispatchKey(257, 0, 0);
        log.accept("[Lodekeeper UI verification] Submitted config command through the native chat screen Enter key");
    }

    @Override public Object currentScreen() { return GameApi.screen(client); }
    @Override public void openSettingsScreen() { GameApi.setScreen(client, settingsScreen.get()); }
    @Override public boolean isClaimsScreen(Object screen) { return screen instanceof ClaimsScreen; }
    @Override public void resizeCurrentScreen(int width, int height) {
        Screen screen = GameApi.screen(client);
        if (screen == null) throw new IllegalStateException("no screen to resize");
        screen.resize(width, height);
    }
    @Override public String nativeEventReceipt() {
        return "MouseButtonEvent(x,y,MouseButtonInfo(native left button,0)) -> Screen.mouseClicked(event,false)/mouseReleased(event), "
                + "KeyEvent(key,scanCode,modifiers) -> Screen.keyPressed(event), "
                + "CharacterEvent(codePoint) -> Screen.charTyped(event), Screen.resize(width,height)";
    }
    @Override public int screenWidth() { Screen screen = GameApi.screen(client); return screen == null ? 0 : screen.width; }
    @Override public int screenHeight() { Screen screen = GameApi.screen(client); return screen == null ? 0 : screen.height; }

    @Override public int focusedTextLength() {
        Screen screen = GameApi.screen(client);
        if (screen == null) throw new IllegalStateException("no screen for focused text input");
        for (GuiEventListener child : screen.children())
            if (child instanceof EditBox field && field.isFocused())
                return field.getValue().codePointCount(0, field.getValue().length());
        throw new IllegalStateException("no focused native text field");
    }

    @Override public void dispatchMouseClick(double x, double y) {
        Screen screen = GameApi.screen(client);
        if (screen == null) throw new IllegalStateException("no screen for mouse click");
        MouseButtonEvent event = new MouseButtonEvent(x, y,
                new MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    @Override public void dispatchKey(int keyCode, int scanCode, int modifiers) {
        Screen screen = GameApi.screen(client);
        if (screen == null) throw new IllegalStateException("no screen for key input");
        if (scanCode != 0 || modifiers != 0) throw new IllegalArgumentException("UI probes require unmodified keys");
        screen.keyPressed(NativeSettingsInput.key(keyCode));
    }

    @Override public void dispatchCharacter(int codePoint) {
        Screen screen = GameApi.screen(client);
        if (screen == null) throw new IllegalStateException("no screen for character input");
        screen.charTyped(new CharacterEvent(codePoint));
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
