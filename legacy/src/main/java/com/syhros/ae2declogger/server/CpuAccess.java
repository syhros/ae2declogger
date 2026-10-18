package com.syhros.ae2declogger.server;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LText;

import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.MECraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Read-only view of one crafting CPU. The legacy AE2 versions keep a job's state in private fields of
 * {@link CraftingCPUCluster} (the same names in AE2 8, AE2 Extended Life and AE2 Unofficial): {@code tasks} (pattern to
 * crafts left), {@code waitingFor} (items out at machines), {@code inventory} (items held), {@code finalOutput},
 * {@code myLastLink} and {@code lastTime}. They are read by reflection and never written.
 *
 * <p>
 * The amounts are read once per look and cached, so a whole analysis sees one consistent state.
 */
final class CpuAccess {

    private static final Field TASKS = field("tasks");
    private static final Field WAITING_FOR = field("waitingFor");
    private static final Field INVENTORY = field("inventory");
    private static final Field FINAL_OUTPUT = field("finalOutput");
    private static final Field LINK = field("myLastLink");
    private static final Field LAST_TIME = field("lastTime");
    private static Field taskValue;

    final CraftingCPUCluster cpu;
    private Map<ICraftingPatternDetails, Long> tasks;
    private Map<LKey, Long> stored;
    private Map<LKey, Long> active;
    private Map<LKey, Long> pending;

    private CpuAccess(CraftingCPUCluster cpu) {
        this.cpu = cpu;
    }

    static CpuAccess of(CraftingCPUCluster cpu) {
        return new CpuAccess(cpu);
    }

    /** Every field the reader needs, for the names self-check. Missing ones are listed as "missing:<name>". */
    static java.util.List<String> names() {
        java.util.List<String> out = new ArrayList<>();
        String[] names = { "tasks", "waitingFor", "inventory", "finalOutput", "myLastLink", "lastTime" };
        Field[] fields = { TASKS, WAITING_FOR, INVENTORY, FINAL_OUTPUT, LINK, LAST_TIME };
        for (int i = 0; i < names.length; i++) {
            out.add(fields[i] != null ? names[i] : "missing:" + names[i]);
        }
        return out;
    }

    private static Field field(String name) {
        try {
            Field f = CraftingCPUCluster.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ae2Declogger.LOG.warn("AE2 crafting CPU field '{}' not found; some details will be missing", name);
            return null;
        }
    }

    private Object get(Field f) {
        if (f == null) {
            return null;
        }
        try {
            return f.get(cpu);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    boolean isBusy() {
        return cpu.isBusy();
    }

    boolean isActive() {
        try {
            return cpu.isActive();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The CPU's name as the player set it, or null. */
    LText name() {
        try {
            //#if MC >= 1.16
            net.minecraft.util.text.ITextComponent n = cpu.getName();
            return n == null || n.getString().isEmpty() ? null : LText.literal(n.getString());
            //#else
            //$$ String n = cpu.getName();
            //$$ return n == null || n.isEmpty() ? null : LText.literal(n);
            //#endif
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What the job makes, or null. */
    IAEItemStack finalOutput() {
        Object o = get(FINAL_OUTPUT);
        return o instanceof IAEItemStack ? (IAEItemStack) o : null;
    }

    ICraftingLink link() {
        Object o = get(LINK);
        return o instanceof ICraftingLink ? (ICraftingLink) o : null;
    }

    /** Milliseconds since an output last came back to the CPU, or -1 when unknown. */
    long msSinceLastReturn() {
        Object o = get(LAST_TIME);
        if (!(o instanceof Long) || (Long) o <= 0) {
            return -1;
        }
        return Math.max(0, (System.nanoTime() - (Long) o) / 1_000_000L);
    }

    /** Job progress 0..1 from AE2's item counts (1 - remaining / start). */
    float progress() {
        try {
            long start = cpu.getStartItemCount();
            long remaining = cpu.getRemainingItemCount();
            if (start <= 0) {
                return 0;
            }
            return (float) Math.max(0, Math.min(1, 1.0 - (double) remaining / start));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Patterns the job still has to push, with how many times each. Empty when unreadable. */
    Map<ICraftingPatternDetails, Long> tasks() {
        if (tasks != null) {
            return tasks;
        }
        Map<ICraftingPatternDetails, Long> result = new LinkedHashMap<>();
        Object raw = get(TASKS);
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : new ArrayList<>(((Map<?, ?>) raw).entrySet())) {
                if (!(e.getKey() instanceof ICraftingPatternDetails) || e.getValue() == null) {
                    continue;
                }
                long value = taskValue(e.getValue());
                if (value > 0) {
                    result.put((ICraftingPatternDetails) e.getKey(), value);
                }
            }
        }
        tasks = Collections.unmodifiableMap(result);
        return tasks;
    }

    private static long taskValue(Object progress) {
        try {
            Field f = taskValue;
            if (f == null || f.getDeclaringClass() != progress.getClass()) {
                f = progress.getClass().getDeclaredField("value");
                f.setAccessible(true);
                taskValue = f;
            }
            return f.getLong(progress);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    Map<LKey, Long> storedMap() {
        if (stored == null) {
            Object inv = get(INVENTORY);
            IItemList<IAEItemStack> list = null;
            try {
                list = inv instanceof MECraftingInventory ? ((MECraftingInventory) inv).getItemList() : null;
            } catch (RuntimeException e) {
                list = null;
            }
            stored = toMap(list);
        }
        return stored;
    }

    @SuppressWarnings("unchecked")
    Map<LKey, Long> activeMap() {
        if (active == null) {
            Object o = get(WAITING_FOR);
            active = toMap(o instanceof IItemList ? (IItemList<IAEItemStack>) o : null);
        }
        return active;
    }

    /** Items still to be crafted: each remaining task times its pattern's outputs (AE2's "Scheduled"). */
    Map<LKey, Long> pendingMap() {
        if (pending == null) {
            Map<LKey, Long> m = new LinkedHashMap<>();
            for (Map.Entry<ICraftingPatternDetails, Long> e : tasks().entrySet()) {
                for (IAEItemStack out : Ae.outputs(e.getKey())) {
                    LKey key = Ae.key(out);
                    if (key != null) {
                        m.merge(key, out.getStackSize() * e.getValue(), Long::sum);
                        aeStacks.putIfAbsent(key, out);
                    }
                }
            }
            pending = m;
        }
        return pending;
    }

    private final Map<LKey, IAEItemStack> aeStacks = new java.util.HashMap<>();

    /** AE2's own stack for an item the job still has to make (for AE2 calls that take one), or null. */
    IAEItemStack aeStack(LKey key) {
        pendingMap();
        return aeStacks.get(key);
    }

    /** Wall time the job has been running, as far as AE2 counts it (up to the last returned output), in ms. */
    long elapsedMs() {
        try {
            return Math.max(0, cpu.getElapsedTime() / 1_000_000L);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    int coProcessors() {
        try {
            return cpu.getCoProcessors();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    long storage() {
        try {
            return cpu.getAvailableStorage();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    long stored(LKey key) {
        Long v = storedMap().get(key);
        return v == null ? 0 : v;
    }

    long active(LKey key) {
        Long v = activeMap().get(key);
        return v == null ? 0 : v;
    }

    long pending(LKey key) {
        Long v = pendingMap().get(key);
        return v == null ? 0 : v;
    }

    private static Map<LKey, Long> toMap(IItemList<IAEItemStack> list) {
        Map<LKey, Long> m = new LinkedHashMap<>();
        if (list == null) {
            return m;
        }
        try {
            for (IAEItemStack s : list) {
                if (s == null || s.getStackSize() <= 0) {
                    continue;
                }
                LKey key = Ae.key(s);
                if (key != null) {
                    m.merge(key, s.getStackSize(), Long::sum);
                }
            }
        } catch (RuntimeException e) {
            // a list changed while we read it: keep what we have
        }
        return m;
    }
}
