package com.syhros.ae2declogger.server;

import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;

import net.minecraft.tileentity.TileEntity;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;

/**
 * Where a monitor gets its network from: a block the player looked at or opened last, or the node behind an open AE2
 * terminal. Resolved again on every update so a rebuilt network is picked up.
 */
public final class GridSource {

    /** A network, or why there is none. */
    public static final class Resolved {
        public final IGrid grid;
        public final LText error;

        private Resolved(IGrid grid, LText error) {
            this.grid = grid;
            this.error = error;
        }

        static Resolved ok(IGrid grid) {
            return new Resolved(grid, null);
        }

        static Resolved fail(String key, Object... args) {
            return new Resolved(null, LText.tr(key, args));
        }
    }

    final LPos link;
    final int side;
    final IGridNode node;
    final LText title;

    private GridSource(LPos link, int side, IGridNode node, LText title) {
        this.link = link;
        this.side = side;
        this.node = node;
        this.title = title;
    }

    public static GridSource ofLink(LPos link, int side, LText title) {
        return new GridSource(link, side, null, title);
    }

    public static GridSource ofNode(IGridNode node, LText title) {
        return new GridSource(null, -1, node, title);
    }

    public Resolved resolve(Object player) {
        if (node != null) {
            IGrid grid = Ae.gridOf(node);
            return grid != null ? Resolved.ok(grid) : Resolved.fail("ae2declogger.error.network_gone");
        }
        if (link == null) {
            return Resolved.fail("ae2declogger.error.not_linked");
        }
        if (!Plat.dimensionExists(link.dim)) {
            return Resolved.fail("ae2declogger.error.dimension_missing", link.dim);
        }
        boolean sameDimension = Plat.playerDim(player).equals(link.dim);
        if (!sameDimension && !DeclogConfig.allowCrossDimension()) {
            return Resolved.fail("ae2declogger.error.other_dimension");
        }
        int maxDistance = DeclogConfig.maxLinkDistance();
        if (maxDistance > 0) {
            double[] p = Plat.playerPos(player);
            if (!sameDimension || link.distSq(p[0], p[1], p[2]) > (double) maxDistance * maxDistance) {
                return Resolved.fail("ae2declogger.error.too_far", maxDistance);
            }
        }
        return resolveAt(link, side);
    }

    /** Finds the network of the AE2 block at {@code pos}. */
    public static Resolved resolveAt(LPos pos, int side) {
        if (!Plat.isLoaded(pos)) {
            return Resolved.fail("ae2declogger.error.unloaded", pos.x, pos.y, pos.z);
        }
        TileEntity te = Plat.tileAt(pos);
        if (!(te instanceof appeng.api.networking.IGridHost)) {
            return Resolved.fail("ae2declogger.error.no_host", pos.x, pos.y, pos.z);
        }
        IGridNode found = Ae.nodeOf(te, side);
        if (found == null) {
            return Resolved.fail("ae2declogger.error.no_node");
        }
        IGrid grid = Ae.gridOf(found);
        return grid != null ? Resolved.ok(grid) : Resolved.fail("ae2declogger.error.no_node");
    }
}
