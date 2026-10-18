package com.syhros.ae2declogger.client;

import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.syhros.ae2declogger.client.ui.Canvas;
import com.syhros.ae2declogger.client.ui.Keys;
import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.data.LKey;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;

/** 1.7.10 screen that hosts a shared {@link UiScreen} and draws it. */
public final class HostScreen extends GuiScreen implements Canvas {

    final UiScreen ui;

    HostScreen(UiScreen ui) {
        this.ui = ui;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ui.init(width, height);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ui.render(this, mouseX, mouseY);
        closeIfAsked();
    }

    private void closeIfAsked() {
        if (ui.takeCloseRequest()) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        ui.mouseClicked(mouseX, mouseY, mouseButton);
        closeIfAsked();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mx = Mouse.getEventX() * width / mc.displayWidth;
            int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            ui.mouseScrolled(mx, my, wheel > 0 ? 1 : -1);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        int key = map(keyCode);
        if (!ui.keyTyped(typedChar, key, isCtrlKeyDown())) {
            if (keyCode == Keyboard.KEY_ESCAPE
                    || !ui.typing() && keyCode == mc.gameSettings.keyBindInventory.getKeyCode()) {
                mc.displayGuiScreen(null);
            }
        }
        closeIfAsked();
    }

    private static int map(int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                return Keys.ESCAPE;
            case Keyboard.KEY_BACK:
                return Keys.BACKSPACE;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                return Keys.ENTER;
            case Keyboard.KEY_TAB:
                return Keys.TAB;
            case Keyboard.KEY_DELETE:
                return Keys.DELETE;
            case Keyboard.KEY_F:
                return Keys.F;
            default:
                if (keyCode >= Keyboard.KEY_1 && keyCode <= Keyboard.KEY_9) {
                    return Keys.DIGIT_1 + keyCode - Keyboard.KEY_1;
                }
                return Keys.OTHER;
        }
    }

    @Override
    public void updateScreen() {
        ui.tick();
        closeIfAsked();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        ui.removed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---------------------------------------------------------------- Canvas

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        Gui.drawRect(x1, y1, x2, y2, argb);
    }

    @Override
    public int width(String text) {
        return fontRendererObj.getStringWidth(text);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        fontRendererObj.drawString(text, x, y, argb, shadow);
    }

    @Override
    public void smallText(String text, int right, int bottom, int argb) {
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 300);
        GL11.glScalef(0.5f, 0.5f, 1f);
        fontRendererObj.drawString(text, right * 2 - fontRendererObj.getStringWidth(text), bottom * 2 - 8, argb, true);
        GL11.glPopMatrix();
    }

    @Override
    public void item(LKey key, int x, int y) {
        if (key == null) {
            return;
        }
        GL11.glPushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), key.stack(), x, y);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glPopMatrix();
    }

    @Override
    public void scissor(int x1, int y1, int x2, int y2) {
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int s = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x1 * s, mc.displayHeight - y2 * s, Math.max(0, (x2 - x1) * s), Math.max(0, (y2 - y1) * s));
    }

    @Override
    public void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    @Override
    public void tooltip(List<String> lines, int mouseX, int mouseY) {
        drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
        GL11.glDisable(GL11.GL_LIGHTING);
    }
}
