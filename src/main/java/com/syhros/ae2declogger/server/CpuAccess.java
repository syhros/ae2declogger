package com.syhros.ae2declogger.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
 * Read-only view of one crafting CPU. Uses AE2's public classes; the per-pattern task list is read by reflection
 * because AE2 keeps it package-private. Anything that fails falls back to the coarse {@link CraftingJobStatus}.
 */
final class CpuAccess {

    private static final Map<Class<?>, Optional<Function<Object, CraftingCpuLogic>>> LOGIC_LOOKUP =
            new ConcurrentHashMap<>();

    private static volatile boolean taskReflectionFailed;
    private static Field jobField;
    private static Field tasksField;
    private static Field taskValueField;

    final ICraftingCPU cpu;
    @Nullable
    final CraftingCPUCluster cluster;
    @Nullable
    final CraftingCpuLogic logic;

    private CpuAccess(ICraftingCPU cpu, @Nullable CraftingCPUCluster cluster, @Nullable CraftingCpuLogic logic) {
        this.cpu = cpu;
        this.cluster = cluster;
        this.logic = logic;
    }

    static CpuAccess of(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            return new CpuAccess(cpu, cluster, cluster.craftingLogic);
        }
        // Add-on CPUs (e.g. Advanced AE's quantum computer) usually keep a CraftingCpuLogic too.
        var finder = LOGIC_LOOKUP.computeIfAbsent(cpu.getClass(), CpuAccess::findLogicAccessor);
        CraftingCpuLogic logic = null;
        if (finder.isPresent()) {
            try {
                logic = finder.get().apply(cpu);
            } catch (Throwable t) {
                logic = null;
            }
        }
        return new CpuAccess(cpu, null, logic);
    }

    private static Optional<Function<Object, CraftingCpuLogic>> findLogicAccessor(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (CraftingCpuLogic.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return Optional.of(o -> {
                            try {
                                return (CraftingCpuLogic) f.get(o);
                            } catch (IllegalAccessException e) {
                                return null;
                            }
                        });
                    } catch (RuntimeException ignored) {
                        // inaccessible, keep looking
                    }
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && CraftingCpuLogic.class.isAssignableFrom(m.getReturnType())) {
                    try {
                        m.setAccessible(true);
                        return Optional.of(o -> {
                            try {
                                return (CraftingCpuLogic) m.invoke(o);
                            } catch (ReflectiveOperationException e) {
                                return null;
                            }
                        });
                    } catch (RuntimeException ignored) {
                        // keep looking
                    }
                }
            }
        }
        return Optional.empty();
    }

    boolean hasDetails() {
        return logic != null && logic.hasJob();
    }

    /** False when the CPU has no power or channel. Unknown CPU types count as active. */
    boolean isActive() {
        return cluster == null || cluster.isActive();
    }

    @Nullable
    ICraftingLink link() {
        return logic == null ? null : logic.getLastLink();
    }

    /** AE2 tick of the last change to the job, or -1. */
    long lastModifiedTick() {
        return logic == null ? -1 : logic.getLastModifiedOnTick();
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
            float p = logic.getElapsedTimeTracker().getProgress();
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
            logic.getAllItems(out);
        }
    }

    long stored(AEKey key) {
        return logic == null ? 0 : logic.getStored(key);
    }

    long active(AEKey key) {
        return logic == null ? 0 : logic.getWaitingFor(key);
    }

    long pending(AEKey key) {
        return logic == null ? 0 : logic.getPendingOutputs(key);
    }

    /**
     * Patterns the job still has to push, with how many times each. Null when it cannot be read; callers then fall
     * back to asking the network which patterns produce an item.
     */
    @Nullable
    Map<IPatternDetails, Long> tasks() {
        if (logic == null || taskReflectionFailed) {
            return null;
        }
        try {
            if (jobField == null) {
                Field f = CraftingCpuLogic.class.getDeclaredField("job");
                f.setAccessible(true);
                jobField = f;
            }
            Object job = jobField.get(logic);
            if (job == null) {
                return Map.of();
            }
            if (tasksField == null) {
                Field f = job.getClass().getDeclaredField("tasks");
                f.setAccessible(true);
                tasksField = f;
            }
            Map<?, ?> raw = (Map<?, ?>) tasksField.get(job);
            Map<IPatternDetails, Long> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : new ArrayList<>(raw.entrySet())) {
                Object progress = e.getValue();
                if (progress == null || !(e.getKey() instanceof IPatternDetails pattern)) {
                    continue;
                }
                if (taskValueField == null) {
                    Field f = progress.getClass().getDeclaredField("value");
                    f.setAccessible(true);
                    taskValueField = f;
                }
                long remaining = taskValueField.getLong(progress);
                if (remaining > 0) {
                    result.put(pattern, remaining);
                }
            }
            return result;
        } catch (Throwable t) {
            taskReflectionFailed = true;
            Ae2Declogger.LOG.warn("Could not read AE2 crafting task list, falling back to pattern lookup: {}",
                    t.toString());
            return null;
        }
    }
}
