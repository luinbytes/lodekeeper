package dev.lodekeeper.fabric;

import dev.lodekeeper.nav.NavigationOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

final class WorldVisualizationRenderApi {
    private WorldVisualizationRenderApi() { }

    static void register(WorldVisualization visualization) {
        LineEmitter emitter = new LineEmitter();
        WorldRenderEvents.AFTER_ENTITIES.register(context -> render(context, visualization, emitter));
    }

    private static void render(WorldRenderContext context, WorldVisualization visualization, LineEmitter emitter) {
        if (!visualization.isVisibleNow() || visualization.isHudHiddenNow()) return;
        if (context.consumers() == null) return;
        Vec3d camera = context.camera().getPos();
        MatrixStack.Entry matrix = context.matrixStack().peek();
        emitter.begin(context.consumers().getBuffer(RenderLayer.getLines()), matrix,
                camera.x, camera.y, camera.z);
        visualization.draw(visualization.snapshotForRender(), emitter, camera.x, camera.y, camera.z);
        emitter.end();
    }

    private static final class LineEmitter implements NavigationOverlay.Lines {
        private VertexConsumer vertices;
        private Matrix4f positionMatrix;
        private Matrix3f normalMatrix;
        private double cameraX, cameraY, cameraZ;

        void begin(VertexConsumer vertices, MatrixStack.Entry matrix,
                   double cameraX, double cameraY, double cameraZ) {
            this.vertices = vertices;
            this.positionMatrix = matrix.getPositionMatrix();
            this.normalMatrix = matrix.getNormalMatrix();
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
        }

        void end() {
            vertices = null;
            positionMatrix = null;
            normalMatrix = null;
        }

        @Override public void line(double x1, double y1, double z1,
                                   double x2, double y2, double z2, int argb) {
            if (vertices == null) return;
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
            vertices.vertex(positionMatrix, (float) (x - cameraX), (float) (y - cameraY), (float) (z - cameraZ))
                    .color(red, green, blue, alpha)
                    .normal(normalMatrix, normalX, normalY, normalZ);
            vertices.next();
        }
    }
}
