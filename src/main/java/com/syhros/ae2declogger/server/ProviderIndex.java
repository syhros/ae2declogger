package com.syhros.ae2declogger.server;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.compat.gtceu.GtCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.config.LockCraftingMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.GenericStack;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.InWorldGridNode;
import appeng.parts.AEBasePart;

/**
 * Finds where each crafting provider on a network sits and which blocks it pushes into. Built lazily, at most once
 * per analysis.
 *
 * <p>
 * AE2's own pattern providers are read directly. Add-on providers that copy AE2's design under their own classes
 * (Advanced AE's advanced pattern providers, for one) are read through the same method names by reflection:
 * {@code getTargets()} and {@code getLogic()} on the block or part, {@code isBlocking()},
 * {@code getCraftingLockedReason()} and {@code getUnlockStack()} on the logic. A provider that offers none of these
 * is assumed to push into every side (a part: the side it faces), as AE2's provider does by default.
 */
final class ProviderIndex {

    /**
     * @param targets blocks this provider pushes into (adjacent machines, or the multiblock controller for a GT
     *                pattern buffer)
     * @param state   blocking mode and crafting lock, when the provider exposes them
     */
    record ProviderInfo(ICraftingProvider provider, ServerLevel level, BlockPos pos, List<BlockPos> targets,
            @Nullable ProviderState state, boolean online) {
    }

    /** Blocking mode and crafting lock of a pattern provider. */
    interface ProviderState {
        LockCraftingMode lockReason();

        boolean blocking();

        @Nullable
        GenericStack unlockStack();

        static ProviderState of(PatternProviderLogic logic) {
            return new ProviderState() {
                @Override
                public LockCraftingMode lockReason() {
                    return logic.getCraftingLockedReason();
                }

                @Override
                public boolean blocking() {
                    return logic.isBlocking();
                }

                @Override
                @Nullable
                public GenericStack unlockStack() {
                    return logic.getUnlockStack();
                }
            };
        }
    }

    private final Map<ICraftingProvider, ProviderInfo> byProvider = new IdentityHashMap<>();
    private final Map<IPatternDetails, List<ICraftingProvider>> byPattern = new HashMap<>();

    private ProviderIndex() {
    }

    static ProviderIndex build(IGrid grid) {
        ProviderIndex index = new ProviderIndex();
        for (IGridNode node : grid.getNodes()) {
            ICraftingProvider provider;
            try {
                provider = node.getService(ICraftingProvider.class);
            } catch (Throwable t) {
                continue;
            }
            if (provider == null) {
                continue;
            }
            ServerLevel level = node.getLevel();
            BlockPos pos = locate(node);
            if (level == null || pos == null) {
                continue;
            }
            List<BlockPos> targets = new ArrayList<>();
            ProviderState state = null;
            Collection<Direction> sides = List.of();
            Object owner = node.getOwner();
            if (owner instanceof PatternProviderLogicHost host) {
                state = ProviderState.of(host.getLogic());
                sides = host.getTargets();
            } else if (provider instanceof PatternProviderLogic ppl) {
                state = ProviderState.of(ppl);
            } else {
                AddonHost addon = AddonHost.of(owner, provider);
                state = addon.state();
                sides = addon.sides(owner);
            }
            for (Direction d : sides) {
                BlockPos target = pos.relative(d);
                if (canReceive(grid, level, target, d.getOpposite())) {
                    targets.add(target);
                }
            }
            if (targets.isEmpty()) {
                // GregTech ME pattern buffers live inside the multiblock; the controller is the "machine".
                targets.addAll(GtCompat.controllersOf(level, pos));
            }
            boolean online;
            try {
                online = node.isActive();
            } catch (RuntimeException e) {
                online = true;
            }
            index.byProvider.put(provider, new ProviderInfo(provider, level, pos, List.copyOf(targets), state, online));
            try {
                for (IPatternDetails pattern : provider.getAvailablePatterns()) {
                    if (pattern != null) {
                        index.byPattern.computeIfAbsent(pattern, k -> new ArrayList<>(2)).add(provider);
                    }
                }
            } catch (RuntimeException e) {
                // a provider that cannot list its patterns is simply not matched
            }
        }
        return index;
    }

    /**
     * Same idea as AE2's own target search: a neighbour counts if it is a crafting machine (molecular assembler),
     * has an item or fluid inventory on the touching side, or is a GregTech machine. Cables, crafting storage and
     * other AE2 blocks without an inventory are skipped.
     */
    static boolean canReceive(IGrid grid, ServerLevel level, BlockPos target, Direction side) {
        if (!level.isLoaded(target)) {
            return true; // cannot tell; keep it so the user sees the unloaded machine
        }
        BlockEntity be = level.getBlockEntity(target);
        if (be == null) {
            return false;
        }
        // AE2 never pushes into another pattern provider, or into an interface on the same network.
        IInWorldGridNodeHost nodeHost = GridHelper.getNodeHost(level, target);
        if (nodeHost != null) {
            try {
                IGridNode other = nodeHost.getGridNode(side);
                if (other != null) {
                    Object owner = other.getOwner();
                    if (owner instanceof PatternProviderLogicHost
                            || owner instanceof InterfaceLogicHost && grid.equals(other.getGrid())) {
                        return false;
                    }
                }
            } catch (RuntimeException ignored) {
                // treat as a normal block
            }
        }
        try {
            if (ICraftingMachine.of(be, side) != null) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // fall through to the capability checks
        }
        return Caps.items(be, side) != null
                || Caps.fluids(be, side) != null
                || GtCompat.inspect(level, target) != null;
    }

    @Nullable
    private static BlockPos locate(IGridNode node) {
        if (node instanceof InWorldGridNode inWorld) {
            return inWorld.getLocation();
        }
        Object owner = node.getOwner();
        if (owner instanceof BlockEntity be) {
            return be.getBlockPos();
        }
        if (owner instanceof AEBasePart part && part.getHost() != null) {
            return part.getHost().getLocation().getPos();
        }
        return null;
    }

    /**
     * Every provider on the network (online or not) that holds {@code pattern}. Built from the providers' own pattern
     * lists instead of AE2's provider lookup, because iterating that lookup moves AE2's round-robin position.
     */
    List<ICraftingProvider> providersFor(IPatternDetails pattern) {
        return byPattern.getOrDefault(pattern, List.of());
    }

    @Nullable
    ProviderInfo get(ICraftingProvider provider) {
        return byProvider.get(provider);
    }

    // ---------------------------------------------------------------- add-on providers

    /** Visible for game tests: "sides=[...] blocking=... lock=..." for an add-on provider's block or part. */
    static String describeAddon(Object owner, ICraftingProvider provider) {
        AddonHost addon = AddonHost.of(owner, provider);
        ProviderState state = addon.state();
        List<String> sides = new ArrayList<>();
        for (Direction d : addon.sides(owner)) {
            sides.add(d.getName());
        }
        java.util.Collections.sort(sides);
        return "sides=" + sides + " blocking=" + (state != null && state.blocking()) + " lock="
                + (state != null ? state.lockReason() : "none");
    }

    private record Methods(@Nullable Method getTargets, @Nullable Method getLogic) {
    }

    private record StateMethods(@Nullable Method isBlocking, @Nullable Method lockReason, @Nullable Method unlock) {
    }

    private static final Map<Class<?>, Methods> HOST_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<StateMethods>> STATE_METHODS = new ConcurrentHashMap<>();

    /** An add-on pattern provider read through AE2's method names. */
    private record AddonHost(@Nullable Set<Direction> targets, @Nullable ProviderState state) {

        static AddonHost of(@Nullable Object owner, ICraftingProvider provider) {
            Set<Direction> targets = null;
            Object logic = null;
            if (owner != null) {
                Methods m = HOST_METHODS.computeIfAbsent(owner.getClass(), c -> new Methods(
                        noArg(c, "getTargets", Set.class), noArg(c, "getLogic", Object.class)));
                targets = call(m.getTargets(), owner) instanceof Set<?> set ? directions(set) : null;
                logic = call(m.getLogic(), owner);
            }
            if (logic == null) {
                logic = provider;
            }
            return new AddonHost(targets, stateOf(logic));
        }

        /** The sides it pushes into; without a target list, every side (a part: the side it faces). */
        Collection<Direction> sides(@Nullable Object owner) {
            if (targets != null) {
                return targets;
            }
            if (owner instanceof AEBasePart part) {
                return part.getSide() != null ? List.of(part.getSide()) : List.of();
            }
            return EnumSet.allOf(Direction.class);
        }

        private static Set<Direction> directions(Set<?> set) {
            EnumSet<Direction> result = EnumSet.noneOf(Direction.class);
            for (Object o : set) {
                if (o instanceof Direction d) {
                    result.add(d);
                }
            }
            return result;
        }

        @Nullable
        private static ProviderState stateOf(Object logic) {
            Optional<StateMethods> found = STATE_METHODS.computeIfAbsent(logic.getClass(), c -> {
                StateMethods m = new StateMethods(noArg(c, "isBlocking", boolean.class),
                        noArg(c, "getCraftingLockedReason", LockCraftingMode.class),
                        noArg(c, "getUnlockStack", GenericStack.class));
                return m.isBlocking() == null && m.lockReason() == null ? Optional.empty() : Optional.of(m);
            });
            if (found.isEmpty()) {
                return null;
            }
            StateMethods m = found.get();
            return new ProviderState() {
                @Override
                public LockCraftingMode lockReason() {
                    return call(m.lockReason(), logic) instanceof LockCraftingMode mode ? mode : LockCraftingMode.NONE;
                }

                @Override
                public boolean blocking() {
                    return call(m.isBlocking(), logic) instanceof Boolean b && b;
                }

                @Override
                @Nullable
                public GenericStack unlockStack() {
                    return call(m.unlock(), logic) instanceof GenericStack stack ? stack : null;
                }
            };
        }
    }

    /** A public no-argument method returning {@code type} (or a subtype), or null. */
    @Nullable
    private static Method noArg(Class<?> owner, String name, Class<?> type) {
        try {
            Method m = owner.getMethod(name);
            if (!type.isAssignableFrom(m.getReturnType())) {
                return null;
            }
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Object call(@Nullable Method m, Object target) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    int size() {
        return byProvider.size();
    }
}
