package com.syhros.ae2declogger.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.client.ui.Canvas;
import com.syhros.ae2declogger.data.LKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.item.ItemStack;

/** 1.16.5 drawing over AE2's crafting status screen, and where its table cells are. */
final class OverlayCanvas implements Canvas {

    private static final String CPU_SCREEN = "appeng.client.gui.me.crafting.CraftingCPUScreen";
    private static final int COLS = 3;
    private static final int ROWS = 5;
    private static final int CELL_W = 67;
    private static final int CELL_H = 22;
    private static final String[][] NAMES = {
            { CPU_SCREEN, "field table" },
            { CPU_SCREEN, "field scrollbar" },
            { CPU_SCREEN, "field status" },
            { "appeng.client.gui.me.crafting.AbstractTableRenderer", "field x" },
            { "appeng.client.gui.me.crafting.AbstractTableRenderer", "field y" },
            { "appeng.container.me.crafting.CraftingStatus", "method getEntries" },
            { "appeng.container.me.crafting.CraftingStatusEntry", "method getItem" },
            { "appeng.client.gui.widgets.Scrollbar", "method getCurrentScroll" } };
    private static Field tableField;
    private static Field scrollbarField;
    private static Field statusField;
    private static Field tableX;
    private static Field tableY;
    private static boolean broken;

    private final MatrixStack pose;
    private final FontRenderer font = Minecraft.getInstance().font;

    OverlayCanvas(MatrixStack pose) {
        this.pose = pose;
    }

    /** AE2's crafting CPU screen and the crafting status screen (its subclass), recognised by name. */
    static boolean isCraftingScreen(Object gui) {
        return cpuScreenClass(gui) != null;
    }

    private static Class<?> cpuScreenClass(Object gui) {
        for (Class<?> c = gui == null ? null : gui.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(CPU_SCREEN)) {
                return c;
            }
        }
        return null;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** The entries AE2 draws right now, with their screen rectangles (AE2 8's fixed table layout). */
    static List<CraftingOverlay.Cell> cells(Object gui) {
        Class<?> screenClass = broken ? null : cpuScreenClass(gui);
        if (screenClass == null) {
            return Collections.emptyList();
        }
        List<CraftingOverlay.Cell> cells = new ArrayList<>();
        try {
            if (tableField == null) {
                tableField = field(screenClass, "table");
                scrollbarField = field(screenClass, "scrollbar");
                statusField = field(screenClass, "status");
                Class<?> renderer = Class.forName("appeng.client.gui.me.crafting.AbstractTableRenderer");
                tableX = field(renderer, "x");
                tableY = field(renderer, "y");
            }
            Object status = statusField.get(gui);
            Object table = tableField.get(gui);
            if (status == null || table == null) {
                return cells;
            }
            List<?> entries = (List<?>) status.getClass().getMethod("getEntries").invoke(status);
            Object bar = scrollbarField.get(gui);
            int scroll = bar == null ? 0 : (Integer) bar.getClass().getMethod("getCurrentScroll").invoke(bar);
            ContainerScreen<?> c = (ContainerScreen<?>) gui;
            int left = c.getGuiLeft() + tableX.getInt(table);
            int top = c.getGuiTop() + tableY.getInt(table);
            Method getItem = null;
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int i = (row + scroll) * COLS + col;
                    if (i >= entries.size()) {
                        return cells;
                    }
                    Object entry = entries.get(i);
                    if (entry == null) {
                        continue;
                    }
                    if (getItem == null) {
                        getItem = entry.getClass().getMethod("getItem");
                    }
                    LKey key = LKey.of((ItemStack) getItem.invoke(entry));
                    if (key != null) {
                        cells.add(new CraftingOverlay.Cell(key, left + col * (CELL_W + 1), top + row * (CELL_H + 1),
                                CELL_W, CELL_H));
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
        pose.scale(0.5f, 0.5f, 1f);
        font.drawShadow(pose, text, right * 2 - font.width(text), bottom * 2 - 8, argb);
        pose.popPose();
    }

    @Override
    public void item(LKey key, int x, int y) {
        if (key != null) {
            Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(key.stack(), x, y);
        }
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
