package com.syhros.ae2declogger.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.syhros.ae2declogger.client.ClientState;
import com.syhros.ae2declogger.client.Exporter;
import com.syhros.ae2declogger.client.pins.PinManager;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.Formatting;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.logic.JobStats;
import com.syhros.ae2declogger.network.DeclogNetwork;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import appeng.api.stacks.AEKey;

/**
 * The monitor: running jobs with their steps, history, per-item stats and locator pins. Plain screen, no container, so
 * nothing in it can move items.
 */
public class DecloggerScreen extends Screen {

    private enum Tab {
        JOBS,
        HISTORY,
        STATS,
        PINS
    }

    private static final int JOB_ROW = 24;
    private static final int STEP_ROW = 22;
    private static final int ROW = 22;

    private static Tab lastTab = Tab.JOBS;
    @Nullable
    private static Boolean problemsOnlyDefault;
    private static boolean problemsOnly;

    private Tab tab = lastTab;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int contentTop;
    private int contentBottom;

    private EditBox search;
    private Button problemsButton;
    private Button alertsButton;
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> jobButtons = new ArrayList<>();
    private final List<Button> historyButtons = new ArrayList<>();
    private final List<Button> statsButtons = new ArrayList<>();
    private final List<Button> pinButtons = new ArrayList<>();
    private Button gotoButton;
    private Button viewButton;
    private Button pinButton;
    private Button teleportButton;
    private Button navigateButton;
    private Button pinTeleportButton;
    private Button pinRemoveButton;

    private long selectedJob = -1;
    @Nullable
    private AEKey selectedStep;
    @Nullable
    private String selectedPin;

    private double jobScroll;
    private double stepScroll;
    private double listScroll;

    private boolean keepSubscription;
    private long builtVersion = -1;
    private String builtFilter = "";
    private boolean builtProblemsOnly;
    private List<JobView> jobs = List.of();
    private List<HistoryEntry> history = List.of();
    private List<JobStats.Row<AEKey>> stats = List.of();

    public DecloggerScreen() {
        super(Component.translatable("ae2declogger.monitor.screen_title"));
        if (problemsOnlyDefault == null) {
            try {
                problemsOnlyDefault = com.syhros.ae2declogger.config.DeclogConfig.CLIENT.showOnlyProblems.get();
            } catch (IllegalStateException notLoaded) {
                problemsOnlyDefault = false;
            }
            problemsOnly = problemsOnlyDefault;
        }
    }

    /** Called before a child screen (machine viewer) replaces this one, so the server keeps sending updates. */
    public void keepSubscriptionForChild() {
        keepSubscription = true;
    }

    @Override
    protected void init() {
        keepSubscription = false;
        panelW = Math.min(width - 12, 460);
        panelH = Math.min(height - 12, 310);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        contentTop = top + 38;
        contentBottom = top + panelH - 26;

        tabButtons.clear();
        int tx = left + 6;
        for (Tab t : Tab.values()) {
            Tab target = t;
            Button b = Button.builder(Component.translatable("ae2declogger.tab." + t.name().toLowerCase(Locale.ROOT)),
                    btn -> switchTab(target)).bounds(tx, top + 19, 54, 14).build();
            tabButtons.add(addRenderableWidget(b));
            tx += 56;
        }

        int right = left + panelW - 6;
        alertsButton = addRenderableWidget(Button.builder(Component.literal("♪"),
                b -> DeclogNetwork.sendToServer(new DeclogNetwork.ToggleAlerts()))
                .bounds(right - 16, top + 19, 16, 14).build());
        problemsButton = addRenderableWidget(Button.builder(Component.literal("!"), b -> {
            problemsOnly = !problemsOnly;
            jobScroll = 0;
        }).bounds(right - 34, top + 19, 16, 14).build());
        int searchW = Math.max(60, Math.min(120, right - 38 - tx - 4));
        search = new EditBox(font, right - 38 - searchW, top + 20, searchW, 12,
                Component.translatable("ae2declogger.search"));
        search.setMaxLength(64);
        search.setBordered(true);
        addRenderableWidget(search);

        int by = top + panelH - 21;
        int bw = Math.min(90, (panelW - 12 - 9) / 4);
        int bx = left + 6;
        jobButtons.clear();
        gotoButton = addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.goto"),
                b -> goTo(targetMachine())).bounds(bx, by, bw, 16).build());
        viewButton = addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.view"),
                b -> view(targetMachine())).bounds(bx + (bw + 3), by, bw, 16).build());
        pinButton = addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.pin"),
                b -> pin(targetMachine(), false)).bounds(bx + 2 * (bw + 3), by, bw, 16).build());
        teleportButton = addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.teleport"),
                b -> teleport(targetMachine())).bounds(bx + 3 * (bw + 3), by, bw, 16).build());
        gotoButton.setTooltip(Tooltip.create(Component.translatable("ae2declogger.button.goto.tooltip")));
        viewButton.setTooltip(Tooltip.create(Component.translatable("ae2declogger.button.view.tooltip")));
        pinButton.setTooltip(Tooltip.create(Component.translatable("ae2declogger.button.pin.tooltip")));
        jobButtons.add(gotoButton);
        jobButtons.add(viewButton);
        jobButtons.add(pinButton);
        jobButtons.add(teleportButton);

        historyButtons.clear();
        historyButtons.add(addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.export"),
                b -> Exporter.export(new ArrayList<>(ClientState.JOBS.values()), ClientState.HISTORY))
                .bounds(bx, by, bw, 16).build()));
        historyButtons.add(addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.clear"),
                b -> DeclogNetwork.sendToServer(new DeclogNetwork.ClearHistory())).bounds(bx + bw + 3, by, bw, 16)
                .build()));

        statsButtons.clear();
        statsButtons.add(addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.export"),
                b -> Exporter.export(new ArrayList<>(ClientState.JOBS.values()), ClientState.HISTORY))
                .bounds(bx, by, bw, 16).build()));

        pinButtons.clear();
        navigateButton = addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.navigate"),
                b -> toggleNavigate()).bounds(bx, by, bw, 16).build());
        pinTeleportButton = addRenderableWidget(Button.builder(
                Component.translatable("ae2declogger.button.teleport"), b -> {
                    PinManager.Pin p = selectedPinObj();
                    if (p != null && p.dimensionKey() != null) {
                        DeclogNetwork.sendToServer(new DeclogNetwork.Teleport(p.dimensionKey(), p.pos()));
                    }
                }).bounds(bx + bw + 3, by, bw, 16).build());
        pinRemoveButton = addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.remove"),
                b -> {
                    PinManager.Pin p = selectedPinObj();
                    if (p != null) {
                        PinManager.remove(p);
                        selectedPin = null;
                    }
                }).bounds(bx + 2 * (bw + 3), by, bw, 16).build());
        pinButtons.add(navigateButton);
        pinButtons.add(pinTeleportButton);
        pinButtons.add(pinRemoveButton);
        pinButtons.add(addRenderableWidget(Button.builder(Component.translatable("ae2declogger.button.clear_all"),
                b -> {
                    PinManager.clear();
                    selectedPin = null;
                }).bounds(bx + 3 * (bw + 3), by, bw, 16).build()));

        updateWidgets();
    }

    private void switchTab(Tab t) {
        tab = t;
        lastTab = t;
        listScroll = 0;
        updateWidgets();
    }

    private void updateWidgets() {
        for (int i = 0; i < tabButtons.size(); i++) {
            tabButtons.get(i).active = Tab.values()[i] != tab;
        }
        jobButtons.forEach(b -> b.visible = tab == Tab.JOBS);
        historyButtons.forEach(b -> b.visible = tab == Tab.HISTORY);
        statsButtons.forEach(b -> b.visible = tab == Tab.STATS);
        pinButtons.forEach(b -> b.visible = tab == Tab.PINS);

        MachineRef target = targetMachine();
        gotoButton.active = target != null;
        viewButton.active = target != null;
        pinButton.active = target != null;
        teleportButton.active = target != null && ClientState.canTeleport();
        teleportButton.visible = tab == Tab.JOBS && ClientState.canTeleport();

        PinManager.Pin pin = selectedPinObj();
        navigateButton.active = pin != null;
        navigateButton.setMessage(Component.translatable(pin != null && pin == PinManager.active()
                ? "ae2declogger.button.stop" : "ae2declogger.button.navigate"));
        pinTeleportButton.active = pin != null && ClientState.canTeleport();
        pinTeleportButton.visible = tab == Tab.PINS && ClientState.canTeleport();
        pinRemoveButton.active = pin != null;

        problemsButton.setMessage(Component.literal("!").withStyle(problemsOnly ? ChatFormatting.RED
                : ChatFormatting.GRAY));
        problemsButton.setTooltip(Tooltip.create(Component.translatable(problemsOnly
                ? "ae2declogger.button.problems.on" : "ae2declogger.button.problems.off")));
        alertsButton.setMessage(Component.literal("♪").withStyle(ClientState.alertsOn ? ChatFormatting.GREEN
                : ChatFormatting.DARK_GRAY));
        alertsButton.setTooltip(Tooltip.create(Component.translatable(ClientState.alertsOn
                ? "ae2declogger.button.alerts.on" : "ae2declogger.button.alerts.off")));
        search.visible = tab != Tab.PINS;
    }

    @Override
    public void tick() {
        search.tick();
        applyPendingFocus();
        updateWidgets();
    }

    /** Inspect in AE2's crafting status screen: select that job and step once the data is here. */
    private void applyPendingFocus() {
        if (ClientState.pendingFocusJob < 0 || ClientState.summary == null) {
            return;
        }
        long id = ClientState.pendingFocusJob;
        AEKey step = ClientState.pendingFocusStep;
        ClientState.pendingFocusJob = -1;
        ClientState.pendingFocusStep = null;
        JobView job = ClientState.JOBS.get(id);
        if (job == null && step != null) {
            // The job id is unknown (job just finished or restarted): pick a job that has this step.
            for (JobView j : ClientState.JOBS.values()) {
                if (j.steps().stream().anyMatch(s -> s.key().equals(step)) || j.output().equals(step)) {
                    job = j;
                    break;
                }
            }
        }
        if (job == null) {
            return;
        }
        if (tab != Tab.JOBS) {
            switchTab(Tab.JOBS);
        }
        // Clear the search and problems filter so the job is visible.
        problemsOnly = false;
        search.setValue("");
        selectedJob = job.id();
        selectedStep = step != null && job.steps().stream().anyMatch(s -> s.key().equals(step)) ? step : null;
    }

    /** For the self-test: the selected job and step. */
    public long testSelectedJob() {
        return selectedJob;
    }

    @Nullable
    public AEKey testSelectedStep() {
        return selectedStep;
    }

    // ---------------------------------------------------------------- data

    private void rebuildIfNeeded() {
        String filter = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (builtVersion == ClientState.version && filter.equals(builtFilter) && builtProblemsOnly == problemsOnly) {
            return;
        }
        builtVersion = ClientState.version;
        builtFilter = filter;
        builtProblemsOnly = problemsOnly;

        List<JobView> j = new ArrayList<>();
        for (JobView job : ClientState.JOBS.values()) {
            if (problemsOnly && job.state() == JobState.IN_PROGRESS) {
                continue;
            }
            if (!filter.isEmpty() && !matches(job, filter)) {
                continue;
            }
            j.add(job);
        }
        jobs = j;

        List<HistoryEntry> h = new ArrayList<>();
        List<JobStats.Entry<AEKey>> entries = new ArrayList<>();
        for (HistoryEntry e : ClientState.HISTORY) {
            entries.add(new JobStats.Entry<>(e.output(), e.amount(), e.durationMs(), e.completed()));
            if (problemsOnly && e.completed()) {
                continue;
            }
            if (!filter.isEmpty() && !name(e.output()).contains(filter)) {
                continue;
            }
            h.add(e);
        }
        history = h;

        List<JobStats.Row<AEKey>> s = new ArrayList<>();
        for (JobStats.Row<AEKey> row : JobStats.compute(entries)) {
            if (filter.isEmpty() || name(row.key()).contains(filter)) {
                s.add(row);
            }
        }
        stats = s;
    }

    private static boolean matches(JobView job, String filter) {
        if (name(job.output()).contains(filter)) {
            return true;
        }
        for (StepView step : job.steps()) {
            if (name(step.key()).contains(filter)) {
                return true;
            }
        }
        return false;
    }

    private static String name(AEKey key) {
        return key.getDisplayName().getString().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private JobView selectedJobView() {
        for (JobView j : jobs) {
            if (j.id() == selectedJob) {
                return j;
            }
        }
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    @Nullable
    private StepView selectedStepView(@Nullable JobView job) {
        if (job == null || selectedStep == null) {
            return null;
        }
        for (StepView s : job.steps()) {
            if (s.key().equals(selectedStep)) {
                return s;
            }
        }
        return null;
    }

    /** Machine the action buttons act on: selected step's worst machine, else the job's main machine. */
    @Nullable
    private MachineRef targetMachine() {
        if (tab != Tab.JOBS) {
            return null;
        }
        rebuildIfNeeded();
        JobView job = selectedJobView();
        StepView step = selectedStepView(job);
        if (step != null && !step.machines().isEmpty()) {
            for (MachineRef m : step.machines()) {
                if (m.hasProblem()) {
                    return m;
                }
            }
            return step.machines().get(0);
        }
        return job == null ? null : job.primaryMachine();
    }

    @Nullable
    private PinManager.Pin selectedPinObj() {
        if (selectedPin == null) {
            return null;
        }
        for (PinManager.Pin p : PinManager.pins()) {
            if (p.id.equals(selectedPin)) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- actions

    private void goTo(@Nullable MachineRef m) {
        if (m == null) {
            return;
        }
        PinManager.Pin pin = pin(m, true);
        PinManager.setActive(pin);
        onClose();
    }

    private PinManager.Pin pin(@Nullable MachineRef m, boolean quiet) {
        if (m == null) {
            return null;
        }
        JobView job = selectedJobView();
        String label = m.name().getString();
        if (job != null) {
            label = job.output().getDisplayName().getString() + " - " + label;
        }
        PinManager.Pin pin = PinManager.add(label, m.dimension(), m.pos());
        if (!quiet && minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable("ae2declogger.pin.added", label,
                    UiUtil.coords(m)), true);
        }
        return pin;
    }

    private void view(@Nullable MachineRef m) {
        if (m == null) {
            return;
        }
        DeclogNetwork.sendToServer(new DeclogNetwork.RequestMachine(m.dimension(), m.pos(), true));
    }

    private void teleport(@Nullable MachineRef m) {
        if (m != null) {
            DeclogNetwork.sendToServer(new DeclogNetwork.Teleport(m.dimension(), m.pos()));
        }
    }

    private void toggleNavigate() {
        PinManager.Pin p = selectedPinObj();
        if (p == null) {
            return;
        }
        PinManager.setActive(p == PinManager.active() ? null : p);
    }

    @Override
    public void removed() {
        lastTab = tab;
        if (!keepSubscription) {
            DeclogNetwork.sendToServer(new DeclogNetwork.CloseMonitor());
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        rebuildIfNeeded();
        UiUtil.panel(g, left, top, panelW, panelH);
        renderHeader(g);
        List<Component> tooltip = null;
        switch (tab) {
            case JOBS -> tooltip = renderJobs(g, mouseX, mouseY);
            case HISTORY -> tooltip = renderHistory(g, mouseX, mouseY);
            case STATS -> tooltip = renderStats(g, mouseX, mouseY);
            case PINS -> tooltip = renderPins(g, mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (search.visible && search.getValue().isEmpty() && !search.isFocused()) {
            g.drawString(font, Component.translatable("ae2declogger.search"), search.getX() + 4, search.getY() + 2,
                    UiUtil.TEXT_FAINT, false);
        }
        if (tooltip != null && !tooltip.isEmpty()) {
            g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private void renderHeader(GuiGraphics g) {
        int x = left + 6;
        int y = top + 6;
        Component t = ClientState.title;
        UiUtil.text(g, font, t, x, y, panelW / 2, UiUtil.TEXT);
        var s = ClientState.summary;
        Component right;
        int color = UiUtil.TEXT_DIM;
        if (ClientState.closedReason != null) {
            right = ClientState.closedReason;
            color = 0xFFFF6B6B;
        } else if (s == null) {
            right = Component.translatable("ae2declogger.monitor.loading");
        } else {
            String power = Formatting.compactAmount((long) s.storedPower()) + " / "
                    + Formatting.compactAmount((long) s.maxPower()) + " AE";
            right = Component.translatable("ae2declogger.monitor.summary", s.busyCpus(), s.cpus(), power);
            if (!s.powered()) {
                right = Component.translatable("ae2declogger.monitor.unpowered");
                color = 0xFFFF6B6B;
            }
        }
        UiUtil.textRight(g, font, right, left + panelW - 6, y, panelW / 2 - 10, color);
        g.fill(left + 4, top + 35, left + panelW - 4, top + 36, UiUtil.PANEL_BORDER);
    }

    @Nullable
    private List<Component> renderJobs(GuiGraphics g, int mouseX, int mouseY) {
        List<Component> tooltip = null;
        int x = left + 6;
        int w = panelW - 16;
        int split = contentTop + (int) ((contentBottom - contentTop) * 0.52);
        int listTop = contentTop;
        int listBottom = split;

        if (jobs.isEmpty()) {
            Component msg = ClientState.summary == null ? Component.translatable("ae2declogger.monitor.loading")
                    : ClientState.JOBS.isEmpty() ? Component.translatable("ae2declogger.monitor.no_jobs")
                            : Component.translatable("ae2declogger.monitor.no_match");
            g.drawCenteredString(font, msg, left + panelW / 2, listTop + 20, UiUtil.TEXT_DIM);
            return null;
        }

        jobScroll = clampScroll(jobScroll, jobs.size() * JOB_ROW, listBottom - listTop);
        JobView selected = selectedJobView();
        long now = ClientState.serverNow();
        g.enableScissor(left + 1, listTop, left + panelW - 1, listBottom);
        for (int i = 0; i < jobs.size(); i++) {
            int y = listTop + i * JOB_ROW - (int) jobScroll;
            if (y + JOB_ROW < listTop || y > listBottom) {
                continue;
            }
            JobView job = jobs.get(i);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, listTop)
                    && mouseY < Math.min(y + JOB_ROW, listBottom);
            if (job == selected) {
                g.fill(x, y, x + w, y + JOB_ROW - 1, UiUtil.ROW_SELECTED);
            } else if (hover) {
                g.fill(x, y, x + w, y + JOB_ROW - 1, UiUtil.ROW_HOVER);
            }
            g.fill(x, y, x + 2, y + JOB_ROW - 1, 0xFF000000 | job.state().color());
            UiUtil.key(g, job.output(), x + 5, y + 3);

            Component name = Component.translatable("ae2declogger.amount_of", job.output().getDisplayName(),
                    UiUtil.amount(job.output(), job.amount()));
            Component state = UiUtil.stateLabel(job.state());
            int stateW = font.width(state);
            UiUtil.text(g, font, name, x + 24, y + 3, w - 30 - stateW - 6, UiUtil.TEXT);
            g.drawString(font, state, x + w - 4 - stateW, y + 3, 0xFFFFFFFF, false);

            UiUtil.progressBar(g, x + 24, y + 15, 40, 4, job.progress(), job.state().color());
            String times = Formatting.percent(job.progress()) + "  " + Formatting.duration(now - job.startedAtMs());
            if (job.expectedMs() > 0) {
                times += " / ~" + Formatting.duration(job.expectedMs());
            }
            long idle = now - job.lastActivityAtMs();
            if (idle >= 10_000) {
                times += "  " + Component.translatable("ae2declogger.idle", Formatting.duration(idle)).getString();
            }
            int tw = UiUtil.text(g, font, Component.literal(times), x + 68, y + 13, w - 72, UiUtil.TEXT_DIM);
            Component reasonOrCpu = job.reason().isProblem() ? UiUtil.reasonText(job.reason(), job.detail())
                    : job.cpuName();
            int reasonColor = job.reason().isProblem() ? UiUtil.reasonColor(job.reason(), job.state())
                    : UiUtil.TEXT_FAINT;
            UiUtil.textRight(g, font, reasonOrCpu, x + w - 4, y + 13, w - 72 - tw - 10, reasonColor);

            if (hover) {
                tooltip = jobTooltip(job, now);
            }
        }
        g.disableScissor();
        UiUtil.scrollbar(g, left + panelW - 6, listTop, listBottom - listTop, jobScroll, jobs.size() * JOB_ROW);

        // Steps of the selected job
        g.fill(left + 4, split + 1, left + panelW - 4, split + 2, UiUtil.PANEL_BORDER);
        if (selected == null) {
            return tooltip;
        }
        Component header = Component.translatable("ae2declogger.monitor.steps_for",
                selected.output().getDisplayName(), selected.cpuName());
        UiUtil.text(g, font, header, x, split + 5, w, UiUtil.TEXT_DIM);
        int stepTop = split + 16;
        List<StepView> steps = selected.steps();
        if (steps.isEmpty()) {
            g.drawString(font, Component.translatable("ae2declogger.monitor.no_steps"), x + 4, stepTop + 4,
                    UiUtil.TEXT_FAINT, false);
            return tooltip;
        }
        stepScroll = clampScroll(stepScroll, steps.size() * STEP_ROW, contentBottom - stepTop);
        StepView selStep = selectedStepView(selected);
        g.enableScissor(left + 1, stepTop, left + panelW - 1, contentBottom);
        for (int i = 0; i < steps.size(); i++) {
            int y = stepTop + i * STEP_ROW - (int) stepScroll;
            if (y + STEP_ROW < stepTop || y > contentBottom) {
                continue;
            }
            StepView step = steps.get(i);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, stepTop)
                    && mouseY < Math.min(y + STEP_ROW, contentBottom);
            if (step == selStep) {
                g.fill(x, y, x + w, y + STEP_ROW - 1, UiUtil.ROW_SELECTED);
            } else if (hover) {
                g.fill(x, y, x + w, y + STEP_ROW - 1, UiUtil.ROW_HOVER);
            }
            g.fill(x, y, x + 2, y + STEP_ROW - 1, 0xFF000000 | step.state().color());
            UiUtil.key(g, step.key(), x + 5, y + 2);
            Component state = UiUtil.stateLabel(step.state());
            int stateW = font.width(state);
            UiUtil.text(g, font, step.key().getDisplayName(), x + 24, y + 2, w - 30 - stateW - 6, UiUtil.TEXT);
            g.drawString(font, state, x + w - 4 - stateW, y + 2, 0xFFFFFFFF, false);
            Component counts = Component.translatable("ae2declogger.step.counts",
                    UiUtil.amount(step.key(), step.stored()), UiUtil.amount(step.key(), step.active()),
                    UiUtil.amount(step.key(), step.pending()));
            int cw = UiUtil.text(g, font, counts, x + 24, y + 12, w / 2, UiUtil.TEXT_DIM);
            Component right;
            int color;
            if (step.reason().isProblem() || step.reason() != com.syhros.ae2declogger.logic.BlockReason.NONE) {
                right = UiUtil.reasonText(step.reason(), step.detail());
                color = UiUtil.reasonColor(step.reason(), step.state());
            } else if (!step.machines().isEmpty()) {
                MachineRef m = step.machines().get(0);
                right = Component.literal(m.name().getString() + " @ " + UiUtil.coords(m));
                color = UiUtil.TEXT_FAINT;
            } else {
                right = Component.empty();
                color = UiUtil.TEXT_FAINT;
            }
            UiUtil.textRight(g, font, right, x + w - 4, y + 12, w - 24 - cw - 12, color);
            if (hover) {
                tooltip = stepTooltip(step, now);
            }
        }
        g.disableScissor();
        UiUtil.scrollbar(g, left + panelW - 6, stepTop, contentBottom - stepTop, stepScroll,
                steps.size() * STEP_ROW);
        return tooltip;
    }

    private List<Component> jobTooltip(JobView job, long now) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("ae2declogger.amount_of", job.output().getDisplayName(),
                UiUtil.amount(job.output(), job.amount())));
        lines.add(Component.translatable("ae2declogger.tooltip.state", UiUtil.stateLabel(job.state())));
        if (job.reason() != com.syhros.ae2declogger.logic.BlockReason.NONE) {
            lines.add(UiUtil.reasonText(job.reason(), job.detail()).copy()
                    .withStyle(s -> s.withColor(UiUtil.reasonColor(job.reason(), job.state()) & 0xFFFFFF)));
        }
        lines.add(Component.translatable("ae2declogger.tooltip.elapsed", Formatting.duration(now - job.startedAtMs()),
                job.expectedMs() > 0 ? Formatting.duration(job.expectedMs()) : "-").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("ae2declogger.tooltip.idle",
                Formatting.duration(now - job.lastActivityAtMs())).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("ae2declogger.tooltip.cpu", job.cpuName(), job.coProcessors(),
                Formatting.compactAmount(job.cpuStorage())).withStyle(ChatFormatting.DARK_GRAY));
        MachineRef m = job.primaryMachine();
        if (m != null) {
            lines.add(machineLine(m));
        }
        lines.add(Component.translatable("ae2declogger.tooltip.click_job").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private List<Component> stepTooltip(StepView step, long now) {
        List<Component> lines = new ArrayList<>();
        lines.add(step.key().getDisplayName());
        lines.add(Component.translatable("ae2declogger.step.counts.long", UiUtil.amount(step.key(), step.stored()),
                UiUtil.amount(step.key(), step.active()), UiUtil.amount(step.key(), step.pending()))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("ae2declogger.tooltip.state", UiUtil.stateLabel(step.state())));
        if (step.reason() != com.syhros.ae2declogger.logic.BlockReason.NONE) {
            lines.add(UiUtil.reasonText(step.reason(), step.detail()).copy()
                    .withStyle(s -> s.withColor(UiUtil.reasonColor(step.reason(), step.state()) & 0xFFFFFF)));
        }
        if (step.sinceMs() > 0) {
            lines.add(Component.translatable("ae2declogger.tooltip.unchanged",
                    Formatting.duration(now - step.sinceMs())).withStyle(ChatFormatting.GRAY));
        }
        if (step.recipeMs() > 0) {
            lines.add(Component.translatable("ae2declogger.tooltip.recipe_time", Formatting.duration(step.recipeMs()))
                    .withStyle(ChatFormatting.GRAY));
        }
        for (MachineRef m : step.machines()) {
            lines.add(machineLine(m));
        }
        if (!step.machines().isEmpty()) {
            lines.add(Component.translatable("ae2declogger.tooltip.click_step").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    private static Component machineLine(MachineRef m) {
        Component status = Component.translatable(m.status().translationKey())
                .withStyle(s -> s.withColor(m.status().color()));
        var line = Component.translatable("ae2declogger.tooltip.machine", m.name(), UiUtil.coords(m), status);
        if (m.hasProblem()) {
            line.append(" - ").append(UiUtil.reasonText(m.problem(), m.detail()).copy()
                    .withStyle(ChatFormatting.RED));
        } else if (m.detail() != null) {
            line.append(" - ").append(m.detail());
        }
        if (m.duration() > 0) {
            line.append(Component.literal(String.format(Locale.ROOT, " (%.1fs/%.1fs)", m.progress() / 20.0,
                    m.duration() / 20.0)).withStyle(ChatFormatting.DARK_GRAY));
        }
        return line;
    }

    @Nullable
    private List<Component> renderHistory(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 6;
        int w = panelW - 16;
        if (history.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("ae2declogger.history.empty"), left + panelW / 2,
                    contentTop + 20, UiUtil.TEXT_DIM);
            return null;
        }
        listScroll = clampScroll(listScroll, history.size() * ROW, contentBottom - contentTop);
        long now = ClientState.serverNow();
        List<Component> tooltip = null;
        g.enableScissor(left + 1, contentTop, left + panelW - 1, contentBottom);
        for (int i = 0; i < history.size(); i++) {
            int y = contentTop + i * ROW - (int) listScroll;
            if (y + ROW < contentTop || y > contentBottom) {
                continue;
            }
            HistoryEntry e = history.get(i);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, contentTop)
                    && mouseY < Math.min(y + ROW, contentBottom);
            if (hover) {
                g.fill(x, y, x + w, y + ROW - 1, UiUtil.ROW_HOVER);
            }
            JobState state = e.completed() ? JobState.COMPLETE : JobState.CANCELLED;
            g.fill(x, y, x + 2, y + ROW - 1, 0xFF000000 | state.color());
            UiUtil.key(g, e.output(), x + 5, y + 2);
            Component label = UiUtil.stateLabel(state);
            int lw = font.width(label);
            UiUtil.text(g, font, Component.translatable("ae2declogger.amount_of", e.output().getDisplayName(),
                    UiUtil.amount(e.output(), e.amount())), x + 24, y + 2, w - 30 - lw - 6, UiUtil.TEXT);
            g.drawString(font, label, x + w - 4 - lw, y + 2, 0xFFFFFFFF, false);
            Component line2 = Component.translatable("ae2declogger.history.line", Formatting.duration(e.durationMs()),
                    UiUtil.ago(now - e.finishedAtMs()), e.cpuName());
            UiUtil.text(g, font, line2, x + 24, y + 12, w - 30, UiUtil.TEXT_DIM);
            if (hover) {
                tooltip = List.of(e.output().getDisplayName(), line2);
            }
        }
        g.disableScissor();
        UiUtil.scrollbar(g, left + panelW - 6, contentTop, contentBottom - contentTop, listScroll,
                history.size() * ROW);
        return tooltip;
    }

    @Nullable
    private List<Component> renderStats(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 6;
        int w = panelW - 16;
        if (stats.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("ae2declogger.stats.empty"), left + panelW / 2,
                    contentTop + 20, UiUtil.TEXT_DIM);
            return null;
        }
        listScroll = clampScroll(listScroll, stats.size() * ROW, contentBottom - contentTop);
        g.enableScissor(left + 1, contentTop, left + panelW - 1, contentBottom);
        for (int i = 0; i < stats.size(); i++) {
            int y = contentTop + i * ROW - (int) listScroll;
            if (y + ROW < contentTop || y > contentBottom) {
                continue;
            }
            JobStats.Row<AEKey> row = stats.get(i);
            UiUtil.key(g, row.key(), x + 5, y + 2);
            int rate = (int) Math.round(row.successRate() * 100);
            Component right = Component.translatable("ae2declogger.stats.jobs", row.jobs(), rate);
            int rw = font.width(right);
            UiUtil.text(g, font, row.key().getDisplayName(), x + 24, y + 2, w - 30 - rw - 6, UiUtil.TEXT);
            g.drawString(font, right, x + w - 4 - rw, y + 2, rate >= 90 ? 0xFF55FF55 : rate >= 50 ? 0xFFFFDD33
                    : 0xFFFF5555, false);
            String perUnit = row.msPerUnit() > 0 ? Formatting.duration(Math.round(row.msPerUnit()))
                    : "-";
            if (row.msPerUnit() > 0 && row.msPerUnit() < 1000) {
                perUnit = String.format(Locale.ROOT, "%.2fs", row.msPerUnit() / 1000.0);
            }
            Component line2 = Component.translatable("ae2declogger.stats.line", Formatting.duration(row.avgDurationMs()),
                    Formatting.duration(row.bestDurationMs()), perUnit, Formatting.compactAmount(row.totalAmount()));
            UiUtil.text(g, font, line2, x + 24, y + 12, w - 30, UiUtil.TEXT_DIM);
        }
        g.disableScissor();
        UiUtil.scrollbar(g, left + panelW - 6, contentTop, contentBottom - contentTop, listScroll,
                stats.size() * ROW);
        return null;
    }

    @Nullable
    private List<Component> renderPins(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 6;
        int w = panelW - 16;
        List<PinManager.Pin> pins = PinManager.pins();
        if (pins.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("ae2declogger.pins.empty"), left + panelW / 2,
                    contentTop + 20, UiUtil.TEXT_DIM);
            return null;
        }
        listScroll = clampScroll(listScroll, pins.size() * ROW, contentBottom - contentTop);
        Player player = minecraft == null ? null : minecraft.player;
        PinManager.Pin active = PinManager.active();
        g.enableScissor(left + 1, contentTop, left + panelW - 1, contentBottom);
        for (int i = 0; i < pins.size(); i++) {
            int y = contentTop + i * ROW - (int) listScroll;
            if (y + ROW < contentTop || y > contentBottom) {
                continue;
            }
            PinManager.Pin pin = pins.get(i);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= Math.max(y, contentTop)
                    && mouseY < Math.min(y + ROW, contentBottom);
            if (pin.id.equals(selectedPin)) {
                g.fill(x, y, x + w, y + ROW - 1, UiUtil.ROW_SELECTED);
            } else if (hover) {
                g.fill(x, y, x + w, y + ROW - 1, UiUtil.ROW_HOVER);
            }
            g.fill(x + 4, y + 4, x + 14, y + 14, 0xFF000000 | pin.color);
            String distance = "";
            if (player != null && player.level().dimension().location().toString().equals(pin.dimension)) {
                distance = Math.round(Math.sqrt(player.blockPosition().distSqr(pin.pos()))) + "m";
            }
            Component right = Component.literal((pin == active ? "▶ " : "") + distance);
            int rw = font.width(right);
            UiUtil.text(g, font, Component.literal(pin.label), x + 20, y + 2, w - 26 - rw - 6, UiUtil.TEXT);
            g.drawString(font, right, x + w - 4 - rw, y + 2, pin == active ? 0xFF55FF55 : UiUtil.TEXT_DIM, false);
            UiUtil.text(g, font, Component.literal(pin.x + " " + pin.y + " " + pin.z + "  " + pin.dimension),
                    x + 20, y + 12, w - 26, UiUtil.TEXT_DIM);
        }
        g.disableScissor();
        UiUtil.scrollbar(g, left + panelW - 6, contentTop, contentBottom - contentTop, listScroll,
                pins.size() * ROW);
        return null;
    }

    private static double clampScroll(double scroll, int content, int view) {
        return Math.max(0, Math.min(scroll, Math.max(0, content - view)));
    }

    // ---------------------------------------------------------------- hooks for the automated client self-test

    /** Selects the job at {@code index} in the current (filtered) list. */
    public void testSelectJob(int index) {
        rebuildIfNeeded();
        if (index >= 0 && index < jobs.size()) {
            selectedJob = jobs.get(index).id();
            selectedStep = null;
        }
    }

    /** Selects the first job whose output display name contains {@code text}. */
    public boolean testSelectJobNamed(String text) {
        rebuildIfNeeded();
        for (JobView job : jobs) {
            if (job.output().getDisplayName().getString().contains(text)) {
                selectedJob = job.id();
                selectedStep = null;
                return true;
            }
        }
        return false;
    }

    public void testSelectStep(int index) {
        JobView job = selectedJobView();
        if (job != null && index >= 0 && index < job.steps().size()) {
            selectedJob = job.id();
            selectedStep = job.steps().get(index).key();
        }
    }

    public void testSwitchTab(int index) {
        switchTab(Tab.values()[index]);
    }

    /** Presses one of the job buttons: "goto", "view" or "pin". */
    public void testPress(String button) {
        MachineRef target = targetMachine();
        switch (button) {
            case "goto" -> goTo(target);
            case "view" -> view(target);
            case "pin" -> pin(target, false);
            default -> throw new IllegalArgumentException(button);
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int x = left + 6;
        int w = panelW - 16;
        if (mouseX < x || mouseX >= x + w || mouseY < contentTop || mouseY >= contentBottom) {
            return false;
        }
        switch (tab) {
            case JOBS -> {
                int split = contentTop + (int) ((contentBottom - contentTop) * 0.52);
                if (mouseY < split) {
                    int index = (int) ((mouseY - contentTop + jobScroll) / JOB_ROW);
                    if (index >= 0 && index < jobs.size()) {
                        long id = jobs.get(index).id();
                        if (id != selectedJob) {
                            selectedStep = null;
                            stepScroll = 0;
                        }
                        selectedJob = id;
                        if (button == 1) {
                            view(jobs.get(index).primaryMachine());
                        }
                        return true;
                    }
                } else if (mouseY >= split + 16) {
                    JobView job = selectedJobView();
                    if (job != null) {
                        int index = (int) ((mouseY - split - 16 + stepScroll) / STEP_ROW);
                        if (index >= 0 && index < job.steps().size()) {
                            StepView step = job.steps().get(index);
                            selectedJob = job.id();
                            selectedStep = step.key().equals(selectedStep) && button == 0 ? null : step.key();
                            if (button == 1 && !step.machines().isEmpty()) {
                                selectedStep = step.key();
                                view(targetMachine());
                            }
                            return true;
                        }
                    }
                }
            }
            case PINS -> {
                int index = (int) ((mouseY - contentTop + listScroll) / ROW);
                List<PinManager.Pin> pins = PinManager.pins();
                if (index >= 0 && index < pins.size()) {
                    PinManager.Pin pin = pins.get(index);
                    if (pin.id.equals(selectedPin) && button == 0) {
                        // Second click on a pin starts navigating to it.
                        PinManager.setActive(pin == PinManager.active() ? null : pin);
                    }
                    selectedPin = pin.id;
                    return true;
                }
            }
            default -> {
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double amount = -delta * 12;
        if (tab == Tab.JOBS) {
            int split = contentTop + (int) ((contentBottom - contentTop) * 0.52);
            if (mouseY < split) {
                jobScroll += amount;
            } else {
                stepScroll += amount;
            }
        } else {
            listScroll += amount;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && search.visible) {
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (search.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            return search.keyPressed(keyCode, scanCode, modifiers) || search.canConsumeInput();
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_4 && modifiers == 0) {
            switchTab(Tab.values()[keyCode - GLFW.GLFW_KEY_1]);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
