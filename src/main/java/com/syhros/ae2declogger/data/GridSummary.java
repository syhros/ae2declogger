package com.syhros.ae2declogger.data;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * Header line of the monitor: which network, how many CPUs, power.
 *
 * @param serverTimeMs server wall clock when this was built, used by the client to turn timestamps into durations
 */
public record GridSummary(Component networkName, int cpus, int busyCpus, double storedPower, double maxPower,
        boolean powered, long serverTimeMs, boolean canTeleport) {

    public void write(FriendlyByteBuf buf) {
        buf.writeComponent(networkName);
        buf.writeVarInt(cpus);
        buf.writeVarInt(busyCpus);
        buf.writeDouble(storedPower);
        buf.writeDouble(maxPower);
        buf.writeBoolean(powered);
        buf.writeLong(serverTimeMs);
        buf.writeBoolean(canTeleport);
    }

    public static GridSummary read(FriendlyByteBuf buf) {
        return new GridSummary(buf.readComponent(), buf.readVarInt(), buf.readVarInt(), buf.readDouble(),
                buf.readDouble(), buf.readBoolean(), buf.readLong(), buf.readBoolean());
    }
}
