package com.syhros.ae2declogger.server;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.config.DeclogConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;

/**
 * Where a monitor gets its network from: a block the Declogger was linked to, or the node behind an open AE2
 * terminal. Resolved again on every update so a rebuilt network is picked up.
 */
public record GridSource(@Nullable GlobalPos link, @Nullable Direction side, @Nullable IGridNode node,
        Component title) {

    public record Resolved(@Nullable IGrid grid, @Nullable Component error) {
        static Resolved ok(IGrid grid) {
            return new Resolved(grid, null);
        }

        static Resolved fail(String key, Object... args) {
            return new Resolved(null, Component.translatable(key, args));
        }
    }

    public static GridSource ofLink(GlobalPos link, @Nullable Direction side, Component title) {
        return new GridSource(link, side, null, title);
    }

    public static GridSource ofNode(IGridNode node, Component title) {
        return new GridSource(null, null, node, title);
    }

    public Resolved resolve(ServerPlayer player) {
        if (node != null) {
            try {
                IGrid grid = node.getGrid();
                return grid != null ? Resolved.ok(grid) : Resolved.fail("ae2declogger.error.network_gone");
            } catch (RuntimeException e) {
                return Resolved.fail("ae2declogger.error.network_gone");
            }
        }
        if (link == null) {
            return Resolved.fail("ae2declogger.error.not_linked");
        }
        ServerLevel level = player.server.getLevel(link.dimension());
        if (level == null) {
            return Resolved.fail("ae2declogger.error.dimension_missing", link.dimension().location().toString());
        }
        boolean sameDimension = Mc.level(player).dimension().equals(link.dimension());
        if (!sameDimension && !DeclogConfig.COMMON.allowCrossDimension.get()) {
            return Resolved.fail("ae2declogger.error.other_dimension");
        }
        int maxDistance = DeclogConfig.COMMON.maxLinkDistance.get();
        if (maxDistance > 0) {
            BlockPos p = link.pos();
            if (!sameDimension || player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)
                    > (double) maxDistance * maxDistance) {
                return Resolved.fail("ae2declogger.error.too_far", maxDistance);
            }
        }
        return resolveAt(level, link.pos(), side);
    }

    /** Finds the network of the AE2 block at {@code pos}. */
    public static Resolved resolveAt(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        if (!level.isLoaded(pos)) {
            return Resolved.fail("ae2declogger.error.unloaded", pos.getX(), pos.getY(), pos.getZ());
        }
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host == null) {
            return Resolved.fail("ae2declogger.error.no_host", pos.getX(), pos.getY(), pos.getZ());
        }
        IGridNode found = findNode(host, side);
        if (found == null) {
            return Resolved.fail("ae2declogger.error.no_node");
        }
        try {
            IGrid grid = found.getGrid();
            return grid != null ? Resolved.ok(grid) : Resolved.fail("ae2declogger.error.no_node");
        } catch (RuntimeException e) {
            return Resolved.fail("ae2declogger.error.no_node");
        }
    }

    @Nullable
    static IGridNode findNode(IInWorldGridNodeHost host, @Nullable Direction side) {
        if (side != null) {
            IGridNode n = safeNode(host, side);
            if (n != null) {
                return n;
            }
        }
        for (Direction d : Direction.values()) {
            IGridNode n = safeNode(host, d);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    @Nullable
    private static IGridNode safeNode(IInWorldGridNodeHost host, Direction side) {
        try {
            return host.getGridNode(side);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
