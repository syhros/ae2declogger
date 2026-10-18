package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.compat.Machines;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.logic.JobStats;
import com.syhros.ae2declogger.logic.StallClassifier;
import com.syhros.ae2declogger.logic.StepClock;
import com.syhros.ae2declogger.logic.Thresholds;

import net.minecraft.tileentity.TileEntity;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.IInterfaceHost;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Looks at every crafting CPU on a network and works out, per job and per step, whether it is moving and if not,
 * why. Read-only: it only calls getters. The legacy port of the modern GridAnalyzer: the same rules, with AE2
 * interfaces as the pattern holders.
 */
public final class GridAnalyzer {

    private static final int MAX_PATTERNS_PER_STEP = 3;

    /** Limits for one analysis. */
    public static final class Limits {
        final int maxJobs;
        final int maxSteps;
        final int maxMachines;

        public Limits(int maxJobs, int maxSteps, int maxMachines) {
            this.maxJobs = maxJobs;
            this.maxSteps = maxSteps;
            this.maxMachines = maxMachines;
        }

        public static final Limits DEFAULT = new Limits(32, 24, 4);
    }

    private static final class Diagnosis {
        static final Diagnosis CLEAR = new Diagnosis(BlockReason.NONE, null, Collections.<MachineRef>emptyList());
        final BlockReason reason;
        final LText detail;
        final List<MachineRef> machines;

        Diagnosis(BlockReason reason, LText detail, List<MachineRef> machines) {
            this.reason = reason;
            this.detail = detail;
            this.machines = machines;
        }
    }

    private final IGrid grid;
    private final GridTracker tracker;
    private final long now;
    private final Thresholds thresholds;
    private final Limits limits;
    private final IEnergyGrid energy;
    private final Map<ICraftingMedium, Ae.Holder> holders = new IdentityHashMap<>();
    private final Map<LPos, MachineRef> machineCache = new HashMap<>();
    private final Set<LPos> referenced = new HashSet<>();
    private Map<LKey, List<ICraftingPatternDetails>> patternsByOutput;
    private List<JobStats.Entry<LKey>> statsEntries;
    private Boolean powered;

    private GridAnalyzer(IGrid grid, GridTracker tracker, long now, Thresholds thresholds, Limits limits) {
        this.grid = grid;
        this.tracker = tracker;
        this.now = now;
        this.thresholds = thresholds;
        this.limits = limits;
        this.energy = Ae.energy(grid);
    }

    public static GridAnalysis analyze(IGrid grid, GridTracker tracker, long nowMs, Thresholds thresholds,
            Limits limits) {
        return new GridAnalyzer(grid, tracker, nowMs, thresholds, limits).run();
    }

    /**
     * The light pass for networks nobody is watching: follows each job and times its steps for the History and Stats
     * tabs, without diagnosing anything. Jobs that finish are recorded exactly as by a full analysis.
     */
    public static void record(IGrid grid, GridTracker tracker, long nowMs) {
        Set<CraftingCPUCluster> present = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CraftingCPUCluster cpu : Ae.allCpus(grid)) {
            present.add(cpu);
            int number = tracker.cpuNumber(cpu);
            CpuAccess access = CpuAccess.of(cpu);
            IAEItemStack output = access.isBusy() ? access.finalOutput() : null;
            LKey outKey = Ae.key(output);
            if (outKey == null) {
                tracker.cpuIdle(cpu, nowMs);
                continue;
            }
            try {
                GridTracker.CpuTrack track = trackOf(tracker, access, outKey, output.getStackSize(), number, nowMs);
                track.lastProgress = access.progress();
                observeSteps(tracker, track, access, nowMs);
            } catch (RuntimeException | LinkageError e) {
                Ae2Declogger.LOG.debug("Could not follow crafting CPU {}", number, e);
            }
        }
        tracker.retainCpus(present, nowMs);
    }

    private static GridTracker.CpuTrack trackOf(GridTracker tracker, CpuAccess access, LKey output, long amount,
            int number, long nowMs) {
        ICraftingLink link = access.link();
        LText name = access.name();
        LText cpuName = name != null ? name : LText.tr("ae2declogger.cpu.numbered", String.valueOf(number));
        long sinceReturn = Math.max(0, access.msSinceLastReturn());
        long started = nowMs - access.elapsedMs() - sinceReturn;
        String craftId = null;
        try {
            craftId = link != null ? link.getCraftingID() : null;
        } catch (RuntimeException e) {
            craftId = null;
        }
        return tracker.trackFor(access.cpu, craftId, output, amount, cpuName, started, link, nowMs);
    }

    /**
     * One look at a job: records the steps that finished since the last look. A step counts items still to be made
     * (crafts left x items per craft) plus items out at machines.
     */
    private static void observeSteps(GridTracker tracker, GridTracker.CpuTrack track, CpuAccess access, long nowMs) {
        Map<LKey, Long> remaining = new LinkedHashMap<>();
        for (Map.Entry<ICraftingPatternDetails, Long> e : access.tasks().entrySet()) {
            List<IAEItemStack> outs = Ae.outputs(e.getKey());
            if (outs.isEmpty()) {
                continue;
            }
            LKey key = Ae.key(outs.get(0));
            if (key != null) {
                remaining.merge(key, e.getValue() * Math.max(1, outs.get(0).getStackSize()), Long::sum);
            }
        }
        for (Map.Entry<LKey, Long> e : access.activeMap().entrySet()) {
            if (!remaining.containsKey(e.getKey()) && e.getValue() > 0) {
                remaining.put(e.getKey(), 0L);
            }
        }
        List<StepClock.Done<LKey>> done = track.steps.observe(remaining, k -> k, k -> 1, access::active, nowMs);
        tracker.recordSteps(track, done);
    }

    private GridAnalysis run() {
        int cpus = 0;
        int busy = 0;
        List<JobView> jobs = new ArrayList<>();
        Set<CraftingCPUCluster> present = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CraftingCPUCluster cpu : Ae.allCpus(grid)) {
            cpus++;
            present.add(cpu);
            int number = tracker.cpuNumber(cpu);
            CpuAccess access = CpuAccess.of(cpu);
            IAEItemStack output = access.isBusy() ? access.finalOutput() : null;
            LKey outKey = Ae.key(output);
            if (outKey == null) {
                tracker.cpuIdle(cpu, now);
                continue;
            }
            busy++;
            try {
                jobs.add(analyzeJob(access, number, outKey, output.getStackSize()));
            } catch (RuntimeException | LinkageError e) {
                // One odd CPU (a half-loaded cluster) must not hide the others.
                Ae2Declogger.LOG.debug("Could not analyse crafting CPU {}", number, e);
            }
        }
        tracker.retainCpus(present, now);

        jobs.sort(Comparator.comparingInt((JobView j) -> -j.state.severity())
                .thenComparingLong(j -> j.startedAtMs));
        if (jobs.size() > limits.maxJobs) {
            jobs = new ArrayList<>(jobs.subList(0, limits.maxJobs));
        }
        double stored = 0;
        double max = 0;
        try {
            stored = energy != null ? energy.getStoredPower() : 0;
            max = energy != null ? energy.getMaxStoredPower() : 0;
        } catch (RuntimeException e) {
            // keep zeros
        }
        return new GridAnalysis(jobs, cpus, busy, stored, max, powered(), referenced, now);
    }

    private boolean powered() {
        if (powered == null) {
            try {
                powered = energy == null || energy.isNetworkPowered();
            } catch (RuntimeException e) {
                powered = true;
            }
        }
        return powered;
    }

    private static final class Amounts {
        final LKey key;
        final long stored;
        final long active;
        final long pending;

        Amounts(LKey key, long stored, long active, long pending) {
            this.key = key;
            this.stored = stored;
            this.active = active;
            this.pending = pending;
        }
    }

    private JobView analyzeJob(CpuAccess access, int number, LKey output, long amount) {
        GridTracker.CpuTrack track = trackOf(tracker, access, output, amount, number, now);
        float progress = Math.round(access.progress() * 1000f) / 1000f;

        List<Amounts> amounts = new ArrayList<>();
        Set<LKey> analysed = new LinkedHashSet<>();
        long totalActive = 0;
        long totalPending = 0;
        Set<LKey> keys = new LinkedHashSet<>();
        keys.addAll(access.activeMap().keySet());
        keys.addAll(access.pendingMap().keySet());
        for (LKey key : keys) {
            long active = access.active(key);
            long pending = access.pending(key);
            if (active <= 0 && pending <= 0) {
                continue;
            }
            amounts.add(new Amounts(key, access.stored(key), active, pending));
            analysed.add(key);
            totalActive += active;
            totalPending += pending;
        }
        // Forget timers for keys that dropped to zero, so a value that comes back is seen as a change.
        track.retainKeys(analysed);
        observeSteps(tracker, track, access, now);
        track.observeActivity(access.msSinceLastReturn(),
                new GridTracker.Fingerprint(progress, totalActive, totalPending, amounts.size()), now);
        long jobIdle = Math.max(0, now - track.lastActivityAtMs);

        List<StepView> steps = new ArrayList<>();
        for (Amounts a : amounts) {
            steps.add(analyzeStep(access, track, a.key, a.stored, a.active, a.pending, jobIdle));
        }
        markQueued(steps, access);

        List<JobState> stepStates = new ArrayList<>(steps.size());
        for (StepView s : steps) {
            stepStates.add(s.state);
        }
        JobState state = StallClassifier.classifyJob(jobIdle, stepStates, true, thresholds);
        BlockReason reason = BlockReason.NONE;
        LText detail = null;

        if (!access.isActive()) {
            state = jobIdle >= thresholds.blockedMs() ? JobState.BLOCKED : JobState.worst(state, JobState.SLOW);
            reason = BlockReason.CPU_OFFLINE;
        }

        steps.sort(Comparator.comparingInt((StepView s) -> -s.state.severity())
                .thenComparingInt(s -> s.reason.isProblem() ? 0 : 1)
                .thenComparingLong(s -> -s.active));

        MachineRef primary = null;
        for (StepView s : steps) {
            if (s.state == state && s.reason.isProblem()) {
                if (reason == BlockReason.NONE) {
                    reason = s.reason;
                    detail = s.detail != null ? s.detail : LText.item(s.key);
                }
                primary = firstProblemMachine(s.machines);
                if (primary != null) {
                    break;
                }
            }
        }
        if (primary == null) {
            for (StepView s : steps) {
                if (!s.machines.isEmpty()) {
                    primary = firstProblemMachine(s.machines);
                    if (primary == null) {
                        primary = s.machines.get(0);
                    }
                    break;
                }
            }
        }
        if (reason == BlockReason.NONE && state.isProblem()) {
            reason = BlockReason.UNKNOWN;
        }
        if (steps.size() > limits.maxSteps) {
            steps = new ArrayList<>(steps.subList(0, limits.maxSteps));
        }
        for (StepView s : steps) {
            for (MachineRef m : s.machines) {
                referenced.add(m.pos);
            }
        }
        if (primary != null) {
            referenced.add(primary.pos);
        }
        long expected = JobStats.expectedMs(stats(), output, amount);
        return new JobView(track.jobId, track.cpuName, access.coProcessors(), access.storage(), output, amount,
                progress, track.startedAtMs, track.lastActivityAtMs, expected, state, reason, detail, primary, steps);
    }

    /** Reasons that only mean "the machine will not take more right now". */
    private static boolean machineWontTake(BlockReason r) {
        return r == BlockReason.PUSH_REJECTED || r == BlockReason.PROVIDERS_BUSY || r == BlockReason.PROVIDER_BLOCKING
                || r == BlockReason.MACHINE_INPUT_FULL;
    }

    /**
     * A blocked step whose machine is busy with another step of the same job is not stuck, it is next in line: two
     * dusts from the same macerator for one job are made one after the other.
     */
    private void markQueued(List<StepView> steps, CpuAccess access) {
        boolean anyCandidate = false;
        for (StepView s : steps) {
            anyCandidate |= s.active <= 0 && s.state == JobState.BLOCKED && machineWontTake(s.reason)
                    && !s.machines.isEmpty();
        }
        if (!anyCandidate) {
            return;
        }
        Map<LPos, StepView> busyAt = new LinkedHashMap<>();
        for (StepView s : steps) {
            if (s.active > 0) {
                List<MachineRef> machines = s.machines.isEmpty() ? machinesProducing(s.key, access) : s.machines;
                for (MachineRef m : machines) {
                    busyAt.putIfAbsent(m.pos, s);
                }
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            StepView s = steps.get(i);
            if (s.active > 0 || s.state != JobState.BLOCKED || !machineWontTake(s.reason)) {
                continue;
            }
            for (MachineRef m : s.machines) {
                StepView running = busyAt.get(m.pos);
                if (running != null) {
                    steps.set(i, new StepView(s.key, s.stored, s.active, s.pending, JobState.QUEUED,
                            BlockReason.QUEUED, LText.item(running.key), s.sinceMs, s.recipeMs, s.machines));
                    break;
                }
            }
        }
    }

    private StepView analyzeStep(CpuAccess access, GridTracker.CpuTrack track, LKey key, long stored, long active,
            long pending, long jobIdle) {
        long pendingChangedAt = track.pendingTimer.observe(key, pending, now, jobIdle);
        long activeChangedAt = track.activeTimer.observe(key, active, now, jobIdle);

        if (active > 0) {
            track.clearBlocked(key);
            // Pending going down also means the step is moving (blocking mode keeps "active" at the same value).
            long movedAt = Math.max(activeChangedAt, pendingChangedAt);
            long idle = Math.max(0, now - movedAt);
            JobState state = StallClassifier.classifyActive(idle, -1, thresholds);
            BlockReason reason = BlockReason.NONE;
            LText detail = null;
            List<MachineRef> machines = Collections.emptyList();
            long recipeMs = -1;
            if (state != JobState.IN_PROGRESS) {
                machines = machinesProducing(key, access);
                for (MachineRef m : machines) {
                    recipeMs = Math.max(recipeMs, MachineInspector.recipeMs(m));
                }
                state = StallClassifier.classifyActive(idle, recipeMs, thresholds);
                if (state != JobState.IN_PROGRESS) {
                    MachineRef bad = firstProblemMachine(machines);
                    if (bad != null) {
                        reason = bad.problem;
                        detail = bad.detail;
                        state = StallClassifier.classifyMachineFault(state, reason);
                    } else if (anyWorking(machines)) {
                        // The machine is running a recipe: moving, maybe slowly, but not stuck.
                        state = StallClassifier.classifyWorking(idle, recipeMs, thresholds);
                    } else {
                        reason = BlockReason.OUTPUT_NOT_RETURNING;
                        detail = LText.item(key);
                    }
                }
            }
            return new StepView(key, stored, active, pending, state, reason, detail, movedAt, recipeMs, machines);
        }

        Diagnosis d = diagnosePending(access, key);
        long since = pendingChangedAt;
        long blockedFor = 0;
        if (d.reason.isProblem()) {
            long firstSeen = track.blockedSince(key, now, jobIdle);
            long start = Math.max(firstSeen, pendingChangedAt);
            blockedFor = Math.max(0, now - start);
            since = start;
        } else {
            track.clearBlocked(key);
        }
        JobState state = StallClassifier.classifyPending(d.reason, blockedFor, thresholds);
        BlockReason shown = d.reason;
        LText detail = d.detail;
        if (shown.isProblem() && state != JobState.BLOCKED) {
            // Not confirmed yet: AE2 may push it on the next tick.
            shown = BlockReason.NONE;
            detail = null;
        }
        return new StepView(key, stored, 0, pending, state, shown, detail, since, -1, d.machines);
    }

    private Diagnosis diagnosePending(CpuAccess access, LKey key) {
        List<ICraftingPatternDetails> patterns = patternsFor(key, access, true);
        if (patterns.isEmpty()) {
            return new Diagnosis(BlockReason.NO_PROVIDER, LText.item(key), Collections.<MachineRef>emptyList());
        }
        Diagnosis best = null;
        for (ICraftingPatternDetails pattern : patterns) {
            Diagnosis d = diagnosePattern(access, pattern);
            if (!d.reason.isProblem() && d.reason != BlockReason.WAITING_INGREDIENTS) {
                return d;
            }
            if (best == null || BlockReason.stronger(best.reason, d.reason) == d.reason && d.reason != best.reason) {
                best = d;
            }
        }
        if (best == null) {
            return Diagnosis.CLEAR;
        }
        return refineWithAe2Reason(access, key, best);
    }

    /**
     * GTNH's AE2 records why it did not push a pattern. Where our own check found only a generic reason, AE2's is more
     * precise.
     */
    private Diagnosis refineWithAe2Reason(CpuAccess access, LKey key, Diagnosis d) {
        if (d.reason != BlockReason.PUSH_REJECTED && d.reason != BlockReason.UNKNOWN) {
            return d;
        }
        IAEItemStack stack = access.aeStack(key);
        String ae2 = stack != null ? Ae.scheduledReason(access.cpu, stack) : null;
        if (ae2 == null) {
            return d;
        }
        BlockReason mapped;
        switch (ae2) {
            case "BLOCKING_MODE":
                mapped = BlockReason.PROVIDER_BLOCKING;
                break;
            case "LOCK_MODE":
                mapped = BlockReason.PROVIDER_LOCKED;
                break;
            case "NO_TARGET":
            case "SAME_NETWORK":
                mapped = BlockReason.NO_PROVIDER;
                break;
            case "NOT_ENOUGH_INGREDIENTS":
                mapped = BlockReason.MISSING_INGREDIENT;
                break;
            default:
                mapped = d.reason;
        }
        return mapped == d.reason ? d : new Diagnosis(mapped, d.detail, d.machines);
    }

    private Diagnosis diagnosePattern(CpuAccess access, ICraftingPatternDetails pattern) {
        LKey missing = firstMissingInput(access, pattern);
        if (missing != null) {
            boolean coming = access.active(missing) > 0 || access.pending(missing) > 0;
            return new Diagnosis(coming ? BlockReason.WAITING_INGREDIENTS : BlockReason.MISSING_INGREDIENT,
                    LText.item(missing), Collections.<MachineRef>emptyList());
        }
        // Another output of this pattern is still out at the machine: it is busy with the previous batch.
        for (IAEItemStack out : Ae.outputs(pattern)) {
            LKey k = Ae.key(out);
            if (k != null && access.active(k) > 0) {
                return new Diagnosis(BlockReason.MACHINE_BUSY, LText.item(k), Collections.<MachineRef>emptyList());
            }
        }
        if (!access.isActive()) {
            return new Diagnosis(BlockReason.CPU_OFFLINE, null, Collections.<MachineRef>emptyList());
        }
        List<Ae.Holder> holderList = holdersOf(pattern);
        if (holderList.isEmpty()) {
            List<IAEItemStack> outs = Ae.outputs(pattern);
            LKey first = outs.isEmpty() ? null : Ae.key(outs.get(0));
            return new Diagnosis(BlockReason.NO_PROVIDER, first != null ? LText.item(first) : null,
                    Collections.<MachineRef>emptyList());
        }
        if (!powered()) {
            return new Diagnosis(BlockReason.NETWORK_POWER, null, Collections.<MachineRef>emptyList());
        }

        List<MachineRef> machines = new ArrayList<>();
        BlockReason providerReason = null;
        LText providerDetail = null;
        BlockReason freeMachineReason = BlockReason.NONE;
        LText freeMachineDetail = null;
        boolean anyFree = false;

        for (Ae.Holder holder : holderList) {
            BlockReason r = BlockReason.NONE;
            LText d = null;
            boolean busy;
            try {
                busy = holder.medium.isBusy();
            } catch (RuntimeException e) {
                busy = false;
            }
            if (!holder.online) {
                r = BlockReason.PROVIDER_OFFLINE;
            } else if (!"NONE".equals(holder.lock)) {
                r = BlockReason.PROVIDER_LOCKED;
                d = LText.tr("ae2declogger.lock." + lockKey(holder.lock));
            } else if (busy && holder.blocking) {
                // Old AE2 interfaces count as busy while blocking mode waits for the machine to empty.
                r = BlockReason.PROVIDER_BLOCKING;
            } else if (busy) {
                r = BlockReason.PROVIDERS_BUSY;
            }

            List<MachineRef> targets = targetsOf(holder, r);
            MachineRef bad = firstProblemMachine(targets);
            for (MachineRef m : targets) {
                if (machines.size() < limits.maxMachines && !machines.contains(m)) {
                    machines.add(m);
                }
            }
            if (r == BlockReason.NONE) {
                anyFree = true;
                if (bad != null) {
                    freeMachineReason = BlockReason.stronger(freeMachineReason, bad.problem);
                    if (freeMachineDetail == null) {
                        freeMachineDetail = bad.detail;
                    }
                }
                continue;
            }
            // A machine-level problem explains a holder-level one better (busy because the machine has no power).
            if (bad != null && bad.problem.priority() > r.priority()) {
                r = bad.problem;
                d = bad.detail;
            } else if (d == null && bad != null) {
                d = bad.detail;
            }
            if (providerReason == null || BlockReason.stronger(providerReason, r) == r && r != providerReason) {
                providerReason = r;
                providerDetail = d;
            }
        }
        if (anyFree) {
            if (freeMachineReason.isProblem()) {
                return new Diagnosis(freeMachineReason, freeMachineDetail, machines);
            }
            return new Diagnosis(BlockReason.PUSH_REJECTED, null, machines);
        }
        return new Diagnosis(providerReason == null ? BlockReason.UNKNOWN : providerReason, providerDetail, machines);
    }

    private static String lockKey(String lock) {
        switch (lock) {
            case "LOCK_WHILE_LOW":
                return "while_low";
            case "LOCK_WHILE_HIGH":
                return "while_high";
            case "LOCK_UNTIL_PULSE":
                return "until_pulse";
            default:
                return "until_result_unknown";
        }
    }

    /**
     * First pattern input the CPU does not hold enough of, or null if all inputs are there. Like AE2 when it pushes, an
     * item of the same kind with other NBT counts too (a GregTech tool that has been used once is the same ingredient).
     */
    static LKey firstMissingInput(CpuAccess access, ICraftingPatternDetails pattern) {
        boolean substitute = Ae.canSubstitute(pattern);
        for (IAEItemStack input : Ae.inputs(pattern)) {
            LKey key = Ae.key(input);
            if (key == null) {
                continue;
            }
            long need = Math.max(1, input.getStackSize());
            if (access.stored(key) >= need) {
                continue;
            }
            if (substitute || heldFuzzy(access, key) >= need) {
                continue;
            }
            return key;
        }
        return null;
    }

    /** How many items of the same item (and damage, for items without durability) the CPU holds, any NBT. */
    private static long heldFuzzy(CpuAccess access, LKey key) {
        long total = 0;
        for (Map.Entry<LKey, Long> e : access.storedMap().entrySet()) {
            if (e.getKey().sameKind(key)) {
                total += e.getValue();
            }
        }
        return total;
    }

    private List<ICraftingPatternDetails> patternsFor(LKey key, CpuAccess access, boolean preferTasks) {
        Set<ICraftingPatternDetails> result = new LinkedHashSet<>();
        for (ICraftingPatternDetails pattern : access.tasks().keySet()) {
            if (produces(pattern, key)) {
                result.add(pattern);
            }
            if (result.size() >= MAX_PATTERNS_PER_STEP) {
                break;
            }
        }
        if (result.isEmpty() || !preferTasks) {
            for (ICraftingPatternDetails pattern : patternsByOutput().getOrDefault(key,
                    Collections.<ICraftingPatternDetails>emptyList())) {
                result.add(pattern);
                if (result.size() >= MAX_PATTERNS_PER_STEP) {
                    break;
                }
            }
        }
        return new ArrayList<>(result);
    }

    private Map<LKey, List<ICraftingPatternDetails>> patternsByOutput() {
        if (patternsByOutput == null) {
            patternsByOutput = new HashMap<>();
            for (ICraftingPatternDetails p : Ae.allPatterns(grid)) {
                for (IAEItemStack out : Ae.outputs(p)) {
                    LKey k = Ae.key(out);
                    if (k != null) {
                        patternsByOutput.computeIfAbsent(k, x -> new ArrayList<>(2)).add(p);
                    }
                }
            }
        }
        return patternsByOutput;
    }

    private static boolean produces(ICraftingPatternDetails pattern, LKey key) {
        for (IAEItemStack out : Ae.outputs(pattern)) {
            if (key.equals(Ae.key(out))) {
                return true;
            }
        }
        return false;
    }

    /** Holders of {@code pattern}, online ones first, capped. */
    private List<Ae.Holder> holdersOf(ICraftingPatternDetails pattern) {
        List<Ae.Holder> all = new ArrayList<>();
        for (ICraftingMedium medium : Ae.mediums(grid, pattern)) {
            Ae.Holder h = holders.get(medium);
            if (h == null && !holders.containsKey(medium)) {
                h = Ae.holder(medium);
                holders.put(medium, h);
            }
            if (h != null) {
                all.add(h);
            }
        }
        all.sort(Comparator.comparingInt(h -> h.online ? 0 : 1));
        return all.size() > limits.maxMachines ? all.subList(0, limits.maxMachines) : all;
    }

    /** Machines that make {@code key}: the targets of every holder with a pattern for it. */
    private List<MachineRef> machinesProducing(LKey key, CpuAccess access) {
        Map<LPos, MachineRef> found = new LinkedHashMap<>();
        for (ICraftingPatternDetails pattern : patternsFor(key, access, false)) {
            for (Ae.Holder holder : holdersOf(pattern)) {
                for (MachineRef ref : targetsOf(holder, BlockReason.NONE)) {
                    found.putIfAbsent(ref.pos, ref);
                    if (found.size() >= limits.maxMachines) {
                        return sortProblemsFirst(found.values());
                    }
                }
            }
        }
        return sortProblemsFirst(found.values());
    }

    /** Refs for the blocks a holder pushes into, or the holder itself if it has no targets. */
    private List<MachineRef> targetsOf(Ae.Holder holder, BlockReason holderReason) {
        List<MachineRef> refs = new ArrayList<>();
        List<LPos> targets = new ArrayList<>();
        if (holder.iface) {
            for (int side : holder.sides) {
                LPos target = holder.pos.offset(side);
                if (canReceive(target, LPos.opposite(side))) {
                    targets.add(target);
                }
            }
        }
        if (targets.isEmpty()) {
            // GregTech ME input buffers live inside the multiblock; the controller is the "machine".
            targets.addAll(Machines.controllersOf(holder.pos));
        }
        for (LPos target : targets) {
            MachineRef ref = machineAt(target, MachineRef.Kind.MACHINE);
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }
        if (refs.isEmpty()) {
            MachineRef self = machineAt(holder.pos, MachineRef.Kind.PROVIDER);
            if (holderReason.isProblem()) {
                self = self.withProblem(holderReason);
            }
            refs.add(self);
        }
        return refs;
    }

    /**
     * Same idea as AE2's own target search: a neighbour counts if it has an inventory or tank, or is a machine an
     * integration reads. AE2 never pushes into another interface of the same network.
     */
    private boolean canReceive(LPos target, int side) {
        if (!Plat.isLoaded(target)) {
            return true; // cannot tell; keep it so the user sees the unloaded machine
        }
        TileEntity te = Plat.tileAt(target);
        if (te == null) {
            return false;
        }
        if (te instanceof IInterfaceHost) {
            IGridNode other = Ae.nodeOf(te, side);
            if (other != null && grid.equals(Ae.gridOf(other))) {
                return false;
            }
        }
        return Plat.hasInventory(te, side) || Machines.isMachine(target);
    }

    private MachineRef machineAt(LPos pos, MachineRef.Kind kind) {
        MachineRef cached = machineCache.get(pos);
        if (cached == null) {
            cached = MachineInspector.describe(pos, kind);
            machineCache.put(pos, cached);
        }
        return cached;
    }

    private List<JobStats.Entry<LKey>> stats() {
        if (statsEntries == null) {
            statsEntries = tracker.statsEntries();
        }
        return statsEntries;
    }

    private static MachineRef firstProblemMachine(List<MachineRef> machines) {
        MachineRef best = null;
        for (MachineRef m : machines) {
            if (m.hasProblem() && (best == null || m.problem.priority() > best.problem.priority())) {
                best = m;
            }
        }
        return best;
    }

    private static boolean anyWorking(List<MachineRef> machines) {
        for (MachineRef m : machines) {
            if (m.status == MachineStatus.WORKING) {
                return true;
            }
        }
        return false;
    }

    private static List<MachineRef> sortProblemsFirst(Iterable<MachineRef> refs) {
        List<MachineRef> list = new ArrayList<>();
        for (MachineRef r : refs) {
            list.add(r);
        }
        list.sort(Comparator.comparingInt((MachineRef m) -> m.hasProblem() ? -m.problem.priority() : 0));
        return list;
    }

    /** Inputs of every pattern whose holder pushes into (or is) {@code target}; highlighted in the viewer. */
    static Set<LKey> patternInputsFor(IGrid grid, LPos target) {
        Set<LKey> result = new HashSet<>();
        try {
            GridAnalyzer a = new GridAnalyzer(grid, new GridTracker(1), System.currentTimeMillis(),
                    Thresholds.DEFAULT, Limits.DEFAULT);
            for (ICraftingPatternDetails pattern : Ae.allPatterns(grid)) {
                boolean feeds = false;
                for (Ae.Holder h : a.holdersOf(pattern)) {
                    if (h.pos.equals(target)) {
                        feeds = true;
                    }
                    for (int side : h.sides) {
                        feeds |= h.pos.offset(side).equals(target);
                    }
                }
                if (feeds) {
                    for (IAEItemStack in : Ae.inputs(pattern)) {
                        LKey k = Ae.key(in);
                        if (k != null) {
                            result.add(k);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not collect pattern inputs", e);
        }
        return result;
    }
}
