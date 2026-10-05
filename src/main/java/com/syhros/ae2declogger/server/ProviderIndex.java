package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.compat.gtceu.GtCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
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
            @Nullable PatternProviderLogic logic) {
    }

    private final Map<ICraftingProvider, ProviderInfo> byProvider = new IdentityHashMap<>();

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
                    targets.add(pos.relative(d));
                }
            } else if (provider instanceof PatternProviderLogic ppl) {
                logic = ppl;
            }
            if (targets.isEmpty()) {
                // GregTech ME pattern buffers live inside the multiblock; the controller is the "machine".
                targets.addAll(GtCompat.controllersOf(level, pos));
            }
            index.byProvider.put(provider, new ProviderInfo(provider, level, pos, List.copyOf(targets), logic));
        }
        return index;
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

    @Nullable
    ProviderInfo get(ICraftingProvider provider) {
        return byProvider.get(provider);
    }

    int size() {
        return byProvider.size();
    }
}
