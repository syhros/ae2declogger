package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.compat.gtceu.GtCompat;
import com.syhros.ae2declogger.compat.gtceu.GtMachineInfo;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import appeng.api.stacks.AEKey;

/**
 * Reads the state of the block a pattern provider pushes into. GregTech machines get full detail; anything else gets
 * its block name and whether its inventory is full. Never modifies the block.
 */
public final class MachineInspector {

    private MachineInspector() {
    }

    /** Short status for job and step rows. */
    public static MachineRef describe(ServerLevel level, BlockPos pos, MachineRef.Kind kind) {
        if (!level.isLoaded(pos)) {
            return new MachineRef(level.dimension(), pos, Component.translatable("ae2declogger.machine.unloaded"), kind,
                    MachineStatus.UNKNOWN, BlockReason.NONE,
                    Component.translatable("ae2declogger.machine.unloaded_detail"), -1, -1);
        }
        BlockState state = level.getBlockState(pos);
        Component name = state.getBlock().getName();

        GtMachineInfo gt = GtCompat.inspect(level, pos);
        if (gt != null) {
            BlockPos refPos = gt.controllerPos() != null ? gt.controllerPos() : pos;
            MachineRef.Kind refKind = gt.controllerPos() != null ? MachineRef.Kind.CONTROLLER : kind;
            return new MachineRef(level.dimension(), refPos, gt.name(), refKind, gt.status(), gt.problem(),
                    gt.detail(), gt.progress(), gt.duration());
        }

        if (state.isAir()) {
            return new MachineRef(level.dimension(), pos, Component.translatable("ae2declogger.machine.nothing"), kind,
                    MachineStatus.UNKNOWN, BlockReason.NONE, null, -1, -1);
        }

        BlockReason problem = BlockReason.NONE;
        Component detail = null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && kind != MachineRef.Kind.PROVIDER) {
            IItemHandler items = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
            if (items != null && InventoryReader.isCompletelyFull(items)) {
                problem = BlockReason.MACHINE_INPUT_FULL;
                detail = Component.translatable("ae2declogger.machine.inventory_full");
            }
        }
        return new MachineRef(level.dimension(), pos, name, kind, MachineStatus.UNKNOWN, problem, detail, -1, -1);
    }

    /** Full read-only snapshot for the machine viewer. */
    public static MachineSnapshot snapshot(ServerLevel level, BlockPos pos, Set<AEKey> patternInputs, int maxSlots) {
        List<Component> errors = new ArrayList<>();
        List<MachineSnapshot.Section> sections = new ArrayList<>();
        if (!level.isLoaded(pos)) {
            errors.add(Component.translatable("ae2declogger.machine.unloaded_detail"));
            return new MachineSnapshot(level.dimension(), pos, Component.translatable("ae2declogger.machine.unloaded"),
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

        GtMachineInfo gt = GtCompat.inspect(level, pos);
        if (gt != null) {
            name = gt.controllerPos() != null
                    ? Component.translatable("ae2declogger.viewer.part_of", state.getBlock().getName(), gt.name())
                    : gt.name();
            status = gt.status();
            statusText = gt.detail();
            progress = gt.progress();
            duration = gt.duration();
            if (gt.euCapacity() > 0) {
                energyStored = gt.euStored();
                energyCapacity = gt.euCapacity();
                unit = "EU";
            }
            tier = gt.tier();
            errors.addAll(gt.errors());
        }

        boolean gtSections = GtCompat.fillSections(level, pos, patternInputs, maxSlots, sections);
        int used = 0;
        for (MachineSnapshot.Section s : sections) {
            used += s.slots().size();
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!gtSections && be != null) {
            IItemHandler items = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
            if (items != null && items.getSlots() > 0) {
                List<MachineSnapshot.Slot> slots = new ArrayList<>();
                used += InventoryReader.readItems(items, slots, patternInputs, true, true, maxSlots - used);
                sections.add(new MachineSnapshot.Section(MachineSnapshot.SectionKind.INVENTORY,
                        Component.translatable("ae2declogger.viewer.section.inventory"), slots));
                if (InventoryReader.isCompletelyFull(items)) {
                    errors.add(Component.translatable("ae2declogger.machine.inventory_full"));
                }
            }
            IFluidHandler fluids = be.getCapability(ForgeCapabilities.FLUID_HANDLER, null).resolve().orElse(null);
            if (fluids != null && fluids.getTanks() > 0 && used < maxSlots) {
                List<MachineSnapshot.Slot> slots = new ArrayList<>();
                used += InventoryReader.readFluids(fluids, slots, patternInputs, true, true, maxSlots - used);
                sections.add(new MachineSnapshot.Section(MachineSnapshot.SectionKind.FLUIDS,
                        Component.translatable("ae2declogger.viewer.section.fluids"), slots));
            }
        }
        if (unit.isEmpty() && be != null) {
            IEnergyStorage fe = be.getCapability(ForgeCapabilities.ENERGY, null).resolve().orElse(null);
            if (fe != null && fe.getMaxEnergyStored() > 0) {
                energyStored = fe.getEnergyStored();
                energyCapacity = fe.getMaxEnergyStored();
                unit = "FE";
            }
        }
        if (be == null && gt == null) {
            errors.add(Component.translatable("ae2declogger.viewer.no_inventory"));
        }
        boolean truncated = used >= maxSlots;
        return new MachineSnapshot(level.dimension(), pos, name, status, statusText, progress, duration, energyStored,
                energyCapacity, unit, tier, errors, sections, truncated);
    }

    /** Recipe duration in ms for a machine ref, or -1. */
    static long recipeMs(@Nullable MachineRef ref) {
        if (ref == null || ref.duration() <= 0) {
            return -1;
        }
        return ref.duration() * 50L;
    }
}
