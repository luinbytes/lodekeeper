package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.LodekeeperClient;
import dev.lodekeeper.fabric.modern.LodekeeperClient.BreakHelperStage;
import dev.lodekeeper.navigation.kernel.OwnedKernelRuntime;
import dev.lodekeeper.navigation.kernel.OwnedMutationGuard;
import dev.lodekeeper.navigation.kernel.api.utils.IPlayerContext;
import dev.lodekeeper.navigation.kernel.api.utils.IPlayerController;
import dev.lodekeeper.navigation.kernel.utils.BlockBreakHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockBreakHelper.class, remap = false)
public abstract class BlockBreakObservationMixin {
    @Shadow(remap = false) private boolean wasHitting;
    @Shadow(remap = false) private int breakDelayTimer;

    @Inject(method = "tick(Z)V", at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void lodekeeper$head(boolean leftClick, CallbackInfo callback) {
        LodekeeperClient.observeBreakHelper(this, BreakHelperStage.HEAD, false, null, leftClick, breakDelayTimer, wasHitting);
    }

    @Inject(method = "tick(Z)V", at = @At(value = "RETURN", ordinal = 0), remap = false, require = 1, allow = 1)
    private void lodekeeper$return0(boolean leftClick, CallbackInfo callback) {
        LodekeeperClient.observeBreakHelper(this, BreakHelperStage.DELAY, false, null, leftClick, breakDelayTimer, wasHitting);
    }

    @Inject(method = "tick(Z)V", at = @At(value = "RETURN", ordinal = 1), remap = false, require = 1, allow = 1)
    private void lodekeeper$return1(boolean leftClick, CallbackInfo callback) {
        LodekeeperClient.observeBreakHelper(this, BreakHelperStage.GUARD_DENIED, false, null, leftClick, breakDelayTimer, wasHitting);
    }

    @Inject(method = "tick(Z)V", at = @At(value = "RETURN", ordinal = 2), remap = false, require = 1, allow = 1)
    private void lodekeeper$return2(boolean leftClick, CallbackInfo callback) {
        LodekeeperClient.observeBreakHelper(this, BreakHelperStage.NORMAL, false, null, leftClick, breakDelayTimer, wasHitting);
    }

    @Redirect(method = "tick(Z)V", at = @At(value = "INVOKE", target = "Ldev/lodekeeper/navigation/kernel/api/utils/IPlayerContext;objectMouseOver()Lnet/minecraft/world/phys/HitResult;", remap = false), remap = false, require = 1, allow = 1)
    private HitResult lodekeeper$ray(IPlayerContext context) {
        HitResult result = context.objectMouseOver();
        LodekeeperClient.observeBreakHelper(this, BreakHelperStage.RAY, false, result, false, 0, false);
        return result;
    }

    @Redirect(method = "tick(Z)V", at = @At(value = "INVOKE", target = "Ldev/lodekeeper/navigation/kernel/OwnedMutationGuard;executeBreak(Ldev/lodekeeper/navigation/kernel/OwnedKernelRuntime;Lnet/minecraft/core/BlockPos;)Z", remap = false), remap = false, require = 1, allow = 1)
    private boolean lodekeeper$guard(OwnedKernelRuntime runtime, BlockPos block) {
        boolean result = OwnedMutationGuard.executeBreak(runtime, block);
        LodekeeperClient.observeBreakHelper(this, BreakHelperStage.GUARD, result, null, false, 0, false);
        return result;
    }

    @Redirect(method = "tick(Z)V", at = @At(value = "INVOKE", target = "Ldev/lodekeeper/navigation/kernel/api/utils/IPlayerController;hasBrokenBlock()Z", ordinal = 0, remap = false), remap = false, require = 1, allow = 1)
    private boolean lodekeeper$choice(IPlayerController controller) {
        boolean result = controller.hasBrokenBlock();
        LodekeeperClient.observeBreakHelper(this, BreakHelperStage.CHOICE, result, null, false, 0, false);
        return result;
    }
}
