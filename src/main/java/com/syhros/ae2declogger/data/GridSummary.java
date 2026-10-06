package com.syhros.ae2declogger.data;

import com.syhros.ae2declogger.network.Bufs;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * Header line of the monitor: which network, how many CPUs, power.
 *
 * @param serverTimeMs server wall clock when this was built, used by the client to turn timestamps into durations
 */
public record GridSummary(Component networkName, int cpus, int busyCpus, double storedPower, double maxPower,
        boolean powered, long serverTimeMs) {

    public void write(FriendlyByteBuf buf) {
        Bufs.writeComponent(buf, networkName);
        buf.writeVarInt(cpus);
        buf.writeVarInt(busyCpus);
        buf.writeDouble(storedPower);
        buf.writeDouble(maxPower);
        buf.writeBoolean(powered);
        buf.writeLong(serverTimeMs);
    }

    public static GridSummary read(FriendlyByteBuf buf) {
        return new GridSummary(Bufs.readComponent(buf), buf.readVarInt(), buf.readVarInt(), buf.readDouble(),
                buf.readDouble(), buf.readBoolean(), buf.readLong());
    }
}
