package com.syhros.ae2declogger.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.server.Plat;

import cpw.mods.fml.common.Loader;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * GregTech 5 (GT5-Unofficial, as in GT: New Horizons) machines, read by reflection only so the jar loads with or
 * without GregTech and across GT versions. Never calls anything that changes a machine ({@code checkRecipe} consumes
 * inputs; it is never touched).
 *
 * <ul>
 * <li>Single-block machines: disabled, no power (stuttering), output full, working, progress, stored EU, tier.</li>
 * <li>Multiblocks: not formed, disabled, maintenance, the last recipe check (no recipe, output full, not enough
 * power...), the shutdown reason, progress, EU in the energy hatches.</li>
 * <li>Hatches (input buses, ME crafting input buffers and their slaves) report their multiblock controller.</li>
 * </ul>
 */
public final class Machines {

    private static final int CONTROLLER_SEARCH_RADIUS = 16;
    private static final long CONTROLLER_CACHE_MS = 5_000;

    private static Boolean gregtech;
    private static Class<?> igte;
    private static Class<?> multiBase;
    private static Class<?> basicMachine;
    private static Class<?> hatch;
    private static final Map<String, Object> MEMBERS = new ConcurrentHashMap<>();
    private static final Map<LPos, CachedController> CONTROLLERS = new HashMap<>();

    private static final class CachedController {
        final LPos controller;
        final long at;

        CachedController(LPos controller, long at) {
            this.controller = controller;
            this.at = at;
        }
    }

    private Machines() {
    }

    private static boolean gt() {
        if (gregtech == null) {
            gregtech = false;
            if (Loader.isModLoaded("gregtech")) {
                try {
                    igte = Class.forName("gregtech.api.interfaces.tileentity.IGregTechTileEntity");
                    multiBase = Class.forName("gregtech.api.metatileentity.implementations.MTEMultiBlockBase");
                    basicMachine = Class.forName("gregtech.api.metatileentity.implementations.MTEBasicMachine");
                    hatch = Class.forName("gregtech.api.metatileentity.implementations.MTEHatch");
                    gregtech = true;
                } catch (ReflectiveOperationException | LinkageError e) {
                    Ae2Declogger.LOG.warn("GregTech is installed but its classes were not found; machines are read "
                            + "as plain inventories: {}", e.toString());
                }
            }
        }
        return gregtech;
    }

    // ---------------------------------------------------------------- reflection helpers

    private static Field field(Class<?> c, String name) {
        String key = c.getName() + "#" + name;
        Object found = MEMBERS.computeIfAbsent(key, k -> {
            for (Class<?> k2 = c; k2 != null && k2 != Object.class; k2 = k2.getSuperclass()) {
                try {
                    Field f = k2.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    // keep looking
                } catch (RuntimeException e) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.FALSE;
        });
        return found instanceof Field ? (Field) found : null;
    }

    private static Method method(Class<?> c, String name) {
        String key = c.getName() + "." + name + "()";
        Object found = MEMBERS.computeIfAbsent(key, k -> {
            for (Class<?> k2 = c; k2 != null && k2 != Object.class; k2 = k2.getSuperclass()) {
                try {
                    Method m = k2.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException e) {
                    // keep looking
                } catch (RuntimeException e) {
                    return Boolean.FALSE;
                }
            }
            try {
                return c.getMethod(name);
            } catch (NoSuchMethodException | RuntimeException e) {
                return Boolean.FALSE;
            }
        });
        return found instanceof Method ? (Method) found : null;
    }

    private static Object get(Object o, String name) {
        if (o == null) {
            return null;
        }
        Field f = field(o.getClass(), name);
        try {
            return f != null ? f.get(o) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object call(Object o, String name) {
        if (o == null) {
            return null;
        }
        Method m = method(o.getClass(), name);
        try {
            return m != null ? m.invoke(o) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean bool(Object o, boolean fallback) {
        return o instanceof Boolean ? (Boolean) o : fallback;
    }

    private static long num(Object o, long fallback) {
        return o instanceof Number ? ((Number) o).longValue() : fallback;
    }

    /** The meta tile entity of a GregTech block entity, or null. */
    private static Object meta(TileEntity te) {
        if (te == null || !gt() || !igte.isInstance(te)) {
            return null;
        }
        return call(te, "getMetaTileEntity");
    }

    // ---------------------------------------------------------------- the facade

    public static MachineInfo inspect(LPos pos) {
        TileEntity te = Plat.tileAt(pos);
        Object mte = meta(te);
        if (mte == null) {
            return null;
        }
        try {
            if (hatch.isInstance(mte) || isCraftingInput(mte)) {
                LPos controller = controllerOf(pos, te, mte);
                if (controller != null) {
                    TileEntity cte = Plat.tileAt(controller);
                    Object cmte = meta(cte);
                    if (cmte != null && multiBase.isInstance(cmte)) {
                        MachineInfo info = multiblock(controller, cte, cmte);
                        return new MachineInfo(info.name, info.status, info.problem, info.detail, info.progress,
                                info.duration, controller, info.energyStored, info.energyCapacity, info.energyUnit,
                                info.tier, info.errors);
                    }
                }
                return null; // a lone hatch: read as a plain inventory
            }
            if (multiBase.isInstance(mte)) {
                return multiblock(pos, te, mte);
            }
            return single(pos, te, mte);
        } catch (RuntimeException | LinkageError e) {
            Ae2Declogger.LOG.debug("Could not read the GregTech machine at {}", pos, e);
            return null;
        }
    }

    public static List<LPos> controllersOf(LPos pos) {
        TileEntity te = Plat.tileAt(pos);
        Object mte = meta(te);
        if (mte == null) {
            return Collections.emptyList();
        }
        LPos c = controllerOf(pos, te, mte);
        return c == null ? Collections.<LPos>emptyList() : Collections.singletonList(c);
    }

    public static boolean isMachine(LPos pos) {
        return meta(Plat.tileAt(pos)) != null;
    }

    /** GregTech machines are shown through their inventory (all GT block entities are inventories). */
    public static boolean fillSections(LPos pos, Set<LKey> patternInputs, int maxSlots,
            List<MachineSnapshot.Section> out) {
        return false;
    }

    /** Names read by reflection, for the self-check ("missing:<name>" when absent). */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        if (!gt()) {
            out.add("gregtech not loaded");
            return out;
        }
        String[][] fields = { { "mMachine", "multi" }, { "mProgresstime", "multi" }, { "mMaxProgresstime", "multi" },
                { "mInputBusses", "multi" }, { "mInputHatches", "multi" }, { "mEnergyHatches", "multi" },
                { "mOutputBlocked", "basic" }, { "mStuttering", "basic" } };
        for (String[] f : fields) {
            Class<?> c = "multi".equals(f[1]) ? multiBase : basicMachine;
            out.add(field(c, f[0]) != null ? f[0] : "missing:" + f[0]);
        }
        for (String m : new String[] { "getRepairStatus", "getIdealStatus" }) {
            out.add(method(multiBase, m) != null ? m : "missing:" + m);
        }
        for (String m : new String[] { "getMetaTileEntity", "isAllowedToWork", "isActive", "getStoredEU",
                "getEUCapacity" }) {
            out.add(method(igte, m) != null ? m : "missing:" + m);
        }
        return out;
    }

    // ---------------------------------------------------------------- machines

    private static LText name(Object mte) {
        Object stack = null;
        Method m = null;
        try {
            m = mte.getClass().getMethod("getStackForm", long.class);
            stack = m.invoke(mte, 1L);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            stack = null;
        }
        LKey key = stack instanceof ItemStack ? LKey.of((ItemStack) stack) : null;
        if (key != null) {
            return LText.item(key);
        }
        Object local = call(mte, "getLocalName");
        return LText.literal(local != null ? local.toString() : mte.getClass().getSimpleName());
    }

    private static String tier(Object mte) {
        Object t = get(mte, "mTier");
        if (!(t instanceof Number)) {
            return "";
        }
        int tier = ((Number) t).intValue();
        for (String cls : new String[] { "gregtech.api.enums.GTValues", "gregtech.api.enums.GT_Values" }) {
            try {
                Object vn = Class.forName(cls).getField("VN").get(null);
                if (vn instanceof String[] && tier >= 0 && tier < ((String[]) vn).length) {
                    return ((String[]) vn)[tier];
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // try the older name
            }
        }
        return "T" + tier;
    }

    private static MachineInfo single(LPos pos, TileEntity te, Object mte) {
        boolean allowed = bool(call(te, "isAllowedToWork"), true);
        boolean active = bool(call(te, "isActive"), false);
        long stored = num(call(te, "getStoredEU"), 0);
        long capacity = num(call(te, "getEUCapacity"), 0);
        int progress = (int) num(get(mte, "mProgresstime"), num(call(te, "getProgress"), -1));
        int duration = (int) num(get(mte, "mMaxProgresstime"), num(call(te, "getMaxProgress"), -1));
        MachineStatus status = active ? MachineStatus.WORKING : MachineStatus.IDLE;
        BlockReason problem = BlockReason.NONE;
        LText detail = null;
        List<LText> errors = new ArrayList<>();
        if (!allowed) {
            status = MachineStatus.SUSPENDED;
            problem = BlockReason.MACHINE_DISABLED;
            detail = LText.tr("ae2declogger.gt.disabled");
        } else if (basicMachine.isInstance(mte) && num(get(mte, "mOutputBlocked"), 0) != 0) {
            status = MachineStatus.WAITING;
            problem = BlockReason.MACHINE_OUTPUT_FULL;
            detail = LText.tr("ae2declogger.gt.output_full");
        } else if (bool(get(mte, "mStuttering"), false)) {
            status = MachineStatus.WAITING;
            problem = BlockReason.MACHINE_NO_POWER;
            detail = LText.tr("ae2declogger.compat.not_enough_energy");
        }
        if (problem.isProblem()) {
            errors.add(detail);
        }
        if (!active || duration <= 0) {
            progress = -1;
            duration = active ? duration : -1;
        }
        return new MachineInfo(name(mte), status, problem, detail, progress, duration, null, stored, capacity,
                capacity > 0 ? "EU" : "", tier(mte), errors);
    }

    private static MachineInfo multiblock(LPos pos, TileEntity te, Object mte) {
        boolean formed = bool(get(mte, "mMachine"), true);
        boolean allowed = bool(call(te, "isAllowedToWork"), true);
        boolean active = bool(call(te, "isActive"), false);
        long repair = num(call(mte, "getRepairStatus"), 0);
        long ideal = num(call(mte, "getIdealStatus"), 0);
        int progress = (int) num(get(mte, "mProgresstime"), -1);
        int duration = (int) num(get(mte, "mMaxProgresstime"), -1);
        Object result = call(mte, "getCheckRecipeResult");
        if (result == null) {
            result = get(mte, "checkRecipeResult");
        }
        Object shutdown = bool(call(te, "wasShutdown"), false) ? call(te, "getLastShutDownReason") : null;

        long stored = 0;
        long capacity = 0;
        Object hatches = get(mte, "mEnergyHatches");
        if (hatches instanceof Collection) {
            for (Object h : (Collection<?>) hatches) {
                Object base = call(h, "getBaseMetaTileEntity");
                stored += num(call(base, "getStoredEU"), 0);
                capacity += num(call(base, "getEUCapacity"), 0);
            }
        }

        MachineStatus status = active ? MachineStatus.WORKING : MachineStatus.IDLE;
        BlockReason problem = BlockReason.NONE;
        LText detail = null;
        List<LText> errors = new ArrayList<>();
        if (!formed) {
            status = MachineStatus.NOT_FORMED;
            problem = BlockReason.MACHINE_NOT_FORMED;
            detail = LText.tr("ae2declogger.gt.not_formed");
        } else if (!allowed) {
            status = MachineStatus.SUSPENDED;
            problem = BlockReason.MACHINE_DISABLED;
            detail = shutdown != null ? message(shutdown) : LText.tr("ae2declogger.gt.disabled");
            BlockReason why = shutdownReason(shutdown);
            if (why != null) {
                problem = why;
            }
        } else if (ideal > 0 && repair < ideal) {
            status = MachineStatus.WAITING;
            problem = BlockReason.MACHINE_MAINTENANCE;
            detail = LText.tr("ae2declogger.gt.maintenance", ideal - repair);
        } else if (!active && result != null && !bool(call(result, "wasSuccessful"), true)) {
            BlockReason why = resultReason(result);
            status = MachineStatus.WAITING;
            problem = why;
            detail = message(result);
        }
        if (problem.isProblem() && detail != null) {
            errors.add(detail);
        }
        if (!active || duration <= 0) {
            progress = -1;
            duration = -1;
        }
        return new MachineInfo(name(mte), status, problem, detail, progress, duration, null, stored, capacity,
                capacity > 0 ? "EU" : "", "", errors);
    }

    /** GregTech's own text for a recipe check result or shutdown reason (already in the server's language). */
    private static LText message(Object msg) {
        Object text = call(msg, "getDisplayString");
        if (text != null && !text.toString().trim().isEmpty()) {
            return LText.literal(text.toString().replaceAll("§.", "").trim());
        }
        Object id = call(msg, "getID");
        return LText.literal(String.valueOf(id));
    }

    private static BlockReason resultReason(Object result) {
        String id = String.valueOf(call(result, "getID"));
        if (id.contains("output_full")) {
            return BlockReason.MACHINE_OUTPUT_FULL;
        }
        if (id.contains("power") || id.contains("voltage") || id.contains("amperage")) {
            return BlockReason.MACHINE_NO_POWER;
        }
        if (id.equals("no_recipe")) {
            return BlockReason.MACHINE_NO_RECIPE;
        }
        return BlockReason.MACHINE_WAITING;
    }

    private static BlockReason shutdownReason(Object shutdown) {
        if (shutdown == null) {
            return null;
        }
        String id = String.valueOf(call(shutdown, "getID"));
        if (id.contains("power")) {
            return BlockReason.MACHINE_NO_POWER;
        }
        if (id.contains("structure")) {
            return BlockReason.MACHINE_NOT_FORMED;
        }
        if (id.contains("repair")) {
            return BlockReason.MACHINE_MAINTENANCE;
        }
        return null;
    }

    // ---------------------------------------------------------------- hatches

    private static boolean isCraftingInput(Object mte) {
        String n = mte.getClass().getName();
        return n.endsWith("MTEHatchCraftingInputME") || n.endsWith("MTEHatchCraftingInputSlave");
    }

    private static final String[] HATCH_LISTS = { "mInputBusses", "mInputHatches", "mDualInputHatches",
            "mOutputBusses", "mOutputHatches", "mEnergyHatches" };

    /** The multiblock controller a hatch belongs to: a loaded controller nearby that lists it. */
    private static LPos controllerOf(LPos pos, TileEntity te, Object mte) {
        long now = System.currentTimeMillis();
        CachedController cached = CONTROLLERS.get(pos);
        if (cached != null && now - cached.at < CONTROLLER_CACHE_MS) {
            return cached.controller;
        }
        if (CONTROLLERS.size() > 2048) {
            CONTROLLERS.clear();
        }
        Object target = mte;
        if (mte.getClass().getName().endsWith("MTEHatchCraftingInputSlave")) {
            Object master = call(mte, "getMaster");
            if (master != null) {
                target = master;
            }
        }
        LPos found = null;
        World w = te.getWorldObj();
        if (w != null && multiBase != null) {
            outer:
            for (int cx = (pos.x - CONTROLLER_SEARCH_RADIUS) >> 4; cx <= (pos.x + CONTROLLER_SEARCH_RADIUS) >> 4; cx++) {
                for (int cz = (pos.z - CONTROLLER_SEARCH_RADIUS) >> 4; cz <= (pos.z + CONTROLLER_SEARCH_RADIUS) >> 4;
                        cz++) {
                    if (!w.getChunkProvider().chunkExists(cx, cz)) {
                        continue;
                    }
                    Chunk chunk = w.getChunkFromChunkCoords(cx, cz);
                    for (Object o : new ArrayList<>(chunk.chunkTileEntityMap.values())) {
                        if (!(o instanceof TileEntity)) {
                            continue;
                        }
                        TileEntity other = (TileEntity) o;
                        if (Math.abs(other.xCoord - pos.x) > CONTROLLER_SEARCH_RADIUS
                                || Math.abs(other.yCoord - pos.y) > CONTROLLER_SEARCH_RADIUS
                                || Math.abs(other.zCoord - pos.z) > CONTROLLER_SEARCH_RADIUS) {
                            continue;
                        }
                        Object cmte = meta(other);
                        if (cmte == null || !multiBase.isInstance(cmte)) {
                            continue;
                        }
                        if (holds(cmte, target)) {
                            found = Plat.posOf(other);
                            break outer;
                        }
                    }
                }
            }
        }
        CONTROLLERS.put(pos, new CachedController(found, now));
        return found;
    }

    private static boolean holds(Object controller, Object hatchMte) {
        for (String list : HATCH_LISTS) {
            Object l = get(controller, list);
            if (l instanceof Collection) {
                for (Object h : (Collection<?>) l) {
                    if (h == hatchMte) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
