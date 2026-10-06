package com.syhros.ae2declogger.client.pins;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.logic.Formatting;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/** Draws pins in the world (outline box + label, through walls) and the navigation line on the HUD. */
public final class PinRenderer {

    private PinRenderer() {
    }

    public static void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || PinManager.isHidden() || PinManager.visiblePins().isEmpty()
                || !DeclogConfig.CLIENT.pinsInWorld.get()) {
            return;
        }
        String dim = mc.level.dimension().location().toString();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        double maxDist = DeclogConfig.CLIENT.pinRenderDistance.get();
        PoseStack pose = event.getPoseStack();
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

        BufferBuilder fill = tesselator.getBuilder();
        fill.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (PinManager.Pin pin : visible) {
            box(fill, matrix, pin.pos(), pin.color, pin == active ? 0.28f : 0.14f, true);
        }
        BufferUploader.drawWithShader(fill.end());

        BufferBuilder lines = tesselator.getBuilder();
        lines.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (PinManager.Pin pin : visible) {
            box(lines, matrix, pin.pos(), pin.color, 0.95f, false);
        }
        BufferUploader.drawWithShader(lines.end());

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
            Component text = Component.literal(pin.label + " (" + Math.round(distance) + "m)");
            pose.pushPose();
            pose.translate(dx, dy, dz);
            pose.mulPose(camera.rotation());
            float scale = (float) (0.025 * Math.max(1.0, distance / 10.0));
            pose.scale(-scale, -scale, scale);
            float x = -font.width(text) / 2f;
            font.drawInBatch(text, x, 0, 0xFF000000 | pin.color, false, pose.last().pose(), buffers,
                    Font.DisplayMode.SEE_THROUGH, 0x66000000, LightTexture.FULL_BRIGHT);
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
            b.vertex(m, a[0], a[1], a[2]).color(r, g, bl, alpha).endVertex();
            b.vertex(m, d[0], d[1], d[2]).color(r, g, bl, alpha).endVertex();
        }
    }

    private static void quad(BufferBuilder b, Matrix4f m, float r, float g, float bl, float a, float... v) {
        for (int i = 0; i < 12; i += 3) {
            b.vertex(m, v[i], v[i + 1], v[i + 2]).color(r, g, bl, a).endVertex();
        }
    }

    /** HUD line: "Pin: Label  x y z  ↑ 42m ▲3". */
    public static void renderHud(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
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
            line = Component.translatable("ae2declogger.hud.other_dimension", pin.label, pin.dimension);
        } else {
            BlockPos p = pin.pos();
            double dx = p.getX() + 0.5 - player.getX();
            double dz = p.getZ() + 0.5 - player.getZ();
            int dy = p.getY() - player.blockPosition().getY();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            String arrow = Formatting.directionArrow(player.getYRot(), dx, dz);
            String height3 = dy == 0 ? "" : (dy > 0 ? " ▲" + dy : " ▼" + (-dy));
            if (horizontal < 2.5 && Math.abs(dy) < 3) {
                line = Component.translatable("ae2declogger.hud.arrived", pin.label);
            } else {
                line = Component.translatable("ae2declogger.hud.navigate", pin.label, p.getX(), p.getY(), p.getZ(),
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
