package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.compat.MachineInfo;
import com.syhros.ae2declogger.compat.Machines;
import com.syhros.ae2declogger.compat.gtceu.GtCompat;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//#if NEOFORGE
//$$ import net.neoforged.neoforge.energy.IEnergyStorage;
//$$ import net.neoforged.neoforge.fluids.capability.IFluidHandler;
//$$ import net.neoforged.neoforge.items.IItemHandler;
//#else
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
//#endif

import appeng.api.stacks.AEKey;

/**
 * Reads the state of the block a pattern provider pushes into. Machines from mods with an integration
 * ({@link Machines}: GregTech, Mekanism, Create...) get their status and why they are not working; anything else gets
 * its block name, whether its inventory is full and whether its energy buffer is empty. Never modifies the block.
 */
public final class MachineInspector {

    private MachineInspector() {
    }

    /** Short status for job and step rows. */
    public static MachineRef describe(ServerLevel level, BlockPos pos, MachineRef.Kind kind) {
        if (!level.isLoaded(pos)) {
            return new MachineRef(level.dimension(), pos, Text.translatable("ae2declogger.machine.unloaded"), kind,
                    MachineStatus.UNKNOWN, BlockReason.NONE,
                    Text.translatable("ae2declogger.machine.unloaded_detail"), -1, -1);
        }
        BlockState state = level.getBlockState(pos);
        Component name = state.getBlock().getName();

        MachineInfo info = Machines.inspect(level, pos);
        if (info != null) {
            BlockPos refPos = info.controllerPos() != null ? info.controllerPos() : pos;
            MachineRef.Kind refKind = info.controllerPos() != null ? MachineRef.Kind.CONTROLLER : kind;
            return new MachineRef(level.dimension(), refPos, info.name(), refKind, info.status(), info.problem(),
                    info.detail(), info.progress(), info.duration());
        }

        if (state.isAir()) {
            return new MachineRef(level.dimension(), pos, Text.translatable("ae2declogger.machine.nothing"), kind,
                    MachineStatus.UNKNOWN, BlockReason.NONE, null, -1, -1);
        }

        BlockReason problem = BlockReason.NONE;
        Component detail = null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && kind != MachineRef.Kind.PROVIDER) {
            IItemHandler items = items(be);
            IEnergyStorage fe = Caps.energy(be, null);
            if (items != null && InventoryReader.isCompletelyFull(items)) {
                problem = BlockReason.MACHINE_INPUT_FULL;
                detail = Text.translatable("ae2declogger.machine.inventory_full");
            } else if (fe != null && fe.getMaxEnergyStored() > 0 && fe.getEnergyStored() <= 0) {
                // A machine with an FE buffer and none in it cannot run.
                problem = BlockReason.MACHINE_NO_POWER;
                detail = Text.translatable("ae2declogger.compat.energy_empty");
            }
        }
        return new MachineRef(level.dimension(), pos, name, kind, MachineStatus.UNKNOWN, problem, detail, -1, -1);
    }

    /** Full read-only snapshot for the machine viewer. */
    public static MachineSnapshot snapshot(ServerLevel level, BlockPos pos, Set<AEKey> patternInputs, int maxSlots) {
        List<Component> errors = new ArrayList<>();
        List<MachineSnapshot.Section> sections = new ArrayList<>();
        if (!level.isLoaded(pos)) {
            errors.add(Text.translatable("ae2declogger.machine.unloaded_detail"));
            return new MachineSnapshot(level.dimension(), pos, Text.translatable("ae2declogger.machine.unloaded"),
                    MachineStatus.UNKNOWN, null, -1, -1, 0, 0, "", "", errors, sections, false);
        }
        BlockState state = level.getBlockState(pos);
        Component name = state.getBlock().getName();
        MachineStatus status = MachineStatus.UNKNOWN;
        Component statusText = null;
        int progress = -1;
        int duration = -1;
        long energyStored = 0;
        long energyCapacity = 0;
        String unit = "";
        String tier = "";

        MachineInfo info = Machines.inspect(level, pos);
        if (info != null) {
            name = info.controllerPos() != null
                    ? Text.translatable("ae2declogger.viewer.part_of", state.getBlock().getName(), info.name())
                    : info.name();
            status = info.status();
            statusText = info.detail();
            progress = info.progress();
            duration = info.duration();
            if (info.energyCapacity() > 0 && !info.energyUnit().isEmpty()) {
                energyStored = info.energyStored();
                energyCapacity = info.energyCapacity();
                unit = info.energyUnit();
            }
            tier = info.tier();
            errors.addAll(info.errors());
        }

        boolean gtSections = GtCompat.fillSections(level, pos, patternInputs, maxSlots, sections);
        int used = 0;
        for (MachineSnapshot.Section s : sections) {
            used += s.slots().size();
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!gtSections && be != null) {
            IItemHandler items = items(be);
            if (items != null && items.getSlots() > 0) {
                List<MachineSnapshot.Slot> slots = new ArrayList<>();
                used += InventoryReader.readItems(items, slots, patternInputs, true, true, maxSlots - used);
                sections.add(new MachineSnapshot.Section(MachineSnapshot.SectionKind.INVENTORY,
                        Text.translatable("ae2declogger.viewer.section.inventory"), slots));
                if (InventoryReader.isCompletelyFull(items)) {
                    errors.add(Text.translatable("ae2declogger.machine.inventory_full"));
                }
            }
            IFluidHandler fluids = fluids(be);
            if (fluids != null && fluids.getTanks() > 0 && used < maxSlots) {
                List<MachineSnapshot.Slot> slots = new ArrayList<>();
                used += InventoryReader.readFluids(fluids, slots, patternInputs, true, true, maxSlots - used);
                sections.add(new MachineSnapshot.Section(MachineSnapshot.SectionKind.FLUIDS,
                        Text.translatable("ae2declogger.viewer.section.fluids"), slots));
            }
        }
        if (unit.isEmpty() && be != null) {
            IEnergyStorage fe = Caps.energy(be, null);
            if (fe != null && fe.getMaxEnergyStored() > 0) {
                energyStored = fe.getEnergyStored();
                energyCapacity = fe.getMaxEnergyStored();
                unit = "FE";
            }
        }
        if (be == null && info == null) {
            errors.add(Text.translatable("ae2declogger.viewer.no_inventory"));
        }
        boolean truncated = used >= maxSlots;
        return new MachineSnapshot(level.dimension(), pos, name, status, statusText, progress, duration, energyStored,
                energyCapacity, unit, tier, errors, sections, truncated);
    }

    /**
     * The block's items without naming a side, else from the first side that has any. Some mods only expose their
     * inventory on the sides configured for it.
     */
    @Nullable
    public static IItemHandler items(BlockEntity be) {
        IItemHandler items = Caps.items(be, null);
        for (int i = 0; items == null && i < Direction.values().length; i++) {
            items = Caps.items(be, Direction.values()[i]);
        }
        return items;
    }

    @Nullable
    static IFluidHandler fluids(BlockEntity be) {
        IFluidHandler fluids = Caps.fluids(be, null);
        for (int i = 0; fluids == null && i < Direction.values().length; i++) {
            fluids = Caps.fluids(be, Direction.values()[i]);
        }
        return fluids;
    }

    /** Recipe duration in ms for a machine ref, or -1. */
    static long recipeMs(@Nullable MachineRef ref) {
        if (ref == null || ref.duration() <= 0) {
            return -1;
        }
        return ref.duration() * 50L;
    }
}
