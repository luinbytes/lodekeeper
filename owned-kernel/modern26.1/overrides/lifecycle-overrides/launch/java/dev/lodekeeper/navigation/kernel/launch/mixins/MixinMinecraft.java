/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lodekeeper.navigation.kernel.launch.mixins;

import dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI;
import dev.lodekeeper.navigation.kernel.api.IBaritone;
import dev.lodekeeper.navigation.kernel.api.event.events.PlayerUpdateEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.TickEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.WorldEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.type.EventState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiFunction;

/**
 * @author Brady
 * @since 7/31/2018
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Shadow
    public LocalPlayer player;
    @Shadow
    public ClientLevel level;

    @Unique
    private BiFunction<EventState, TickEvent.Type, TickEvent> tickProvider;

@Inject(
            method = "tick",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.GETFIELD,
                    target = "net/minecraft/client/Minecraft.screen:Lnet/minecraft/client/gui/screens/Screen;",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            ),
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            opcode = Opcodes.PUTFIELD,
                            target = "net/minecraft/client/Minecraft.missTime:I"
                    )
            )
    )
    private void runTick(CallbackInfo ci) {
        this.tickProvider = TickEvent.createNextProvider();

        for (IBaritone baritone : OwnedKernelAPI.getProvider().getAllBaritones()) {
            TickEvent.Type type = baritone.getPlayerContext().player() != null && baritone.getPlayerContext().world() != null
                    ? TickEvent.Type.IN
                    : TickEvent.Type.OUT;
            baritone.getGameEventHandler().onTick(this.tickProvider.apply(EventState.PRE, type));
        }
    }

    @Inject(
            method = "tick",
            at = @At("RETURN")
    )
    private void postRunTick(CallbackInfo ci) {
        if (this.tickProvider == null) {
            return;
        }

        for (IBaritone baritone : OwnedKernelAPI.getProvider().getAllBaritones()) {
            TickEvent.Type type = baritone.getPlayerContext().player() != null && baritone.getPlayerContext().world() != null
                    ? TickEvent.Type.IN
                    : TickEvent.Type.OUT;
            baritone.getGameEventHandler().onPostTick(this.tickProvider.apply(EventState.POST, type));
        }

        this.tickProvider = null;
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/client/multiplayer/ClientLevel.tickEntities()V",
                    shift = At.Shift.AFTER
            )
    )
    private void postUpdateEntities(CallbackInfo ci) {
        IBaritone baritone = OwnedKernelAPI.getProvider().getBaritoneForPlayer(this.player);
        if (baritone != null) {
            // Intentionally call this after all entities have been updated. That way, any modification to rotations
            // can be recognized by other entity code. (Fireworks and Pigs, for example)
            baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
        }
    }

    @Inject(
            method = "setLevel",
            at = @At("HEAD")
    )
    private void preLoadWorld(final ClientLevel world, final CallbackInfo ci) {
        if (this.level == null && world == null) {
            return;
        }
        IBaritone primary = OwnedKernelAPI.getProvider().getPrimaryBaritone();
        if (primary != null) {
            primary.getGameEventHandler().onWorldEvent(new WorldEvent(world, EventState.PRE));
        }
    }

    @Inject(
            method = "setLevel",
            at = @At("RETURN")
    )
    private void postLoadWorld(final ClientLevel world, final CallbackInfo ci) {
        IBaritone primary = OwnedKernelAPI.getProvider().getPrimaryBaritone();
        if (primary != null) {
            primary.getGameEventHandler().onWorldEvent(new WorldEvent(world, EventState.POST));
        }
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "FIELD",
                    opcode = Opcodes.GETFIELD,
                    target = "Lnet/minecraft/client/Minecraft;screen:Lnet/minecraft/client/gui/screens/Screen;"
            ),
            slice = @Slice(
                    from = @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;showDebugScreen()Z"
                    ),
                    to = @At(
                            value = "CONSTANT",
                            args = "stringValue=Keybindings"
                    )
            )
    )
    private Screen passEvents(Minecraft instance) {
        IBaritone primary = OwnedKernelAPI.getProvider().getPrimaryBaritone();
        if (primary != null && primary.getPathingBehavior().isPathing() && player != null) {
            return null;
        }
        return instance.screen;
    }

            return null;
        }
        return instance.screen;
    }


}
