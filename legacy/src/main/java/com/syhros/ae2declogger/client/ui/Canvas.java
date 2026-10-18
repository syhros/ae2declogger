package com.syhros.ae2declogger.client.ui;

import java.util.List;

import com.syhros.ae2declogger.data.LKey;

/**
 * What the shared screens draw with. Each version's host screen implements it with that version's renderer, so the
 * monitor and the machine viewer are written once for all three versions.
 */
public interface Canvas {

    int LINE_HEIGHT = 9;

    void fill(int x1, int y1, int x2, int y2, int argb);

    default void outline(int x, int y, int w, int h, int argb) {
        fill(x, y, x + w, y + 1, argb);
        fill(x, y + h - 1, x + w, y + h, argb);
        fill(x, y + 1, x + 1, y + h - 1, argb);
        fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }

    int width(String text);

    void text(String text, int x, int y, int argb, boolean shadow);

    /** Half-size text, right-aligned at {@code right} with its baseline area ending at {@code bottom}. */
    void smallText(String text, int right, int bottom, int argb);

    /** A 16x16 item icon. */
    void item(LKey key, int x, int y);

    /** Clips drawing to the rectangle until {@link #endScissor}. */
    void scissor(int x1, int y1, int x2, int y2);

    void endScissor();

    /** Lines may use § colour codes. */
    void tooltip(List<String> lines, int mouseX, int mouseY);

    /** {@code text} cut to {@code maxWidth}, with "..." when cut. */
    default String clip(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (width(text) <= maxWidth) {
            return text;
        }
        int ellipsis = width("...");
        int end = text.length();
        while (end > 0 && width(text.substring(0, end)) + ellipsis > maxWidth) {
            end--;
        }
        return text.substring(0, end) + "...";
    }
}
