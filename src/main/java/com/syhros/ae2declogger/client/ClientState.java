package com.syhros.ae2declogger.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.data.GridSummary;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.network.DeclogNetwork;

import appeng.api.stacks.AEKey;

import net.minecraft.network.chat.Component;

/** What the client currently knows about the monitored network. Only touched on the render thread. */
public final class ClientState {

    private static final int MAX_HISTORY = 2000;

    public static Component title = Text.empty();
    @Nullable
    public static GridSummary summary;
    public static final LinkedHashMap<Long, JobView> JOBS = new LinkedHashMap<>();
    /** Newest first. */
    public static final List<HistoryEntry> HISTORY = new ArrayList<>();
    public static boolean alertsOn;
    /** Job of the CPU shown in AE2's crafting status screen (from the server), or -1. */
    public static long focusJob = -1;
    /** Set by Inspect: the monitor selects this job and step once it opens. */
    public static long pendingFocusJob = -1;
    @Nullable
    public static AEKey pendingFocusStep;
    public static long serverOffsetMs;
    /** Bumped on every change so screens can rebuild cached lists. */
    public static long version;
    @Nullable
    public static Component closedReason;
    @Nullable
    public static MachineSnapshot machine;

    private ClientState() {
    }

    public static void reset(Component newTitle) {
        title = newTitle;
        summary = null;
        JOBS.clear();
        HISTORY.clear();
        closedReason = null;
        focusJob = -1;
        version++;
    }

    public static void apply(DeclogNetwork.MonitorUpdate update) {
        Map<Long, JobView> merged = update.full() ? new HashMap<>() : new HashMap<>(JOBS);
        for (long id : update.removed()) {
            merged.remove(id);
        }
        for (JobView job : update.changed()) {
            merged.put(job.id(), job);
        }
        JOBS.clear();
        for (long id : update.order()) {
            JobView job = merged.get(id);
            if (job != null) {
                JOBS.put(id, job);
            }
        }
        if (update.historyReset()) {
            HISTORY.clear();
        }
        for (HistoryEntry entry : update.history()) {
            HISTORY.add(0, entry);
        }
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.remove(HISTORY.size() - 1);
        }
        summary = update.summary();
        serverOffsetMs = summary.serverTimeMs() - System.currentTimeMillis();
        alertsOn = update.alertsOn();
        focusJob = update.focusJob();
        closedReason = null;
        version++;
    }

    public static void closed(Component reason) {
        closedReason = reason;
        version++;
    }

    /** Current server wall-clock time as seen from this client. */
    public static long serverNow() {
        return System.currentTimeMillis() + serverOffsetMs;
    }
}
