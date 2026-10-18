package com.syhros.ae2declogger.server;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.data.GridSummary;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.logic.AlertTracker;
import com.syhros.ae2declogger.logic.DeltaTracker;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.net.Messages;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Server side of the mod: who is watching which network, sending them updates, background stall alerts, history
 * recording, the machine viewer. Everything runs on the server thread. Players are the version's server player
 * objects, passed around as {@code Object} and handled by {@link Plat}.
 */
public final class DecloggerServer {

    private static final int MAX_ALLOWED_POSITIONS = 4096;
    /** How often jobs on networks nobody is watching are followed for the history (1 s). */
    private static final int RECORD_INTERVAL_TICKS = 20;
    private static final int VIEWER_COOLDOWN_TICKS = 5;

    private static final Map<UUID, Subscription> MONITORS = new HashMap<>();
    private static final Map<IGrid, GridTracker> TRACKERS = new WeakHashMap<>();
    private static final Map<UUID, AlertState> ALERTS = new HashMap<>();
    private static final Map<UUID, Long> LAST_VIEW_REQUEST = new HashMap<>();
    private static long ticks;

    private static final class Subscription {
        final GridSource source;
        final DeltaTracker<Long, JobView> delta = new DeltaTracker<>();
        final Set<LPos> allowed = new HashSet<>();
        IGrid grid;
        long historyVersionSent = -1;
        int historyEpochSent = -1;
        GridSummary lastSummary;
        boolean lastAlerts;
        long lastFocus = -1;
        /** Set while the player has AE2's crafting status screen open: data for the overlay only. */
        WeakReference<Object> overlayMenu;

        Subscription(GridSource source) {
            this.source = source;
        }

        boolean overlay() {
            return overlayMenu != null;
        }
    }

    private static final class AlertState {
        final AlertTracker<Long> tracker = new AlertTracker<>();
        // Weak so a network that was rebuilt or unloaded can be garbage collected.
        WeakReference<IGrid> grid = new WeakReference<>(null);
    }

    private DecloggerServer() {
    }

    // ---------------------------------------------------------------- monitor

    /**
     * The button in an AE2 terminal or crafting status screen ({@code fromTerminal}), or the hotkey: the ME block the
     * player is looking at, otherwise the network they opened last.
     */
    public static void openFromRequest(Object player, boolean fromTerminal) {
        if (fromTerminal) {
            IGridNode node = Ae.menuNode(Plat.openContainer(player));
            if (node != null) {
                // Close the terminal first so the client is not left with a dangling container.
                Plat.closeContainer(player);
                openMonitor(player, GridSource.ofNode(node, LText.tr("ae2declogger.monitor.title_terminal")));
                return;
            }
            Plat.message(player, LText.tr("ae2declogger.error.no_terminal"), true);
            return;
        }
        Plat.Hit hit = Plat.lookedAt(player);
        if (hit != null && Plat.tileAt(hit.pos) instanceof appeng.api.networking.IGridHost) {
            openMonitor(player, GridSource.ofLink(hit.pos, hit.side, LText.tr("ae2declogger.monitor.title")));
            return;
        }
        LPos last = NetworkMemory.last(player);
        if (last == null) {
            Plat.message(player, LText.tr("ae2declogger.error.no_network"), true);
            return;
        }
        openMonitor(player, GridSource.ofLink(last, -1, LText.tr("ae2declogger.monitor.title")));
    }

    /**
     * AE2's crafting status screen opened ({@code watch}) or closed. While it is open the client gets the analysis for
     * that network so it can mark stuck entries.
     */
    public static void watchCraftingScreen(Object player, boolean watch) {
        UUID id = Plat.uuid(player);
        Subscription current = MONITORS.get(id);
        if (!watch) {
            if (current != null && current.overlay()) {
                MONITORS.remove(id);
            }
            return;
        }
        Object menu = Plat.openContainer(player);
        IGridNode node = Ae.isCraftingCpuMenu(menu) ? Ae.menuNode(menu) : null;
        if (node == null) {
            return;
        }
        GridSource source = GridSource.ofNode(node, LText.tr("ae2declogger.monitor.title_terminal"));
        GridSource.Resolved resolved = source.resolve(player);
        if (resolved.grid == null) {
            return;
        }
        Subscription sub = new Subscription(source);
        sub.overlayMenu = new WeakReference<>(menu);
        MONITORS.put(id, sub);
        sendUpdate(player, sub, resolved.grid, true);
    }

    /** Job id of the CPU shown in an AE2 crafting CPU / status screen, or -1. */
    private static long focusedJob(Subscription sub, IGrid grid) {
        Object menu = sub.overlayMenu != null ? sub.overlayMenu.get() : null;
        CraftingCPUCluster cpu = menu != null ? Ae.menuCpu(menu) : null;
        if (cpu == null) {
            return -1;
        }
        GridTracker.CpuTrack track = tracker(grid).track(cpu);
        return track != null ? track.jobId : -1;
    }

    public static void openMonitor(Object player, GridSource source) {
        GridSource.Resolved resolved = source.resolve(player);
        if (resolved.error != null) {
            Plat.message(player, resolved.error, true);
            return;
        }
        Subscription sub = new Subscription(source);
        MONITORS.put(Plat.uuid(player), sub);
        LPos remembered = NetworkMemory.positionOf(resolved.grid, source.node);
        if (remembered == null) {
            remembered = source.link;
        }
        if (remembered != null) {
            NetworkMemory.rememberLast(player, remembered);
        }
        Plat.send(player, Messages.openMonitorScreen(source.title));
        sendUpdate(player, sub, resolved.grid, true);
    }

    public static void closeMonitor(Object player) {
        MONITORS.remove(Plat.uuid(player));
    }

    public static void onLogout(Object player) {
        UUID id = Plat.uuid(player);
        MONITORS.remove(id);
        ALERTS.remove(id);
        LAST_VIEW_REQUEST.remove(id);
    }

    public static void onServerStopped() {
        MONITORS.clear();
        ALERTS.clear();
        TRACKERS.clear();
        LAST_VIEW_REQUEST.clear();
        ticks = 0;
    }

    /** Called at the end of every server tick. */
    public static void tick() {
        ticks++;
        if (!MONITORS.isEmpty() && ticks % DeclogConfig.updateIntervalTicks() == 0) {
            for (Map.Entry<UUID, Subscription> e : new ArrayList<>(MONITORS.entrySet())) {
                Object player = Plat.player(e.getKey());
                if (player == null) {
                    MONITORS.remove(e.getKey());
                    continue;
                }
                try {
                    sendUpdate(player, e.getValue(), null, false);
                } catch (RuntimeException | LinkageError ex) {
                    Ae2Declogger.LOG.error("AE2 Declogger update failed for {}", Plat.playerName(player), ex);
                    MONITORS.remove(e.getKey());
                    Plat.send(player, Messages.monitorClosed(LText.tr("ae2declogger.error.internal")));
                }
            }
        }
        if (ticks % DeclogConfig.alertIntervalTicks() == 0) {
            runAlerts();
        }
        if (ticks % RECORD_INTERVAL_TICKS == 0) {
            recordAll();
        }
    }

    /**
     * Follows the jobs of every network with a busy crafting CPU (or one whose last job was still being followed), so
     * jobs and their steps reach the history even when nobody has the monitor open.
     */
    private static void recordAll() {
        long now = System.currentTimeMillis();
        for (IGrid grid : Ae.allGrids()) {
            try {
                GridTracker known = TRACKERS.get(grid);
                if (known != null && known.cachedTick == ticks) {
                    continue; // fully analysed for a watcher this tick
                }
                if (!Ae.hasBusyCpu(grid) && (known == null || known.trackedJobs() == 0)) {
                    continue;
                }
                GridAnalyzer.record(grid, tracker(grid), now);
            } catch (RuntimeException | LinkageError e) {
                Ae2Declogger.LOG.debug("Could not record the jobs of a network", e);
            }
        }
    }

    private static void sendUpdate(Object player, Subscription sub, IGrid known, boolean full) {
        UUID id = Plat.uuid(player);
        if (sub.overlay() && Plat.openContainer(player) != sub.overlayMenu.get()) {
            // The crafting status screen was closed without telling us.
            MONITORS.remove(id);
            return;
        }
        IGrid grid = known;
        if (grid == null) {
            GridSource.Resolved resolved = sub.source.resolve(player);
            if (resolved.error != null) {
                MONITORS.remove(id);
                if (!sub.overlay()) {
                    Plat.send(player, Messages.monitorClosed(resolved.error));
                }
                return;
            }
            grid = resolved.grid;
        }
        if (sub.grid != grid) {
            full = true;
            sub.grid = grid;
            sub.allowed.clear();
        }
        if (full) {
            sub.delta.reset();
            sub.lastSummary = null;
        }
        GridTracker tracker = tracker(grid);
        GridAnalysis analysis = analysis(grid, tracker);

        Map<Long, JobView> current = new LinkedHashMap<>();
        for (JobView job : analysis.jobs) {
            current.put(job.id, job);
        }
        DeltaTracker.Delta<Long, JobView> delta = sub.delta.update(current);

        boolean historyReset = full || sub.historyEpochSent != tracker.historyEpoch();
        List<HistoryEntry> history = tracker.historySince(historyReset ? -1 : sub.historyVersionSent);
        sub.historyVersionSent = tracker.historyVersion();
        sub.historyEpochSent = tracker.historyEpoch();

        if (sub.allowed.size() > MAX_ALLOWED_POSITIONS) {
            sub.allowed.clear();
        }
        sub.allowed.addAll(analysis.machines);

        GridSummary summary = new GridSummary(sub.source.title, analysis.cpus, analysis.busyCpus,
                analysis.storedPower, analysis.maxPower, analysis.powered, System.currentTimeMillis());
        boolean alerts = alertsOnFor(player, grid);
        long focus = focusedJob(sub, grid);

        boolean summaryChanged = sub.lastSummary == null || !sub.lastSummary.sameIgnoringTime(summary);
        if (!full && delta.isEmpty() && history.isEmpty() && !historyReset && !summaryChanged
                && alerts == sub.lastAlerts && focus == sub.lastFocus) {
            return;
        }
        sub.lastSummary = summary;
        sub.lastAlerts = alerts;
        sub.lastFocus = focus;

        long[] removed = new long[delta.removed().size()];
        for (int i = 0; i < removed.length; i++) {
            removed[i] = delta.removed().get(i);
        }
        long[] order = new long[current.size()];
        int i = 0;
        for (Long k : current.keySet()) {
            order[i++] = k;
        }
        Plat.send(player, new Messages.MonitorUpdate(full, summary, new ArrayList<>(delta.changed().values()), removed,
                order, history, historyReset, alerts, focus).encode());
    }

    static GridTracker tracker(IGrid grid) {
        GridTracker tracker = TRACKERS.get(grid);
        if (tracker == null) {
            tracker = new GridTracker(DeclogConfig.historySize());
            try {
                HistoryData data = Plat.historyData();
                if (data != null) {
                    tracker.attach(data.storeFor(grid));
                }
            } catch (RuntimeException e) {
                Ae2Declogger.LOG.warn("Could not load the saved crafting history of a network", e);
            }
            TRACKERS.put(grid, tracker);
        }
        tracker.setHistorySize(DeclogConfig.historySize());
        return tracker;
    }

    private static GridAnalysis analysis(IGrid grid, GridTracker tracker) {
        if (tracker.cached != null && tracker.cachedTick == ticks) {
            return tracker.cached;
        }
        GridAnalysis analysis = GridAnalyzer.analyze(grid, tracker, System.currentTimeMillis(),
                DeclogConfig.thresholds(), new GridAnalyzer.Limits(DeclogConfig.maxJobs(),
                        DeclogConfig.maxStepsPerJob(), DeclogConfig.maxMachinesPerStep()));
        tracker.cached = analysis;
        tracker.cachedTick = ticks;
        return analysis;
    }

    // ---------------------------------------------------------------- alerts

    private static void runAlerts() {
        for (Object player : Plat.players()) {
            try {
                alertsFor(player);
            } catch (RuntimeException | LinkageError ex) {
                // One broken network must not stop alerts for everyone else.
                Ae2Declogger.LOG.debug("Alert check failed for {}", Plat.playerName(player), ex);
            }
        }
    }

    private static void alertsFor(Object player) {
        UUID id = Plat.uuid(player);
        LPos target = NetworkMemory.alerts(player);
        if (target == null) {
            ALERTS.remove(id);
            return;
        }
        GridSource.Resolved resolved = GridSource.ofLink(target, -1, LText.EMPTY).resolve(player);
        if (resolved.grid == null) {
            return;
        }
        IGrid grid = resolved.grid;
        GridAnalysis analysis = analysis(grid, tracker(grid));
        AlertState state = ALERTS.get(id);
        if (state == null) {
            state = new AlertState();
            ALERTS.put(id, state);
        }
        if (state.grid.get() != grid) {
            state.tracker.reset();
            state.grid = new WeakReference<>(grid);
        }
        Map<Long, JobState> states = new HashMap<>();
        Map<Long, JobView> byId = new HashMap<>();
        for (JobView job : analysis.jobs) {
            states.put(job.id, job.state);
            byId.put(job.id, job);
        }
        for (Long jobId : state.tracker.update(states)) {
            JobView job = byId.get(jobId);
            if (job != null) {
                Plat.send(player, new Messages.Alert(job.output, job.amount, job.state, job.reason, job.detail,
                        job.primaryMachine).encode());
            }
        }
    }

    private static boolean alertsOnFor(Object player, IGrid grid) {
        LPos target = NetworkMemory.alerts(player);
        if (target == null) {
            return false;
        }
        return GridSource.ofLink(target, -1, LText.EMPTY).resolve(player).grid == grid;
    }

    /** The bell button in the monitor: alerts on or off for the network the monitor shows. */
    public static void toggleAlerts(Object player) {
        Subscription sub = MONITORS.get(Plat.uuid(player));
        if (sub == null || sub.grid == null) {
            return;
        }
        boolean enable = !alertsOnFor(player, sub.grid);
        if (enable) {
            LPos pos = NetworkMemory.positionOf(sub.grid, sub.source.node);
            if (pos == null) {
                pos = sub.source.link;
            }
            if (pos == null) {
                Plat.message(player, LText.tr("ae2declogger.error.alerts_no_position"), true);
                return;
            }
            NetworkMemory.setAlerts(player, pos);
        } else {
            NetworkMemory.setAlerts(player, null);
            ALERTS.remove(Plat.uuid(player));
        }
        Plat.message(player, LText.tr(enable ? "ae2declogger.alerts_on" : "ae2declogger.alerts_off"), false);
        sendUpdate(player, sub, null, false);
    }

    public static void clearHistory(Object player) {
        Subscription sub = MONITORS.get(Plat.uuid(player));
        if (sub == null || sub.grid == null) {
            return;
        }
        tracker(sub.grid).clearHistory();
        sendUpdate(player, sub, null, false);
    }

    // ---------------------------------------------------------------- machine viewer

    /**
     * Sends a read-only snapshot of the block at {@code pos}. Allowed for blocks listed in the player's open monitor and
     * for operators.
     */
    public static void requestMachine(Object player, LPos pos, boolean open) {
        if (!Plat.dimensionExists(pos.dim)) {
            return;
        }
        UUID id = Plat.uuid(player);
        Long last = LAST_VIEW_REQUEST.get(id);
        if (!open && last != null && ticks - last < VIEWER_COOLDOWN_TICKS) {
            return; // refresh spam from a client
        }
        LAST_VIEW_REQUEST.put(id, ticks);
        Subscription sub = MONITORS.get(id);
        boolean listed = sub != null && sub.allowed.contains(pos);
        if (!listed && !Plat.isOp(player)) {
            if (open) {
                Plat.message(player, LText.tr("ae2declogger.error.viewer_not_allowed"), true);
            }
            return;
        }
        Set<LKey> inputs = sub != null && sub.grid != null ? GridAnalyzer.patternInputsFor(sub.grid, pos)
                : new HashSet<LKey>();
        MachineSnapshot snapshot = MachineInspector.snapshot(pos, inputs, DeclogConfig.machineViewerMaxSlots());
        Plat.send(player, Messages.machineData(snapshot, open));
    }

    /** Visible for tests. */
    public static int watcherCount() {
        return MONITORS.size();
    }

    /** Visible for tests. */
    public static GridTracker trackerForTests(IGrid grid) {
        return tracker(grid);
    }
}
