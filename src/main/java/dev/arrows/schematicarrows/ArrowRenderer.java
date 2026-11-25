package dev.arrows.schematicarrows;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws the world-space markers:
 * <ul>
 *   <li>{@link ArrowManager.Kind#MISSING}: an arrow above and below the block,
 *       both pointing at it (green above, blue below by default).</li>
 *   <li>{@link ArrowManager.Kind#WRONG} / {@link ArrowManager.Kind#EXTRA}: an
 *       X above and below the block, in the warning colour, so a mis-placed
 *       block is never confused with one that still has to be placed.</li>
 * </ul>
 *
 * <p>All markers share one orientation: the plane of the camera's horizontal
 * look direction. They therefore keep facing the player while walking around
 * the build, stay perfectly level (pitch never tilts them), and their top and
 * bottom edges are parallel to the screen's horizontal axis.</p>
 */
public final class ArrowRenderer {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("schematic-arrows");
    private static boolean failureReported;

    private ArrowRenderer() {
    }

    public static void render(WorldRenderContext context) {
        try {
            draw(context);
        } catch (RuntimeException | LinkageError e) {
            if (!failureReported) {
                failureReported = true;
                LOGGER.error("Failed to render the schematic arrows", e);
            }
        }
    }

    private static void draw(WorldRenderContext context) {
        if (!SchematicArrowsClient.isEnabled()) {
            return;
        }
        List<ArrowManager.Target> targets = ArrowManager.targets();
        if (targets.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }
        MatrixStack matrices = context.matrices();
        VertexConsumer buffer = context.consumers() == null ? null : context.consumers().getBuffer(RenderLayers.lines());
        if (matrices == null || buffer == null) {
            return;
        }
        ArrowsConfig config = ArrowsConfig.get();
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        // One shared, purely horizontal "to the player's right" axis. Using the
        // camera yaw instead of the per-block direction keeps every marker in a
        // plane parallel to the screen, so looking up or down never tilts them.
        Vec3d right = billboardRight(client.gameRenderer.getCamera().getYaw());

        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        MatrixStack.Entry entry = matrices.peek();

        double phase = (System.currentTimeMillis() % 3141L) / 3141.0 * Math.PI * 2.0;
        float bob = (float) (Math.sin(phase) * config.bobAmplitude);
        float width = Math.max(0.5f, config.lineWidth);

        for (ArrowManager.Target target : targets) {
            int blockX = target.pos().getX();
            int blockY = target.pos().getY();
            int blockZ = target.pos().getZ();
            double cx = blockX + 0.5;
            double cz = blockZ + 0.5;

            if (target.kind() == ArrowManager.Kind.MISSING) {
                // Arrow above the block: body upwards, tip pointing down at the block.
                arrow(buffer, entry, right, cx, cz, blockY + 1.0 + config.arrowGap + bob,
                        +1, config.aboveColor, width, config);
                // Arrow below the block: body downwards, tip pointing up at the block.
                arrow(buffer, entry, right, cx, cz, blockY - config.arrowGap - bob,
                        -1, config.belowColor, width, config);
                if (config.drawBox) {
                    VertexRendering.drawOutline(matrices, buffer, VoxelShapes.fullCube(),
                            blockX, blockY, blockZ, config.boxColor, 1.0f);
                }
            } else {
                int color = target.kind() == ArrowManager.Kind.WRONG ? config.wrongColor : config.extraColor;
                double half = Math.max(0.05, config.badMarkerSize);
                cross(buffer, entry, right, cx, cz, blockY + 1.0 + config.arrowGap + half, half, color, width);
                cross(buffer, entry, right, cx, cz, blockY - config.arrowGap - half, half, color, width);
                if (config.drawBox) {
                    int boxColor = (color & 0xFFFFFF) | 0x66000000;
                    VertexRendering.drawOutline(matrices, buffer, VoxelShapes.fullCube(),
                            blockX, blockY, blockZ, boxColor, 1.0f);
                }
            }
        }
        matrices.pop();
    }

    /**
     * One billboarded arrow whose tip is at (cx, tipY, cz).
     *
     * @param direction +1 if the arrow body extends upwards (arrow pointing down),
     *                  -1 if the body extends downwards (arrow pointing up)
     */
    private static void arrow(VertexConsumer buffer, MatrixStack.Entry entry, Vec3d right,
                              double cx, double cz, double tipY, int direction,
                              int color, float width, ArrowsConfig config) {
        double half = Math.max(0.02, config.arrowRadius);
        double baseY = tipY + direction * Math.max(0.05, config.arrowHeight);
        double leftX = cx - right.x * half;
        double leftZ = cz - right.z * half;
        double rightX = cx + right.x * half;
        double rightZ = cz + right.z * half;

        line(buffer, entry, cx, tipY, cz, leftX, baseY, leftZ, color, width);
        line(buffer, entry, leftX, baseY, leftZ, rightX, baseY, rightZ, color, width);
        line(buffer, entry, rightX, baseY, rightZ, cx, tipY, cz, color, width);

        double stem = Math.max(0.0, config.arrowStem);
        if (stem > 1.0e-4) {
            line(buffer, entry, cx, baseY, cz, cx, baseY + direction * stem, cz, color, width);
        }
    }

    /** A billboarded X centred at (cx, centerY, cz). */
    private static void cross(VertexConsumer buffer, MatrixStack.Entry entry, Vec3d right,
                              double cx, double cz, double centerY, double half,
                              int color, float width) {
        line(buffer, entry,
                cx - right.x * half, centerY - half, cz - right.z * half,
                cx + right.x * half, centerY + half, cz + right.z * half, color, width);
        line(buffer, entry,
                cx - right.x * half, centerY + half, cz - right.z * half,
                cx + right.x * half, centerY - half, cz + right.z * half, color, width);
    }

    /**
     * Horizontal vector pointing to the player's right, derived from the camera
     * yaw only. Pitch is deliberately ignored, so markers never tilt with the
     * view and their horizontal edges stay parallel to the screen.
     */
    private static Vec3d billboardRight(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        // Minecraft horizontal look direction for a yaw angle.
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);
        return new Vec3d(-lookZ, 0.0, lookX);
    }

    private static void line(VertexConsumer buffer, MatrixStack.Entry entry,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             int color, float width) {
        Vector3f normal = new Vector3f((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
        if (normal.lengthSquared() < 1.0e-6f) {
            normal.set(0.0f, 1.0f, 0.0f);
        } else {
            normal.normalize();
        }
        buffer.vertex(entry, (float) x1, (float) y1, (float) z1)
                .color(color)
                .normal(entry, normal)
                .lineWidth(width);
        buffer.vertex(entry, (float) x2, (float) y2, (float) z2)
                .color(color)
                .normal(entry, normal)
                .lineWidth(width);
    }
}
