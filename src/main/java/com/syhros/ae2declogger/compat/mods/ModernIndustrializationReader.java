package com.syhros.ae2declogger.compat.mods;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.compat.MachineInfo;
import com.syhros.ae2declogger.compat.ModReader;
import com.syhros.ae2declogger.compat.Refl;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Modern Industrialization machines (EU, not FE) and multiblocks. AE2 pushes into an input hatch; the hatch has no link
 * back to its controller, so nearby controllers are asked which hatches they matched.
 */
public final class ModernIndustrializationReader implements ModReader {

    private static final String P = "aztech.modern_industrialization.";
    private static final String MACHINE = P + "machines.MachineBlockEntity";
    private static final String MULTIBLOCK = P + "machines.multiblocks.MultiblockMachineBlockEntity";
    private static final String HATCH = P + "machines.multiblocks.HatchBlockEntity";
    private static final String CRAFTER = P + "api.machine.holder.CrafterComponentHolder";
    private static final String ENERGY = P + "api.machine.holder.EnergyComponentHolder";
    /** Large MI multiblocks reach about this far from their controller. */
    private static final int HATCH_SEARCH = 16;

    @Override
    public String modId() {
        return "modern_industrialization";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (!Refl.isA(be, MACHINE)) {
            return null;
        }
        if (Refl.isA(be, HATCH)) {
            BlockEntity controller = controllerOf(level, be);
            if (controller == null) {
                return R.builder(modId(), be).problem(BlockReason.MACHINE_NOT_FORMED, R.text("hatch_unmatched")).build();
            }
            MachineInfo.Builder b = machine(controller);
            return b.controller(controller.getBlockPos()).build();
        }
        return machine(be).build();
    }

    @Nullable
    private static BlockEntity controllerOf(ServerLevel level, BlockEntity hatch) throws ReflectiveOperationException {
        if (!Refl.bool(hatch, "isMatched")) {
            return null;
        }
        for (BlockEntity c : Refl.nearby(level, hatch.getBlockPos(), HATCH_SEARCH, e -> Refl.isA(e, MULTIBLOCK))) {
            Object matcher = Refl.fieldOrNull(c, "shapeMatcher");
            if (matcher != null && Refl.call(matcher, "getMatchedHatches") instanceof List<?> hatches
                    && hatches.contains(hatch)) {
                return c;
            }
        }
        return null;
    }

    private MachineInfo.Builder machine(BlockEntity be) throws ReflectiveOperationException {
        MachineInfo.Builder b = R.builder(modId(), be);
        if (Refl.isA(be, MULTIBLOCK) && !Refl.bool(be, "isShapeValid")) {
            b.problem(BlockReason.MACHINE_NOT_FORMED, R.text("not_formed"));
        }
        Object redstone = Refl.fieldOfType(be, "RedstoneControlComponent");
        if (redstone != null && !Refl.bool(redstone, "doAllowNormalOperation", be)) {
            b.problem(BlockReason.MACHINE_DISABLED, R.text("redstone_off"));
        }
        if (Refl.isA(be, ENERGY)) {
            Object energy = Refl.call(be, "getEnergyComponent");
            long eu = Refl.num(energy, "getEu");
            long capacity = Refl.num(energy, "getCapacity");
            b.energy(eu, capacity, "EU");
            if (capacity > 0 && eu <= 0) {
                b.problem(BlockReason.MACHINE_NO_POWER, R.text("energy_empty"));
            }
        }
        boolean active = false;
        if (Refl.isA(be, CRAFTER)) {
            Object crafter = Refl.call(be, "getCrafterComponent");
            active = Refl.bool(crafter, "hasActiveRecipe");
            if (active && Refl.call(crafter, "getProgress") instanceof Float f) {
                b.note(R.percent(f));
            }
        }
        b.status(active ? MachineStatus.WORKING : b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.IDLE);
        return b;
    }

    @Override
    public List<String> names() {
        return List.of(MULTIBLOCK + "#isShapeValid", MULTIBLOCK + "@shapeMatcher",
                P + "machines.multiblocks.ShapeMatcher#getMatchedHatches", HATCH + "#isMatched",
                P + "machines.components.RedstoneControlComponent#doAllowNormalOperation", CRAFTER + "#getCrafterComponent",
                P + "api.machine.component.CrafterAccess#hasActiveRecipe", P + "api.machine.component.CrafterAccess#getProgress",
                ENERGY + "#getEnergyComponent", P + "api.machine.component.EnergyAccess#getEu",
                P + "api.machine.component.EnergyAccess#getCapacity");
    }
}
