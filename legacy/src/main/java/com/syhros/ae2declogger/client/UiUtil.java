package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.client.ui.Canvas;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.Formatting;
import com.syhros.ae2declogger.logic.JobState;

/** Colours, labels and drawing helpers shared by the screens. Same look as the modern versions. */
public final class UiUtil {

    public static final int PANEL_BG = 0xF0101318;
    public static final int PANEL_BORDER = 0xFF3C4250;
    public static final int ROW_HOVER = 0x22FFFFFF;
    public static final int ROW_SELECTED = 0x44FFFFFF;
    public static final int TEXT = 0xFFE8E8E8;
    public static final int TEXT_DIM = 0xFF9AA0A8;
    public static final int TEXT_FAINT = 0xFF6A707A;
    public static final int RED = 0xFFFF6B6B;

    private UiUtil() {
    }

    public static void panel(Canvas c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, PANEL_BG);
        c.outline(x, y, w, h, PANEL_BORDER);
    }

    public static void progressBar(Canvas c, int x, int y, int w, int h, float fraction, int rgb) {
        c.fill(x, y, x + w, y + h, 0xFF2A2E36);
        int filled = Math.round(Math.max(0, Math.min(1, fraction)) * w);
        if (filled > 0) {
            c.fill(x, y, x + filled, y + h, 0xFF000000 | rgb);
        }
    }

    /** Draws text cut to {@code maxWidth}; returns the width drawn. */
    public static int text(Canvas c, String text, int x, int y, int maxWidth, int color) {
        String s = c.clip(text, maxWidth);
        c.text(s, x, y, color, false);
        return c.width(s);
    }

    public static void textRight(Canvas c, String text, int right, int y, int maxWidth, int color) {
        String s = c.clip(text, maxWidth);
        c.text(s, right - c.width(s), y, color, false);
    }

    /** Text wrapped to {@code maxWidth}, centred on {@code cx}. */
    public static void centeredWrapped(Canvas c, String text, int cx, int y, int maxWidth, int color) {
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (c.width(next) > maxWidth && line.length() > 0) {
                String s = line.toString();
                c.text(s, cx - c.width(s) / 2, lineY, color, false);
                lineY += 10;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            String s = c.clip(line.toString(), maxWidth);
            c.text(s, cx - c.width(s) / 2, lineY, color, false);
        }
    }

    public static String stateLabel(JobState state) {
        return Lang.t(state.translationKey());
    }

    public static int stateColor(JobState state) {
        return 0xFF000000 | state.color();
    }

    public static String reasonText(BlockReason reason, LText detail) {
        String text = Lang.t(reason.translationKey());
        String d = Lang.of(detail);
        return d.isEmpty() ? text : text + ": " + d;
    }

    public static int reasonColor(BlockReason reason, JobState state) {
        if (!reason.isProblem()) {
            return TEXT_DIM;
        }
        return state.isProblem() ? RED : 0xFFFFD966;
    }

    public static String amount(long amount) {
        return Formatting.compactAmount(amount);
    }

    public static String coords(MachineRef ref) {
        return ref.pos.coords();
    }

    public static String ago(long ms) {
        return Lang.t("ae2declogger.ago", Formatting.duration(ms));
    }

    /** Thin scrollbar on the right of a list area. */
    public static void scrollbar(Canvas c, int x, int top, int height, double scroll, int contentHeight) {
        if (contentHeight <= height) {
            return;
        }
        c.fill(x, top, x + 3, top + height, 0x33FFFFFF);
        int thumb = Math.max(12, height * height / contentHeight);
        int travel = height - thumb;
        int pos = (int) Math.round(travel * (scroll / Math.max(1, contentHeight - height)));
        c.fill(x, top + pos, x + 3, top + pos + thumb, 0xAAFFFFFF);
    }

    /** A § colour code close to {@code argb}, for tooltip lines. */
    public static String code(int argb) {
        int rgb = argb & 0xFFFFFF;
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        if (r > 200 && g < 120 && b < 120) {
            return "§c";
        }
        if (r > 200 && g > 180 && b < 120) {
            return "§e";
        }
        if (r > 200 && g > 120 && b < 80) {
            return "§6";
        }
        if (g > 200 && r < 150 && b < 150) {
            return "§a";
        }
        if (b > 200 && r < 150) {
            return "§9";
        }
        if (g > 180 && b > 180 && r < 150) {
            return "§b";
        }
        if (r < 130 && g < 130 && b < 130) {
            return "§8";
        }
        return "§7";
    }
}
