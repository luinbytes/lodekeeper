package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.LodekeeperClient;
import dev.lodekeeper.fabric.modern.LodekeeperClient.PreparationStage;
import dev.lodekeeper.navigation.kernel.api.utils.IPlayerContext;
import dev.lodekeeper.navigation.kernel.api.utils.Rotation;
import dev.lodekeeper.navigation.kernel.api.utils.RotationUtils;
import dev.lodekeeper.navigation.kernel.pathing.movement.Movement;
import dev.lodekeeper.navigation.kernel.pathing.movement.MovementState;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = Movement.class, remap = false)
public abstract class MovementPreparationMixin {
    @Unique private static final String LODEKEEPER_PREPARED = "prepared(Ldev/lodekeeper/navigation/kernel/pathing/movement/MovementState;)Z";

    @Inject(method = LODEKEEPER_PREPARED, at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void lodekeeper$head(MovementState state, CallbackInfoReturnable<Boolean> callback) {
        LodekeeperClient.observePreparation(this, PreparationStage.HEAD, false, state, null, null, null, null);
    }

    @Inject(method = LODEKEEPER_PREPARED, at = @At(value = "RETURN", ordinal = 0), remap = false, require = 1, allow = 1)
    private void lodekeeper$return0(MovementState state, CallbackInfoReturnable<Boolean> callback) {
        LodekeeperClient.observePreparation(this, PreparationStage.WAITING, callback.getReturnValue(), state, null, null, null, null);
    }

    @Inject(method = LODEKEEPER_PREPARED, at = @At(value = "RETURN", ordinal = 1), remap = false, require = 1, allow = 1)
    private void lodekeeper$return1(MovementState state, CallbackInfoReturnable<Boolean> callback) {
        LodekeeperClient.observePreparation(this, PreparationStage.FALLING_WAIT, callback.getReturnValue(), state, null, null, null, null);
    }

    @Inject(method = LODEKEEPER_PREPARED, at = @At(value = "RETURN", ordinal = 2), remap = false, require = 1, allow = 1)
    private void lodekeeper$return2(MovementState state, CallbackInfoReturnable<Boolean> callback) {
        LodekeeperClient.observePreparation(this, PreparationStage.REACHABLE, callback.getReturnValue(), state, null, null, null, null);
    }

    @Inject(method = LODEKEEPER_PREPARED, at = @At(value = "RETURN", ordinal = 3), remap = false, require = 1, allow = 1)
    private void lodekeeper$return3(MovementState state, CallbackInfoReturnable<Boolean> callback) {
        LodekeeperClient.observePreparation(this, PreparationStage.FALLBACK, callback.getReturnValue(), state, null, null, null, null);
    }

    @Inject(method = LODEKEEPER_PREPARED, at = @At(value = "RETURN", ordinal = 4), remap = false, require = 1, allow = 1)
    private void lodekeeper$return4(MovementState state, CallbackInfoReturnable<Boolean> callback) {
        LodekeeperClient.observePreparation(this, PreparationStage.UNREACHABLE, callback.getReturnValue(), state, null, null, null, null);
    }

    @Inject(method = LODEKEEPER_PREPARED, at = @At(value = "RETURN", ordinal = 5), remap = false, require = 1, allow = 1)
    private void lodekeeper$return5(MovementState state, CallbackInfoReturnable<Boolean> callback) {
        LodekeeperClient.observePreparation(this, PreparationStage.CLEAR, callback.getReturnValue(), state, null, null, null, null);
    }

    @Redirect(method = LODEKEEPER_PREPARED, at = @At(value = "INVOKE", target = "Ldev/lodekeeper/navigation/kernel/api/utils/RotationUtils;reachable(Ldev/lodekeeper/navigation/kernel/api/utils/IPlayerContext;Lnet/minecraft/core/BlockPos;D)Ljava/util/Optional;", remap = false), remap = false, require = 1, allow = 1)
    private Optional<Rotation> lodekeeper$reachable(IPlayerContext ctx, BlockPos block, double reach) {
        Optional<Rotation> result = RotationUtils.reachable(ctx, block, reach);
        LodekeeperClient.observePreparation(this, PreparationStage.REACHABILITY, false, null, block, result, null, null);
        return result;
    }

    @Redirect(method = LODEKEEPER_PREPARED, at = @At(value = "INVOKE", target = "Ldev/lodekeeper/navigation/kernel/api/utils/IPlayerContext;isLookingAt(Lnet/minecraft/core/BlockPos;)Z", remap = false), remap = false, require = 1, allow = 1)
    private boolean lodekeeper$lookingAt(IPlayerContext ctx, BlockPos block) {
        boolean result = ctx.isLookingAt(block);
        LodekeeperClient.observePreparation(this, PreparationStage.LOOKING_AT, result, null, block, null, null, null);
        return result;
    }

    @Redirect(method = LODEKEEPER_PREPARED, at = @At(value = "INVOKE", target = "Ldev/lodekeeper/navigation/kernel/api/utils/Rotation;isReallyCloseTo(Ldev/lodekeeper/navigation/kernel/api/utils/Rotation;)Z", remap = false), remap = false, require = 1, allow = 1)
    private boolean lodekeeper$rotationClose(Rotation effective, Rotation desired) {
        boolean result = effective.isReallyCloseTo(desired);
        LodekeeperClient.observePreparation(this, PreparationStage.ROTATION_CLOSE, result, null, null, null, effective, desired);
        return result;
    }
}
