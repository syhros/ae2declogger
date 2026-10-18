package com.syhros.ae2declogger.data;

import java.util.Locale;

/** What a machine is doing, as far as we can tell. */
public enum MachineStatus {
    WORKING(0x55FF55),
    IDLE(0xAAAAAA),
    WAITING(0xFFDD33),
    SUSPENDED(0xFF8833),
    NOT_FORMED(0xFF5555),
    UNKNOWN(0x888888);

    private final int color;

    MachineStatus(int color) {
        this.color = color;
    }

    public int color() {
        return color;
    }

    public String translationKey() {
        return "ae2declogger.machine_status." + name().toLowerCase(Locale.ROOT);
    }
}
