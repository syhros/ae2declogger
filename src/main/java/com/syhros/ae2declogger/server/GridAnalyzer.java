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

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.logic.JobStats;
import com.syhros.ae2declogger.logic.StallClassifier;
import com.syhros.ae2declogger.logic.StepClock;
import com.syhros.ae2declogger.logic.Thresholds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import appeng.api.config.FuzzyMode;
import appeng.api.config.LockCraftingMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
//#if MC >= 1.20
import appeng.helpers.patternprovider.PatternProviderLogic;
//#else
//$$ import appeng.helpers.iface.PatternProviderLogic;
//#endif
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Looks at every crafting CPU on a network and works out, per job and per step, whether it is moving and if not,
 * why. Read-only: it only calls getters and simulated queries.
 */
public final class GridAnalyzer {

    private static final int MAX_PATTERNS_PER_STEP = 3;

    /** Limits for one analysis. */
    public record Limits(int maxJobs, int maxSteps, int maxMachines) {
        public static final Limits DEFAULT = new Limits(32, 24, 4);
    }

    private record Diagnosis(BlockReason reason, @Nullable Component detail, List<MachineRef> machines) {
        static final Diagnosis CLEAR = new Diagnosis(BlockReason.NONE, null, List.of());
    }

    private final IGrid grid;
    private final GridTracker tracker;
    private final long now;
    private final Thresholds thresholds;
    private final Limits limits;
    private final ICraftingService crafting;
    private final IEnergyService energy;
    @Nullable
    private ProviderIndex providers;
    private final Map<GlobalPos, MachineRef> machineCache = new HashMap<>();
    private final Set<GlobalPos> referenced = new HashSet<>();
    @Nullable
    private List<JobStats.Entry<AEKey>> statsEntries;

    private GridAnalyzer(IGrid grid, GridTracker tracker, long now, Thresholds thresholds, Limits limits) {
        this.grid = grid;
        this.tracker = tracker;
        this.now = now;
        this.thresholds = thresholds;
        this.limits = limits;
        this.crafting = grid.getCraftingService();
        this.energy = grid.getEnergyService();
    }

    public static GridAnalysis analyze(IGrid grid, GridTracker tracker, long nowMs, Thresholds thresholds,
            Limits limits) {
        return new GridAnalyzer(grid, tracker, nowMs, thresholds, limits).run();
    }

    /** Visible for game tests: analyses one CPU the way a network scan would. */
    public static JobView analyzeCpuForTests(IGrid grid, GridTracker tracker, ICraftingCPU cpu, long nowMs,
            Thresholds thresholds, Limits limits) {
        CraftingJobStatus status = cpu.getJobStatus();
        if (status == null) {
            throw new IllegalStateException("CPU has no job");
        }
        return new GridAnalyzer(grid, tracker, nowMs, thresholds, limits).analyzeJob(cpu, tracker.cpuNumber(cpu),
                status);
    }

    /**
     * The light pass for networks nobody is watching: follows each job and times its steps for the History and Stats
     * tabs, without diagnosing anything. Jobs that finish are recorded exactly as by a full analysis.
     */
    public static void record(IGrid grid, GridTracker tracker, long nowMs) {
        Set<ICraftingCPU> present = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ICraftingCPU cpu : allCpus(grid)) {
            present.add(cpu);
            int number = tracker.cpuNumber(cpu);
            if (!cpu.isBusy()) {
                tracker.cpuIdle(cpu, nowMs);
                continue;
            }
            CraftingJobStatus status = cpu.getJobStatus();
            if (status == null) {
                continue;
            }
            try {
                CpuAccess access = CpuAccess.of(cpu);
                ICraftingLink link = access.link();
                Component cpuName = cpu.getName() != null ? cpu.getName()
                        : Text.translatable("ae2declogger.cpu.numbered", String.valueOf(number));
                long elapsedMs = Math.max(0, status.elapsedTimeNanos() / 1_000_000L);
                GridTracker.CpuTrack track = tracker.trackFor(cpu, link != null ? link.getCraftingID() : null,
                        status.crafting(), cpuName, nowMs - elapsedMs, link, nowMs);
                track.lastProgress = access.progress(status);
                if (access.hasDetails()) {
                    observeSteps(tracker, track, access, access.tasks(), nowMs);
                }
            } catch (RuntimeException | LinkageError e) {
                Ae2Declogger.LOG.debug("Could not follow crafting CPU {}", number, e);
            }
        }
        tracker.retainCpus(present, nowMs);
    }

    /** Visible for game tests: how an add-on pattern provider's block or part is read (see ProviderIndex). */
    public static String describeAddonProviderForTests(Object owner, ICraftingProvider provider) {
        return ProviderIndex.describeAddon(owner, provider);
    }

    /** Visible for game tests: the ingredient check the Missing ingredient reason comes from. */
    @Nullable
    public static AEKey firstMissingInputForTests(ICraftingCPU cpu, IPatternDetails pattern, @Nullable Level level) {
        return firstMissingInput(CpuAccess.of(cpu), pattern, level);
    }

    /**
     * One look at a job: records the steps that finished since the last look. Steps are counted per item: items still
     * to be made (crafts left x items per craft) plus items out at machines. AE2 drops a pattern from its task list
     * once every craft is pushed, so items out at machines are what keeps a step visible until it comes back.
     */
    private static void observeSteps(GridTracker tracker, GridTracker.CpuTrack track, CpuAccess access,
            @Nullable Map<IPatternDetails, Long> tasks, long nowMs) {
        if (tasks == null) {
            return;
        }
        Map<AEKey, Long> remaining = new LinkedHashMap<>();
        for (Map.Entry<IPatternDetails, Long> e : tasks.entrySet()) {
            GenericStack out = e.getKey().getPrimaryOutput();
            remaining.merge(out.what(), e.getValue() * Math.max(1, out.amount()), Long::sum);
        }
        KeyCounter all = new KeyCounter();
        access.allItems(all);
        for (var e : all) {
            if (!remaining.containsKey(e.getKey()) && access.active(e.getKey()) > 0) {
                remaining.put(e.getKey(), 0L);
            }
        }
        List<StepClock.Done<AEKey>> done = track.steps.observe(remaining, k -> k, k -> 1, access::active, nowMs);
        tracker.recordSteps(track, done);
    }

    private GridAnalysis run() {
        int cpus = 0;
        int busy = 0;
        List<JobView> jobs = new ArrayList<>();
        Set<ICraftingCPU> present = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ICraftingCPU cpu : allCpus(grid)) {
            cpus++;
            present.add(cpu);
            int number = tracker.cpuNumber(cpu);
            if (!cpu.isBusy()) {
                tracker.cpuIdle(cpu, now);
                continue;
            }
            CraftingJobStatus status = cpu.getJobStatus();
            if (status == null) {
                continue;
            }
            busy++;
            try {
                jobs.add(analyzeJob(cpu, number, status));
            } catch (RuntimeException | LinkageError e) {
                // One odd CPU (an add-on type, a half-loaded cluster) must not hide the others.
                Ae2Declogger.LOG.debug("Could not analyse crafting CPU {}", number, e);
            }
        }
        tracker.retainCpus(present, now);

        jobs.sort(Comparator.comparingInt((JobView j) -> -j.state().severity())
                .thenComparingLong(JobView::startedAtMs));
        if (jobs.size() > limits.maxJobs()) {
            jobs = new ArrayList<>(jobs.subList(0, limits.maxJobs()));
        }
        boolean powered = energy.isNetworkPowered();
        return new GridAnalysis(jobs, cpus, busy, energy.getStoredPower(), energy.getMaxStoredPower(), powered,
                referenced, now);
    }

    /**
     * The CPUs AE2 reports plus crafting clusters it leaves out because they are offline, so a CPU that loses power
     * mid-job stays visible (as CPU offline) instead of looking cancelled.
     */
    static List<ICraftingCPU> allCpus(IGrid grid) {
        List<ICraftingCPU> result = new ArrayList<>(grid.getCraftingService().getCpus());
        Set<ICraftingCPU> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(result);
        try {
            for (Class<?> type : grid.getMachineClasses()) {
                if (!CraftingBlockEntity.class.isAssignableFrom(type)) {
                    continue;
                }
                for (Object machine : grid.getMachines(type)) {
                    CraftingCPUCluster cluster = ((CraftingBlockEntity) machine).getCluster();
                    if (cluster != null && !cluster.isDestroyed() && seen.add(cluster)) {
                        result.add(cluster);
                    }
                }
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not list offline crafting CPUs", e);
        }
        return result;
    }

    private JobView analyzeJob(ICraftingCPU cpu, int number, CraftingJobStatus status) {
        CpuAccess access = CpuAccess.of(cpu);
        ICraftingLink link = access.link();
        Component cpuName = cpu.getName() != null ? cpu.getName()
                : Text.translatable("ae2declogger.cpu.numbered", String.valueOf(number));
        long elapsedMs = Math.max(0, status.elapsedTimeNanos() / 1_000_000L);
        GridTracker.CpuTrack track = tracker.trackFor(cpu, link != null ? link.getCraftingID() : null,
                status.crafting(), cpuName, now - elapsedMs, link, now);

        float progress = Math.round(access.progress(status) * 1000f) / 1000f;

        record Amounts(AEKey key, long stored, long active, long pending) {
        }
        List<Amounts> amounts = new ArrayList<>();
        Map<IPatternDetails, Long> tasks = null;
        Set<AEKey> analysed = new LinkedHashSet<>();
        long totalActive = 0;
        long totalPending = 0;
        if (access.hasDetails()) {
            KeyCounter all = new KeyCounter();
            access.allItems(all);
            tasks = access.tasks();
            Set<AEKey> keys = new LinkedHashSet<>();
            for (var entry : all) {
                keys.add(entry.getKey());
            }
            for (AEKey key : keys) {
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
            observeSteps(tracker, track, access, tasks, now);
        }
        track.observeActivity(access.lastModifiedTick(), CpuAccess.currentTick(),
                new GridTracker.Fingerprint(progress, totalActive, totalPending, amounts.size()), now);
        long jobIdle = Math.max(0, now - track.lastActivityAtMs);

        List<StepView> steps = new ArrayList<>();
        for (Amounts a : amounts) {
            steps.add(analyzeStep(access, track, a.key(), a.stored(), a.active(), a.pending(), tasks, jobIdle));
        }
        markQueued(steps, tasks);

        List<JobState> stepStates = new ArrayList<>(steps.size());
        for (StepView s : steps) {
            stepStates.add(s.state());
        }
        JobState state = StallClassifier.classifyJob(jobIdle, stepStates, access.hasDetails(), thresholds);
        BlockReason reason = BlockReason.NONE;
        Component detail = null;

        if (!access.isActive()) {
            state = jobIdle >= thresholds.blockedMs() ? JobState.BLOCKED : JobState.worst(state, JobState.SLOW);
            reason = BlockReason.CPU_OFFLINE;
        }

        steps.sort(Comparator.comparingInt((StepView s) -> -s.state().severity())
                .thenComparingInt(s -> s.reason().isProblem() ? 0 : 1)
                .thenComparingLong(s -> -s.active()));

        MachineRef primary = null;
        for (StepView s : steps) {
            if (s.state() == state && s.reason().isProblem()) {
                if (reason == BlockReason.NONE) {
                    reason = s.reason();
                    detail = s.detail() != null ? s.detail() : s.key().getDisplayName();
                }
                primary = firstProblemMachine(s.machines());
                if (primary != null) {
                    break;
                }
            }
        }
        if (primary == null) {
            for (StepView s : steps) {
                if (!s.machines().isEmpty()) {
                    primary = firstProblemMachine(s.machines());
                    if (primary == null) {
                        primary = s.machines().get(0);
                    }
                    break;
                }
            }
        }
        if (reason == BlockReason.NONE && state.isProblem()) {
            reason = BlockReason.UNKNOWN;
        }

        if (steps.size() > limits.maxSteps()) {
            steps = new ArrayList<>(steps.subList(0, limits.maxSteps()));
        }
        for (StepView s : steps) {
            for (MachineRef m : s.machines()) {
                referenced.add(GlobalPos.of(m.dimension(), m.pos()));
            }
        }
        if (primary != null) {
            referenced.add(GlobalPos.of(primary.dimension(), primary.pos()));
        }

        GenericStack output = status.crafting();
        long expected = JobStats.expectedMs(stats(), output.what(), output.amount());
        return new JobView(track.jobId, cpuName, cpu.getCoProcessors(), cpu.getAvailableStorage(), output.what(),
                output.amount(), progress, track.startedAtMs, track.lastActivityAtMs, expected, state, reason, detail,
                primary, steps);
    }

    /** Reasons that only mean "the machine will not take more right now". */
    private static boolean machineWontTake(BlockReason r) {
        return r == BlockReason.PUSH_REJECTED || r == BlockReason.PROVIDERS_BUSY || r == BlockReason.PROVIDER_BLOCKING
                || r == BlockReason.MACHINE_INPUT_FULL;
    }

    /**
     * A blocked step whose machine is busy with another step of the same job is not stuck, it is next in line: two
     * dusts from the same macerator for one electrum job are made one after the other. Only steps of this job count;
     * the same machine working for another CPU still means blocked.
     */
    private void markQueued(List<StepView> steps, @Nullable Map<IPatternDetails, Long> tasks) {
        boolean anyCandidate = false;
        for (StepView s : steps) {
            anyCandidate |= s.active() <= 0 && s.state() == JobState.BLOCKED && machineWontTake(s.reason())
                    && !s.machines().isEmpty();
        }
        if (!anyCandidate) {
            return;
        }
        Map<GlobalPos, StepView> busyAt = new LinkedHashMap<>();
        for (StepView s : steps) {
            if (s.active() > 0) {
                List<MachineRef> machines = s.machines().isEmpty() ? machinesProducing(s.key(), tasks) : s.machines();
                for (MachineRef m : machines) {
                    busyAt.putIfAbsent(GlobalPos.of(m.dimension(), m.pos()), s);
                }
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            StepView s = steps.get(i);
            if (s.active() > 0 || s.state() != JobState.BLOCKED || !machineWontTake(s.reason())) {
                continue;
            }
            for (MachineRef m : s.machines()) {
                StepView running = busyAt.get(GlobalPos.of(m.dimension(), m.pos()));
                if (running != null) {
                    steps.set(i, new StepView(s.key(), s.stored(), s.active(), s.pending(), JobState.QUEUED,
                            BlockReason.QUEUED, running.key().getDisplayName(), s.sinceMs(), s.recipeMs(),
                            s.machines()));
                    break;
                }
            }
        }
    }

    private StepView analyzeStep(CpuAccess access, GridTracker.CpuTrack track, AEKey key, long stored, long active,
            long pending, @Nullable Map<IPatternDetails, Long> tasks, long jobIdle) {
        long pendingChangedAt = track.pendingTimer.observe(key, pending, now, jobIdle);
        long activeChangedAt = track.activeTimer.observe(key, active, now, jobIdle);

        if (active > 0) {
            track.clearBlocked(key);
            // Pending going down also means the step is moving (blocking mode keeps "active" at the same value).
            long movedAt = Math.max(activeChangedAt, pendingChangedAt);
            long idle = Math.max(0, now - movedAt);
            JobState state = StallClassifier.classifyActive(idle, -1, thresholds);
            BlockReason reason = BlockReason.NONE;
            Component detail = null;
            List<MachineRef> machines = List.of();
            long recipeMs = -1;
            if (state != JobState.IN_PROGRESS) {
                machines = machinesProducing(key, tasks);
                for (MachineRef m : machines) {
                    recipeMs = Math.max(recipeMs, MachineInspector.recipeMs(m));
                }
                state = StallClassifier.classifyActive(idle, recipeMs, thresholds);
                if (state != JobState.IN_PROGRESS) {
                    MachineRef bad = firstProblemMachine(machines);
                    if (bad != null) {
                        reason = bad.problem();
                        detail = bad.detail();
                        state = StallClassifier.classifyMachineFault(state, reason);
                    } else if (anyWorking(machines)) {
                        // The machine is running a recipe: moving, maybe slowly, but not stuck.
                        state = StallClassifier.classifyWorking(idle, recipeMs, thresholds);
                    } else {
                        reason = BlockReason.OUTPUT_NOT_RETURNING;
                        detail = key.getDisplayName();
                    }
                }
            }
            return new StepView(key, stored, active, pending, state, reason, detail, movedAt, recipeMs, machines);
        }

        Diagnosis d = diagnosePending(access, key, tasks);
        long since = pendingChangedAt;
        long blockedFor = 0;
        if (d.reason().isProblem()) {
            long firstSeen = track.blockedSince(key, now, jobIdle);
            long start = Math.max(firstSeen, pendingChangedAt);
            blockedFor = Math.max(0, now - start);
            since = start;
        } else {
            track.clearBlocked(key);
        }
        JobState state = StallClassifier.classifyPending(d.reason(), blockedFor, thresholds);
        BlockReason shown = d.reason();
        Component detail = d.detail();
        if (shown.isProblem() && state != JobState.BLOCKED) {
            // Not confirmed yet: AE2 may push it on the next tick.
            shown = BlockReason.NONE;
            detail = null;
        }
        return new StepView(key, stored, 0, pending, state, shown, detail, since, -1, d.machines());
    }

    private Diagnosis diagnosePending(CpuAccess access, AEKey key, @Nullable Map<IPatternDetails, Long> tasks) {
        List<IPatternDetails> patterns = patternsFor(key, tasks, true);
        if (patterns.isEmpty()) {
            return new Diagnosis(BlockReason.NO_PROVIDER, key.getDisplayName(), List.of());
        }
        Diagnosis best = null;
        for (IPatternDetails pattern : patterns) {
            Diagnosis d = diagnosePattern(access, pattern);
            if (!d.reason().isProblem() && d.reason() != BlockReason.WAITING_INGREDIENTS) {
                return d;
            }
            if (best == null || BlockReason.stronger(best.reason(), d.reason()) == d.reason()
                    && d.reason() != best.reason()) {
                best = d;
            }
        }
        return best == null ? Diagnosis.CLEAR : best;
    }

    private Diagnosis diagnosePattern(CpuAccess access, IPatternDetails pattern) {
        AEKey missing = firstMissingInput(access, pattern, level());
        if (missing != null) {
            boolean coming = access.active(missing) > 0 || access.pending(missing) > 0;
            return new Diagnosis(coming ? BlockReason.WAITING_INGREDIENTS : BlockReason.MISSING_INGREDIENT,
                    missing.getDisplayName(), List.of());
        }
        // Another output of this pattern is still out at the machine: it is busy with the previous batch.
        for (GenericStack out : pattern.getOutputs()) {
            if (out != null && access.active(out.what()) > 0) {
                return new Diagnosis(BlockReason.MACHINE_BUSY, out.what().getDisplayName(), List.of());
            }
        }
        if (!access.isActive()) {
            return new Diagnosis(BlockReason.CPU_OFFLINE, null, List.of());
        }

        List<ICraftingProvider> providerList = providersOf(pattern);
        if (providerList.isEmpty()) {
            return new Diagnosis(BlockReason.NO_PROVIDER, pattern.getPrimaryOutput().what().getDisplayName(),
                    List.of());
        }
        if (!energy.isNetworkPowered()) {
            return new Diagnosis(BlockReason.NETWORK_POWER, null, List.of());
        }

        List<MachineRef> machines = new ArrayList<>();
        BlockReason providerReason = null;
        Component providerDetail = null;
        BlockReason freeMachineReason = BlockReason.NONE;
        Component freeMachineDetail = null;
        boolean anyFree = false;

        for (ICraftingProvider provider : providerList) {
            ProviderIndex.ProviderInfo info = providers().get(provider);
            ProviderIndex.ProviderState logic = info != null ? info.state()
                    : provider instanceof PatternProviderLogic p ? ProviderIndex.ProviderState.of(p) : null;

            BlockReason r = BlockReason.NONE;
            Component d = null;
            if (info != null && !info.online()) {
                r = BlockReason.PROVIDER_OFFLINE;
            } else if (provider.isBusy()) {
                r = BlockReason.PROVIDERS_BUSY;
            } else if (logic != null && logic.lockReason() != LockCraftingMode.NONE) {
                r = BlockReason.PROVIDER_LOCKED;
                d = lockText(logic);
            } else if (logic != null && logic.blocking()) {
                r = BlockReason.PROVIDER_BLOCKING;
            }

            List<MachineRef> targets = info != null ? targetsOf(info, r) : List.of();
            MachineRef bad = firstProblemMachine(targets);
            for (MachineRef m : targets) {
                if (machines.size() < limits.maxMachines() && !machines.contains(m)) {
                    machines.add(m);
                }
            }

            if (r == BlockReason.NONE) {
                anyFree = true;
                if (bad != null) {
                    freeMachineReason = BlockReason.stronger(freeMachineReason, bad.problem());
                    if (freeMachineDetail == null) {
                        freeMachineDetail = bad.detail();
                    }
                }
                continue;
            }
            // A machine-level problem explains a provider-level one better (busy because the machine has no power).
            if (bad != null && bad.problem().priority() > r.priority()) {
                r = bad.problem();
                d = bad.detail();
            } else if (d == null && bad != null) {
                d = bad.detail();
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

    /**
     * First pattern input the CPU does not hold enough of, or null if all inputs are there.
     *
     * <p>
     * Matched the way AE2 does when it pushes a pattern: any stored item the input accepts counts, not only the exact
     * one written in the pattern. A GregTech file or hammer that has been used once has different damage data than
     * the pattern's, but it is the same ingredient.
     */
    @Nullable
    static AEKey firstMissingInput(CpuAccess access, IPatternDetails pattern, @Nullable Level level) {
        KeyCounter held = null;
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            GenericStack[] options = input.getPossibleInputs();
            if (options.length == 0) {
                continue;
            }
            long multiplier = Math.max(1, input.getMultiplier());
            boolean satisfied = false;
            for (GenericStack option : options) {
                if (access.stored(option.what()) >= option.amount() * multiplier) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                if (held == null) {
                    held = new KeyCounter();
                    access.allItems(held);
                }
                satisfied = heldLikeAe2(access, held, input, options, multiplier, level);
            }
            if (!satisfied) {
                return options[0].what();
            }
        }
        return null;
    }

    /** AE2's own test (CraftingCpuHelper): fuzzy matches the input accepts, added up over all of them. */
    private static boolean heldLikeAe2(CpuAccess access, KeyCounter held, IPatternDetails.IInput input,
            GenericStack[] options, long multiplier, @Nullable Level level) {
        Set<AEKey> counted = new HashSet<>();
        long crafts = 0;
        for (GenericStack option : options) {
            long per = Math.max(1, option.amount());
            for (var e : held.findFuzzy(option.what(), FuzzyMode.IGNORE_ALL)) {
                AEKey key = e.getKey();
                if (!counted.add(key)) {
                    continue;
                }
                boolean valid;
                try {
                    valid = level == null || input.isValid(key, level);
                } catch (RuntimeException ex) {
                    valid = false;
                }
                if (valid) {
                    crafts += access.stored(key) / per;
                    if (crafts >= multiplier) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    private Level level() {
        IGridNode pivot = grid.getPivot();
        return pivot != null ? pivot.getLevel() : null;
    }

    private List<IPatternDetails> patternsFor(AEKey key, @Nullable Map<IPatternDetails, Long> tasks,
            boolean preferTasks) {
        Set<IPatternDetails> result = new LinkedHashSet<>();
        if (tasks != null) {
            for (IPatternDetails pattern : tasks.keySet()) {
                if (produces(pattern, key)) {
                    result.add(pattern);
                }
                if (result.size() >= MAX_PATTERNS_PER_STEP) {
                    break;
                }
            }
        }
        if (result.isEmpty() || !preferTasks || tasks == null) {
            for (IPatternDetails pattern : crafting.getCraftingFor(key)) {
                result.add(pattern);
                if (result.size() >= MAX_PATTERNS_PER_STEP) {
                    break;
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static boolean produces(IPatternDetails pattern, AEKey key) {
        for (GenericStack out : pattern.getOutputs()) {
            if (out != null && key.equals(out.what())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Providers holding {@code pattern}, online ones first, capped. Read from the provider index, never from AE2's
     * own provider lookup (iterating that would shift AE2's round-robin).
     */
    private List<ICraftingProvider> providersOf(IPatternDetails pattern) {
        List<ICraftingProvider> all = new ArrayList<>(providers().providersFor(pattern));
        all.sort(Comparator.comparingInt((ICraftingProvider p) -> {
            ProviderIndex.ProviderInfo info = providers().get(p);
            return info == null || info.online() ? 0 : 1;
        }));
        return all.size() > limits.maxMachines() ? all.subList(0, limits.maxMachines()) : all;
    }

    /** Machines that make {@code key}: the targets of every provider holding a pattern for it. */
    private List<MachineRef> machinesProducing(AEKey key, @Nullable Map<IPatternDetails, Long> tasks) {
        Map<GlobalPos, MachineRef> found = new LinkedHashMap<>();
        for (IPatternDetails pattern : patternsFor(key, tasks, false)) {
            for (ICraftingProvider provider : providersOf(pattern)) {
                ProviderIndex.ProviderInfo info = providers().get(provider);
                if (info == null) {
                    continue;
                }
                for (MachineRef ref : targetsOf(info, BlockReason.NONE)) {
                    found.putIfAbsent(GlobalPos.of(ref.dimension(), ref.pos()), ref);
                    if (found.size() >= limits.maxMachines()) {
                        return sortProblemsFirst(found.values());
                    }
                }
            }
        }
        return sortProblemsFirst(found.values());
    }

    /** Refs for the blocks a provider pushes into, or the provider itself if it has no targets. */
    private List<MachineRef> targetsOf(ProviderIndex.ProviderInfo info, BlockReason providerReason) {
        List<MachineRef> refs = new ArrayList<>();
        ServerLevel level = info.level();
        for (BlockPos target : info.targets()) {
            MachineRef ref = machineAt(level, target, MachineRef.Kind.MACHINE);
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }
        if (refs.isEmpty()) {
            MachineRef self = machineAt(level, info.pos(), MachineRef.Kind.PROVIDER);
            if (providerReason.isProblem()) {
                self = new MachineRef(self.dimension(), self.pos(), self.name(), MachineRef.Kind.PROVIDER,
                        self.status(), providerReason, self.detail(), self.progress(), self.duration());
            }
            refs.add(self);
        }
        return refs;
    }

    private MachineRef machineAt(ServerLevel level, BlockPos pos, MachineRef.Kind kind) {
        GlobalPos key = GlobalPos.of(level.dimension(), pos);
        MachineRef cached = machineCache.get(key);
        if (cached == null) {
            cached = MachineInspector.describe(level, pos, kind);
            machineCache.put(key, cached);
        }
        return cached;
    }

    private ProviderIndex providers() {
        if (providers == null) {
            providers = ProviderIndex.build(grid);
        }
        return providers;
    }

    private List<JobStats.Entry<AEKey>> stats() {
        if (statsEntries == null) {
            statsEntries = tracker.statsEntries();
        }
        return statsEntries;
    }

    @Nullable
    private static MachineRef firstProblemMachine(List<MachineRef> machines) {
        MachineRef best = null;
        for (MachineRef m : machines) {
            if (m.hasProblem() && (best == null || m.problem().priority() > best.problem().priority())) {
                best = m;
            }
        }
        return best;
    }

    private static boolean anyWorking(List<MachineRef> machines) {
        for (MachineRef m : machines) {
            if (m.status() == MachineStatus.WORKING) {
                return true;
            }
        }
        return false;
    }

    private static List<MachineRef> sortProblemsFirst(Iterable<MachineRef> refs) {
        List<MachineRef> list = new ArrayList<>();
        refs.forEach(list::add);
        list.sort(Comparator.comparingInt((MachineRef m) -> m.hasProblem() ? -m.problem().priority() : 0));
        return list;
    }

    private static Component lockText(ProviderIndex.ProviderState logic) {
        LockCraftingMode mode = logic.lockReason();
        return switch (mode) {
            case LOCK_WHILE_LOW -> Text.translatable("ae2declogger.lock.while_low");
            case LOCK_WHILE_HIGH -> Text.translatable("ae2declogger.lock.while_high");
            case LOCK_UNTIL_PULSE -> Text.translatable("ae2declogger.lock.until_pulse");
            case LOCK_UNTIL_RESULT -> {
                GenericStack unlock = logic.unlockStack();
                yield unlock != null
                        ? Text.translatable("ae2declogger.lock.until_result", unlock.what().getDisplayName())
                        : Text.translatable("ae2declogger.lock.until_result_unknown");
            }
            default -> Text.literal(mode.name());
        };
    }
}
