package com.syhros.ae2declogger.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.syhros.ae2declogger.client.ui.Btn;
import com.syhros.ae2declogger.client.ui.Canvas;
import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.Formatting;
import com.syhros.ae2declogger.net.Messages;

/**
 * Read-only view of a machine's slots, tanks, progress and errors. There is no container behind it, so nothing can be
 * taken out or put in. Refreshes once a second while open.
 */
public final class MachineViewerScreen extends UiScreen {

    private static final int SLOT = 18;

    private final UiScreen parent;
    private final LPos pos;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int bodyTop;
    private int bodyBottom;
    private double scroll;
    private int ticks;
    private boolean goingBack;

    public MachineViewerScreen(UiScreen parent, LPos pos) {
        this.parent = parent;
        this.pos = pos;
    }

    public boolean shows(MachineSnapshot snapshot) {
        return snapshot.pos.equals(pos);
    }

    private MachineSnapshot snapshot() {
        MachineSnapshot s = ClientState.machine;
        return s != null && shows(s) ? s : null;
    }

    private int targetPanelHeight() {
        int w = Math.min(width - 12, 9 * SLOT + 120);
        MachineSnapshot snap = snapshot();
        int body = snap == null ? 60 : measure(snap, w - 12);
        return Math.max(140, Math.min(height - 12, 30 + body + 36));
    }

    @Override
    protected void layout() {
        panelW = Math.min(width - 12, 9 * SLOT + 120);
        panelH = targetPanelHeight();
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        bodyTop = top + 30;
        bodyBottom = top + panelH - 34;
        int by = top + panelH - 20;
        int bw = (panelW - 12 - 9) / 4;
        int bx = left + 6;
        buttons.add(new Btn(bx, by, bw, 16, Lang.t(parent != null ? "ae2declogger.button.back"
                : "ae2declogger.button.close"), this::back));
        buttons.add(new Btn(bx + bw + 3, by, bw, 16, Lang.t("ae2declogger.button.refresh"), this::refresh));
        buttons.add(new Btn(bx + 2 * (bw + 3), by, bw, 16, Lang.t("ae2declogger.button.pin"), () -> pin(false)));
        buttons.add(new Btn(bx + 3 * (bw + 3), by, bw, 16, Lang.t("ae2declogger.button.goto"), () -> pin(true)));
    }

    private void refresh() {
        ClientPlat.send(Messages.requestMachine(pos, false));
    }

    private void pin(boolean navigate) {
        MachineSnapshot snap = snapshot();
        String label = snap != null ? Lang.of(snap.name) : pos.coords();
        PinManager.Pin pin = PinManager.add(label, pos);
        if (navigate) {
            PinManager.setActive(pin);
            goingBack = false;
            ClientPlat.closeScreen();
        } else {
            ClientPlat.status(Lang.t("ae2declogger.pin.added", label, pos.coords()));
        }
    }

    /** Back to the monitor (keeping its subscription), or closed when opened on its own. */
    private void back() {
        if (parent != null) {
            goingBack = true;
            if (parent instanceof DecloggerScreen) {
                ((DecloggerScreen) parent).keepSubscriptionForChild();
            }
            ClientPlat.openScreen(parent);
        } else {
            requestClose();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0) {
            refresh();
        }
        if (targetPanelHeight() != panelH) {
            init(width, height);
        }
    }

    @Override
    public void removed() {
        // Leaving the viewer some other way than "Back" also ends the monitor subscription.
        if (parent instanceof DecloggerScreen && !goingBack) {
            ClientPlat.send(Messages.closeMonitor());
        }
    }

    @Override
    public void mouseScrolled(int mx, int my, double delta) {
        scroll -= delta * 12;
    }

    private static boolean hasStatusLine(MachineSnapshot snap) {
        return snap.status != MachineStatus.UNKNOWN || snap.statusText != null;
    }

    private int measure(MachineSnapshot snap, int w) {
        int h = hasStatusLine(snap) ? 11 : 0;
        if (snap.duration > 0) {
            h += 10;
        }
        if (snap.energyCapacity > 0) {
            h += 10;
        }
        h += snap.errors.size() * 10 + 3;
        int perRow = Math.max(1, (w - 4) / SLOT);
        for (MachineSnapshot.Section section : snap.sections) {
            h += 10 + ((section.slots.size() + perRow - 1) / perRow) * SLOT + 4;
        }
        return h + 12;
    }

    @Override
    protected List<String> draw(Canvas c, int mx, int my) {
        UiUtil.panel(c, left, top, panelW, panelH);
        MachineSnapshot snap = snapshot();
        int x = left + 6;
        int w = panelW - 12;
        if (snap == null) {
            UiUtil.centeredWrapped(c, Lang.t("ae2declogger.monitor.loading"), left + panelW / 2, top + 40,
                    panelW - 16, UiUtil.TEXT_DIM);
            return null;
        }
        UiUtil.text(c, Lang.of(snap.name), x, top + 6, w, UiUtil.TEXT);
        String where = pos.coords() + "  " + pos.dim;
        if (!snap.tier.isEmpty()) {
            where = snap.tier + "  " + where;
        }
        UiUtil.text(c, where, x, top + 17, w, UiUtil.TEXT_FAINT);

        List<String> tooltip = null;
        int contentHeight = measure(snap, w);
        scroll = clampScroll(scroll, contentHeight, bodyBottom - bodyTop);
        c.scissor(left + 1, bodyTop, left + panelW - 1, bodyBottom);
        int y = bodyTop - (int) scroll;
        if (hasStatusLine(snap)) {
            String status = Lang.t(snap.status.translationKey());
            String line = snap.statusText != null
                    ? Lang.t("ae2declogger.viewer.status_detail", status, Lang.of(snap.statusText))
                    : Lang.t("ae2declogger.viewer.status", status);
            UiUtil.text(c, line, x, y, w, 0xFF000000 | snap.status.color());
            y += 11;
        }
        if (snap.duration > 0) {
            UiUtil.progressBar(c, x, y + 1, w - 90, 5, snap.progress / (float) snap.duration, 0x55AAFF);
            c.text(String.format(Locale.ROOT, "%.1fs / %.1fs", snap.progress / 20.0, snap.duration / 20.0),
                    x + w - 86, y, UiUtil.TEXT_DIM, false);
            y += 10;
        }
        if (snap.energyCapacity > 0) {
            float fraction = snap.energyStored / (float) snap.energyCapacity;
            UiUtil.progressBar(c, x, y + 1, w - 90, 5, fraction, fraction < 0.05f ? 0xFF4444 : 0xFFCC33);
            c.text(Formatting.compactAmount(snap.energyStored) + " / " + Formatting.compactAmount(snap.energyCapacity)
                    + " " + snap.energyUnit, x + w - 86, y, UiUtil.TEXT_DIM, false);
            y += 10;
        }
        for (LText error : snap.errors) {
            UiUtil.text(c, "⚠ " + Lang.of(error), x, y, w, UiUtil.RED);
            y += 10;
        }
        y += 3;
        int perRow = Math.max(1, (w - 4) / SLOT);
        for (MachineSnapshot.Section section : snap.sections) {
            UiUtil.text(c, Lang.of(section.title), x, y, w, UiUtil.TEXT_DIM);
            y += 10;
            List<MachineSnapshot.Slot> slots = section.slots;
            for (int i = 0; i < slots.size(); i++) {
                int sx = x + (i % perRow) * SLOT;
                int sy = y + (i / perRow) * SLOT;
                List<String> t = drawSlot(c, slots.get(i), sx, sy, mx, my);
                if (t != null && my >= bodyTop && my < bodyBottom) {
                    tooltip = t;
                }
            }
            y += ((slots.size() + perRow - 1) / perRow) * SLOT + 4;
        }
        if (snap.sections.isEmpty()) {
            c.text(Lang.t("ae2declogger.viewer.no_slots"), x, y, UiUtil.TEXT_FAINT, false);
            y += 10;
        }
        if (snap.truncated) {
            c.text(Lang.t("ae2declogger.viewer.truncated"), x, y, UiUtil.TEXT_FAINT, false);
        }
        c.endScissor();
        UiUtil.scrollbar(c, left + panelW - 5, bodyTop, bodyBottom - bodyTop, scroll, contentHeight);
        UiUtil.text(c, Lang.t("ae2declogger.viewer.read_only"), x, bodyBottom + 3, w, UiUtil.TEXT_FAINT);
        return tooltip;
    }

    private List<String> drawSlot(Canvas c, MachineSnapshot.Slot slot, int x, int y, int mx, int my) {
        c.fill(x, y, x + SLOT - 1, y + SLOT - 1, 0xFF373B44);
        c.fill(x + 1, y + 1, x + SLOT - 2, y + SLOT - 2, 0xFF1C1F25);
        int border = 0;
        if (slot.has(MachineSnapshot.FLAG_FULL)) {
            border = 0xFFFF4444;
        } else if (slot.has(MachineSnapshot.FLAG_PATTERN_INPUT)) {
            border = 0xFFFFCC33;
        }
        if (border != 0) {
            c.outline(x, y, SLOT - 1, SLOT - 1, border);
        }
        String amount = slot.fluid != null ? fluidAmount(slot.amount) : UiUtil.amount(slot.amount);
        if (slot.item != null) {
            c.item(slot.item, x + 1, y + 1);
        } else if (slot.fluid != null) {
            c.fill(x + 3, y + 3, x + SLOT - 4, y + SLOT - 4, 0xFF3F76C9);
        }
        if (slot.fluid != null || slot.item != null && slot.amount > 1) {
            c.smallText(amount, x + SLOT - 2, y + SLOT - 2, 0xFFFFFFFF);
        }
        boolean hover = mx >= x && mx < x + SLOT - 1 && my >= y && my < y + SLOT - 1;
        if (!hover) {
            return null;
        }
        c.fill(x + 1, y + 1, x + SLOT - 2, y + SLOT - 2, 0x40FFFFFF);
        List<String> lines = new ArrayList<>();
        if (slot.isEmpty()) {
            lines.add("§7" + Lang.t("ae2declogger.viewer.empty_slot"));
        } else {
            lines.add(slot.item != null ? slot.item.displayName() : Lang.of(slot.fluid));
            String cap = slot.capacity > 0
                    ? " / " + (slot.fluid != null ? fluidAmount(slot.capacity) : UiUtil.amount(slot.capacity)) : "";
            lines.add("§7" + amount + cap);
        }
        if (slot.has(MachineSnapshot.FLAG_FULL)) {
            lines.add("§c" + Lang.t("ae2declogger.viewer.slot_full"));
        }
        if (slot.has(MachineSnapshot.FLAG_PATTERN_INPUT)) {
            lines.add("§6" + Lang.t("ae2declogger.viewer.slot_pattern_input"));
        }
        return lines;
    }

    private static String fluidAmount(long mb) {
        return mb >= 1000 ? Formatting.compactAmount(mb / 1000) + " B" : mb + " mB";
    }
}
