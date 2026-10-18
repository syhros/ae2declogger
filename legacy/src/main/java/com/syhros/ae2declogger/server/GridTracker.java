package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.logic.BoundedHistory;
import com.syhros.ae2declogger.logic.ChangeTimer;
import com.syhros.ae2declogger.logic.JobStats;
import com.syhros.ae2declogger.logic.StepClock;

import appeng.api.networking.crafting.ICraftingLink;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Server-side memory for one ME network: timers per running job and the list of finished jobs and steps. Lives as long
 * as the network object does (held weakly by {@link DecloggerServer}); the history is also kept in a
 * {@link HistoryData.Store}, so it survives the network being rebuilt and the world closing.
 */
public final class GridTracker {

    private static final AtomicLong JOB_IDS = new AtomicLong(1);

    /** Per-CPU state for the job it is running. */
    static final class CpuTrack {
        final long jobId;
        final String craftId;
        final LKey output;
        final long amount;
        final LText cpuName;
        final long startedAtMs;
        final ICraftingLink link;
        Fingerprint lastFingerprint;
        long lastActivityAtMs;
        float lastProgress = -1;
        final ChangeTimer<LKey> activeTimer = new ChangeTimer<>();
        final ChangeTimer<LKey> pendingTimer = new ChangeTimer<>();
        final Map<LKey, Long> blockedSince = new HashMap<>();
        /** Times each step (item made on the way) of the job for the History and Stats tabs. */
        final StepClock<LKey, LKey> steps = new StepClock<>();

        CpuTrack(long jobId, String craftId, LKey output, long amount, LText cpuName, long startedAtMs,
                ICraftingLink link) {
            this.jobId = jobId;
            this.craftId = craftId;
            this.output = output;
            this.amount = amount;
            this.cpuName = cpuName;
            this.startedAtMs = startedAtMs;
            this.link = link;
            this.lastActivityAtMs = startedAtMs;
        }

        boolean sameJob(String otherId, LKey otherOutput, long otherAmount) {
            if (craftId != null || otherId != null) {
                return Objects.equals(craftId, otherId);
            }
            return output.equals(otherOutput) && amount == otherAmount;
        }

        /**
         * Updates the "last activity" time. Activity means real progress: the job's progress, the items out at
         * machines or the items still to be started changed. The first time a job is seen, {@code initialAgeMs} (how
         * long ago an output last came back, or -1) says how long it has already been sitting still.
         */
        void observeActivity(long initialAgeMs, Fingerprint fingerprint, long nowMs) {
            if (lastFingerprint == null) {
                lastActivityAtMs = Math.max(startedAtMs, nowMs - Math.max(0, initialAgeMs));
                lastFingerprint = fingerprint;
                lastProgress = fingerprint.progress;
                return;
            }
            if (!fingerprint.equals(lastFingerprint)) {
                lastActivityAtMs = nowMs;
                lastFingerprint = fingerprint;
                lastProgress = fingerprint.progress;
            }
        }

        /** When the current blocker for {@code key} was first seen. */
        long blockedSince(LKey key, long nowMs, long initialAgeMs) {
            Long since = blockedSince.get(key);
            if (since == null) {
                since = nowMs - Math.max(0, initialAgeMs);
                blockedSince.put(key, since);
            }
            return since;
        }

        void clearBlocked(LKey key) {
            blockedSince.remove(key);
        }

        void retainKeys(Set<LKey> keys) {
            activeTimer.retainOnly(keys);
            pendingTimer.retainOnly(keys);
            blockedSince.keySet().retainAll(keys);
        }
    }

    /** What counts as progress for a job. */
    static final class Fingerprint {
        final float progress;
        final long active;
        final long pending;
        final int steps;

        Fingerprint(float progress, long active, long pending, int steps) {
            this.progress = progress;
            this.active = active;
            this.pending = pending;
            this.steps = steps;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Fingerprint)) {
                return false;
            }
            Fingerprint f = (Fingerprint) o;
            return Float.compare(progress, f.progress) == 0 && active == f.active && pending == f.pending
                    && steps == f.steps;
        }

        @Override
        public int hashCode() {
            return Objects.hash(progress, active, pending, steps);
        }
    }

    private static final class Versioned {
        final long version;
        final HistoryEntry entry;

        Versioned(long version, HistoryEntry entry) {
            this.version = version;
            this.entry = entry;
        }
    }

    private final Map<CraftingCPUCluster, CpuTrack> tracks = new IdentityHashMap<>();
    private final Map<CraftingCPUCluster, Integer> cpuNumbers = new IdentityHashMap<>();
    private final BoundedHistory<Versioned> history;
    private HistoryData.Store store;
    private long historyVersion;
    private int historyEpoch;
    private int nextCpuNumber = 1;

    // Per-tick cache so several players watching the same network share one analysis.
    long cachedTick = -1;
    GridAnalysis cached;

    public GridTracker(int historySize) {
        this.history = new BoundedHistory<>(historySize);
    }

    /** Keeps the history in the world's saved data from now on, starting with what was saved before. */
    public synchronized void attach(HistoryData.Store saved) {
        store = saved;
        saved.trim(history.capacity());
        history.clear();
        for (HistoryEntry e : saved.entries()) {
            historyVersion++;
            history.add(new Versioned(historyVersion, e));
        }
        historyEpoch++;
    }

    public int cpuNumber(CraftingCPUCluster cpu) {
        Integer n = cpuNumbers.get(cpu);
        if (n == null) {
            n = nextCpuNumber++;
            cpuNumbers.put(cpu, n);
        }
        return n;
    }

    CpuTrack track(CraftingCPUCluster cpu) {
        return tracks.get(cpu);
    }

    /** Returns the track for the CPU's current job, finishing the previous job if the CPU moved on. */
    CpuTrack trackFor(CraftingCPUCluster cpu, String craftId, LKey output, long amount, LText cpuName,
            long startedAtMs, ICraftingLink link, long nowMs) {
        CpuTrack existing = tracks.get(cpu);
        if (existing != null && existing.sameJob(craftId, output, amount)) {
            return existing;
        }
        if (existing != null) {
            finish(existing, nowMs, false);
        }
        CpuTrack created = new CpuTrack(JOB_IDS.getAndIncrement(), craftId, output, amount, cpuName, startedAtMs,
                link);
        tracks.put(cpu, created);
        return created;
    }

    /** CPU is idle now: record its last job in the history. */
    void cpuIdle(CraftingCPUCluster cpu, long nowMs) {
        CpuTrack track = tracks.remove(cpu);
        if (track != null) {
            finish(track, nowMs, false);
        }
    }

    /** Drops CPUs that no longer exist (a broken crafting storage: its job counts as cancelled). */
    public void retainCpus(Set<CraftingCPUCluster> present, long nowMs) {
        Iterator<Map.Entry<CraftingCPUCluster, CpuTrack>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<CraftingCPUCluster, CpuTrack> e = it.next();
            if (!present.contains(e.getKey())) {
                finish(e.getValue(), nowMs, true);
                it.remove();
            }
        }
        cpuNumbers.keySet().retainAll(present);
    }

    /**
     * Whether a job that left its CPU finished. A cancelled job has its crafting link cancelled; a finished one has it
     * marked done (jobs started from a terminal may only ever be cancelled, so "not cancelled" also counts as done).
     */
    static boolean completed(CpuTrack track, boolean cpuGone) {
        if (track.link != null) {
            if (track.link.isCanceled()) {
                return false;
            }
            if (track.link.isDone()) {
                return true;
            }
            return !cpuGone;
        }
        if (cpuGone) {
            return false;
        }
        return track.lastProgress >= 0.999f;
    }

    private void finish(CpuTrack track, long nowMs, boolean cpuGone) {
        boolean completed = completed(track, cpuGone);
        // A completed job finished every step still open; a cancelled one keeps only the steps that did finish.
        recordSteps(track, track.steps.finish(completed, nowMs));
        addHistory(new HistoryEntry(track.output, track.amount, Math.max(0, nowMs - track.startedAtMs), completed,
                nowMs, track.cpuName, null));
    }

    /** Records finished steps of a running job, leaving out the step that makes the ordered item itself. */
    void recordSteps(CpuTrack track, List<StepClock.Done<LKey>> done) {
        for (StepClock.Done<LKey> d : done) {
            if (d.key().equals(track.output) || d.amount() <= 0) {
                continue;
            }
            addHistory(new HistoryEntry(d.key(), d.amount(), d.durationMs(), true, d.finishedAtMs(), track.cpuName,
                    track.output));
        }
    }

    public synchronized void addHistory(HistoryEntry entry) {
        historyVersion++;
        history.add(new Versioned(historyVersion, entry));
        if (store != null) {
            store.add(entry, history.capacity());
        }
    }

    synchronized void clearHistory() {
        history.clear();
        historyEpoch++;
        if (store != null) {
            store.clear();
        }
    }

    synchronized void setHistorySize(int size) {
        if (history.capacity() != size) {
            history.setCapacity(size);
            if (store != null) {
                store.trim(size);
            }
        }
    }

    synchronized long historyVersion() {
        return historyVersion;
    }

    synchronized int historyEpoch() {
        return historyEpoch;
    }

    /** Entries newer than {@code version}, oldest first. */
    public synchronized List<HistoryEntry> historySince(long version) {
        List<HistoryEntry> result = new ArrayList<>();
        List<Versioned> all = history.snapshot();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).version > version) {
                result.add(all.get(i).entry);
            }
        }
        return result;
    }

    synchronized List<JobStats.Entry<LKey>> statsEntries() {
        List<JobStats.Entry<LKey>> result = new ArrayList<>();
        for (Versioned v : history.snapshot()) {
            HistoryEntry e = v.entry;
            result.add(new JobStats.Entry<>(e.output, e.amount, e.durationMs, e.completed, e.isStep()));
        }
        return result;
    }

    int trackedJobs() {
        return tracks.size();
    }

    /** For tests: every track's job id, in no particular order. */
    List<Long> jobIds() {
        List<Long> ids = new ArrayList<>();
        for (CpuTrack t : tracks.values()) {
            ids.add(t.jobId);
        }
        return Collections.unmodifiableList(ids);
    }
}
