package com.syhros.ae2declogger.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen written once for all legacy versions: layout, drawing through a {@link Canvas}, mouse and keys. Each
 * version's host screen sizes it, forwards input and draws it.
 */
public abstract class UiScreen {

    public int width;
    public int height;
    protected final List<Btn> buttons = new ArrayList<>();
    protected final List<TextInput> inputs = new ArrayList<>();
    /** Set by the screen to ask its host to close it. */
    private boolean closeRequested;

    /** (Re)builds the layout for a new size. */
    public final void init(int w, int h) {
        width = w;
        height = h;
        buttons.clear();
        inputs.clear();
        layout();
    }

    protected abstract void layout();

    /** Draws the screen; returns tooltip lines for what the mouse is over, or null. */
    protected abstract List<String> draw(Canvas c, int mx, int my);

    public final void render(Canvas c, int mx, int my) {
        List<String> tooltip = draw(c, mx, my);
        for (Btn b : buttons) {
            b.draw(c, mx, my);
        }
        for (TextInput in : inputs) {
            in.draw(c);
        }
        if (tooltip == null) {
            for (Btn b : buttons) {
                if (b.tooltip != null && b.isOver(mx, my)) {
                    tooltip = java.util.Collections.singletonList(b.tooltip);
                }
            }
        }
        if (tooltip != null && !tooltip.isEmpty()) {
            c.tooltip(tooltip, mx, my);
        }
    }

    public boolean mouseClicked(int mx, int my, int button) {
        for (TextInput in : inputs) {
            in.focused = in.isOver(mx, my);
        }
        if (button == 0) {
            for (Btn b : buttons) {
                if (b.click(mx, my)) {
                    return true;
                }
            }
        }
        for (TextInput in : inputs) {
            if (in.focused) {
                return true;
            }
        }
        return clicked(mx, my, button);
    }

    /** A click not on a button or input. */
    protected boolean clicked(int mx, int my, int button) {
        return false;
    }

    /** {@code delta} > 0 is a scroll up. */
    public void mouseScrolled(int mx, int my, double delta) {
    }

    /**
     * A key press. Returns false when the host should handle it (Escape closes the screen, the inventory key closes it
     * when no input is focused).
     */
    public boolean keyTyped(char ch, int key, boolean ctrl) {
        for (TextInput in : inputs) {
            if (in.key(ch, key, ctrl)) {
                return true;
            }
        }
        return key != Keys.ESCAPE && keyPressed(ch, key, ctrl);
    }

    protected boolean keyPressed(char ch, int key, boolean ctrl) {
        return false;
    }

    /** True while a text input has focus (the host then keeps keys like E from closing the screen). */
    public boolean typing() {
        for (TextInput in : inputs) {
            if (in.focused && in.visible) {
                return true;
            }
        }
        return false;
    }

    public void tick() {
        for (TextInput in : inputs) {
            in.tick();
        }
    }

    /** The screen went away (closed or replaced). */
    public void removed() {
    }

    protected final void requestClose() {
        closeRequested = true;
    }

    /** Polled by the host every frame. */
    public final boolean takeCloseRequest() {
        boolean r = closeRequested;
        closeRequested = false;
        return r;
    }

    protected static double clampScroll(double scroll, int content, int view) {
        return Math.max(0, Math.min(scroll, Math.max(0, content - view)));
    }
}
