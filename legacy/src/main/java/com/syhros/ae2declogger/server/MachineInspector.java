package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.syhros.ae2declogger.compat.MachineInfo;
import com.syhros.ae2declogger.compat.Machines;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;

/**
 * Reads the state of the block an interface pushes into. Machines with an integration (GregTech 5 on GTNH) get their
 * status and why they are not working; anything else gets its name, whether its inventory is full and whether its
 * energy buffer is empty. Never modifies the block.
 */
public final class MachineInspector {

    private MachineInspector() {
    }

    /** Short status for job and step rows. */
    public static MachineRef describe(LPos pos, MachineRef.Kind kind) {
        if (!Plat.isLoaded(pos)) {
            return new MachineRef(pos, LText.tr("ae2declogger.machine.unloaded"), kind, MachineStatus.UNKNOWN,
                    BlockReason.NONE, LText.tr("ae2declogger.machine.unloaded_detail"), -1, -1);
        }
        MachineInfo info = Machines.inspect(pos);
        if (info != null) {
            LPos refPos = info.controllerPos != null ? info.controllerPos : pos;
            MachineRef.Kind refKind = info.controllerPos != null ? MachineRef.Kind.CONTROLLER : kind;
            return new MachineRef(refPos, info.name, refKind, info.status, info.problem, info.detail, info.progress,
                    info.duration);
        }
        if (Plat.isAir(pos)) {
            return new MachineRef(pos, LText.tr("ae2declogger.machine.nothing"), kind, MachineStatus.UNKNOWN,
                    BlockReason.NONE, null, -1, -1);
        }
        BlockReason problem = BlockReason.NONE;
        LText detail = null;
        if (kind != MachineRef.Kind.PROVIDER) {
            Plat.Problem p = Plat.genericProblem(pos);
            if (p != null) {
                problem = p.reason;
                detail = p.detail;
            }
        }
        return new MachineRef(pos, Plat.blockName(pos), kind, MachineStatus.UNKNOWN, problem, detail, -1, -1);
    }

    /** Full read-only snapshot for the machine viewer. */
    public static MachineSnapshot snapshot(LPos pos, Set<LKey> patternInputs, int maxSlots) {
        List<LText> errors = new ArrayList<>();
        List<MachineSnapshot.Section> sections = new ArrayList<>();
        if (!Plat.isLoaded(pos)) {
            errors.add(LText.tr("ae2declogger.machine.unloaded_detail"));
            return new MachineSnapshot(pos, LText.tr("ae2declogger.machine.unloaded"), MachineStatus.UNKNOWN, null,
                    -1, -1, 0, 0, "", "", errors, sections, false);
        }
        LText name = Plat.blockName(pos);
        MachineStatus status = MachineStatus.UNKNOWN;
        LText statusText = null;
        int progress = -1;
        int duration = -1;
        long energyStored = 0;
        long energyCapacity = 0;
        String unit = "";
        String tier = "";

        MachineInfo info = Machines.inspect(pos);
        if (info != null) {
            name = info.controllerPos != null ? LText.tr("ae2declogger.viewer.part_of", name, info.name) : info.name;
            status = info.status;
            statusText = info.detail;
            progress = info.progress;
            duration = info.duration;
            if (info.energyCapacity > 0 && !info.energyUnit.isEmpty()) {
                energyStored = info.energyStored;
                energyCapacity = info.energyCapacity;
                unit = info.energyUnit;
            }
            tier = info.tier;
            errors.addAll(info.errors);
        }

        boolean ownSections = Machines.fillSections(pos, patternInputs, maxSlots, sections);
        int used = 0;
        for (MachineSnapshot.Section s : sections) {
            used += s.slots.size();
        }
        if (!ownSections) {
            used += Plat.readInventory(pos, patternInputs, maxSlots - used, sections, errors);
        }
        if (unit.isEmpty()) {
            Plat.Energy e = Plat.energy(pos);
            if (e != null && e.capacity > 0) {
                energyStored = e.stored;
                energyCapacity = e.capacity;
                unit = e.unit;
            }
        }
        if (sections.isEmpty() && info == null && !Plat.hasTile(pos)) {
            errors.add(LText.tr("ae2declogger.viewer.no_inventory"));
        }
        return new MachineSnapshot(pos, name, status, statusText, progress, duration, energyStored, energyCapacity,
                unit, tier, errors, sections, used >= maxSlots);
    }

    /** Recipe duration in ms for a machine ref, or -1. */
    static long recipeMs(MachineRef ref) {
        if (ref == null || ref.duration <= 0) {
            return -1;
        }
        return ref.duration * 50L;
    }
}
