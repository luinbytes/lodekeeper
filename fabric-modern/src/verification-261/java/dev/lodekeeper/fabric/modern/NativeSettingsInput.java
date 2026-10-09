package dev.lodekeeper.fabric.modern;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

final class NativeSettingsInput {
    private NativeSettingsInput() {}

    static void refusedAnimalFixture(net.minecraft.world.entity.animal.Animal animal) {
        throw new IllegalStateException("Native animal verification requires Minecraft 26.3");
    }

    static void selectCookingFixtureSlot(net.minecraft.server.level.ServerPlayer player, int selected) {
        throw new IllegalStateException("Native cooking original-slot verification requires Minecraft26.3");
    }

    static KeyEvent key(int logicalKey) {
        int key = switch (logicalKey) {
            case 257 -> InputConstants.KEY_RETURN;
            case 259 -> InputConstants.KEY_BACKSPACE;
            case 269 -> InputConstants.KEY_END;
            default -> throw new IllegalArgumentException("Unknown UI probe key");
        };
        return new KeyEvent(key, 0, 0);
    }
}
