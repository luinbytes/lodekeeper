package dev.lodekeeper.fabric.modern;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

final class NativeSettingsInput {
    private NativeSettingsInput() {}

    static void refusedAnimalFixture(net.minecraft.world.entity.animal.Animal animal) {
        animal.setPermanentlyInvulnerable(true);
    }

    static void selectCookingFixtureSlot(net.minecraft.server.level.ServerPlayer player, int selected) {
        if (selected != 3 && selected != 5) throw new IllegalArgumentException("cooking original slot must be3 or5");
        player.getInventory().setSelectedSlot(selected);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(selected));
    }

    static KeyEvent key(int logicalKey) {
        return switch (logicalKey) {
            case 257 -> new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0);
            case 259 -> new KeyEvent(InputConstants.KEY_BACKSPACE, InputConstants.KEYCODE_BACKSPACE, 0);
            case 269 -> new KeyEvent(InputConstants.KEY_END, InputConstants.KEYCODE_END, 0);
            default -> throw new IllegalArgumentException("Unknown UI probe key");
        };
    }
}
