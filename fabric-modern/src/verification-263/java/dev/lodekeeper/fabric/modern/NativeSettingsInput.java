package dev.lodekeeper.fabric.modern;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

final class NativeSettingsInput {
    private NativeSettingsInput() {}

    static KeyEvent key(int logicalKey) {
        return switch (logicalKey) {
            case 257 -> new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0);
            case 259 -> new KeyEvent(InputConstants.KEY_BACKSPACE, InputConstants.KEYCODE_BACKSPACE, 0);
            case 269 -> new KeyEvent(InputConstants.KEY_END, InputConstants.KEYCODE_END, 0);
            default -> throw new IllegalArgumentException("Unknown UI probe key");
        };
    }
}
