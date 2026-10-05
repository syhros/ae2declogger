package com.syhros.ae2declogger.client.screen;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.Formatting;
import com.syhros.ae2declogger.logic.JobState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;

/** Drawing and text helpers shared by the screens. */
public final class UiUtil {

    public static final int PANEL_BG = 0xF0101318;
    public static final int PANEL_BORDER = 0xFF3C4250;
    public static final int ROW_HOVER = 0x22FFFFFF;
    public static final int ROW_SELECTED = 0x44FFFFFF;
    public static final int TEXT = 0xFFE8E8E8;
    public static final int TEXT_DIM = 0xFF9AA0A8;
    public static final int TEXT_FAINT = 0xFF6A707A;

    private UiUtil() {
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.renderOutline(x, y, w, h, PANEL_BORDER);
    }

    public static void progressBar(GuiGraphics g, int x, int y, int w, int h, float fraction, int rgb) {
        g.fill(x, y, x + w, y + h, 0xFF2A2E36);
        int filled = Math.round(Math.max(0, Math.min(1, fraction)) * w);
        if (filled > 0) {
            g.fill(x, y, x + filled, y + h, 0xFF000000 | rgb);
        }
    }

    /** Draws text cut to {@code maxWidth} with an ellipsis. Returns the width drawn. */
    public static int text(GuiGraphics g, Font font, Component text, int x, int y, int maxWidth, int color) {
        if (maxWidth <= 0) {
            return 0;
        }
        FormattedCharSequence seq = clip(font, text, maxWidth);
        g.drawString(font, seq, x, y, color, false);
        return font.width(seq);
    }

    /** Right-aligned version of {@link #text}. */
    public static void textRight(GuiGraphics g, Font font, Component text, int right, int y, int maxWidth,
            int color) {
        FormattedCharSequence seq = clip(font, text, maxWidth);
        g.drawString(font, seq, right - font.width(seq), y, color, false);
    }

    public static FormattedCharSequence clip(Font font, Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        int ellipsis = font.width("...");
        FormattedText cut = font.substrByWidth(text, Math.max(0, maxWidth - ellipsis));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, Component.literal("...")));
    }

    public static void key(GuiGraphics g, AEKey key, int x, int y) {
        AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x, y, key);
    }

    public static MutableComponent stateLabel(JobState state) {
        return Component.translatable(state.translationKey()).withStyle(s -> s.withColor(state.color()));
    }

    public static Component reasonText(BlockReason reason, @Nullable Component detail) {
        MutableComponent text = Component.translatable(reason.translationKey());
        if (detail != null && !detail.getString().isEmpty()) {
            text = text.append(": ").append(detail);
        }
        return text;
    }

    public static int reasonColor(BlockReason reason, JobState state) {
        if (!reason.isProblem()) {
            return TEXT_DIM;
        }
        return state.isProblem() ? 0xFFFF6B6B : 0xFFFFD966;
    }

    /** "64", "1.5k" or "1,000 mB" for fluids. */
    public static String amount(AEKey key, long amount) {
        if (key instanceof AEFluidKey) {
            return amount >= 1000 ? Formatting.compactAmount(amount / 1000) + " B" : amount + " mB";
        }
        return Formatting.compactAmount(amount);
    }

    public static String coords(MachineRef ref) {
        return ref.pos().getX() + " " + ref.pos().getY() + " " + ref.pos().getZ();
    }

    public static Component ago(long ms) {
        return Component.translatable("ae2declogger.ago", Formatting.duration(ms));
    }

    /** Thin scrollbar on the right of a list area. */
    public static void scrollbar(GuiGraphics g, int x, int top, int height, double scroll, int contentHeight) {
        if (contentHeight <= height) {
            return;
        }
        g.fill(x, top, x + 3, top + height, 0x33FFFFFF);
        int thumb = Math.max(12, height * height / contentHeight);
        int travel = height - thumb;
        int pos = (int) Math.round(travel * (scroll / Math.max(1, contentHeight - height)));
        g.fill(x, top + pos, x + 3, top + pos + thumb, 0xAAFFFFFF);
    }
}
