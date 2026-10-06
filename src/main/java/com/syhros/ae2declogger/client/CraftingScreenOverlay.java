package com.syhros.ae2declogger.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.Formatting;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.network.DeclogNetwork;

import com.syhros.ae2declogger.client.screen.Gfx;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.client.gui.widgets.Scrollbar;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;

/**
 * Adds to AE2's crafting status screen (the "Crafting Status" tab of a terminal and the crafting CPU screen): entries
 * whose step is slow, stalled or blocked get a coloured frame, and clicking an entry opens a small popup with what
 * the Declogger knows about that step and an Inspect button that opens the monitor on it.
 *
 * <p>
 * AE2 does not expose the table layout, so it is read by reflection. If that ever fails (a different AE2 version),
 * clicking still works through AE2's public "stack under the mouse"; only the frames are missing.
 *
 * <p>
 * The loader's screen events call the methods below (see ClientEvents).
 */
public final class CraftingScreenOverlay {

    private static final int POPUP_WIDTH = 170;
    private static final int PAD = 5;

    @Nullable
    private static Screen watched;
    @Nullable
    private static AEKey selected;
    @Nullable
    private static Rect2i selectedCell;
    @Nullable
    private static Rect2i popupRect;
    @Nullable
    private static Rect2i inspectRect;

    private CraftingScreenOverlay() {
    }

    // ---------------------------------------------------------------- events

    /** A screen finished opening (or was resized). */
    public static void onInit(Screen screen) {
        if (!(screen instanceof CraftingCPUScreen<?>) || !DeclogConfig.CLIENT.craftingOverlay.get()) {
            return;
        }
        if (watched != screen) {
            watched = screen;
            clearSelection();
            ClientState.focusJob = -1;
            DeclogNetwork.sendToServer(new DeclogNetwork.WatchCraftingScreen(true));
        }
    }

    public static void onClosing(Screen screen) {
        if (screen == watched) {
            watched = null;
            clearSelection();
            DeclogNetwork.sendToServer(new DeclogNetwork.WatchCraftingScreen(false));
        }
    }

    /** After the screen has drawn itself. */
    public static void onRender(Screen screen, Gfx g, int mouseX, int mouseY) {
        if (screen != watched) {
            return;
        }
        g.push();
        g.translate(0, 0, 350);
        try {
            JobView job = focusedJob(null);
            if (job != null) {
                for (Cell cell : visibleCells(screen)) {
                    StepView step = step(job, cell.key);
                    if (step != null && step.state() != JobState.IN_PROGRESS && !step.state().isFinished()) {
                        frame(g, cell.rect, 0xFF000000 | step.state().color());
                    }
                }
            }
            if (selected != null && selectedCell != null) {
                frame(g, selectedCell, 0xFFFFFFFF);
                renderPopup(g, screen, mouseX, mouseY);
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("Crafting screen overlay failed", e);
        } finally {
            g.pop();
        }
    }

    /** A mouse button went down; true when the click was used here and the screen must not see it. */
    public static boolean onClick(Screen screen, double x, double y, int button) {
        if (screen != watched || button != 0) {
            return false;
        }
        if (inspectRect != null && contains(inspectRect, x, y)) {
            inspect();
            return true;
        }
        if (popupRect != null && contains(popupRect, x, y)) {
            return true;
        }
        Cell hit = cellAt(screen, x, y);
        if (hit != null) {
            if (hit.key.equals(selected)) {
                clearSelection();
            } else {
                selected = hit.key;
                selectedCell = hit.rect;
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

    /** Opens the Declogger monitor on the selected step. */
    private static void inspect() {
        if (selected == null) {
            return;
        }
        JobView job = focusedJob(selected);
        ClientState.pendingFocusJob = job != null ? job.id() : -1;
        ClientState.pendingFocusStep = selected;
        DeclogNetwork.sendToServer(new DeclogNetwork.OpenMonitor(true));
        clearSelection();
    }

    // ---------------------------------------------------------------- popup

    private static void renderPopup(Gfx g, Screen screen, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        List<Component> lines = new ArrayList<>();
        int stateColor = 0xFFAAAAAA;
        lines.add(selected.getDisplayName().copy().withStyle(ChatFormatting.WHITE));

        JobView job = focusedJob(selected);
        StepView step = job != null ? step(job, selected) : null;
        if (step != null) {
            stateColor = 0xFF000000 | step.state().color();
            long stuckFor = Math.max(0, ClientState.serverNow() - step.sinceMs());
            Component state = Text.translatable(step.state().translationKey());
            if (step.state() != JobState.IN_PROGRESS) {
                state = Text.translatable("ae2declogger.overlay.state_for", state, Formatting.duration(stuckFor));
            }
            lines.add(state.copy().withStyle(s -> s.withColor(0xFFFFFF & step.state().color())));
            if (step.reason() != BlockReason.NONE) {
                Component reason = Text.translatable(step.reason().translationKey());
                if (step.detail() != null) {
                    reason = reason.copy().append(": ").append(step.detail());
                }
                lines.add(reason.copy().withStyle(ChatFormatting.GRAY));
            }
            lines.add(Text.translatable("ae2declogger.overlay.amounts", Formatting.compactAmount(step.stored()),
                    Formatting.compactAmount(step.active()), Formatting.compactAmount(step.pending()))
                    .withStyle(ChatFormatting.DARK_GRAY));
            MachineRef machine = step.machines().isEmpty() ? null : step.machines().get(0);
            if (machine != null) {
                lines.add(Text.translatable("ae2declogger.overlay.machine", machine.name(),
                        machine.pos().getX(), machine.pos().getY(), machine.pos().getZ())
                        .withStyle(ChatFormatting.GRAY));
            }
        } else if (job != null && job.steps().isEmpty() && job.state() != JobState.IN_PROGRESS) {
            // No per-step details for this CPU: show what is known about the whole job.
            stateColor = 0xFF000000 | job.state().color();
            long idle = Math.max(0, ClientState.serverNow() - job.lastActivityAtMs());
            lines.add(Text.translatable("ae2declogger.overlay.job_state_for",
                    Text.translatable(job.state().translationKey()), Formatting.duration(idle))
                    .withStyle(s -> s.withColor(0xFFFFFF & job.state().color())));
            if (job.reason() != BlockReason.NONE) {
                Component reason = Text.translatable(job.reason().translationKey());
                if (job.detail() != null) {
                    reason = reason.copy().append(": ").append(job.detail());
                }
                lines.add(reason.copy().withStyle(ChatFormatting.GRAY));
            }
        } else if (job != null) {
            lines.add(Text.translatable("ae2declogger.overlay.no_problem").withStyle(ChatFormatting.GRAY));
        } else if (ClientState.summary == null) {
            lines.add(Text.translatable("ae2declogger.overlay.loading").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Text.translatable("ae2declogger.overlay.no_data").withStyle(ChatFormatting.GRAY));
        }

        List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : lines) {
            wrapped.addAll(font.split(line, POPUP_WIDTH - 2 * PAD));
        }
        int buttonH = 14;
        int height = PAD + wrapped.size() * 10 + 4 + buttonH + PAD;
        int x = selectedCell.getX();
        int y = selectedCell.getY() + selectedCell.getHeight() + 2;
        if (y + height > screen.height - 2) {
            y = selectedCell.getY() - height - 2;
        }
        x = Math.max(2, Math.min(x, screen.width - POPUP_WIDTH - 2));
        y = Math.max(2, y);
        popupRect = new Rect2i(x, y, POPUP_WIDTH, height);

        g.fill(x - 1, y - 1, x + POPUP_WIDTH + 1, y + height + 1, stateColor);
        g.fill(x, y, x + POPUP_WIDTH, y + height, 0xF0101018);
        int ty = y + PAD;
        for (var line : wrapped) {
            g.drawString(font, line, x + PAD, ty, 0xFFFFFFFF, false);
            ty += 10;
        }
        int bw = 60;
        int bx = x + POPUP_WIDTH - PAD - bw;
        int by = y + height - PAD - buttonH;
        inspectRect = new Rect2i(bx, by, bw, buttonH);
        boolean hover = contains(inspectRect, mouseX, mouseY);
        g.fill(bx, by, bx + bw, by + buttonH, hover ? 0xFF5A7FD0 : 0xFF3A5280);
        g.fill(bx, by, bx + bw, by + 1, 0xFF8FA8E0);
        Component label = Text.translatable("ae2declogger.overlay.inspect");
        g.drawString(font, label, bx + (bw - font.width(label)) / 2, by + 3, 0xFFFFFFFF, false);
    }

    private static void frame(Gfx g, Rect2i r, int color) {
        int x0 = r.getX(), y0 = r.getY(), x1 = x0 + r.getWidth(), y1 = y0 + r.getHeight();
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    private static boolean contains(Rect2i r, double x, double y) {
        return x >= r.getX() && x < r.getX() + r.getWidth() && y >= r.getY() && y < r.getY() + r.getHeight();
    }

    // ---------------------------------------------------------------- data

    /**
     * The job shown in this screen (the server matches it to the screen's CPU). Falls back to any job that has a step
     * for {@code key} when the CPU could not be matched.
     */
    @Nullable
    private static JobView focusedJob(@Nullable AEKey key) {
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
            if (j.output().equals(key)) {
                return j;
            }
        }
        return null;
    }

    @Nullable
    private static StepView step(JobView job, AEKey key) {
        for (StepView s : job.steps()) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- AE2 table layout

    private record Cell(AEKey key, Rect2i rect) {
    }

    private static boolean layoutBroken;
    private static Field statusField;
    private static Field scrollbarField;
    private static Field tableField;
    private static int cellWidth = 67;
    private static int cellHeight = 22;
    private static int cellBorder = 1;
    private static int cols = 3;

    @Nullable
    private static Cell cellAt(Screen screen, double x, double y) {
        for (Cell cell : visibleCells(screen)) {
            if (contains(cell.rect, x, y)) {
                return cell;
            }
        }
        if (layoutBroken && screen instanceof CraftingCPUScreen<?> cpuScreen) {
            var hovered = cpuScreen.getStackUnderMouse(x, y);
            //#if MC >= 1.20
            if (hovered != null && hovered.stack() != null) {
                var b = hovered.bounds();
                return new Cell(hovered.stack().what(), new Rect2i(b.getX(), b.getY(), b.getWidth(), b.getHeight()));
            }
            //#else
            //$$ if (hovered != null) {
            //$$     return new Cell(hovered.what(), new Rect2i((int) x - 8, (int) y - 8, 16, 16));
            //$$ }
            //#endif
        }
        return null;
    }

    /** Entries currently drawn in AE2's table, with their screen rectangles. */
    private static List<Cell> visibleCells(Screen screen) {
        List<Cell> cells = new ArrayList<>();
        if (layoutBroken || !(screen instanceof CraftingCPUScreen<?> cpuScreen)) {
            return cells;
        }
        try {
            if (statusField == null) {
                statusField = CraftingCPUScreen.class.getDeclaredField("status");
                statusField.setAccessible(true);
                scrollbarField = CraftingCPUScreen.class.getDeclaredField("scrollbar");
                scrollbarField.setAccessible(true);
                tableField = CraftingCPUScreen.class.getDeclaredField("table");
                tableField.setAccessible(true);
                Class<?> renderer = tableField.get(cpuScreen).getClass();
                cellWidth = staticInt(renderer, "CELL_WIDTH", 67);
                cellHeight = staticInt(renderer, "CELL_HEIGHT", 22);
                cellBorder = staticInt(renderer, "CELL_BORDER", 1);
                cols = staticInt(renderer, "COLS", 3);
            }
            CraftingStatus status = (CraftingStatus) statusField.get(cpuScreen);
            if (status == null) {
                return cells;
            }
            int scroll = ((Scrollbar) scrollbarField.get(cpuScreen)).getCurrentScroll();
            Object table = tableField.get(cpuScreen);
            int tx = instanceInt(table, "x");
            int ty = instanceInt(table, "y");
            // AE2 15 sizes the table to the screen ("rows"); AE2 12 and older use a fixed ROWS.
            int rows;
            try {
                rows = instanceInt(table, "rows");
            } catch (NoSuchFieldException e) {
                rows = staticInt(table.getClass(), "ROWS", 6);
            }
            List<CraftingStatusEntry> entries = status.getEntries();
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    int i = (row + scroll) * cols + col;
                    if (i >= entries.size()) {
                        return cells;
                    }
                    int cx = cpuScreen.getGuiLeft() + tx + col * (cellWidth + cellBorder);
                    int cy = cpuScreen.getGuiTop() + ty + row * (cellHeight + cellBorder);
                    cells.add(new Cell(entries.get(i).getWhat(), new Rect2i(cx, cy, cellWidth, cellHeight)));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            layoutBroken = true;
            Ae2Declogger.LOG.warn("Could not read AE2's crafting status layout; stuck entries will not be marked", e);
        }
        return cells;
    }

    private static int staticInt(Class<?> type, String name, int fallback) {
        try {
            return findField(type, name).getInt(null);
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    private static int instanceInt(Object target, String name) throws ReflectiveOperationException {
        return findField(target.getClass(), name).getInt(target);
    }

    /** The table renderer subclass is AE2-internal, so its fields are looked up along the class hierarchy. */
    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // keep looking in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ---------------------------------------------------------------- hooks for the automated client self-test

    /** Whether the entry for {@code key} currently has a problem frame. */
    public static boolean testHasFrame(AEKey key) {
        JobView job = focusedJob(null);
        if (job == null || watched == null) {
            return false;
        }
        for (Cell cell : visibleCells(watched)) {
            StepView step = step(job, cell.key);
            if (cell.key.equals(key) && step != null && step.state().isProblem()) {
                return true;
            }
        }
        return false;
    }

    /** Clicks the entry for {@code key} through Forge's mouse event, as a player would. */
    public static boolean testClickEntry(AEKey key) {
        if (watched == null) {
            return false;
        }
        for (Cell cell : visibleCells(watched)) {
            if (cell.key.equals(key)) {
                click(cell.rect);
                return selected != null && selected.equals(key);
            }
        }
        return false;
    }

    /** Clicks the Inspect button (the popup must have been drawn once). */
    public static boolean testClickInspect() {
        if (watched == null || inspectRect == null) {
            return false;
        }
        click(inspectRect);
        return true;
    }

    public static boolean testPopupShown() {
        return popupRect != null && inspectRect != null;
    }

    private static void click(Rect2i r) {
        double x = r.getX() + r.getWidth() / 2.0;
        double y = r.getY() + r.getHeight() / 2.0;
        onClick(watched, x, y, 0);
    }
}
