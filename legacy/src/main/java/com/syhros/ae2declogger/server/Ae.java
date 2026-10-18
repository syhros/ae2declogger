package com.syhros.ae2declogger.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;

import net.minecraft.tileentity.TileEntity;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.DimensionalCoord;
import appeng.container.AEBaseContainer;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
//#if MC >= 1.12
import appeng.api.util.AEPartLocation;
//#else
//$$ import net.minecraftforge.common.util.ForgeDirection;
//#endif
//#if MC >= 1.16
import appeng.hooks.ticking.TickHandler;
import appeng.tile.crafting.CraftingTileEntity;
import appeng.tile.networking.ControllerTileEntity;
//#else
//$$ import appeng.hooks.TickHandler;
//$$ import appeng.tile.crafting.TileCraftingTile;
//$$ import appeng.tile.networking.TileController;
//#endif
//#if MC < 1.16
//$$ import appeng.api.config.LockCraftingMode;
//#endif

/**
 * Everything the Declogger reads from AE2 that differs between AE2 8 (1.16.5), AE2 Extended Life (1.12.2) and AE2
 * Unofficial (GTNH 1.7.10). Read-only: never pushes patterns, starts or cancels jobs.
 */
public final class Ae {

    private Ae() {
    }

    // ---------------------------------------------------------------- stacks and patterns

    public static LKey key(IAEItemStack stack) {
        if (stack == null) {
            return null;
        }
        //#if MC >= 1.12
        return LKey.of(stack.createItemStack());
        //#else
        //$$ return LKey.of(stack.getItemStack());
        //#endif
    }

    /** The pattern's outputs, each item once with its total amount. */
    static List<IAEItemStack> outputs(ICraftingPatternDetails pattern) {
        try {
            //#if MC >= 1.16
            return nonNull(pattern.getOutputs());
            //#else
            //$$ return nonNull(Arrays.asList(pattern.getCondensedOutputs()));
            //#endif
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /** The pattern's inputs, each item once with its total amount. */
    static List<IAEItemStack> inputs(ICraftingPatternDetails pattern) {
        try {
            //#if MC >= 1.16
            return nonNull(pattern.getInputs());
            //#else
            //$$ return nonNull(Arrays.asList(pattern.getCondensedInputs()));
            //#endif
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    static boolean canSubstitute(ICraftingPatternDetails pattern) {
        try {
            return pattern.canSubstitute();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<IAEItemStack> nonNull(List<IAEItemStack> list) {
        List<IAEItemStack> out = new ArrayList<>(list.size());
        for (IAEItemStack s : list) {
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- grid

    static CraftingGridCache crafting(IGrid grid) {
        Object cache = grid.getCache(ICraftingGrid.class);
        return cache instanceof CraftingGridCache ? (CraftingGridCache) cache : null;
    }

    static IEnergyGrid energy(IGrid grid) {
        return grid.getCache(IEnergyGrid.class);
    }

    private static final Field CLUSTERS = field(CraftingGridCache.class, "craftingCPUClusters");
    private static final Field METHODS = field(CraftingGridCache.class, "craftingMethods");

    /**
     * Every crafting CPU on the network, including ones that are offline (no power or channel): AE2 leaves those out
     * of its own list, so a CPU that loses power mid-job would look like its job ended.
     */
    static List<CraftingCPUCluster> allCpus(IGrid grid) {
        List<CraftingCPUCluster> result = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        CraftingGridCache cache = crafting(grid);
        if (cache == null) {
            return result;
        }
        try {
            Object raw = CLUSTERS != null ? CLUSTERS.get(cache) : null;
            if (raw instanceof Collection) {
                for (Object o : new ArrayList<>((Collection<?>) raw)) {
                    if (o instanceof CraftingCPUCluster && !((CraftingCPUCluster) o).isDestroyed() && seen.add(o)) {
                        result.add((CraftingCPUCluster) o);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not list all crafting CPUs", e);
        }
        try {
            for (Object o : cache.getCpus()) {
                if (o instanceof CraftingCPUCluster && seen.add(o)) {
                    result.add((CraftingCPUCluster) o);
                }
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not list crafting CPUs", e);
        }
        return result;
    }

    static boolean hasBusyCpu(IGrid grid) {
        CraftingGridCache cache = crafting(grid);
        if (cache == null) {
            return false;
        }
        for (Object o : cache.getCpus()) {
            if (o instanceof CraftingCPUCluster && ((CraftingCPUCluster) o).isBusy()) {
                return true;
            }
        }
        return false;
    }

    /** The interfaces (and other pattern holders) that have {@code pattern}. Read-only lookup. */
    static List<ICraftingMedium> mediums(IGrid grid, ICraftingPatternDetails pattern) {
        CraftingGridCache cache = crafting(grid);
        if (cache == null) {
            return Collections.emptyList();
        }
        try {
            List<ICraftingMedium> list = cache.getMediums(pattern);
            return list == null ? Collections.<ICraftingMedium>emptyList() : new ArrayList<>(list);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /** Every pattern on the network (the keys of AE2's pattern table). */
    static List<ICraftingPatternDetails> allPatterns(IGrid grid) {
        CraftingGridCache cache = crafting(grid);
        List<ICraftingPatternDetails> out = new ArrayList<>();
        if (cache == null || METHODS == null) {
            return out;
        }
        try {
            Object raw = METHODS.get(cache);
            if (raw instanceof Map) {
                for (Object k : new ArrayList<>(((Map<?, ?>) raw).keySet())) {
                    if (k instanceof ICraftingPatternDetails) {
                        out.add((ICraftingPatternDetails) k);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not list the network's patterns", e);
        }
        return out;
    }

    /** Every network AE2 knows of on this server. */
    @SuppressWarnings("unchecked")
    static Iterable<IGrid> allGrids() {
        try {
            //#if MC >= 1.16
            Iterable<?> grids = TickHandler.instance().getGridList();
            //#else
            //$$ Iterable<?> grids = TickHandler.INSTANCE.getGridList();
            //#endif
            return (Iterable<IGrid>) grids;
        } catch (RuntimeException | LinkageError e) {
            return Collections.emptyList();
        }
    }

    // ---------------------------------------------------------------- nodes and positions

    /** The node of an AE2 block entity on {@code side} (0-5), any side if that one has none. */
    static IGridNode nodeOf(TileEntity te, int side) {
        if (!(te instanceof IGridHost)) {
            return null;
        }
        IGridHost host = (IGridHost) te;
        IGridNode n = side >= 0 ? safeNode(host, side) : null;
        for (int s = -1; n == null && s < 6; s++) {
            n = safeNode(host, s);
        }
        return n;
    }

    private static IGridNode safeNode(IGridHost host, int side) {
        try {
            //#if MC >= 1.12
            return host.getGridNode(side < 0 ? AEPartLocation.INTERNAL : AEPartLocation.fromOrdinal(side));
            //#else
            //$$ return host.getGridNode(side < 0 ? ForgeDirection.UNKNOWN : ForgeDirection.getOrientation(side));
            //#endif
        } catch (RuntimeException e) {
            return null;
        }
    }

    static IGrid gridOf(IGridNode node) {
        try {
            return node == null ? null : node.getGrid();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Where a node's block is, or null for nodes without a block (wireless terminals...). */
    static LPos posOf(IGridNode node) {
        try {
            DimensionalCoord c = node.getGridBlock().getLocation();
            if (c == null || c.getWorld() == null) {
                return null;
            }
            return new LPos(Plat.dimOf(c.getWorld()), c.x, c.y, c.z);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** ME controllers and crafting CPU blocks: what a network is recognised by across rebuilds. */
    static boolean isAnchor(IGridNode node) {
        Object m;
        try {
            m = node.getMachine();
        } catch (RuntimeException e) {
            return false;
        }
        //#if MC >= 1.16
        return m instanceof ControllerTileEntity || m instanceof CraftingTileEntity;
        //#else
        //$$ return m instanceof TileController || m instanceof TileCraftingTile;
        //#endif
    }

    static boolean isActive(IGridNode node) {
        try {
            return node.isActive();
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ---------------------------------------------------------------- open AE2 screens

    /** The node behind an open AE2 screen (terminal, wireless terminal, crafting CPU or status screen), or null. */
    static IGridNode menuNode(Object container) {
        if (!(container instanceof AEBaseContainer)) {
            return null;
        }
        Object target;
        try {
            target = ((AEBaseContainer) container).getTarget();
        } catch (RuntimeException e) {
            return null;
        }
        if (target instanceof IActionHost) {
            try {
                IGridNode n = ((IActionHost) target).getActionableNode();
                if (n != null) {
                    return n;
                }
            } catch (RuntimeException e) {
                // fall through
            }
        }
        if (target instanceof IGridHost) {
            return safeNode((IGridHost) target, -1);
        }
        return null;
    }

    /** Per container class: the field holding its crafting CPU, or {@link Boolean#FALSE} when it has none. */
    private static final Map<Class<?>, Object> CPU_FIELDS = new ConcurrentHashMap<>();

    /** The CPU an AE2 crafting CPU screen shows, or null for other screens. */
    static CraftingCPUCluster menuCpu(Object container) {
        Field f = cpuField(container);
        if (f == null) {
            return null;
        }
        try {
            Object v = f.get(container);
            return v instanceof CraftingCPUCluster ? (CraftingCPUCluster) v : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** True for AE2's crafting CPU and crafting status screens (they hold a CPU field). */
    static boolean isCraftingCpuMenu(Object container) {
        return cpuField(container) != null;
    }

    private static Field cpuField(Object container) {
        if (!(container instanceof AEBaseContainer)) {
            return null;
        }
        Object found = CPU_FIELDS.computeIfAbsent(container.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field fd : k.getDeclaredFields()) {
                    if (CraftingCPUCluster.class.isAssignableFrom(fd.getType())) {
                        try {
                            fd.setAccessible(true);
                            return fd;
                        } catch (RuntimeException e) {
                            return Boolean.FALSE;
                        }
                    }
                }
            }
            return Boolean.FALSE;
        });
        return found instanceof Field ? (Field) found : null;
    }

    // ---------------------------------------------------------------- pattern holders

    /** Where a pattern holder is and where it pushes. */
    static final class Holder {
        final ICraftingMedium medium;
        final LPos pos;
        /** Sides (0-5) it pushes into; empty when unknown. */
        final List<Integer> sides;
        final boolean online;
        final boolean blocking;
        /** Crafting lock name ("NONE", "LOCK_UNTIL_PULSE"...), "NONE" where the version has no lock. */
        final String lock;
        /** True for AE2 interfaces (whose targets are the neighbours on {@link #sides}). */
        final boolean iface;

        Holder(ICraftingMedium medium, LPos pos, List<Integer> sides, boolean online, boolean blocking, String lock,
                boolean iface) {
            this.medium = medium;
            this.pos = pos;
            this.sides = sides;
            this.online = online;
            this.blocking = blocking;
            this.lock = lock;
            this.iface = iface;
        }
    }

    private static final Field GRID_PROXY = field(DualityInterface.class, "gridProxy");

    /** Null when the holder's block cannot be found. */
    static Holder holder(ICraftingMedium medium) {
        if (medium instanceof DualityInterface) {
            DualityInterface duality = (DualityInterface) medium;
            Object hostObj;
            try {
                hostObj = duality.getHost();
            } catch (RuntimeException e) {
                hostObj = null;
            }
            TileEntity te = null;
            List<Integer> sides = new ArrayList<>();
            if (hostObj instanceof IInterfaceHost) {
                IInterfaceHost host = (IInterfaceHost) hostObj;
                try {
                    te = host.getTileEntity();
                    for (Object d : host.getTargets()) {
                        sides.add(sideOf(d));
                    }
                } catch (RuntimeException e) {
                    // keep what we have
                }
            }
            if (te == null) {
                try {
                    te = duality.getTile();
                } catch (RuntimeException e) {
                    te = null;
                }
            }
            LPos pos = te != null ? Plat.posOf(te) : null;
            if (pos == null) {
                return null;
            }
            boolean blocking = false;
            try {
                blocking = duality.getConfigManager().getSetting(Settings.BLOCK) == YesNo.YES;
            } catch (RuntimeException e) {
                blocking = false;
            }
            String lock = "NONE";
            //#if MC < 1.16
            //$$ try {
            //$$     LockCraftingMode mode = duality.getCraftingLockedReason();
            //$$     lock = mode == null ? "NONE" : mode.name();
            //$$ } catch (RuntimeException | LinkageError e) {
            //$$     lock = "NONE";
            //$$ }
            //#endif
            return new Holder(medium, pos, sides, proxyActive(duality), blocking, lock, true);
        }
        // Another mod's pattern holder (GregTech's ME crafting input buffer...): find its block by method name.
        TileEntity te = tileOf(medium);
        LPos pos = te != null ? Plat.posOf(te) : null;
        return pos == null ? null
                : new Holder(medium, pos, Collections.<Integer>emptyList(), true, false, "NONE", false);
    }

    private static int sideOf(Object direction) {
        //#if MC >= 1.16
        return ((net.minecraft.util.Direction) direction).get3DDataValue();
        //#elseif MC >= 1.12
        //$$ return ((net.minecraft.util.EnumFacing) direction).getIndex();
        //#else
        //$$ return ((ForgeDirection) direction).ordinal();
        //#endif
    }

    /** Whether the interface's node has power and a channel; true when unknown. */
    private static boolean proxyActive(DualityInterface duality) {
        if (GRID_PROXY == null) {
            return true;
        }
        try {
            Object proxy = GRID_PROXY.get(duality);
            if (proxy == null) {
                return true;
            }
            Method m = proxy.getClass().getMethod("isActive");
            Object r = m.invoke(proxy);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private static final String[] TILE_METHODS = { "getTile", "getTileEntity", "getBaseMetaTileEntity", "getHost" };

    /** The block entity behind another mod's pattern holder, found through common method names. */
    static TileEntity tileOf(Object holder) {
        for (String name : TILE_METHODS) {
            try {
                Method m = holder.getClass().getMethod(name);
                Object r = m.invoke(holder);
                if (r instanceof TileEntity) {
                    return (TileEntity) r;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // try the next name
            }
        }
        return holder instanceof TileEntity ? (TileEntity) holder : null;
    }

    // ---------------------------------------------------------------- GTNH: AE2's own push reasons

    /**
     * AE2 Unofficial (GTNH) records why it did not push a pattern ({@code ScheduledReason}). Returns its name, or
     * null when there is none or the version has no such thing.
     */
    static String scheduledReason(CraftingCPUCluster cpu, IAEItemStack stack) {
        //#if MC < 1.12
        //$$ try {
        //$$     Object r = cpu.getScheduledReason(stack);
        //$$     return r == null ? null : ((Enum<?>) r).name();
        //$$ } catch (RuntimeException | LinkageError e) {
        //$$     return null;
        //$$ }
        //#else
        return null;
        //#endif
    }

    // ---------------------------------------------------------------- reflection

    static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Names this file reads by reflection; missing ones are listed as "missing:<name>". */
    public static List<String> names() {
        List<String> out = new ArrayList<>(CpuAccess.names());
        out.add(CLUSTERS != null ? "craftingCPUClusters" : "missing:craftingCPUClusters");
        out.add(METHODS != null ? "craftingMethods" : "missing:craftingMethods");
        out.add(GRID_PROXY != null ? "gridProxy" : "missing:gridProxy");
        return out;
    }
}
