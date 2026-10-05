package dev.lodekeeper.fabric.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.lodekeeper.fabric.WorldVisualizationRenderApi;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
abstract class WorldRenderOverlayMixin {
    @Shadow @Final private BufferBuilderStorage bufferBuilders;

    @Inject(method = "render", at = @At("TAIL"))
    private void lodekeeper$renderWorldOverlay(ObjectAllocator allocator,
                                               RenderTickCounter tickCounter,
                                               boolean renderBlockOutline,
                                               Camera camera,
                                               Matrix4f positionMatrix,
                                               Matrix4f projectionMatrix,
                                               Matrix4f cullingMatrix,
                                               GpuBufferSlice fogBuffer,
                                               Vector4f fogColor,
                                               boolean renderSky,
                                               CallbackInfo callbackInfo) {
        WorldVisualizationRenderApi.render(bufferBuilders.getEntityVertexConsumers(), camera, positionMatrix);
    }
}
