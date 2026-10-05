package dev.lodekeeper.fabric;

import dev.lodekeeper.nav.NavigationOverlay;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class WorldVisualizationRenderApi {
    private static WorldVisualization visualization;

    private WorldVisualizationRenderApi() { }

    static void register(WorldVisualization registeredVisualization) {
        visualization = registeredVisualization;
    }

    public static void render(VertexConsumerProvider.Immediate consumers, Camera camera,
                              Matrix4f positionMatrix) {
        WorldVisualization currentVisualization = visualization;
        if (currentVisualization == null || !currentVisualization.isVisibleNow()
                || currentVisualization.isHudHiddenNow()) return;

        WorldVisualization.Frame frame = currentVisualization.snapshotForRender();
        if (!frame.enabled()) return;
        Vec3d cameraPos = camera.getPos();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;

        MatrixStack matrices = new MatrixStack();
        matrices.multiplyPositionMatrix(positionMatrix);
        MatrixStack.Entry matrix = matrices.peek();
        matrix.getNormalMatrix().set(new Matrix3f(positionMatrix).invert().transpose());

        RenderLayer lines = RenderLayer.getLines();
        LineEmitter emitter = new LineEmitter();
        emitter.begin(consumers.getBuffer(lines), matrix, cameraX, cameraY, cameraZ);
        try {
            currentVisualization.draw(frame, emitter, cameraX, cameraY, cameraZ);
        } finally {
            emitter.end();
            consumers.draw(lines);
        }
    }

    private static final class LineEmitter implements NavigationOverlay.Lines {
        private VertexConsumer vertices;
        private MatrixStack.Entry matrix;
        private double cameraX, cameraY, cameraZ;

        void begin(VertexConsumer vertices, MatrixStack.Entry matrix,
                   double cameraX, double cameraY, double cameraZ) {
            this.vertices = vertices;
            this.matrix = matrix;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
        }

        void end() {
            vertices = null;
            matrix = null;
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
            vertices.vertex(matrix, (float) (x - cameraX), (float) (y - cameraY), (float) (z - cameraZ))
                    .color(red, green, blue, alpha)
                    .normal(matrix, normalX, normalY, normalZ);
        }
    }
}
