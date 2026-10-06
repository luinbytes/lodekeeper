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
import dev.lodekeeper.navigation.kernel.api.event.events.PacketEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.type.EventState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class MixinNetworkManager {
    @Shadow @Final private PacketFlow receiving;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void lodekeeper$movementPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (receiving != PacketFlow.CLIENTBOUND || !(packet instanceof ServerboundMovePlayerPacket)) return;
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || client.player == null) return;
        var kernel = OwnedKernelAPI.getProvider().getBaritoneForConnection(client.player.connection);
        if (kernel != null && client.player.connection.getConnection() == (Connection) (Object) this)
            kernel.getGameEventHandler().onSendPacket(new PacketEvent((Connection) (Object) this, EventState.PRE, packet));
    }
}
