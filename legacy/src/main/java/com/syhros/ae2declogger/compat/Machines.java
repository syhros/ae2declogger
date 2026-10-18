package com.syhros.ae2declogger.compat;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.MachineSnapshot;

/**
 * Machine integrations for the legacy versions. 1.16.5 and 1.12.2 have none yet (machines are read through their
 * inventories and energy only); the GTNH build replaces this file with one that reads GregTech 5 machines.
 */
public final class Machines {

    private Machines() {
    }

    /** Status of the machine at {@code pos}, or null when no integration knows it. */
    public static MachineInfo inspect(LPos pos) {
        return null;
    }

    /** Multiblock controllers a pattern holder at {@code pos} belongs to (GregTech ME input buffers). */
    public static List<LPos> controllersOf(LPos pos) {
        return Collections.emptyList();
    }

    /** True when the block at {@code pos} is a machine an integration can read. */
    public static boolean isMachine(LPos pos) {
        return false;
    }

    /**
     * Adds the machine's own input and output sections for the viewer. Returns false when no integration knows it
     * (the viewer then shows the block's plain inventory).
     */
    public static boolean fillSections(LPos pos, Set<LKey> patternInputs, int maxSlots,
            List<MachineSnapshot.Section> out) {
        return false;
    }

    /** Names read by reflection, for the self-check ("missing:<name>" when absent). */
    public static List<String> names() {
        return Collections.emptyList();
    }
}
