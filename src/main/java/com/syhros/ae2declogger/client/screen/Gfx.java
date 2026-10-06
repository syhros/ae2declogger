package com.syhros.ae2declogger.client.screen;

import com.syhros.ae2declogger.Text;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
//#if MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.gui.GuiComponent;
//$$ import net.minecraft.client.gui.screens.Screen;
//#endif

import appeng.api.stacks.AEKey;

/**
 * The drawing calls the Declogger's screens make, with GuiGraphics' names. Minecraft 1.20 introduced GuiGraphics;
 * before it screens drew with a PoseStack and static helpers. Screens get one of these from their render method and
 * pass it on; nothing else in the client code depends on how a Minecraft version draws.
 */
public final class Gfx {

    //#if MC >= 1.20
    private final GuiGraphics g;

    public Gfx(GuiGraphics g) {
        this.g = g;
    }

    /** The wrapped GuiGraphics, for the few places that hand it to another mod (AE2's key rendering). */
    public GuiGraphics raw() {
        return g;
    }
    //#else
    //$$ private final PoseStack pose;
    //$$ private final Screen screen;
    //$$
    //$$ /** @param screen the screen being drawn (tooltips are drawn through it) */
    //$$ public Gfx(PoseStack pose, Screen screen) {
    //$$     this.pose = pose;
    //$$     this.screen = screen;
    //$$ }
    //$$
    //$$ public PoseStack raw() {
    //$$     return pose;
    //$$ }
    //#endif

    public void fill(int x0, int y0, int x1, int y1, int color) {
        //#if MC >= 1.20
        g.fill(x0, y0, x1, y1, color);
        //#else
        //$$ GuiComponent.fill(pose, x0, y0, x1, y1, color);
        //#endif
    }

    /** One-pixel frame inside x, y, w, h. */
    public void renderOutline(int x, int y, int w, int h, int color) {
        fill(x, y, x + w, y + 1, color);
        fill(x, y + h - 1, x + w, y + h, color);
        fill(x, y + 1, x + 1, y + h - 1, color);
        fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        //#if MC >= 1.20
        return g.drawString(font, text, x, y, color, shadow);
        //#else
        //$$ return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
        //#endif
    }

    public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        return drawString(font, text.getVisualOrderText(), x, y, color, shadow);
    }

    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        return drawString(font, Text.literal(text), x, y, color, shadow);
    }

    public int drawString(Font font, Component text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public void drawCenteredString(Font font, Component text, int centerX, int y, int color) {
        FormattedCharSequence seq = text.getVisualOrderText();
        drawString(font, seq, centerX - font.width(seq) / 2, y, color, true);
    }

    /** Clips drawing to the rectangle (GUI coordinates) until {@link #disableScissor()}. */
    public void enableScissor(int x0, int y0, int x1, int y1) {
        //#if MC >= 1.20
        g.enableScissor(x0, y0, x1, y1);
        //#else
        //$$ var window = Minecraft.getInstance().getWindow();
        //$$ double scale = window.getGuiScale();
        //$$ RenderSystem.enableScissor((int) (x0 * scale), (int) (window.getHeight() - y1 * scale),
        //$$         Math.max(0, (int) ((x1 - x0) * scale)), Math.max(0, (int) ((y1 - y0) * scale)));
        //#endif
    }

    public void disableScissor() {
        //#if MC >= 1.20
        g.disableScissor();
        //#else
        //$$ RenderSystem.disableScissor();
        //#endif
    }

    public void renderComponentTooltip(Font font, List<Component> lines, int x, int y) {
        //#if MC >= 1.20
        g.renderComponentTooltip(font, lines, x, y);
        //#else
        //$$ screen.renderComponentTooltip(pose, lines, x, y);
        //#endif
    }

    /** Draws part of a texture (u, v, w, h within a textureWidth x textureHeight image). */
    public void blit(ResourceLocation texture, int x, int y, int u, int v, int w, int h, int textureWidth,
            int textureHeight) {
        //#if MC >= 1.20
        g.blit(texture, x, y, u, v, w, h, textureWidth, textureHeight);
        //#else
        //$$ RenderSystem.setShaderTexture(0, texture);
        //$$ GuiComponent.blit(pose, x, y, u, v, w, h, textureWidth, textureHeight);
        //#endif
    }

    /** An AE2 item or fluid icon, 16x16. */
    public void key(AEKey key, int x, int y) {
        //#if MC >= 1.20
        appeng.api.client.AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x, y, key);
        //#elseif MC >= 1.19
        //$$ appeng.api.client.AEStackRendering.drawInGui(Minecraft.getInstance(), pose, x, y, 0, key);
        //#else
        //$$ appeng.api.client.AEStackRendering.drawInGui(Minecraft.getInstance(), pose, x, y, 0, key);
        //#endif
    }

    // ---------------------------------------------------------------- transforms

    public void push() {
        //#if MC >= 1.20
        g.pose().pushPose();
        //#else
        //$$ pose.pushPose();
        //#endif
    }

    public void pop() {
        //#if MC >= 1.20
        g.pose().popPose();
        //#else
        //$$ pose.popPose();
        //#endif
    }

    /** Moves drawing by x, y; z lifts it above what is already drawn (ignored where GUIs have no depth). */
    public void translate(float x, float y, float z) {
        //#if MC >= 1.20
        g.pose().translate(x, y, z);
        //#else
        //$$ pose.translate(x, y, z);
        //#endif
    }

    public void scale(float x, float y) {
        //#if MC >= 1.20
        g.pose().scale(x, y, 1f);
        //#else
        //$$ pose.scale(x, y, 1f);
        //#endif
    }
}
