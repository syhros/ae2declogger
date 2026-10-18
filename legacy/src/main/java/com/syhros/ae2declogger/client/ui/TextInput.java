package com.syhros.ae2declogger.client.ui;

/** A one-line text box for the search field. Typing appends, Backspace deletes, Ctrl+Backspace clears. */
public final class TextInput {

    public int x;
    public int y;
    public final int w;
    public final int h;
    public boolean visible = true;
    public boolean focused;
    public String hint = "";
    private final int maxLength;
    private String value = "";
    private int blink;

    public TextInput(int x, int y, int w, int h, int maxLength) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.maxLength = maxLength;
    }

    public String value() {
        return value;
    }

    public void setValue(String v) {
        value = v == null ? "" : v.length() > maxLength ? v.substring(0, maxLength) : v;
    }

    public boolean isOver(int mx, int my) {
        return visible && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public void tick() {
        blink++;
    }

    public void draw(Canvas c) {
        if (!visible) {
            return;
        }
        c.fill(x, y, x + w, y + h, 0xFF000000);
        c.outline(x, y, w, h, focused ? 0xFFE0E0E0 : 0xFF707070);
        if (value.isEmpty() && !focused) {
            c.text(c.clip(hint, w - 6), x + 3, y + (h - 8) / 2, 0xFF6A707A, false);
            return;
        }
        String shown = value;
        while (!shown.isEmpty() && c.width(shown + "_") > w - 6) {
            shown = shown.substring(1);
        }
        boolean cursor = focused && (blink / 6) % 2 == 0;
        c.text(shown + (cursor ? "_" : ""), x + 3, y + (h - 8) / 2, 0xFFE0E0E0, false);
    }

    /** Handles a key; true when the input used it. */
    public boolean key(char ch, int key, boolean ctrl) {
        if (!focused || !visible) {
            return false;
        }
        if (key == Keys.BACKSPACE) {
            if (ctrl) {
                value = "";
            } else if (!value.isEmpty()) {
                value = value.substring(0, value.length() - 1);
            }
            return true;
        }
        if (key == Keys.ESCAPE || key == Keys.ENTER || key == Keys.TAB) {
            focused = false;
            return key != Keys.ESCAPE;
        }
        if (ch >= 32 && ch != 127 && value.length() < maxLength) {
            value += ch;
            return true;
        }
        return ch >= 32;
    }
}
