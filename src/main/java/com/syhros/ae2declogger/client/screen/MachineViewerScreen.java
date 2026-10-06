package com.syhros.ae2declogger.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.client.ClientState;
import com.syhros.ae2declogger.client.pins.PinManager;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.logic.Formatting;
import com.syhros.ae2declogger.network.DeclogNetwork;

import net.minecraft.ChatFormatting;
//#if MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;

/**
 * Read-only view of a machine's slots, tanks, progress and errors. There is no container behind it, so nothing can be
 * taken out or put in. Refreshes once a second while open.
 */
public class MachineViewerScreen extends Screen {

    private static final int SLOT = 18;

    @Nullable
    private final Screen parent;
    private final ResourceKey<Level> dimension;
    private final BlockPos pos;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int bodyTop;
    private int bodyBottom;
    private double scroll;
    private int ticks;
    private boolean goingBack;

    public MachineViewerScreen(@Nullable Screen parent, ResourceKey<Level> dimension, BlockPos pos) {
        super(Text.translatable("ae2declogger.viewer.title"));
        this.parent = parent;
        this.dimension = dimension;
        this.pos = pos.immutable();
    }

    public boolean shows(MachineSnapshot snapshot) {
        return snapshot.dimension().equals(dimension) && snapshot.pos().equals(pos);
    }

    /** Panel height that fits the current snapshot, within the screen. */
    private int targetPanelHeight() {
        int w = Math.min(width - 12, 9 * SLOT + 120);
        MachineSnapshot snap = snapshot();
        int body = snap == null ? 60 : measure(snap, w - 12);
        return Math.max(140, Math.min(height - 12, 30 + body + 36));
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 12, 9 * SLOT + 120);
        panelH = targetPanelHeight();
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        bodyTop = top + 30;
        bodyBottom = top + panelH - 34;
        int by = top + panelH - 20;
        int bw = (panelW - 12 - 9) / 4;
        int bx = left + 6;
        addRenderableWidget(Widgets.builder(Text.translatable(parent != null ? "ae2declogger.button.back"
                : "ae2declogger.button.close"), b -> onClose()).bounds(bx, by, bw, 16).build());
        addRenderableWidget(Widgets.builder(Text.translatable("ae2declogger.button.refresh"),
                b -> refresh()).bounds(bx + bw + 3, by, bw, 16).build());
        addRenderableWidget(Widgets.builder(Text.translatable("ae2declogger.button.pin"), b -> pin(false))
                .bounds(bx + 2 * (bw + 3), by, bw, 16).build());
        addRenderableWidget(Widgets.builder(Text.translatable("ae2declogger.button.goto"), b -> pin(true))
                .bounds(bx + 3 * (bw + 3), by, bw, 16).build());
    }

    private void refresh() {
        DeclogNetwork.sendToServer(new DeclogNetwork.RequestMachine(dimension, pos, false));
    }

    private void pin(boolean navigate) {
        MachineSnapshot snap = snapshot();
        String label = snap != null ? snap.name().getString() : pos.toShortString();
        PinManager.Pin pin = PinManager.add(label, dimension, pos);
        if (navigate) {
            PinManager.setActive(pin);
            goingBack = false;
            if (minecraft != null) {
                minecraft.setScreen(null);
            }
        } else if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Text.translatable("ae2declogger.pin.added", label,
                    pos.getX() + " " + pos.getY() + " " + pos.getZ()), true);
        }
    }

    @Nullable
    private MachineSnapshot snapshot() {
        MachineSnapshot s = ClientState.machine;
        return s != null && shows(s) ? s : null;
    }

    @Override
    public void tick() {
        if (++ticks % 20 == 0) {
            refresh();
        }
    }

    @Override
    public void onClose() {
        if (parent != null && minecraft != null) {
            goingBack = true;
            if (parent instanceof DecloggerScreen monitor) {
                monitor.keepSubscriptionForChild();
            }
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public void removed() {
        // Leaving the viewer some other way than "Back" also ends the monitor subscription.
        if (parent instanceof DecloggerScreen && !goingBack) {
            DeclogNetwork.sendToServer(new DeclogNetwork.CloseMonitor());
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    //#if MC >= 1.20.2
    //$$ public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
    //#else
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    //#endif
        scroll -= delta * 12;
        return true;
    }

    /**
     * The darkened (or blurred) world behind the panel. From 1.20.2 Screen.render() draws it too, and drawing it twice
     * crashes on 1.21 ("Can only blur once per frame"), so there it is drawn here and the automatic one is skipped.
     */
    private void drawBackground(Gfx g, int mouseX, int mouseY, float partialTick) {
        //#if MC >= 1.20.2
        //$$ super.renderBackground(g.raw(), mouseX, mouseY, partialTick);
        //#else
        renderBackground(g.raw());
        //#endif
    }

    //#if MC >= 1.20.2
    //$$ @Override
    //$$ public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    //$$     // drawn by drawBackground() at the start of render()
    //$$ }
    //#endif

    @Override
    //#if MC >= 1.20
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Gfx g = new Gfx(graphics);
    //#else
    //$$ public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
    //$$     Gfx g = new Gfx(pose, this);
    //#endif
        if (targetPanelHeight() != panelH) {
            //#if MC >= 1.19
            rebuildWidgets();
            //#else
            //$$ clearWidgets();
            //$$ init();
            //#endif
        }
        drawBackground(g, mouseX, mouseY, partialTick);
        UiUtil.panel(g, left, top, panelW, panelH);
        MachineSnapshot snap = snapshot();
        int x = left + 6;
        int w = panelW - 12;
        if (snap == null) {
            g.drawCenteredString(font, Text.translatable("ae2declogger.monitor.loading"), left + panelW / 2,
                    top + 40, UiUtil.TEXT_DIM);
            super.render(g.raw(), mouseX, mouseY, partialTick);
            return;
        }

        UiUtil.text(g, font, snap.name(), x, top + 6, w, UiUtil.TEXT);
        String where = pos.getX() + " " + pos.getY() + " " + pos.getZ() + "  " + dimension.location();
        if (!snap.tier().isEmpty()) {
            where = snap.tier() + "  " + where;
        }
        UiUtil.text(g, font, Text.literal(where), x, top + 17, w, UiUtil.TEXT_FAINT);

        List<Component> tooltip = null;
        // Body is scrollable: status, bars, errors, then slot sections.
        int contentHeight = measure(snap, w);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - (bodyBottom - bodyTop))));
        g.enableScissor(left + 1, bodyTop, left + panelW - 1, bodyBottom);
        int y = bodyTop - (int) scroll;

        if (hasStatusLine(snap)) {
            Component status = Text.translatable(snap.status().translationKey())
                    .withStyle(s -> s.withColor(snap.status().color()));
            Component statusLine = snap.statusText() != null
                    ? Text.translatable("ae2declogger.viewer.status_detail", status, snap.statusText())
                    : Text.translatable("ae2declogger.viewer.status", status);
            UiUtil.text(g, font, statusLine, x, y, w, UiUtil.TEXT);
            y += 11;
        }

        if (snap.duration() > 0) {
            float fraction = snap.progress() / (float) snap.duration();
            UiUtil.progressBar(g, x, y + 1, w - 90, 5, fraction, 0x55AAFF);
            g.drawString(font, String.format(Locale.ROOT, "%.1fs / %.1fs", snap.progress() / 20.0,
                    snap.duration() / 20.0), x + w - 86, y, UiUtil.TEXT_DIM, false);
            y += 10;
        }
        if (snap.energyCapacity() > 0) {
            float fraction = snap.energyStored() / (float) snap.energyCapacity();
            UiUtil.progressBar(g, x, y + 1, w - 90, 5, fraction, fraction < 0.05f ? 0xFF4444 : 0xFFCC33);
            g.drawString(font, Formatting.compactAmount(snap.energyStored()) + " / "
                    + Formatting.compactAmount(snap.energyCapacity()) + " " + snap.energyUnit(), x + w - 86, y,
                    UiUtil.TEXT_DIM, false);
            y += 10;
        }
        for (Component error : snap.errors()) {
            UiUtil.text(g, font, Text.literal("⚠ ").append(error), x, y, w, 0xFFFF6B6B);
            y += 10;
        }
        y += 3;

        int perRow = Math.max(1, (w - 4) / SLOT);
        for (MachineSnapshot.Section section : snap.sections()) {
            UiUtil.text(g, font, section.title(), x, y, w, UiUtil.TEXT_DIM);
            y += 10;
            List<MachineSnapshot.Slot> slots = section.slots();
            for (int i = 0; i < slots.size(); i++) {
                int sx = x + (i % perRow) * SLOT;
                int sy = y + (i / perRow) * SLOT;
                List<Component> t = drawSlot(g, slots.get(i), sx, sy, mouseX, mouseY);
                if (t != null && mouseY >= bodyTop && mouseY < bodyBottom) {
                    tooltip = t;
                }
            }
            y += ((slots.size() + perRow - 1) / perRow) * SLOT + 4;
        }
        if (snap.sections().isEmpty()) {
            g.drawString(font, Text.translatable("ae2declogger.viewer.no_slots"), x, y, UiUtil.TEXT_FAINT, false);
            y += 10;
        }
        if (snap.truncated()) {
            g.drawString(font, Text.translatable("ae2declogger.viewer.truncated"), x, y, UiUtil.TEXT_FAINT,
                    false);
        }
        g.disableScissor();
        UiUtil.scrollbar(g, left + panelW - 5, bodyTop, bodyBottom - bodyTop, scroll, contentHeight);

        g.drawString(font, Text.translatable("ae2declogger.viewer.read_only").withStyle(ChatFormatting.ITALIC),
                x, bodyBottom + 3, UiUtil.TEXT_FAINT, false);

        super.render(g.raw(), mouseX, mouseY, partialTick);
        if (tooltip != null) {
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        } else {
            Widgets.renderTooltips(g, font, children(), mouseX, mouseY);
        }
    }

    /** Non-GregTech blocks have no status to report; skip the line instead of showing "Unknown". */
    private static boolean hasStatusLine(MachineSnapshot snap) {
        return snap.status() != com.syhros.ae2declogger.data.MachineStatus.UNKNOWN || snap.statusText() != null;
    }

    private int measure(MachineSnapshot snap, int w) {
        int h = hasStatusLine(snap) ? 11 : 0;
        if (snap.duration() > 0) {
            h += 10;
        }
        if (snap.energyCapacity() > 0) {
            h += 10;
        }
        h += snap.errors().size() * 10 + 3;
        int perRow = Math.max(1, (w - 4) / SLOT);
        for (MachineSnapshot.Section section : snap.sections()) {
            h += 10 + ((section.slots().size() + perRow - 1) / perRow) * SLOT + 4;
        }
        return h + 12;
    }

    @Nullable
    private List<Component> drawSlot(Gfx g, MachineSnapshot.Slot slot, int x, int y, int mouseX,
            int mouseY) {
        g.fill(x, y, x + SLOT - 1, y + SLOT - 1, 0xFF373B44);
        g.fill(x + 1, y + 1, x + SLOT - 2, y + SLOT - 2, 0xFF1C1F25);
        int border = 0;
        if (slot.has(MachineSnapshot.FLAG_FULL)) {
            border = 0xFFFF4444;
        } else if (slot.has(MachineSnapshot.FLAG_PATTERN_INPUT)) {
            border = 0xFFFFCC33;
        }
        if (border != 0) {
            g.renderOutline(x, y, SLOT - 1, SLOT - 1, border);
        }
        GenericStack stack = slot.stack();
        if (stack != null) {
            g.key(stack.what(), x + 1, y + 1);
            String amount = UiUtil.amount(stack.what(), stack.amount());
            if (stack.what() instanceof AEFluidKey || stack.amount() > 1) {
                g.push();
                g.translate(0, 0, 200);
                g.scale(0.5f, 0.5f);
                int tw = font.width(amount);
                g.drawString(font, amount, (x + SLOT - 2) * 2 - tw, (y + SLOT - 6) * 2, 0xFFFFFFFF, true);
                g.pop();
            }
        }
        boolean hover = mouseX >= x && mouseX < x + SLOT - 1 && mouseY >= y && mouseY < y + SLOT - 1;
        if (!hover) {
            return null;
        }
        g.fill(x + 1, y + 1, x + SLOT - 2, y + SLOT - 2, 0x40FFFFFF);
        List<Component> lines = new ArrayList<>();
        if (stack == null) {
            lines.add(Text.translatable("ae2declogger.viewer.empty_slot").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(stack.what().getDisplayName());
            String cap = slot.capacity() > 0 ? " / " + UiUtil.amount(stack.what(), slot.capacity()) : "";
            lines.add(Text.literal(UiUtil.amount(stack.what(), stack.amount()) + cap)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (slot.has(MachineSnapshot.FLAG_FULL)) {
            lines.add(Text.translatable("ae2declogger.viewer.slot_full").withStyle(ChatFormatting.RED));
        }
        if (slot.has(MachineSnapshot.FLAG_PATTERN_INPUT)) {
            lines.add(Text.translatable("ae2declogger.viewer.slot_pattern_input")
                    .withStyle(ChatFormatting.GOLD));
        }
        return lines;
    }
}
