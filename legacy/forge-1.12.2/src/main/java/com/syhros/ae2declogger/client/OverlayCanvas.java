package com.syhros.ae2declogger.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.client.ui.Canvas;
import com.syhros.ae2declogger.data.LKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;

import appeng.api.storage.data.IAEItemStack;

/** 1.12.2 drawing over AE2's crafting status screen, and where its table cells are. */
final class OverlayCanvas implements Canvas {

    private static final int COLS = 3;
    private static final int ROWS = 6;
    private static final int CELL_W = 67;
    private static final int CELL_H = 23;
    private static final String[][] NAMES = {
            { "appeng.client.gui.implementations.GuiCraftingCPU", "field visual" },
            { "appeng.client.gui.AEBaseGui", "method getScrollBar" },
            { "appeng.client.gui.widgets.GuiScrollbar", "method getCurrentScroll" } };
    private static Field visualField;
    private static boolean broken;

    private final FontRenderer font = Minecraft.getMinecraft().fontRenderer;

    /** AE2's crafting CPU screen and the crafting status screen (its subclass), recognised by name. */
    static boolean isCraftingScreen(Object gui) {
        for (Class<?> c = gui == null ? null : gui.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("appeng.client.gui.implementations.GuiCraftingCPU")) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> craftingCpuClass(Object gui) {
        for (Class<?> c = gui.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("appeng.client.gui.implementations.GuiCraftingCPU")) {
                return c;
            }
        }
        return null;
    }

    /** AE2's scrollbar position (rows scrolled), through the protected getScrollBar(). */
    private static int scroll(Object gui) throws ReflectiveOperationException {
        for (Class<?> c = gui.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("getScrollBar");
                m.setAccessible(true);
                Object bar = m.invoke(gui);
                return bar == null ? 0 : (Integer) bar.getClass().getMethod("getCurrentScroll").invoke(bar);
            } catch (NoSuchMethodException e) {
                // keep looking
            }
        }
        return 0;
    }

    /** The entries AE2 draws right now, with their screen rectangles (AE2 Extended Life's fixed layout). */
    @SuppressWarnings("unchecked")
    static List<CraftingOverlay.Cell> cells(GuiScreen gui) {
        if (broken || !isCraftingScreen(gui)) {
            return Collections.emptyList();
        }
        List<CraftingOverlay.Cell> cells = new ArrayList<>();
        try {
            if (visualField == null) {
                visualField = craftingCpuClass(gui).getDeclaredField("visual");
                visualField.setAccessible(true);
            }
            List<IAEItemStack> visual = (List<IAEItemStack>) visualField.get(gui);
            if (visual == null) {
                return cells;
            }
            GuiContainer c = (GuiContainer) gui;
            int scroll = scroll(gui);
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int i = (row + scroll) * COLS + col;
                    if (i >= visual.size()) {
                        return cells;
                    }
                    LKey key = visual.get(i) != null ? LKey.of(visual.get(i).createItemStack()) : null;
                    if (key != null) {
                        cells.add(new CraftingOverlay.Cell(key, c.getGuiLeft() + 9 + col * (CELL_W + 1),
                                c.getGuiTop() + 18 + row * CELL_H, CELL_W, CELL_H));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            Ae2Declogger.LOG.warn("Could not read AE2's crafting status layout; stuck entries will not be marked", e);
        }
        return cells;
    }

    /** The AE2 classes, fields and methods read above that are missing, for the client self-test. */
    static List<String> missingNames() {
        List<String> missing = new ArrayList<>();
        for (String[] n : NAMES) {
            try {
                Class<?> c = Class.forName(n[0]);
                if (n[1].startsWith("field ")) {
                    c.getDeclaredField(n[1].substring(6));
                } else if (n[1].startsWith("method ")) {
                    c.getDeclaredMethod(n[1].substring(7));
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                missing.add(n[0] + " " + n[1]);
            }
        }
        return missing;
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        Gui.drawRect(x1, y1, x2, y2, argb);
    }

    @Override
    public int width(String text) {
        return font.getStringWidth(text);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        font.drawString(text, x, y, argb, shadow);
    }

    @Override
    public void smallText(String text, int right, int bottom, int argb) {
        GlStateManager.pushMatrix();
        GlStateManager.scale(0.5f, 0.5f, 1f);
        font.drawString(text, right * 2 - font.getStringWidth(text), bottom * 2 - 8, argb, true);
        GlStateManager.popMatrix();
    }

    @Override
    public void item(LKey key, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(key.stack(), x, y);
        RenderHelper.disableStandardItemLighting();
    }

    @Override
    public void scissor(int x1, int y1, int x2, int y2) {
    }

    @Override
    public void endScissor() {
    }

    @Override
    public void tooltip(List<String> lines, int mouseX, int mouseY) {
    }
}
