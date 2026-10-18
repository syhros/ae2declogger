package com.syhros.ae2declogger.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;

/** What a machine integration (GregTech 5 on GTNH) found out about a block. */
public final class MachineInfo {

    public final LText name;
    public final MachineStatus status;
    public final BlockReason problem;
    public final LText detail;
    public final int progress;
    public final int duration;
    /** The multiblock controller when the block was a hatch of one, otherwise null. */
    public final LPos controllerPos;
    public final long energyStored;
    public final long energyCapacity;
    public final String energyUnit;
    public final String tier;
    public final List<LText> errors;

    public MachineInfo(LText name, MachineStatus status, BlockReason problem, LText detail, int progress,
            int duration, LPos controllerPos, long energyStored, long energyCapacity, String energyUnit, String tier,
            List<LText> errors) {
        this.name = name;
        this.status = status;
        this.problem = problem == null ? BlockReason.NONE : problem;
        this.detail = detail;
        this.progress = progress;
        this.duration = duration;
        this.controllerPos = controllerPos;
        this.energyStored = energyStored;
        this.energyCapacity = energyCapacity;
        this.energyUnit = energyUnit == null ? "" : energyUnit;
        this.tier = tier == null ? "" : tier;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }
}
