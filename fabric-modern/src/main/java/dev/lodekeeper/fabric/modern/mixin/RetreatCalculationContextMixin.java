package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.RetreatSnapshotDiagnostics;
import dev.lodekeeper.navigation.kernel.api.IBaritone;
import dev.lodekeeper.navigation.kernel.pathing.movement.CalculationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CalculationContext.class, remap = false)
public abstract class RetreatCalculationContextMixin {
    @Inject(method = "<init>(Ldev/lodekeeper/navigation/kernel/api/IBaritone;Z)V", at = @At("TAIL"), remap = false)
    private void lodekeeper$captureRetreatSnapshot(IBaritone bot, boolean threaded, CallbackInfo callback) {
        if (threaded) RetreatSnapshotDiagnostics.capture((CalculationContext) (Object) this);
    }
}
