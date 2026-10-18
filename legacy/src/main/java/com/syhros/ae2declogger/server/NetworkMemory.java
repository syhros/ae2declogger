package com.syhros.ae2declogger.server;

import com.syhros.ae2declogger.data.LPos;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;

/**
 * What the mod remembers per player, in the player's saved data: the last network they opened the monitor for (used
 * by the hotkey) and the network they get stall alerts for. A network is remembered by the position of one of its
 * blocks.
 */
public final class NetworkMemory {

    private static final String LAST = "last";
    private static final String ALERTS = "alerts";

    private NetworkMemory() {
    }

    public static void rememberLast(Object player, LPos pos) {
        Plat.setPlayerData(player, LAST, encode(pos));
    }

    public static LPos last(Object player) {
        return decode(Plat.getPlayerData(player, LAST));
    }

    /** {@code null} turns alerts off. */
    public static void setAlerts(Object player, LPos pos) {
        Plat.setPlayerData(player, ALERTS, pos == null ? null : encode(pos));
    }

    public static LPos alerts(Object player) {
        return decode(Plat.getPlayerData(player, ALERTS));
    }

    /** Position of a block of the network: the preferred node's, otherwise the first block found on it. */
    public static LPos positionOf(IGrid grid, IGridNode preferred) {
        LPos pos = preferred != null ? Ae.posOf(preferred) : null;
        if (pos != null) {
            return pos;
        }
        try {
            for (IGridNode node : grid.getNodes()) {
                pos = Ae.posOf(node);
                if (pos != null) {
                    return pos;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    static String encode(LPos pos) {
        return pos.dim + "|" + pos.x + "|" + pos.y + "|" + pos.z;
    }

    static LPos decode(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String[] p = s.split("\\|");
        if (p.length != 4) {
            return null;
        }
        try {
            return new LPos(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
