package com.syhros.ae2declogger.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.syhros.ae2declogger.client.ui.Canvas;
import com.syhros.ae2declogger.client.ui.Keys;
import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.data.LKey;

import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputMappings;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;

/** 1.16.5 screen that hosts a shared {@link UiScreen} and draws it. */
public final class HostScreen extends Screen implements Canvas {

    final UiScreen ui;
    private MatrixStack pose;

    HostScreen(UiScreen ui) {
        super(new StringTextComponent("AE2 Declogger"));
        this.ui = ui;
    }

    @Override
    protected void init() {
        ui.init(width, height);
    }

    @Override
    public void render(MatrixStack matrix, int mouseX, int mouseY, float partialTicks) {
        pose = matrix;
        renderBackground(matrix);
        ui.render(this, mouseX, mouseY);
        closeIfAsked();
    }

    private void closeIfAsked() {
        if (ui.takeCloseRequest() && minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean used = ui.mouseClicked((int) mouseX, (int) mouseY, button);
        closeIfAsked();
        return used;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        ui.mouseScrolled((int) mouseX, (int) mouseY, delta);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean used = ui.keyTyped('\0', map(keyCode), ctrl);
        if (!used) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || !ui.typing() && minecraft != null
                    && minecraft.options.keyInventory.isActiveAndMatches(InputMappings.getKey(keyCode, scanCode))) {
                onClose();
                return true;
            }
        }
        closeIfAsked();
        return used;
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        boolean used = ui.keyTyped(ch, Keys.OTHER, (modifiers & GLFW.GLFW_MOD_CONTROL) != 0);
        closeIfAsked();
        return used;
    }

    private static int map(int keyCode) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE:
                return Keys.ESCAPE;
            case GLFW.GLFW_KEY_BACKSPACE:
                return Keys.BACKSPACE;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                return Keys.ENTER;
            case GLFW.GLFW_KEY_TAB:
                return Keys.TAB;
            case GLFW.GLFW_KEY_DELETE:
                return Keys.DELETE;
            case GLFW.GLFW_KEY_F:
                return Keys.F;
            default:
                if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
                    return Keys.DIGIT_1 + keyCode - GLFW.GLFW_KEY_1;
                }
                return Keys.OTHER;
        }
    }

    @Override
    public void tick() {
        ui.tick();
        closeIfAsked();
    }

    @Override
    public void removed() {
        ui.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- Canvas

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        AbstractGui.fill(pose, x1, y1, x2, y2, argb);
    }

    @Override
    public int width(String text) {
        return font.width(text);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        if (shadow) {
            font.drawShadow(pose, text, x, y, argb);
        } else {
            font.draw(pose, text, x, y, argb);
        }
    }

    @Override
    public void smallText(String text, int right, int bottom, int argb) {
        pose.pushPose();
        pose.translate(0, 0, 300);
        pose.scale(0.5f, 0.5f, 1f);
        font.drawShadow(pose, text, right * 2 - font.width(text), bottom * 2 - 8, argb);
        pose.popPose();
    }

    @Override
    public void item(LKey key, int x, int y) {
        if (key != null) {
            itemRenderer.renderAndDecorateItem(key.stack(), x, y);
        }
    }

    @Override
    public void scissor(int x1, int y1, int x2, int y2) {
        double s = minecraft.getWindow().getGuiScale();
        int h = minecraft.getWindow().getHeight();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x1 * s), (int) (h - y2 * s), (int) Math.max(0, (x2 - x1) * s),
                (int) Math.max(0, (y2 - y1) * s));
    }

    @Override
    public void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    @Override
    public void tooltip(List<String> lines, int mouseX, int mouseY) {
        List<ITextComponent> components = new ArrayList<>();
        for (String l : lines) {
            components.add(new StringTextComponent(l));
        }
        renderComponentTooltip(pose, components, mouseX, mouseY);
        RenderSystem.disableLighting();
    }
}
