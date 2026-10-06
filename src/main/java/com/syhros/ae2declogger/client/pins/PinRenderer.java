package com.syhros.ae2declogger.client.pins;

//#if MC >= 1.19.3
import org.joml.Matrix4f;
//#else
//$$ import com.mojang.math.Matrix4f;
//#endif

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.logic.Formatting;

import com.syhros.ae2declogger.client.screen.Gfx;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Draws pins in the world (outline box + label, through walls) and the navigation line on the HUD. */
public final class PinRenderer {

    private PinRenderer() {
    }

    /** Called by the loader's level render event after translucent blocks (see ClientEvents). */
    public static void renderWorld(PoseStack pose, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || PinManager.isHidden() || PinManager.visiblePins().isEmpty()
                || !DeclogConfig.CLIENT.pinsInWorld.get()) {
            return;
        }
        String dim = mc.level.dimension().location().toString();
        Vec3 cam = camera.getPosition();
        double maxDist = DeclogConfig.CLIENT.pinRenderDistance.get();
        PinManager.Pin active = PinManager.active();

        java.util.List<PinManager.Pin> visible = new java.util.ArrayList<>();
        for (PinManager.Pin pin : PinManager.visiblePins()) {
            if (dim.equals(pin.dimension) && !tooFar(pin.pos(), cam, maxDist)) {
                visible.add(pin);
            }
        }
        if (visible.isEmpty()) {
            return;
        }

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tesselator = Tesselator.getInstance();

        BufferBuilder fill = begin(tesselator, VertexFormat.Mode.QUADS);
        for (PinManager.Pin pin : visible) {
            box(fill, matrix, pin.pos(), pin.color, pin == active ? 0.28f : 0.14f, true);
        }
        draw(fill);

        BufferBuilder lines = begin(tesselator, VertexFormat.Mode.DEBUG_LINES);
        for (PinManager.Pin pin : visible) {
            box(lines, matrix, pin.pos(), pin.color, 0.95f, false);
        }
        draw(lines);

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        pose.popPose();

        // Labels
        Font font = mc.font;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (PinManager.Pin pin : visible) {
            BlockPos p = pin.pos();
            double dx = p.getX() + 0.5 - cam.x;
            double dy = p.getY() + 1.4 - cam.y;
            double dz = p.getZ() + 0.5 - cam.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            Component text = Text.literal(pin.label + " (" + Math.round(distance) + "m)");
            pose.pushPose();
            pose.translate(dx, dy, dz);
            pose.mulPose(camera.rotation());
            float scale = (float) (0.025 * Math.max(1.0, distance / 10.0));
            pose.scale(-scale, -scale, scale);
            float x = -font.width(text) / 2f;
            //#if MC >= 1.19.4
            font.drawInBatch(text, x, 0, 0xFF000000 | pin.color, false, pose.last().pose(), buffers,
                    Font.DisplayMode.SEE_THROUGH, 0x66000000, LightTexture.FULL_BRIGHT);
            //#else
            //$$ font.drawInBatch(text, x, 0, 0xFF000000 | pin.color, false, pose.last().pose(), buffers, true,
            //$$         0x66000000, LightTexture.FULL_BRIGHT);
            //#endif
            pose.popPose();
        }
        buffers.endBatch();
    }

    private static boolean tooFar(BlockPos pos, Vec3 cam, double max) {
        double dx = pos.getX() + 0.5 - cam.x;
        double dy = pos.getY() + 0.5 - cam.y;
        double dz = pos.getZ() + 0.5 - cam.z;
        return dx * dx + dy * dy + dz * dz > max * max;
    }

    private static void box(BufferBuilder b, Matrix4f m, BlockPos pos, int rgb, float alpha, boolean faces) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float bl = (rgb & 0xFF) / 255f;
        float e = 0.002f;
        float x0 = pos.getX() - e;
        float y0 = pos.getY() - e;
        float z0 = pos.getZ() - e;
        float x1 = pos.getX() + 1 + e;
        float y1 = pos.getY() + 1 + e;
        float z1 = pos.getZ() + 1 + e;
        if (faces) {
            quad(b, m, r, g, bl, alpha, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            quad(b, m, r, g, bl, alpha, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            quad(b, m, r, g, bl, alpha, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            quad(b, m, r, g, bl, alpha, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            quad(b, m, r, g, bl, alpha, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            quad(b, m, r, g, bl, alpha, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            return;
        }
        float[][] c = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 }, { x0, y0, z1 }, { x0, y1, z0 },
                { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } };
        int[][] edges = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 }, { 0, 4 },
                { 1, 5 }, { 2, 6 }, { 3, 7 } };
        for (int[] edge : edges) {
            float[] a = c[edge[0]];
            float[] d = c[edge[1]];
            vertex(b, m, a[0], a[1], a[2], r, g, bl, alpha);
            vertex(b, m, d[0], d[1], d[2], r, g, bl, alpha);
        }
    }

    private static void quad(BufferBuilder b, Matrix4f m, float r, float g, float bl, float a, float... v) {
        for (int i = 0; i < 12; i += 3) {
            vertex(b, m, v[i], v[i + 1], v[i + 2], r, g, bl, a);
        }
    }

    private static BufferBuilder begin(Tesselator tesselator, VertexFormat.Mode mode) {
        //#if MC >= 1.21
        //$$ return tesselator.begin(mode, DefaultVertexFormat.POSITION_COLOR);
        //#else
        BufferBuilder b = tesselator.getBuilder();
        b.begin(mode, DefaultVertexFormat.POSITION_COLOR);
        return b;
        //#endif
    }

    private static void draw(BufferBuilder b) {
        //#if MC >= 1.21
        //$$ var mesh = b.build();
        //$$ if (mesh != null) {
        //$$     BufferUploader.drawWithShader(mesh);
        //$$ }
        //#elseif MC >= 1.19
        BufferUploader.drawWithShader(b.end());
        //#else
        //$$ b.end();
        //$$ BufferUploader.end(b);
        //#endif
    }

    private static void vertex(BufferBuilder b, Matrix4f m, float x, float y, float z, float r, float g, float bl,
            float a) {
        //#if MC >= 1.21
        //$$ b.addVertex(m, x, y, z).setColor(r, g, bl, a);
        //#else
        b.vertex(m, x, y, z).color(r, g, bl, a).endVertex();
        //#endif
    }

    /** HUD line: "Pin: Label  x y z  ↑ 42m ▲3". Called by the loader's HUD overlay (see ClientEvents). */
    public static void renderHud(Gfx graphics, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null || !DeclogConfig.CLIENT.pinsHud.get()) {
            return;
        }
        PinManager.Pin pin = PinManager.active();
        if (pin == null) {
            return;
        }
        Player player = mc.player;
        Component line;
        int color = 0xFF000000 | pin.color;
        if (!mc.level.dimension().location().toString().equals(pin.dimension)) {
            line = Text.translatable("ae2declogger.hud.other_dimension", pin.label, pin.dimension);
        } else {
            BlockPos p = pin.pos();
            double dx = p.getX() + 0.5 - player.getX();
            double dz = p.getZ() + 0.5 - player.getZ();
            int dy = p.getY() - player.blockPosition().getY();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            String arrow = Formatting.directionArrow(player.getYRot(), dx, dz);
            String height3 = dy == 0 ? "" : (dy > 0 ? " ▲" + dy : " ▼" + (-dy));
            if (horizontal < 2.5 && Math.abs(dy) < 3) {
                line = Text.translatable("ae2declogger.hud.arrived", pin.label);
            } else {
                line = Text.translatable("ae2declogger.hud.navigate", pin.label, p.getX(), p.getY(), p.getZ(),
                        arrow, Math.round(horizontal), height3);
            }
        }
        int textWidth = mc.font.width(line);
        int x = (width - textWidth) / 2;
        int y = DeclogConfig.CLIENT.hudY.get();
        graphics.fill(x - 3, y - 2, x + textWidth + 3, y + 10, 0x88000000);
        graphics.drawString(mc.font, line, x, y, color, true);
    }
}
