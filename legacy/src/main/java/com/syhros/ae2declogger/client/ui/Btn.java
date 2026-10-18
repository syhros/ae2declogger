package com.syhros.ae2declogger.client.ui;

/** A flat button drawn by the shared screens (the three versions' button widgets have nothing in common). */
public final class Btn {

    public int x;
    public int y;
    public final int w;
    public final int h;
    public String label;
    public int labelColor = 0xFFE8E8E8;
    public boolean active = true;
    public boolean visible = true;
    public String tooltip;
    private final Runnable onPress;

    public Btn(int x, int y, int w, int h, String label, Runnable onPress) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.label = label;
        this.onPress = onPress;
    }

    public boolean isOver(int mx, int my) {
        return visible && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public void draw(Canvas c, int mx, int my) {
        if (!visible) {
            return;
        }
        boolean hover = active && isOver(mx, my);
        int bg = !active ? 0xFF1E2128 : hover ? 0xFF404756 : 0xFF2A2F3A;
        c.fill(x, y, x + w, y + h, bg);
        c.outline(x, y, w, h, hover ? 0xFF8A93A6 : 0xFF4A5160);
        String text = c.clip(label, w - 4);
        int color = active ? labelColor : 0xFF6A707A;
        c.text(text, x + (w - c.width(text)) / 2, y + (h - 8) / 2, color, false);
    }

    /** Runs the action when clicked; true when the click was on this button. */
    public boolean click(int mx, int my) {
        if (!isOver(mx, my)) {
            return false;
        }
        if (active) {
            onPress.run();
        }
        return true;
    }
}
