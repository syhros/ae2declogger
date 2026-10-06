package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.logic.BoundedHistory;
import com.syhros.ae2declogger.logic.ChangeTimer;
import com.syhros.ae2declogger.logic.JobStats;
import com.syhros.ae2declogger.logic.StepClock;

import net.minecraft.network.chat.Component;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * Server-side memory for one ME network: timers per running job and the list of finished jobs and steps. Lives as long
 * as the network object does (it is held weakly by {@link DecloggerServer}); the history is also kept in a
 * {@link HistorySavedData.Store} when one is attached, so it survives the network being rebuilt and the world closing.
 */
public final class GridTracker {

    private static final AtomicLong JOB_IDS = new AtomicLong(1);

    /** Per-CPU state for the job it is running. */
    static final class CpuTrack {
        final long jobId;
        @Nullable
        final UUID craftId;
        final GenericStack output;
        final Component cpuName;
        final long startedAtMs;
        @Nullable
        final ICraftingLink link;
        /** The CPU exists only for this job (Advanced AE quantum computer), so it vanishing means the job ended. */
        final boolean perJobCpu;
        @Nullable
        Fingerprint lastFingerprint;
        long lastActivityAtMs;
        float lastProgress = -1;
        final ChangeTimer<AEKey> activeTimer = new ChangeTimer<>();
        final ChangeTimer<AEKey> pendingTimer = new ChangeTimer<>();
        final Map<AEKey, Long> blockedSince = new HashMap<>();
        /** Times each step (item made on the way) of the job for the History and Stats tabs. */
        final StepClock<AEKey, AEKey> steps = new StepClock<>();

        CpuTrack(long jobId, @Nullable UUID craftId, GenericStack output, Component cpuName, long startedAtMs,
                @Nullable ICraftingLink link, boolean perJobCpu) {
            this.jobId = jobId;
            this.craftId = craftId;
            this.output = output;
            this.cpuName = cpuName;
            this.startedAtMs = startedAtMs;
            this.link = link;
            this.perJobCpu = perJobCpu;
            this.lastActivityAtMs = startedAtMs;
        }

        boolean sameJob(@Nullable UUID otherId, GenericStack otherOutput) {
            if (craftId != null || otherId != null) {
                return Objects.equals(craftId, otherId);
            }
            return output.equals(otherOutput);
        }

        /**
         * Updates the "last activity" time. Activity means real progress: the job's progress value, the amount of
         * items out at machines or the amount still to be started changed. AE2's own change counter is not used for
         * this because a CPU that keeps retrying a push it cannot make touches its inventory every tick. The counter is
         * only used the first time a job is seen, to tell how long it has already been sitting still.
         */
        void observeActivity(long modTick, long currentTick, Fingerprint fingerprint, long nowMs) {
            if (lastFingerprint == null) {
                long ageMs = 0;
                if (modTick > 0 && currentTick >= modTick) {
                    ageMs = (currentTick - modTick) * 50L;
                }
                lastActivityAtMs = Math.max(startedAtMs, nowMs - ageMs);
                lastFingerprint = fingerprint;
                lastProgress = fingerprint.progress();
                return;
            }
            if (!fingerprint.equals(lastFingerprint)) {
                lastActivityAtMs = nowMs;
                lastFingerprint = fingerprint;
                lastProgress = fingerprint.progress();
            }
        }

        /** When the current blocker for {@code key} was first seen. */
        long blockedSince(AEKey key, long nowMs, long initialAgeMs) {
            return blockedSince.computeIfAbsent(key, k -> nowMs - Math.max(0, initialAgeMs));
        }

        void clearBlocked(AEKey key) {
            blockedSince.remove(key);
        }

        void retainKeys(Set<AEKey> keys) {
            activeTimer.retainOnly(keys);
            pendingTimer.retainOnly(keys);
            blockedSince.keySet().retainAll(keys);
        }
    }

    /** What counts as progress for a job. */
    record Fingerprint(float progress, long active, long pending, int steps) {
    }

    private record Versioned(long version, HistoryEntry entry) {
    }

    private final Map<ICraftingCPU, CpuTrack> tracks = new IdentityHashMap<>();
    /** Keyed by {@link CpuAccess#numberKey}, so per-job add-on CPUs share their multiblock's number. */
    private final Map<Object, Integer> cpuNumbers = new IdentityHashMap<>();
    private final BoundedHistory<Versioned> history;
    @Nullable
    private HistorySavedData.Store store;
    private long historyVersion;
    private int historyEpoch;
    private int nextCpuNumber = 1;
    long lastUsedMs;

    // Per-tick cache so several players watching the same network share one analysis.
    long cachedTick = -1;
    @Nullable
    GridAnalysis cached;

    public GridTracker(int historySize) {
        this.history = new BoundedHistory<>(historySize);
    }

    /** Keeps the history in the world's saved data from now on, starting with what was saved before. */
    public synchronized void attach(HistorySavedData.Store saved) {
        store = saved;
        saved.trim(history.capacity());
        history.clear();
        for (HistoryEntry e : saved.entries()) {
            historyVersion++;
            history.add(new Versioned(historyVersion, e));
        }
        historyEpoch++;
    }

    public int cpuNumber(ICraftingCPU cpu) {
        return cpuNumbers.computeIfAbsent(CpuAccess.numberKey(cpu), c -> nextCpuNumber++);
    }

    @Nullable
    CpuTrack track(ICraftingCPU cpu) {
        return tracks.get(cpu);
    }

    /** Returns the track for the CPU's current job, finishing the previous job if the CPU moved on. */
    CpuTrack trackFor(ICraftingCPU cpu, @Nullable UUID craftId, GenericStack output, Component cpuName,
            long startedAtMs, @Nullable ICraftingLink link, long nowMs) {
        CpuTrack existing = tracks.get(cpu);
        if (existing != null && existing.sameJob(craftId, output)) {
            return existing;
        }
        if (existing != null) {
            finish(existing, nowMs);
        }
        CpuTrack created = new CpuTrack(JOB_IDS.getAndIncrement(), craftId, output, cpuName, startedAtMs, link,
                CpuAccess.isPerJob(cpu));
        tracks.put(cpu, created);
        return created;
    }

    /** CPU is idle now: record its last job in the history. */
    void cpuIdle(ICraftingCPU cpu, long nowMs) {
        CpuTrack track = tracks.remove(cpu);
        if (track != null) {
            finish(track, nowMs);
        }
    }

    /**
     * Drops CPUs that no longer exist: a broken crafting storage (its job counts as cancelled), or an add-on CPU that
     * only lived for one job (its job's crafting link says how it ended).
     */
    public void retainCpus(Set<ICraftingCPU> present, long nowMs) {
        Iterator<Map.Entry<ICraftingCPU, CpuTrack>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ICraftingCPU, CpuTrack> e = it.next();
            if (!present.contains(e.getKey())) {
                finish(e.getValue(), nowMs, true);
                it.remove();
            }
        }
        Set<Object> keys = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (ICraftingCPU cpu : present) {
            keys.add(CpuAccess.numberKey(cpu));
        }
        cpuNumbers.keySet().retainAll(keys);
    }

    private void finish(CpuTrack track, long nowMs) {
        finish(track, nowMs, false);
    }

    /**
     * Records a job that left its CPU. A cancelled job has its crafting link cancelled; a finished one has it marked
     * done (standalone jobs started from a terminal may only ever be cancelled, so "not cancelled" also counts as
     * done).
     */
    private void finish(CpuTrack track, long nowMs, boolean cpuGone) {
        finish(track, nowMs, cpuGone, completed(track, cpuGone));
    }

    static boolean completed(CpuTrack track, boolean cpuGone) {
        if (track.link != null) {
            if (track.link.isCanceled()) {
                return false;
            }
            if (track.link.isDone()) {
                return true;
            }
            return !cpuGone || track.perJobCpu;
        }
        if (cpuGone && !track.perJobCpu) {
            return false;
        }
        return track.lastProgress >= 0.999f || track.perJobCpu;
    }

    private void finish(CpuTrack track, long nowMs, boolean cpuGone, boolean completed) {
        // A completed job finished every step still open; a cancelled one keeps only the steps that did finish.
        recordSteps(track, track.steps.finish(completed, nowMs));
        addHistory(new HistoryEntry(track.output.what(), track.output.amount(),
                Math.max(0, nowMs - track.startedAtMs), completed, nowMs, track.cpuName));
    }

    /**
     * Records finished steps of a running job. The step that makes the ordered item itself is left out: the job's own
     * entry covers it.
     */
    void recordSteps(CpuTrack track, List<StepClock.Done<AEKey>> done) {
        for (StepClock.Done<AEKey> d : done) {
            if (d.key().equals(track.output.what()) || d.amount() <= 0) {
                continue;
            }
            addHistory(new HistoryEntry(d.key(), d.amount(), d.durationMs(), true, d.finishedAtMs(), track.cpuName,
                    track.output.what()));
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
            if (all.get(i).version() > version) {
                result.add(all.get(i).entry());
            }
        }
        return result;
    }

    synchronized List<JobStats.Entry<AEKey>> statsEntries() {
        List<JobStats.Entry<AEKey>> result = new ArrayList<>();
        for (Versioned v : history.snapshot()) {
            HistoryEntry e = v.entry();
            result.add(new JobStats.Entry<>(e.output(), e.amount(), e.durationMs(), e.completed(), e.isStep()));
        }
        return result;
    }

    int trackedJobs() {
        return tracks.size();
    }
}
