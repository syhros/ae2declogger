package com.syhros.ae2declogger.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.syhros.ae2declogger.client.ui.Btn;
import com.syhros.ae2declogger.client.ui.Canvas;
import com.syhros.ae2declogger.client.ui.Keys;
import com.syhros.ae2declogger.client.ui.TextInput;
import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.Formatting;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.logic.JobStats;
import com.syhros.ae2declogger.net.Messages;

/**
 * The monitor: running jobs with their steps, history, per-item stats and locator pins. No container behind it, so
 * nothing in it can move items. The legacy port of the modern screen, drawn through a {@link Canvas}.
 */
public final class DecloggerScreen extends UiScreen {

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
    private static Boolean problemsOnly;

    private Tab tab = lastTab;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int contentTop;
    private int contentBottom;

    private TextInput search;
    private Btn problemsButton;
    private Btn alertsButton;
    private final List<Btn> tabButtons = new ArrayList<>();
    private final List<Btn> jobButtons = new ArrayList<>();
    private final List<Btn> historyButtons = new ArrayList<>();
    private final List<Btn> statsButtons = new ArrayList<>();
    private final List<Btn> pinButtons = new ArrayList<>();
    private Btn gotoButton;
    private Btn viewButton;
    private Btn pinButton;
    private Btn navigateButton;
    private Btn pinRemoveButton;

    private long selectedJob = -1;
    private LKey selectedStep;
    private String selectedPin;

    private double jobScroll;
    private double stepScroll;
    private double listScroll;

    private boolean keepSubscription;
    private long builtVersion = -1;
    private String builtFilter = "";
    private boolean builtProblemsOnly;
    private List<JobView> jobs = Collections.emptyList();
    private List<HistoryEntry> history = Collections.emptyList();
    private List<JobStats.Row<LKey>> stats = Collections.emptyList();

    public DecloggerScreen() {
        if (problemsOnly == null) {
            problemsOnly = DeclogConfig.showOnlyProblems();
        }
    }

    /** Called before a child screen (machine viewer) replaces this one, so the server keeps sending updates. */
    public void keepSubscriptionForChild() {
        keepSubscription = true;
    }

    @Override
    protected void layout() {
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
            final Tab target = t;
            Btn b = new Btn(tx, top + 19, 54, 14, Lang.t("ae2declogger.tab." + t.name().toLowerCase(Locale.ROOT)),
                    () -> switchTab(target));
            tabButtons.add(b);
            buttons.add(b);
            tx += 56;
        }
        int right = left + panelW - 6;
        alertsButton = new Btn(right - 16, top + 19, 16, 14, "♪",
                () -> ClientPlat.send(Messages.toggleAlerts()));
        problemsButton = new Btn(right - 34, top + 19, 16, 14, "!", () -> {
            problemsOnly = !problemsOnly;
            jobScroll = 0;
        });
        buttons.add(alertsButton);
        buttons.add(problemsButton);
        String keep = search != null ? search.value() : "";
        int searchW = Math.max(60, Math.min(120, right - 38 - tx - 4));
        search = new TextInput(right - 38 - searchW, top + 20, searchW, 12, 64);
        search.hint = Lang.t("ae2declogger.search");
        search.setValue(keep);
        inputs.add(search);

        int by = top + panelH - 21;
        int bw = Math.min(110, (panelW - 12 - 6) / 3);
        int bx = left + 6;
        jobButtons.clear();
        gotoButton = add(jobButtons, new Btn(bx, by, bw, 16, Lang.t("ae2declogger.button.goto"),
                () -> goTo(targetMachine())));
        viewButton = add(jobButtons, new Btn(bx + bw + 3, by, bw, 16, Lang.t("ae2declogger.button.view"),
                () -> view(targetMachine())));
        pinButton = add(jobButtons, new Btn(bx + 2 * (bw + 3), by, bw, 16, Lang.t("ae2declogger.button.pin"),
                () -> pin(targetMachine())));
        gotoButton.tooltip = Lang.t("ae2declogger.button.goto.tooltip");
        viewButton.tooltip = Lang.t("ae2declogger.button.view.tooltip");
        pinButton.tooltip = Lang.t("ae2declogger.button.pin.tooltip");

        historyButtons.clear();
        add(historyButtons, new Btn(bx, by, bw, 16, Lang.t("ae2declogger.button.export"),
                () -> Exporter.export(new ArrayList<>(ClientState.JOBS.values()), ClientState.HISTORY)));
        add(historyButtons, new Btn(bx + bw + 3, by, bw, 16, Lang.t("ae2declogger.button.clear"),
                () -> ClientPlat.send(Messages.clearHistory())));
        statsButtons.clear();
        add(statsButtons, new Btn(bx, by, bw, 16, Lang.t("ae2declogger.button.export"),
                () -> Exporter.export(new ArrayList<>(ClientState.JOBS.values()), ClientState.HISTORY)));

        pinButtons.clear();
        navigateButton = add(pinButtons, new Btn(bx, by, bw, 16, Lang.t("ae2declogger.button.navigate"),
                this::toggleNavigate));
        pinRemoveButton = add(pinButtons, new Btn(bx + bw + 3, by, bw, 16, Lang.t("ae2declogger.button.remove"),
                () -> {
                    PinManager.Pin p = selectedPinObj();
                    if (p != null) {
                        PinManager.remove(p);
                        selectedPin = null;
                    }
                }));
        add(pinButtons, new Btn(bx + 2 * (bw + 3), by, bw, 16, Lang.t("ae2declogger.button.clear_all"), () -> {
            PinManager.clear();
            selectedPin = null;
        }));
        updateWidgets();
    }

    private Btn add(List<Btn> group, Btn b) {
        group.add(b);
        buttons.add(b);
        return b;
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
        for (Btn b : jobButtons) {
            b.visible = tab == Tab.JOBS;
        }
        for (Btn b : historyButtons) {
            b.visible = tab == Tab.HISTORY;
        }
        for (Btn b : statsButtons) {
            b.visible = tab == Tab.STATS;
        }
        for (Btn b : pinButtons) {
            b.visible = tab == Tab.PINS;
        }
        MachineRef target = targetMachine();
        gotoButton.active = target != null;
        viewButton.active = target != null;
        pinButton.active = target != null;
        PinManager.Pin pin = selectedPinObj();
        navigateButton.active = pin != null;
        navigateButton.label = Lang.t(pin != null && pin == PinManager.active() ? "ae2declogger.button.stop"
                : "ae2declogger.button.navigate");
        pinRemoveButton.active = pin != null;
        problemsButton.labelColor = problemsOnly ? 0xFFFF5555 : 0xFFAAAAAA;
        problemsButton.tooltip = Lang.t(problemsOnly ? "ae2declogger.button.problems.on"
                : "ae2declogger.button.problems.off");
        alertsButton.labelColor = ClientState.alertsOn ? 0xFF55FF55 : 0xFF555555;
        alertsButton.tooltip = Lang.t(ClientState.alertsOn ? "ae2declogger.button.alerts.on"
                : "ae2declogger.button.alerts.off");
        search.visible = tab != Tab.PINS;
    }

    @Override
    public void tick() {
        super.tick();
        applyPendingFocus();
        updateWidgets();
    }

    /** Inspect in AE2's crafting status screen: select that job and step once the data is here. */
    private void applyPendingFocus() {
        if (ClientState.pendingFocusJob < 0 || ClientState.summary == null) {
            return;
        }
        long id = ClientState.pendingFocusJob;
        LKey step = ClientState.pendingFocusStep;
        ClientState.pendingFocusJob = -1;
        ClientState.pendingFocusStep = null;
        JobView job = ClientState.JOBS.get(id);
        if (job == null && step != null) {
            for (JobView j : ClientState.JOBS.values()) {
                if (j.output.equals(step) || hasStep(j, step)) {
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
        problemsOnly = false;
        search.setValue("");
        selectedJob = job.id;
        selectedStep = step != null && hasStep(job, step) ? step : null;
    }

    private static boolean hasStep(JobView job, LKey key) {
        for (StepView s : job.steps) {
            if (s.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- data

    private void rebuildIfNeeded() {
        String filter = search.value().trim().toLowerCase(Locale.ROOT);
        if (builtVersion == ClientState.version && filter.equals(builtFilter) && builtProblemsOnly == problemsOnly) {
            return;
        }
        builtVersion = ClientState.version;
        builtFilter = filter;
        builtProblemsOnly = problemsOnly;

        List<JobView> j = new ArrayList<>();
        for (JobView job : ClientState.JOBS.values()) {
            if (problemsOnly && job.state == JobState.IN_PROGRESS) {
                continue;
            }
            if (!filter.isEmpty() && !matches(job, filter)) {
                continue;
            }
            j.add(job);
        }
        jobs = j;

        List<HistoryEntry> h = new ArrayList<>();
        List<JobStats.Entry<LKey>> entries = new ArrayList<>();
        for (HistoryEntry e : ClientState.HISTORY) {
            entries.add(new JobStats.Entry<>(e.output, e.amount, e.durationMs, e.completed, e.isStep()));
            if (problemsOnly && e.completed) {
                continue;
            }
            if (!filter.isEmpty() && !name(e.output).contains(filter)) {
                continue;
            }
            h.add(e);
        }
        history = h;

        List<JobStats.Row<LKey>> s = new ArrayList<>();
        for (JobStats.Row<LKey> row : JobStats.compute(entries)) {
            if (filter.isEmpty() || name(row.key()).contains(filter)) {
                s.add(row);
            }
        }
        stats = s;
    }

    private static boolean matches(JobView job, String filter) {
        if (name(job.output).contains(filter)) {
            return true;
        }
        for (StepView step : job.steps) {
            if (name(step.key).contains(filter)) {
                return true;
            }
        }
        return false;
    }

    private static String name(LKey key) {
        return key.displayName().toLowerCase(Locale.ROOT);
    }

    private JobView selectedJobView() {
        for (JobView j : jobs) {
            if (j.id == selectedJob) {
                return j;
            }
        }
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private StepView selectedStepView(JobView job) {
        if (job == null || selectedStep == null) {
            return null;
        }
        for (StepView s : job.steps) {
            if (s.key.equals(selectedStep)) {
                return s;
            }
        }
        return null;
    }

    /** Machine the action buttons act on: selected step's worst machine, else the job's main machine. */
    private MachineRef targetMachine() {
        if (tab != Tab.JOBS) {
            return null;
        }
        rebuildIfNeeded();
        JobView job = selectedJobView();
        StepView step = selectedStepView(job);
        if (step != null && !step.machines.isEmpty()) {
            for (MachineRef m : step.machines) {
                if (m.hasProblem()) {
                    return m;
                }
            }
            return step.machines.get(0);
        }
        return job == null ? null : job.primaryMachine;
    }

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

    /** Closes the monitor and outlines the machine in red, with the way to it on the HUD, until you get there. */
    private void goTo(MachineRef m) {
        if (m == null) {
            return;
        }
        PinManager.setTarget(pinLabel(m), m.pos);
        requestClose();
    }

    private String pinLabel(MachineRef m) {
        JobView job = selectedJobView();
        String label = Lang.of(m.name);
        if (job != null) {
            label = job.output.displayName() + " - " + label;
        }
        return label;
    }

    private void pin(MachineRef m) {
        if (m == null) {
            return;
        }
        String label = pinLabel(m);
        PinManager.add(label, m.pos);
        ClientPlat.status(Lang.t("ae2declogger.pin.added", label, UiUtil.coords(m)));
    }

    private void view(MachineRef m) {
        if (m == null) {
            return;
        }
        ClientPlat.send(Messages.requestMachine(m.pos, true));
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
            ClientPlat.send(Messages.closeMonitor());
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected List<String> draw(Canvas c, int mx, int my) {
        rebuildIfNeeded();
        UiUtil.panel(c, left, top, panelW, panelH);
        drawHeader(c);
        switch (tab) {
            case JOBS:
                return drawJobs(c, mx, my);
            case HISTORY:
                return drawHistory(c, mx, my);
            case STATS:
                return drawStats(c);
            default:
                return drawPins(c, mx, my);
        }
    }

    private void drawHeader(Canvas c) {
        int x = left + 6;
        int y = top + 6;
        UiUtil.text(c, Lang.of(ClientState.title), x, y, panelW / 2, UiUtil.TEXT);
        String right;
        int color = UiUtil.TEXT_DIM;
        if (ClientState.closedReason != null) {
            right = Lang.of(ClientState.closedReason);
            color = UiUtil.RED;
        } else if (ClientState.summary == null) {
            right = Lang.t("ae2declogger.monitor.loading");
        } else {
            String power = Formatting.compactAmount((long) ClientState.summary.storedPower) + " / "
                    + Formatting.compactAmount((long) ClientState.summary.maxPower) + " AE";
            right = Lang.t("ae2declogger.monitor.summary", ClientState.summary.busyCpus, ClientState.summary.cpus,
                    power);
            if (!ClientState.summary.powered) {
                right = Lang.t("ae2declogger.monitor.unpowered");
                color = UiUtil.RED;
            }
        }
        UiUtil.textRight(c, right, left + panelW - 6, y, panelW / 2 - 10, color);
        c.fill(left + 4, top + 35, left + panelW - 4, top + 36, UiUtil.PANEL_BORDER);
    }

    private List<String> drawJobs(Canvas c, int mx, int my) {
        List<String> tooltip = null;
        int x = left + 6;
        int w = panelW - 16;
        int split = contentTop + (int) ((contentBottom - contentTop) * 0.52);
        int listTop = contentTop;
        int listBottom = split;
        if (jobs.isEmpty()) {
            String msg = ClientState.summary == null ? Lang.t("ae2declogger.monitor.loading")
                    : ClientState.JOBS.isEmpty() ? Lang.t("ae2declogger.monitor.no_jobs")
                            : Lang.t("ae2declogger.monitor.no_match");
            UiUtil.centeredWrapped(c, msg, left + panelW / 2, listTop + 20, panelW - 16, UiUtil.TEXT_DIM);
            return null;
        }
        jobScroll = clampScroll(jobScroll, jobs.size() * JOB_ROW, listBottom - listTop);
        JobView selected = selectedJobView();
        long now = ClientState.serverNow();
        c.scissor(left + 1, listTop, left + panelW - 1, listBottom);
        for (int i = 0; i < jobs.size(); i++) {
            int y = listTop + i * JOB_ROW - (int) jobScroll;
            if (y + JOB_ROW < listTop || y > listBottom) {
                continue;
            }
            JobView job = jobs.get(i);
            boolean hover = mx >= x && mx < x + w && my >= Math.max(y, listTop) && my < Math.min(y + JOB_ROW,
                    listBottom);
            if (job == selected) {
                c.fill(x, y, x + w, y + JOB_ROW - 1, UiUtil.ROW_SELECTED);
            } else if (hover) {
                c.fill(x, y, x + w, y + JOB_ROW - 1, UiUtil.ROW_HOVER);
            }
            c.fill(x, y, x + 2, y + JOB_ROW - 1, UiUtil.stateColor(job.state));
            c.item(job.output, x + 5, y + 3);
            String name = Lang.t("ae2declogger.amount_of", job.output.displayName(), UiUtil.amount(job.amount));
            String state = UiUtil.stateLabel(job.state);
            int stateW = c.width(state);
            UiUtil.text(c, name, x + 24, y + 3, w - 30 - stateW - 6, UiUtil.TEXT);
            c.text(state, x + w - 4 - stateW, y + 3, UiUtil.stateColor(job.state), false);

            UiUtil.progressBar(c, x + 24, y + 15, 40, 4, job.progress, job.state.color());
            String times = Formatting.percent(job.progress) + "  " + Formatting.duration(now - job.startedAtMs);
            if (job.expectedMs > 0) {
                times += " / ~" + Formatting.duration(job.expectedMs);
            }
            long idle = now - job.lastActivityAtMs;
            if (idle >= 10_000) {
                times += "  " + Lang.t("ae2declogger.idle", Formatting.duration(idle));
            }
            int tw = UiUtil.text(c, times, x + 68, y + 13, w - 72, UiUtil.TEXT_DIM);
            String reasonOrCpu = job.reason.isProblem() ? UiUtil.reasonText(job.reason, job.detail)
                    : Lang.of(job.cpuName);
            int reasonColor = job.reason.isProblem() ? UiUtil.reasonColor(job.reason, job.state) : UiUtil.TEXT_FAINT;
            UiUtil.textRight(c, reasonOrCpu, x + w - 4, y + 13, w - 72 - tw - 10, reasonColor);
            if (hover) {
                tooltip = jobTooltip(job, now);
            }
        }
        c.endScissor();
        UiUtil.scrollbar(c, left + panelW - 6, listTop, listBottom - listTop, jobScroll, jobs.size() * JOB_ROW);

        c.fill(left + 4, split + 1, left + panelW - 4, split + 2, UiUtil.PANEL_BORDER);
        if (selected == null) {
            return tooltip;
        }
        UiUtil.text(c, Lang.t("ae2declogger.monitor.steps_for", selected.output.displayName(),
                Lang.of(selected.cpuName)), x, split + 5, w, UiUtil.TEXT_DIM);
        int stepTop = split + 16;
        List<StepView> steps = selected.steps;
        if (steps.isEmpty()) {
            UiUtil.text(c, Lang.t("ae2declogger.monitor.no_steps"), x + 4, stepTop + 4, w - 8, UiUtil.TEXT_FAINT);
            return tooltip;
        }
        stepScroll = clampScroll(stepScroll, steps.size() * STEP_ROW, contentBottom - stepTop);
        StepView selStep = selectedStepView(selected);
        c.scissor(left + 1, stepTop, left + panelW - 1, contentBottom);
        for (int i = 0; i < steps.size(); i++) {
            int y = stepTop + i * STEP_ROW - (int) stepScroll;
            if (y + STEP_ROW < stepTop || y > contentBottom) {
                continue;
            }
            StepView step = steps.get(i);
            boolean hover = mx >= x && mx < x + w && my >= Math.max(y, stepTop) && my < Math.min(y + STEP_ROW,
                    contentBottom);
            if (step == selStep) {
                c.fill(x, y, x + w, y + STEP_ROW - 1, UiUtil.ROW_SELECTED);
            } else if (hover) {
                c.fill(x, y, x + w, y + STEP_ROW - 1, UiUtil.ROW_HOVER);
            }
            c.fill(x, y, x + 2, y + STEP_ROW - 1, UiUtil.stateColor(step.state));
            c.item(step.key, x + 5, y + 2);
            String state = UiUtil.stateLabel(step.state);
            int stateW = c.width(state);
            UiUtil.text(c, step.key.displayName(), x + 24, y + 2, w - 30 - stateW - 6, UiUtil.TEXT);
            c.text(state, x + w - 4 - stateW, y + 2, UiUtil.stateColor(step.state), false);
            String counts = Lang.t("ae2declogger.step.counts", UiUtil.amount(step.stored), UiUtil.amount(step.active),
                    UiUtil.amount(step.pending));
            int cw = UiUtil.text(c, counts, x + 24, y + 12, w / 2, UiUtil.TEXT_DIM);
            String right;
            int color;
            if (step.reason != BlockReason.NONE) {
                right = UiUtil.reasonText(step.reason, step.detail);
                color = UiUtil.reasonColor(step.reason, step.state);
            } else if (!step.machines.isEmpty()) {
                MachineRef m = step.machines.get(0);
                right = Lang.of(m.name) + " @ " + UiUtil.coords(m);
                color = UiUtil.TEXT_FAINT;
            } else {
                right = "";
                color = UiUtil.TEXT_FAINT;
            }
            UiUtil.textRight(c, right, x + w - 4, y + 12, w - 24 - cw - 12, color);
            if (hover) {
                tooltip = stepTooltip(step, now);
            }
        }
        c.endScissor();
        UiUtil.scrollbar(c, left + panelW - 6, stepTop, contentBottom - stepTop, stepScroll, steps.size() * STEP_ROW);
        return tooltip;
    }

    private List<String> jobTooltip(JobView job, long now) {
        List<String> lines = new ArrayList<>();
        lines.add(Lang.t("ae2declogger.amount_of", job.output.displayName(), UiUtil.amount(job.amount)));
        lines.add(Lang.t("ae2declogger.tooltip.state",
                UiUtil.code(UiUtil.stateColor(job.state)) + UiUtil.stateLabel(job.state) + "§r"));
        if (job.reason != BlockReason.NONE) {
            lines.add(UiUtil.code(UiUtil.reasonColor(job.reason, job.state)) + UiUtil.reasonText(job.reason,
                    job.detail));
        }
        lines.add("§7" + Lang.t("ae2declogger.tooltip.elapsed", Formatting.duration(now - job.startedAtMs),
                job.expectedMs > 0 ? Formatting.duration(job.expectedMs) : "-"));
        lines.add("§7" + Lang.t("ae2declogger.tooltip.idle", Formatting.duration(now - job.lastActivityAtMs)));
        lines.add("§8" + Lang.t("ae2declogger.tooltip.cpu", Lang.of(job.cpuName), job.coProcessors,
                Formatting.compactAmount(job.cpuStorage)));
        if (job.primaryMachine != null) {
            lines.add(machineLine(job.primaryMachine));
        }
        lines.add("§8" + Lang.t("ae2declogger.tooltip.click_job"));
        return lines;
    }

    private List<String> stepTooltip(StepView step, long now) {
        List<String> lines = new ArrayList<>();
        lines.add(step.key.displayName());
        lines.add("§7" + Lang.t("ae2declogger.step.counts.long", UiUtil.amount(step.stored),
                UiUtil.amount(step.active), UiUtil.amount(step.pending)));
        lines.add(Lang.t("ae2declogger.tooltip.state",
                UiUtil.code(UiUtil.stateColor(step.state)) + UiUtil.stateLabel(step.state) + "§r"));
        if (step.reason != BlockReason.NONE) {
            lines.add(UiUtil.code(UiUtil.reasonColor(step.reason, step.state)) + UiUtil.reasonText(step.reason,
                    step.detail));
        }
        if (step.sinceMs > 0) {
            lines.add("§7" + Lang.t("ae2declogger.tooltip.unchanged", Formatting.duration(now - step.sinceMs)));
        }
        if (step.recipeMs > 0) {
            lines.add("§7" + Lang.t("ae2declogger.tooltip.recipe_time", Formatting.duration(step.recipeMs)));
        }
        for (MachineRef m : step.machines) {
            lines.add(machineLine(m));
        }
        if (!step.machines.isEmpty()) {
            lines.add("§8" + Lang.t("ae2declogger.tooltip.click_step"));
        }
        return lines;
    }

    private static String machineLine(MachineRef m) {
        String status = UiUtil.code(0xFF000000 | m.status.color()) + Lang.t(m.status.translationKey()) + "§r";
        String line = Lang.t("ae2declogger.tooltip.machine", Lang.of(m.name), UiUtil.coords(m), status);
        if (m.hasProblem()) {
            line += " - §c" + UiUtil.reasonText(m.problem, m.detail) + "§r";
        } else if (m.detail != null) {
            line += " - " + Lang.of(m.detail);
        }
        if (m.duration > 0) {
            line += String.format(Locale.ROOT, " §8(%.1fs/%.1fs)", m.progress / 20.0, m.duration / 20.0);
        }
        return line;
    }

    private List<String> drawHistory(Canvas c, int mx, int my) {
        int x = left + 6;
        int w = panelW - 16;
        if (history.isEmpty()) {
            UiUtil.centeredWrapped(c, Lang.t("ae2declogger.history.empty"), left + panelW / 2, contentTop + 20,
                    panelW - 16, UiUtil.TEXT_DIM);
            return null;
        }
        listScroll = clampScroll(listScroll, history.size() * ROW, contentBottom - contentTop);
        long now = ClientState.serverNow();
        List<String> tooltip = null;
        c.scissor(left + 1, contentTop, left + panelW - 1, contentBottom);
        for (int i = 0; i < history.size(); i++) {
            int y = contentTop + i * ROW - (int) listScroll;
            if (y + ROW < contentTop || y > contentBottom) {
                continue;
            }
            HistoryEntry e = history.get(i);
            boolean hover = mx >= x && mx < x + w && my >= Math.max(y, contentTop) && my < Math.min(y + ROW,
                    contentBottom);
            if (hover) {
                c.fill(x, y, x + w, y + ROW - 1, UiUtil.ROW_HOVER);
            }
            JobState state = e.completed ? JobState.COMPLETE : JobState.CANCELLED;
            c.fill(x, y, x + 2, y + ROW - 1, e.isStep() ? 0xFF3A6F78 : UiUtil.stateColor(state));
            c.item(e.output, x + 5, y + 2);
            String label = e.isStep() ? Lang.t("ae2declogger.history.step") : UiUtil.stateLabel(state);
            int labelColor = e.isStep() ? 0xFF7FB8C2 : UiUtil.stateColor(state);
            int lw = c.width(label);
            UiUtil.text(c, Lang.t("ae2declogger.amount_of", e.output.displayName(), UiUtil.amount(e.amount)), x + 24,
                    y + 2, w - 30 - lw - 6, UiUtil.TEXT);
            c.text(label, x + w - 4 - lw, y + 2, labelColor, false);
            String line2 = e.isStep()
                    ? Lang.t("ae2declogger.history.step_line", Formatting.duration(e.durationMs),
                            UiUtil.ago(now - e.finishedAtMs), e.partOf.displayName())
                    : Lang.t("ae2declogger.history.line", Formatting.duration(e.durationMs),
                            UiUtil.ago(now - e.finishedAtMs), Lang.of(e.cpuName));
            UiUtil.text(c, line2, x + 24, y + 12, w - 30, UiUtil.TEXT_DIM);
            if (hover) {
                List<String> t = new ArrayList<>();
                t.add(e.output.displayName());
                t.add("§7" + line2);
                tooltip = t;
            }
        }
        c.endScissor();
        UiUtil.scrollbar(c, left + panelW - 6, contentTop, contentBottom - contentTop, listScroll,
                history.size() * ROW);
        return tooltip;
    }

    /** Time per item: seconds with two decimals under a second, otherwise a duration; "-" when unknown. */
    private static String perItem(double ms) {
        if (ms <= 0) {
            return "-";
        }
        return ms < 1000 ? String.format(Locale.ROOT, "%.2fs", ms / 1000.0) : Formatting.duration(Math.round(ms));
    }

    private List<String> drawStats(Canvas c) {
        int x = left + 6;
        int w = panelW - 16;
        if (stats.isEmpty()) {
            UiUtil.centeredWrapped(c, Lang.t("ae2declogger.stats.empty"), left + panelW / 2, contentTop + 20,
                    panelW - 16, UiUtil.TEXT_DIM);
            return null;
        }
        listScroll = clampScroll(listScroll, stats.size() * ROW, contentBottom - contentTop);
        c.scissor(left + 1, contentTop, left + panelW - 1, contentBottom);
        for (int i = 0; i < stats.size(); i++) {
            int y = contentTop + i * ROW - (int) listScroll;
            if (y + ROW < contentTop || y > contentBottom) {
                continue;
            }
            JobStats.Row<LKey> row = stats.get(i);
            c.item(row.key(), x + 5, y + 2);
            int rate = (int) Math.round(row.successRate() * 100);
            String right = row.jobs() == 0 ? Lang.t("ae2declogger.stats.steps", row.steps())
                    : row.steps() > 0 ? Lang.t("ae2declogger.stats.jobs_steps", row.jobs(), rate, row.steps())
                            : Lang.t("ae2declogger.stats.jobs", row.jobs(), rate);
            int rw = c.width(right);
            UiUtil.text(c, row.key().displayName(), x + 24, y + 2, w - 30 - rw - 6, UiUtil.TEXT);
            c.text(right, x + w - 4 - rw, y + 2, row.jobs() == 0 ? 0xFF7FB8C2 : rate >= 90 ? 0xFF55FF55
                    : rate >= 50 ? 0xFFFFDD33 : 0xFFFF5555, false);
            String line2;
            if (row.jobs() == 0) {
                line2 = Lang.t("ae2declogger.stats.step_line", Formatting.duration(row.stepAvgMs()),
                        perItem(row.stepMsPerUnit()), Formatting.compactAmount(row.totalAmount()));
            } else {
                line2 = Lang.t("ae2declogger.stats.line", Formatting.duration(row.avgDurationMs()),
                        Formatting.duration(row.bestDurationMs()), perItem(row.msPerUnit()),
                        Formatting.compactAmount(row.totalAmount()));
                if (row.steps() > 0) {
                    line2 += Lang.t("ae2declogger.stats.step_suffix", perItem(row.stepMsPerUnit()));
                }
            }
            UiUtil.text(c, line2, x + 24, y + 12, w - 30, UiUtil.TEXT_DIM);
        }
        c.endScissor();
        UiUtil.scrollbar(c, left + panelW - 6, contentTop, contentBottom - contentTop, listScroll,
                stats.size() * ROW);
        return null;
    }

    private List<String> drawPins(Canvas c, int mx, int my) {
        int x = left + 6;
        int w = panelW - 16;
        List<PinManager.Pin> pins = PinManager.pins();
        if (pins.isEmpty()) {
            UiUtil.centeredWrapped(c, Lang.t("ae2declogger.pins.empty"), left + panelW / 2, contentTop + 20,
                    panelW - 16, UiUtil.TEXT_DIM);
            return null;
        }
        listScroll = clampScroll(listScroll, pins.size() * ROW, contentBottom - contentTop);
        String dim = ClientPlat.playerDim();
        double[] p = ClientPlat.playerPos();
        PinManager.Pin active = PinManager.active();
        c.scissor(left + 1, contentTop, left + panelW - 1, contentBottom);
        for (int i = 0; i < pins.size(); i++) {
            int y = contentTop + i * ROW - (int) listScroll;
            if (y + ROW < contentTop || y > contentBottom) {
                continue;
            }
            PinManager.Pin pin = pins.get(i);
            boolean hover = mx >= x && mx < x + w && my >= Math.max(y, contentTop) && my < Math.min(y + ROW,
                    contentBottom);
            if (pin.id.equals(selectedPin)) {
                c.fill(x, y, x + w, y + ROW - 1, UiUtil.ROW_SELECTED);
            } else if (hover) {
                c.fill(x, y, x + w, y + ROW - 1, UiUtil.ROW_HOVER);
            }
            c.fill(x + 4, y + 4, x + 14, y + 14, 0xFF000000 | pin.color);
            String distance = "";
            if (p != null && pin.dimension.equals(dim)) {
                distance = Math.round(Math.sqrt(pin.pos().distSq(p[0], p[1], p[2]))) + "m";
            }
            String right = (pin == active ? "▶ " : "") + distance;
            int rw = c.width(right);
            UiUtil.text(c, pin.label, x + 20, y + 2, w - 26 - rw - 6, UiUtil.TEXT);
            c.text(right, x + w - 4 - rw, y + 2, pin == active ? 0xFF55FF55 : UiUtil.TEXT_DIM, false);
            UiUtil.text(c, pin.x + " " + pin.y + " " + pin.z + "  " + pin.dimension, x + 20, y + 12, w - 26,
                    UiUtil.TEXT_DIM);
        }
        c.endScissor();
        UiUtil.scrollbar(c, left + panelW - 6, contentTop, contentBottom - contentTop, listScroll, pins.size() * ROW);
        return null;
    }

    // ---------------------------------------------------------------- input

    @Override
    protected boolean clicked(int mx, int my, int button) {
        int x = left + 6;
        int w = panelW - 16;
        if (mx < x || mx >= x + w || my < contentTop || my >= contentBottom) {
            return false;
        }
        if (tab == Tab.JOBS) {
            int split = contentTop + (int) ((contentBottom - contentTop) * 0.52);
            if (my < split) {
                int index = (int) ((my - contentTop + jobScroll) / JOB_ROW);
                if (index >= 0 && index < jobs.size()) {
                    long id = jobs.get(index).id;
                    if (id != selectedJob) {
                        selectedStep = null;
                        stepScroll = 0;
                    }
                    selectedJob = id;
                    if (button == 1) {
                        view(jobs.get(index).primaryMachine);
                    }
                    return true;
                }
            } else if (my >= split + 16) {
                JobView job = selectedJobView();
                if (job != null) {
                    int index = (int) ((my - split - 16 + stepScroll) / STEP_ROW);
                    if (index >= 0 && index < job.steps.size()) {
                        StepView step = job.steps.get(index);
                        selectedJob = job.id;
                        selectedStep = step.key.equals(selectedStep) && button == 0 ? null : step.key;
                        if (button == 1 && !step.machines.isEmpty()) {
                            selectedStep = step.key;
                            view(targetMachine());
                        }
                        return true;
                    }
                }
            }
        } else if (tab == Tab.PINS) {
            int index = (int) ((my - contentTop + listScroll) / ROW);
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
        return false;
    }

    @Override
    public void mouseScrolled(int mx, int my, double delta) {
        double amount = -delta * 12;
        if (tab == Tab.JOBS) {
            int split = contentTop + (int) ((contentBottom - contentTop) * 0.52);
            if (my < split) {
                jobScroll += amount;
            } else {
                stepScroll += amount;
            }
        } else {
            listScroll += amount;
        }
    }

    @Override
    protected boolean keyPressed(char ch, int key, boolean ctrl) {
        if (key == Keys.F && ctrl && search.visible) {
            search.focused = true;
            return true;
        }
        if (key >= Keys.DIGIT_1 && key < Keys.DIGIT_1 + 4 && !ctrl) {
            switchTab(Tab.values()[key - Keys.DIGIT_1]);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- hooks for the client self-test

    public void testSwitchTab(int index) {
        switchTab(Tab.values()[index]);
    }

    public int testJobCount() {
        rebuildIfNeeded();
        return jobs.size();
    }
}
