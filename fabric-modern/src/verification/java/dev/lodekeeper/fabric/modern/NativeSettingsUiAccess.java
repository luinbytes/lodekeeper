package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.SettingSpec;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class NativeSettingsUiAccess implements SettingsUiVerification.Access {
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final Supplier<Screen> settingsScreen;
    private final Consumer<String> capture;
    private final Consumer<String> log;
    private final BooleanSupplier engineIdle;

    NativeSettingsUiAccess(Minecraft client, LodekeeperConfig config, Supplier<Screen> settingsScreen,
                           Consumer<String> capture, Consumer<String> log, BooleanSupplier engineIdle) {
        this.client = client;
        this.config = config;
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
        dispatchKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0);
        log.accept("[Lodekeeper UI verification] Submitted config command through the native chat screen Enter key");
    }

    @Override public Object currentScreen() { return GameApi.screen(client); }
    @Override public void openSettingsScreen() { GameApi.setScreen(client, settingsScreen.get()); }
    @Override public String nativeEventReceipt() {
        return "MouseButtonEvent(x,y,MouseButtonInfo(native left button,0)) -> Screen.mouseClicked(event,false)/mouseReleased(event), "
                + "KeyEvent(key,scanCode,modifiers) -> Screen.keyPressed(event), "
                + "CharacterEvent(codePoint) -> Screen.charTyped(event)";
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
        int nativeKey = switch (keyCode) {
            case 259 -> com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE;
            case 269 -> com.mojang.blaze3d.platform.InputConstants.KEY_END;
            default -> keyCode;
        };
        screen.keyPressed(new KeyEvent(nativeKey, scanCode, modifiers));
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

    @Override public void requestCapture(String label) { capture.accept(label); }
    @Override public void log(String line) { log.accept(line); }
    @Override public boolean engineIdle() { return engineIdle.getAsBoolean(); }

    private static SettingsUiVerification.Setting setting(SettingSpec spec) {
        return new SettingsUiVerification.Setting(spec.key(), spec.label(), spec.category(), spec.help(), spec.type().name(),
                spec.defaultValue(), spec.parentKey());
    }
}
