package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.compat.gtceu.GtCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.InWorldGridNode;
import appeng.parts.AEBasePart;

/**
 * Finds where each crafting provider on a network sits and which blocks it pushes into. Built lazily, at most once
 * per analysis.
 */
final class ProviderIndex {

    /**
     * @param targets blocks this provider pushes into (adjacent machines, or the multiblock controller for a GT
     *                pattern buffer)
     */
    record ProviderInfo(ICraftingProvider provider, ServerLevel level, BlockPos pos, List<BlockPos> targets,
            @Nullable PatternProviderLogic logic, boolean online) {
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
            PatternProviderLogic logic = null;
            Object owner = node.getOwner();
            if (owner instanceof PatternProviderLogicHost host) {
                logic = host.getLogic();
                for (Direction d : host.getTargets()) {
                    BlockPos target = pos.relative(d);
                    if (canReceive(grid, level, target, d.getOpposite())) {
                        targets.add(target);
                    }
                }
            } else if (provider instanceof PatternProviderLogic ppl) {
                logic = ppl;
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
            index.byProvider.put(provider, new ProviderInfo(provider, level, pos, List.copyOf(targets), logic, online));
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
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).isPresent()
                || be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).isPresent()
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

    int size() {
        return byProvider.size();
    }
}
