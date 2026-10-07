package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.FoodController;
import dev.lodekeeper.fabric.ShieldController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps automatic food use held without modifying the user's physical key-binding state. */
@Mixin(MinecraftClient.class)
public abstract class AutomationUseMixin {
    @Redirect(method = "handleInputEvents", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
    private boolean lodekeeper$holdFoodUse(KeyBinding binding) {
        return binding.isPressed() || binding == MinecraftClient.getInstance().options.useKey && (FoodController.isHoldingUse() || ShieldController.isHoldingUse());
    }
}
