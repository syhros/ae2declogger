package com.syhros.ae2declogger.client;

import java.util.ArrayList;
import java.util.List;

import com.syhros.ae2declogger.client.ui.Canvas;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.Formatting;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.net.Messages;

/**
 * Adds to AE2's crafting status screen (the Crafting Status tab of a terminal and the crafting CPU screen): entries
 * whose step is slow, stalled or blocked get a coloured frame, and clicking an entry opens a popup with what the
 * Declogger knows about that step and an Inspect button that opens the monitor on it. The legacy port of the modern
 * overlay; each version finds the table cells in AE2's screen and calls the methods below from its screen events.
 */
public final class CraftingOverlay {

    /** One entry of AE2's table on screen. */
    public static final class Cell {
        final LKey key;
        final int x;
        final int y;
        final int w;
        final int h;

        public Cell(LKey key, int x, int y, int w, int h) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private static final int POPUP_WIDTH = 170;
    private static final int PAD = 5;

    private static Object watched;
    private static LKey selected;
    private static Cell selectedCell;
    private static int[] popupRect;
    private static int[] inspectRect;

    private CraftingOverlay() {
    }

    /** AE2's crafting status screen is now open. */
    public static void onOpen(Object screen) {
        if (watched != screen) {
            watched = screen;
            clearSelection();
            ClientState.focusJob = -1;
            ClientPlat.send(Messages.watchCraftingScreen(true));
        }
    }

    /** Another screen (or none) replaced whatever was open. */
    public static void onOtherScreen(Object screen) {
        if (watched != null && screen != watched) {
            watched = null;
            clearSelection();
            ClientPlat.send(Messages.watchCraftingScreen(false));
        }
    }

    public static boolean watching(Object screen) {
        return screen != null && screen == watched;
    }

    /** After AE2's screen has drawn itself. */
    public static void render(Canvas c, Object screen, List<Cell> cells, int mouseX, int mouseY, int screenW,
            int screenH) {
        if (screen != watched) {
            return;
        }
        JobView job = focusedJob(null);
        if (job != null) {
            for (Cell cell : cells) {
                StepView step = step(job, cell.key);
                if (step != null && step.state != JobState.IN_PROGRESS && !step.state.isFinished()) {
                    c.outline(cell.x, cell.y, cell.w, cell.h, 0xFF000000 | step.state.color());
                }
            }
        }
        if (selected != null && selectedCell != null) {
            c.outline(selectedCell.x, selectedCell.y, selectedCell.w, selectedCell.h, 0xFFFFFFFF);
            renderPopup(c, mouseX, mouseY, screenW, screenH);
        }
    }

    /** A left click; true when it was used here and AE2's screen must not see it. */
    public static boolean click(Object screen, List<Cell> cells, double x, double y) {
        if (screen != watched) {
            return false;
        }
        if (inspectRect != null && inside(inspectRect, x, y)) {
            inspect();
            return true;
        }
        if (popupRect != null && inside(popupRect, x, y)) {
            return true;
        }
        Cell hit = null;
        for (Cell cell : cells) {
            if (cell.contains(x, y)) {
                hit = cell;
            }
        }
        if (hit != null) {
            if (hit.key.equals(selected)) {
                clearSelection();
            } else {
                selected = hit.key;
                selectedCell = hit;
                popupRect = null;
                inspectRect = null;
            }
        } else {
            clearSelection();
        }
        return false;
    }

    private static void clearSelection() {
        selected = null;
        selectedCell = null;
        popupRect = null;
        inspectRect = null;
    }

    private static void inspect() {
        if (selected == null) {
            return;
        }
        JobView job = focusedJob(selected);
        ClientState.pendingFocusJob = job != null ? job.id : -1;
        ClientState.pendingFocusStep = selected;
        ClientPlat.send(Messages.openMonitor(true));
        clearSelection();
    }

    private static void renderPopup(Canvas c, int mouseX, int mouseY, int screenW, int screenH) {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        int frameColor = 0xFFAAAAAA;
        lines.add(selected.displayName());
        colors.add(0xFFFFFFFF);
        JobView job = focusedJob(selected);
        StepView step = job != null ? step(job, selected) : null;
        if (step != null) {
            frameColor = 0xFF000000 | step.state.color();
            String state = Lang.t(step.state.translationKey());
            if (step.state != JobState.IN_PROGRESS) {
                state = Lang.t("ae2declogger.overlay.state_for", state,
                        Formatting.duration(Math.max(0, ClientState.serverNow() - step.sinceMs)));
            }
            lines.add(state);
            colors.add(frameColor);
            if (step.reason != BlockReason.NONE) {
                lines.add(UiUtil.reasonText(step.reason, step.detail));
                colors.add(0xFFAAAAAA);
            }
            lines.add(Lang.t("ae2declogger.overlay.amounts", Formatting.compactAmount(step.stored),
                    Formatting.compactAmount(step.active), Formatting.compactAmount(step.pending)));
            colors.add(0xFF666666);
            MachineRef m = step.machines.isEmpty() ? null : step.machines.get(0);
            if (m != null) {
                lines.add(Lang.t("ae2declogger.overlay.machine", Lang.of(m.name), m.pos.x, m.pos.y, m.pos.z));
                colors.add(0xFFAAAAAA);
            }
        } else if (job != null && job.steps.isEmpty() && job.state != JobState.IN_PROGRESS) {
            frameColor = 0xFF000000 | job.state.color();
            lines.add(Lang.t("ae2declogger.overlay.job_state_for", Lang.t(job.state.translationKey()),
                    Formatting.duration(Math.max(0, ClientState.serverNow() - job.lastActivityAtMs))));
            colors.add(frameColor);
            if (job.reason != BlockReason.NONE) {
                lines.add(UiUtil.reasonText(job.reason, job.detail));
                colors.add(0xFFAAAAAA);
            }
        } else {
            String key = job != null ? "ae2declogger.overlay.no_problem"
                    : ClientState.summary == null ? "ae2declogger.overlay.loading" : "ae2declogger.overlay.no_data";
            lines.add(Lang.t(key));
            colors.add(0xFFAAAAAA);
        }
        List<String> wrapped = new ArrayList<>();
        List<Integer> wrappedColors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            for (String part : wrap(c, lines.get(i), POPUP_WIDTH - 2 * PAD)) {
                wrapped.add(part);
                wrappedColors.add(colors.get(i));
            }
        }
        int buttonH = 14;
        int height = PAD + wrapped.size() * 10 + 4 + buttonH + PAD;
        int x = selectedCell.x;
        int y = selectedCell.y + selectedCell.h + 2;
        if (y + height > screenH - 2) {
            y = selectedCell.y - height - 2;
        }
        x = Math.max(2, Math.min(x, screenW - POPUP_WIDTH - 2));
        y = Math.max(2, y);
        popupRect = new int[] { x, y, POPUP_WIDTH, height };
        c.fill(x - 1, y - 1, x + POPUP_WIDTH + 1, y + height + 1, frameColor);
        c.fill(x, y, x + POPUP_WIDTH, y + height, 0xF0101018);
        int ty = y + PAD;
        for (int i = 0; i < wrapped.size(); i++) {
            c.text(wrapped.get(i), x + PAD, ty, wrappedColors.get(i), false);
            ty += 10;
        }
        int bw = 60;
        int bx = x + POPUP_WIDTH - PAD - bw;
        int by = y + height - PAD - buttonH;
        inspectRect = new int[] { bx, by, bw, buttonH };
        boolean hover = inside(inspectRect, mouseX, mouseY);
        c.fill(bx, by, bx + bw, by + buttonH, hover ? 0xFF5A7FD0 : 0xFF3A5280);
        c.fill(bx, by, bx + bw, by + 1, 0xFF8FA8E0);
        String label = Lang.t("ae2declogger.overlay.inspect");
        c.text(label, bx + (bw - c.width(label)) / 2, by + 3, 0xFFFFFFFF, false);
    }

    private static List<String> wrap(Canvas c, String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (c.width(next) > width && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            out.add(c.clip(line.toString(), width));
        }
        return out;
    }

    private static boolean inside(int[] r, double x, double y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    /** The job shown in this screen (the server matches it to the screen's CPU), else any job with that step. */
    private static JobView focusedJob(LKey key) {
        JobView job = ClientState.JOBS.get(ClientState.focusJob);
        if (job != null || key == null) {
            return job;
        }
        for (JobView j : ClientState.JOBS.values()) {
            if (step(j, key) != null) {
                return j;
            }
        }
        for (JobView j : ClientState.JOBS.values()) {
            if (j.output.equals(key)) {
                return j;
            }
        }
        return null;
    }

    private static StepView step(JobView job, LKey key) {
        for (StepView s : job.steps) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }
}
