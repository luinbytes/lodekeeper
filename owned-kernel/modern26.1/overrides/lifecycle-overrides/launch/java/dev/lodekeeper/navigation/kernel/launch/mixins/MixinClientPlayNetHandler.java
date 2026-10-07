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
import dev.lodekeeper.navigation.kernel.api.event.events.BlockChangeEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.ChatEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.ChunkEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.type.EventState;
import dev.lodekeeper.navigation.kernel.api.utils.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Brady
 * @since 8/3/2018
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPlayNetHandler extends ClientCommonPacketListenerImpl {

    

    protected MixinClientPlayNetHandler(final Minecraft arg, final Connection arg2, final CommonListenerCookie arg3) {
        super(arg, arg2, arg3);
    }

    @Inject(
            method = "sendChat(Ljava/lang/String;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sendChatMessage(String string, CallbackInfo ci) {
        if (!this.minecraft.isSameThread()) return;
        ChatEvent event = new ChatEvent(string);
        IBaritone baritone = OwnedKernelAPI.getProvider().getBaritoneForPlayer(this.minecraft.player);
        if (baritone == null) {
            return;
        }
        baritone.getGameEventHandler().onSendChatMessage(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleLevelChunkWithLight",
            at = @At("RETURN")
    )
    private void postHandleChunkData(ClientboundLevelChunkWithLightPacket packetIn, CallbackInfo ci) {
        if (!this.minecraft.isSameThread()) return;
        for (IBaritone ibaritone : OwnedKernelAPI.getProvider().getAllBaritones()) {
            LocalPlayer player = ibaritone.getPlayerContext().player();
            if (player != null && player.connection == (ClientPacketListener) (Object) this) {
                ibaritone.getGameEventHandler().onChunkEvent(
                        new ChunkEvent(
                                EventState.POST,
                                !packetIn.isSkippable() ? ChunkEvent.Type.POPULATE_FULL : ChunkEvent.Type.POPULATE_PARTIAL,
                                packetIn.getX(),
                                packetIn.getZ()
                        )
                );
            }
        }
    }

    @Inject(
            method = "handleForgetLevelChunk",
            at = @At("HEAD")
    )
    private void preChunkUnload(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (!this.minecraft.isSameThread()) return;
        for (IBaritone ibaritone : OwnedKernelAPI.getProvider().getAllBaritones()) {
            LocalPlayer player = ibaritone.getPlayerContext().player();
            if (player != null && player.connection == (ClientPacketListener) (Object) this) {
                ibaritone.getGameEventHandler().onChunkEvent(
                        new ChunkEvent(EventState.PRE, ChunkEvent.Type.UNLOAD, packet.pos().x(), packet.pos().z())
                );
            }
        }
    }

    @Inject(
            method = "handleForgetLevelChunk",
            at = @At("RETURN")
    )
    private void postChunkUnload(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        if (!this.minecraft.isSameThread()) return;
        for (IBaritone ibaritone : OwnedKernelAPI.getProvider().getAllBaritones()) {
            LocalPlayer player = ibaritone.getPlayerContext().player();
            if (player != null && player.connection == (ClientPacketListener) (Object) this) {
                ibaritone.getGameEventHandler().onChunkEvent(
                        new ChunkEvent(EventState.POST, ChunkEvent.Type.UNLOAD, packet.pos().x(), packet.pos().z())
                );
            }
        }
    }

    @Inject(
            method = "handleBlockUpdate",
            at = @At("RETURN")
    )
    private void postHandleBlockChange(ClientboundBlockUpdatePacket packetIn, CallbackInfo ci) {
        if (!this.minecraft.isSameThread()) return;
        IBaritone baritone = OwnedKernelAPI.getProvider().getBaritoneForConnection((ClientPacketListener) (Object) this);
        if (baritone == null) {
            return;
        }

        BlockPos pos = packetIn.getPos().immutable();
        List<Pair<BlockPos, BlockState>> changes = new ArrayList<>(1);
        changes.add(new Pair<>(pos, packetIn.getBlockState()));
        baritone.getGameEventHandler().onBlockChange(new BlockChangeEvent(
                ChunkPos.containing(pos),
                changes
        ));
    }

    @Inject(
            method = "handleChunkBlocksUpdate",
            at = @At("RETURN")
    )
    private void postHandleMultiBlockChange(ClientboundSectionBlocksUpdatePacket packetIn, CallbackInfo ci) {
        if (!this.minecraft.isSameThread()) return;
        IBaritone baritone = OwnedKernelAPI.getProvider().getBaritoneForConnection((ClientPacketListener) (Object) this);
        if (baritone == null) {
            return;
        }

        List<Pair<BlockPos, BlockState>> changes = new ArrayList<>();
        packetIn.runUpdates((mutPos, state) -> {
            changes.add(new Pair<>(mutPos.immutable(), state));
        });
        if (changes.isEmpty()) {
            return;
        }
        baritone.getGameEventHandler().onBlockChange(new BlockChangeEvent(
                ChunkPos.containing(changes.get(0).first()),
                changes
        ));
    }

    @Inject(
            method = "handlePlayerCombatKill",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;shouldShowDeathScreen()Z"
            )
    )
    private void onPlayerDeath(ClientboundPlayerCombatKillPacket packetIn, CallbackInfo ci) {
        if (!this.minecraft.isSameThread()) return;
        for (IBaritone ibaritone : OwnedKernelAPI.getProvider().getAllBaritones()) {
            LocalPlayer player = ibaritone.getPlayerContext().player();
            if (player != null && player.connection == (ClientPacketListener) (Object) this) {
                ibaritone.getGameEventHandler().onPlayerDeath();
            }
        }
    }

    
}
