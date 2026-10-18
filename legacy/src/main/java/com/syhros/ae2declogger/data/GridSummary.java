package com.syhros.ae2declogger.data;

import com.syhros.ae2declogger.net.Buf;

/** Header line of the monitor: which network, how many CPUs, power. */
public final class GridSummary {

    public final LText networkName;
    public final int cpus;
    public final int busyCpus;
    public final double storedPower;
    public final double maxPower;
    public final boolean powered;
    /** Server wall clock when this was built; the client turns timestamps into durations with it. */
    public final long serverTimeMs;

    public GridSummary(LText networkName, int cpus, int busyCpus, double storedPower, double maxPower,
            boolean powered, long serverTimeMs) {
        this.networkName = networkName;
        this.cpus = cpus;
        this.busyCpus = busyCpus;
        this.storedPower = storedPower;
        this.maxPower = maxPower;
        this.powered = powered;
        this.serverTimeMs = serverTimeMs;
    }

    /** Same apart from the time it was built and power changes under 1 AE. */
    public boolean sameIgnoringTime(GridSummary b) {
        return cpus == b.cpus && busyCpus == b.busyCpus && powered == b.powered
                && networkName.equals(b.networkName) && Math.abs(storedPower - b.storedPower) < 1.0
                && Math.abs(maxPower - b.maxPower) < 1.0;
    }

    public void write(Buf buf) {
        networkName.write(buf);
        buf.writeVarInt(cpus);
        buf.writeVarInt(busyCpus);
        buf.writeDouble(storedPower);
        buf.writeDouble(maxPower);
        buf.writeBoolean(powered);
        buf.writeLong(serverTimeMs);
    }

    public static GridSummary read(Buf buf) {
        return new GridSummary(LText.read(buf), buf.readVarInt(), buf.readVarInt(), buf.readDouble(),
                buf.readDouble(), buf.readBoolean(), buf.readLong());
    }
}
