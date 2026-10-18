package com.syhros.ae2declogger.data;

import com.syhros.ae2declogger.net.Buf;

/**
 * A block position with its dimension. The dimension is a string in every version: the registry name on 1.16.5
 * ({@code minecraft:overworld}), the dimension number on 1.12.2 and 1.7.10 ({@code 0}).
 */
public final class LPos {

    public final String dim;
    public final int x;
    public final int y;
    public final int z;

    public LPos(String dim, int x, int y, int z) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** The block next to this one on side {@code side} (0 down, 1 up, 2 north, 3 south, 4 west, 5 east). */
    public LPos offset(int side) {
        switch (side) {
            case 0:
                return new LPos(dim, x, y - 1, z);
            case 1:
                return new LPos(dim, x, y + 1, z);
            case 2:
                return new LPos(dim, x, y, z - 1);
            case 3:
                return new LPos(dim, x, y, z + 1);
            case 4:
                return new LPos(dim, x - 1, y, z);
            case 5:
                return new LPos(dim, x + 1, y, z);
            default:
                return this;
        }
    }

    public static int opposite(int side) {
        return side ^ 1;
    }

    public double distSq(double px, double py, double pz) {
        double dx = x + 0.5 - px;
        double dy = y + 0.5 - py;
        double dz = z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public String coords() {
        return x + " " + y + " " + z;
    }

    public void write(Buf buf) {
        buf.writeString(dim);
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
    }

    public static LPos read(Buf buf) {
        return new LPos(buf.readString(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LPos)) {
            return false;
        }
        LPos p = (LPos) o;
        return x == p.x && y == p.y && z == p.z && dim.equals(p.dim);
    }

    @Override
    public int hashCode() {
        return ((dim.hashCode() * 31 + x) * 31 + y) * 31 + z;
    }

    @Override
    public String toString() {
        return dim + "@" + coords();
    }
}
