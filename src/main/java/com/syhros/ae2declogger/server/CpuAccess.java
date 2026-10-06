package com.syhros.ae2declogger.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.hooks.ticking.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Read-only view of one crafting CPU.
 *
 * <p>
 * AE2's own CPUs are read through {@link CraftingCpuLogic}. Add-on CPUs are read by reflection: Advanced AE's quantum
 * computer, for one, keeps a copy of AE2's crafting logic under its own class names (same method names, same job and
 * task fields) and creates a separate CPU object per job. Anything that fails falls back to the coarse
 * {@link CraftingJobStatus}.
 */
final class CpuAccess {

    /** The parts of a CPU's crafting logic the Declogger reads. */
    private interface Logic {
        boolean hasJob();

        @Nullable
        ICraftingLink lastLink();

        long lastModifiedTick();

        /** Job progress 0..1, or NaN when unknown. */
        float progress();

        void allItems(KeyCounter out);

        long stored(AEKey key);

        long waitingFor(AEKey key);

        long pending(AEKey key);

        /** The running job object (AE2's ExecutingCraftingJob or an add-on's copy of it), or null. */
        @Nullable
        Object job() throws ReflectiveOperationException;
    }

    private static final Map<Class<?>, Optional<Field>> LOGIC_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<ReflectiveLogic.Methods>> LOGIC_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Field>> NAMED_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> ACTIVE_METHODS = new ConcurrentHashMap<>();
    /** Job and task classes whose task list could not be read; they fall back to the pattern lookup. */
    private static final Map<Class<?>, Boolean> TASKS_UNREADABLE = new ConcurrentHashMap<>();

    final ICraftingCPU cpu;
    @Nullable
    final CraftingCPUCluster cluster;
    @Nullable
    private final Logic logic;

    private CpuAccess(ICraftingCPU cpu, @Nullable CraftingCPUCluster cluster, @Nullable Logic logic) {
        this.cpu = cpu;
        this.cluster = cluster;
        this.logic = logic;
    }

    static CpuAccess of(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            return new CpuAccess(cpu, cluster, new NativeLogic(cluster.craftingLogic));
        }
        return new CpuAccess(cpu, null, ReflectiveLogic.find(cpu));
    }

    /**
     * True for CPUs that exist only while they run one job (Advanced AE's quantum computer threads). Such a CPU
     * disappearing is the normal end of its job, not a broken crafting storage.
     */
    static boolean isPerJob(ICraftingCPU cpu) {
        return !(cpu instanceof CraftingCPUCluster);
    }

    /**
     * The object CPU numbers are given to: the multiblock for add-on CPUs that are created per job (so a quantum
     * computer keeps one number), otherwise the CPU itself.
     */
    static Object numberKey(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster) {
            return cpu;
        }
        try {
            Optional<Field> f = NAMED_FIELDS.computeIfAbsent(cpu.getClass(), c -> findField(c, "cluster"));
            if (f.isPresent()) {
                Object owner = f.get().get(cpu);
                if (owner != null) {
                    return owner;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through
        }
        return cpu;
    }

    boolean hasDetails() {
        return logic != null && logic.hasJob();
    }

    /** False when the CPU has no power or channel. Unknown CPU types count as active. */
    boolean isActive() {
        if (cluster != null) {
            return cluster.isActive();
        }
        Optional<Method> m = ACTIVE_METHODS.computeIfAbsent(cpu.getClass(), CpuAccess::findIsActive);
        if (m.isEmpty()) {
            return true;
        }
        try {
            return (boolean) m.get().invoke(cpu);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    @Nullable
    ICraftingLink link() {
        return logic == null ? null : logic.lastLink();
    }

    /** AE2 tick of the last change to the job, or -1. */
    long lastModifiedTick() {
        return logic == null ? -1 : logic.lastModifiedTick();
    }

    static long currentTick() {
        try {
            return TickHandler.instance().getCurrentTick();
        } catch (Throwable t) {
            return -1;
        }
    }

    float progress(CraftingJobStatus status) {
        if (logic != null && logic.hasJob()) {
            float p = logic.progress();
            if (!Float.isNaN(p)) {
                return Math.max(0, Math.min(1, p));
            }
        }
        if (status.totalItems() <= 0) {
            return 0;
        }
        return (float) Math.max(0, Math.min(1, (double) status.progress() / status.totalItems()));
    }

    void allItems(KeyCounter out) {
        if (logic != null) {
            logic.allItems(out);
        }
    }

    long stored(AEKey key) {
        return logic == null ? 0 : logic.stored(key);
    }

    long active(AEKey key) {
        return logic == null ? 0 : logic.waitingFor(key);
    }

    long pending(AEKey key) {
        return logic == null ? 0 : logic.pending(key);
    }

    /**
     * Patterns the job still has to push, with how many times each. Null when it cannot be read; callers then fall
     * back to asking the network which patterns produce an item.
     */
    @Nullable
    Map<IPatternDetails, Long> tasks() {
        if (logic == null) {
            return null;
        }
        Object job;
        try {
            job = logic.job();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        if (job == null) {
            return Map.of();
        }
        Class<?> jobClass = job.getClass();
        if (TASKS_UNREADABLE.containsKey(jobClass)) {
            return null;
        }
        try {
            Field tasksField = findField(jobClass, "tasks").orElseThrow(() -> new NoSuchFieldException("tasks"));
            Map<?, ?> raw = (Map<?, ?>) tasksField.get(job);
            Map<IPatternDetails, Long> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : new ArrayList<>(raw.entrySet())) {
                Object progress = e.getValue();
                if (progress == null || !(e.getKey() instanceof IPatternDetails pattern)) {
                    continue;
                }
                Field value = findField(progress.getClass(), "value")
                        .orElseThrow(() -> new NoSuchFieldException("value"));
                long remaining = value.getLong(progress);
                if (remaining > 0) {
                    result.put(pattern, remaining);
                }
            }
            return result;
        } catch (Throwable t) {
            TASKS_UNREADABLE.put(jobClass, Boolean.TRUE);
            Ae2Declogger.LOG.warn("Could not read the crafting task list of {}, falling back to pattern lookup: {}",
                    jobClass.getName(), t.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------- AE2

    private record NativeLogic(CraftingCpuLogic logic) implements Logic {
        private static volatile Field jobField;

        @Override
        public boolean hasJob() {
            return logic.hasJob();
        }

        @Override
        @Nullable
        public ICraftingLink lastLink() {
            return logic.getLastLink();
        }

        @Override
        public long lastModifiedTick() {
            //#if MC >= 1.20
            return logic.getLastModifiedOnTick();
            //#else
            //$$ return -1; // AE2 12 and older do not record it
            //#endif
        }

        @Override
        public float progress() {
            //#if MC >= 1.20
            return logic.getElapsedTimeTracker().getProgress();
            //#else
            //$$ var t = logic.getElapsedTimeTracker();
            //$$ return t.getStartItemCount() <= 0 ? Float.NaN
            //$$         : 1f - (float) t.getRemainingItemCount() / t.getStartItemCount();
            //#endif
        }

        @Override
        public void allItems(KeyCounter out) {
            logic.getAllItems(out);
        }

        @Override
        public long stored(AEKey key) {
            return logic.getStored(key);
        }

        @Override
        public long waitingFor(AEKey key) {
            return logic.getWaitingFor(key);
        }

        @Override
        public long pending(AEKey key) {
            return logic.getPendingOutputs(key);
        }

        @Override
        @Nullable
        public Object job() throws ReflectiveOperationException {
            if (jobField == null) {
                Field f = CraftingCpuLogic.class.getDeclaredField("job");
                f.setAccessible(true);
                jobField = f;
            }
            return jobField.get(logic);
        }
    }

    // ---------------------------------------------------------------- add-on CPUs

    /** Crafting logic of an add-on CPU, called by method name (the names AE2's {@link CraftingCpuLogic} uses). */
    private record ReflectiveLogic(Object target, Methods m) implements Logic {

        record Methods(Method hasJob, @Nullable Method getLastLink, @Nullable Method getLastModifiedOnTick,
                @Nullable Method getElapsedTimeTracker, Method getAllItems, Method getStored, Method getWaitingFor,
                Method getPendingOutputs, Optional<Field> job) {
        }

        @Nullable
        static Logic find(ICraftingCPU cpu) {
            try {
                Optional<Field> field = LOGIC_FIELDS.computeIfAbsent(cpu.getClass(), ReflectiveLogic::findLogicField);
                if (field.isEmpty()) {
                    return null;
                }
                Object target = field.get().get(cpu);
                if (target == null) {
                    return null;
                }
                if (target instanceof CraftingCpuLogic native_) {
                    return new NativeLogic(native_);
                }
                Optional<Methods> methods = LOGIC_METHODS.computeIfAbsent(target.getClass(), ReflectiveLogic::methods);
                return methods.map(ms -> new ReflectiveLogic(target, ms)).orElse(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        /** A field of the CPU whose type looks like crafting logic (has hasJob() and getWaitingFor(AEKey)). */
        private static Optional<Field> findLogicField(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    if (CraftingCpuLogic.class.isAssignableFrom(f.getType()) || methods(f.getType()).isPresent()) {
                        try {
                            f.setAccessible(true);
                            return Optional.of(f);
                        } catch (RuntimeException ignored) {
                            // inaccessible, keep looking
                        }
                    }
                }
            }
            return Optional.empty();
        }

        private static Optional<Methods> methods(Class<?> type) {
            try {
                Method hasJob = accessible(type.getMethod("hasJob"));
                Method allItems = accessible(type.getMethod("getAllItems", KeyCounter.class));
                Method stored = accessible(type.getMethod("getStored", AEKey.class));
                Method waiting = accessible(type.getMethod("getWaitingFor", AEKey.class));
                Method pending = accessible(type.getMethod("getPendingOutputs", AEKey.class));
                return Optional.of(new Methods(hasJob, optionalMethod(type, "getLastLink"),
                        optionalMethod(type, "getLastModifiedOnTick"), optionalMethod(type, "getElapsedTimeTracker"),
                        allItems, stored, waiting, pending, findField(type, "job")));
            } catch (NoSuchMethodException | RuntimeException e) {
                return Optional.empty();
            }
        }

        /** Public methods of a class nested in a non-public one still need this to be called reflectively. */
        private static Method accessible(Method m) {
            try {
                m.setAccessible(true);
            } catch (RuntimeException ignored) {
                // public anyway; invoke will say if not
            }
            return m;
        }

        @Nullable
        private static Method optionalMethod(Class<?> type, String name) {
            try {
                return accessible(type.getMethod(name));
            } catch (NoSuchMethodException | RuntimeException e) {
                return null;
            }
        }

        @Nullable
        private Object call(@Nullable Method method, Object... args) {
            if (method == null) {
                return null;
            }
            try {
                return method.invoke(target, args);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        private long callLong(Method method, AEKey key) {
            return call(method, key) instanceof Long l ? l : 0;
        }

        @Override
        public boolean hasJob() {
            return call(m.hasJob()) instanceof Boolean b && b;
        }

        @Override
        @Nullable
        public ICraftingLink lastLink() {
            return call(m.getLastLink()) instanceof ICraftingLink link ? link : null;
        }

        @Override
        public long lastModifiedTick() {
            return call(m.getLastModifiedOnTick()) instanceof Long l ? l : -1;
        }

        @Override
        public float progress() {
            Object tracker = call(m.getElapsedTimeTracker());
            if (tracker == null) {
                return Float.NaN;
            }
            try {
                return accessible(tracker.getClass().getMethod("getProgress")).invoke(tracker) instanceof Float f ? f
                        : Float.NaN;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return Float.NaN;
            }
        }

        @Override
        public void allItems(KeyCounter out) {
            call(m.getAllItems(), out);
        }

        @Override
        public long stored(AEKey key) {
            return callLong(m.getStored(), key);
        }

        @Override
        public long waitingFor(AEKey key) {
            return callLong(m.getWaitingFor(), key);
        }

        @Override
        public long pending(AEKey key) {
            return callLong(m.getPendingOutputs(), key);
        }

        @Override
        @Nullable
        public Object job() throws ReflectiveOperationException {
            return m.job().isPresent() ? m.job().get().get(target) : null;
        }
    }

    // ---------------------------------------------------------------- reflection helpers

    private static Optional<Field> findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return Optional.of(f);
            } catch (NoSuchFieldException e) {
                // keep looking in the superclass
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> findIsActive(Class<?> type) {
        try {
            Method m = type.getMethod("isActive");
            return m.getReturnType() == boolean.class ? Optional.of(m) : Optional.empty();
        } catch (NoSuchMethodException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
