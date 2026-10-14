package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.client.screen.Widgets;
import com.syhros.ae2declogger.compat.Refl;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Where the Declogger button goes on an AE2 screen. On a terminal it sits centred directly above AE2's Crafting Status
 * button (MEStorageScreen.craftingStatusBtn, the same private field in AE2 11 to 26.1); anywhere else, and if that
 * button is missing or hidden, at the top right corner of the screen.
 */
final class TerminalButtonPlacement {

    static final int SIZE = 16;

    private TerminalButtonPlacement() {
    }

    /** {x, y} for the 16x16 button. Call after the screen has laid out its widgets (ScreenEvent init, post). */
    static int[] place(AbstractContainerScreen<?> screen) {
        try {
            if (Refl.fieldOrNull(screen, "craftingStatusBtn") instanceof AbstractWidget status && status.visible) {
                int x = Widgets.x(status) + (status.getWidth() - SIZE) / 2;
                int y = Widgets.y(status) - SIZE - 2;
                if (y >= 2) {
                    return new int[] { x, y };
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall back to the corner
        }
        int x = screen.getGuiLeft() + screen.getXSize() - 18;
        int y = screen.getGuiTop() - 18;
        if (y < 2) {
            x = screen.getGuiLeft() + screen.getXSize() + 2;
            y = screen.getGuiTop() + 2;
        }
        return new int[] { x, y };
    }
}
