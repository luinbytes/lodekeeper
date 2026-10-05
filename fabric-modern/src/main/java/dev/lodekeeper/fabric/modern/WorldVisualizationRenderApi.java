package dev.lodekeeper.fabric.modern;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lodekeeper.nav.NavigationOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

final class WorldVisualizationRenderApi {
    private WorldVisualizationRenderApi() { }

    static void register(WorldVisualization visualization) {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> collect(context, visualization));
    }

    private static void collect(LevelRenderContext context, WorldVisualization visualization) {
        WorldVisualization.Frame frame = visualization.snapshotForRender();
        if (!frame.enabled()) return;

        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poses = context.poseStack();
        poses.pushPose();
        context.submitNodeCollector().submitCustomGeometry(poses, RenderTypes.lines(),
                (pose, vertices) -> visualization.draw(frame,
                        new LineEmitter(vertices, pose, camera), camera.x, camera.y, camera.z));
        poses.popPose();
    }

    private static final class LineEmitter implements NavigationOverlay.Lines {
        private final VertexConsumer vertices;
        private final PoseStack.Pose pose;
        private final Vec3 camera;

        private LineEmitter(VertexConsumer vertices, PoseStack.Pose pose, Vec3 camera) {
            this.vertices = vertices;
            this.pose = pose;
            this.camera = camera;
        }

        @Override public void line(double x1, double y1, double z1,
                                   double x2, double y2, double z2, int argb) {
            double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            double lengthSquared = dx * dx + dy * dy + dz * dz;
            if (!(lengthSquared > 0.0) || !Double.isFinite(lengthSquared)) return;
            float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
            float normalX = (float) dx * inverseLength;
            float normalY = (float) dy * inverseLength;
            float normalZ = (float) dz * inverseLength;
            int red = (argb >>> 16) & 0xff;
            int green = (argb >>> 8) & 0xff;
            int blue = argb & 0xff;
            int alpha = (argb >>> 24) & 0xff;
            vertex(x1, y1, z1, red, green, blue, alpha, normalX, normalY, normalZ);
            vertex(x2, y2, z2, red, green, blue, alpha, normalX, normalY, normalZ);
        }

        private void vertex(double x, double y, double z, int red, int green, int blue, int alpha,
                            float normalX, float normalY, float normalZ) {
            vertices.addVertex(pose, (float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z))
                    .setColor(red, green, blue, alpha)
                    .setNormal(pose, normalX, normalY, normalZ)
                    .setLineWidth(1.0f);
        }
    }
}
