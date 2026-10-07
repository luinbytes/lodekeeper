package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.FoodController;
import dev.lodekeeper.fabric.modern.ShieldController;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Holds vanilla use only while Lodekeeper owns a verified food-use action. */
@Mixin(Minecraft.class)
public abstract class AutomationUseMixin {
    @Redirect(method = "handleKeybinds", at = @At(
            value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean lodekeeper$holdFoodUse(KeyMapping mapping) {
        Minecraft client = Minecraft.getInstance();
        return mapping.isDown() || mapping == client.options.keyUse && (FoodController.isHoldingUse() || ShieldController.isHoldingUse());
    }
}
