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
 * Oritech machines. Multiblock machines are built from machine cores that point at their controller; a controller
 * that is not assembled, switched off by redstone or cannot fit its output does not run. The assembled check was
 * renamed from isActive to isAssembled in 26.1.
 */
public final class OritechReader implements ModReader {

    private static final String MACHINE = "rearth.oritech.block.base.entity.MachineBlockEntity";
    private static final String CORE = "rearth.oritech.block.entity.MachineCoreEntity";

    @Override
    public String modId() {
        return "oritech";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (Refl.isA(be, CORE)) {
            Object controllerPos = Refl.bool(be, "isEnabled") ? Refl.call(be, "getControllerPos") : null;
            BlockEntity controller = controllerPos instanceof BlockPos p && level.isLoaded(p) ? level.getBlockEntity(p)
                    : null;
            if (controller == null || !Refl.isA(controller, MACHINE)) {
                return R.builder(modId(), be).problem(BlockReason.MACHINE_NOT_FORMED, R.text("not_formed")).build();
            }
            return machine(controller).controller(controller.getBlockPos()).build();
        }
        if (Refl.isA(be, MACHINE)) {
            return machine(be).build();
        }
        return null;
    }

    private MachineInfo.Builder machine(BlockEntity be) throws ReflectiveOperationException {
        MachineInfo.Builder b = R.builder(modId(), be);
        String assembled = Refl.has(be, "isAssembled", 1) ? "isAssembled" : "isActive";
        if (!Refl.bool(be, assembled, be.getBlockState())) {
            b.problem(BlockReason.MACHINE_NOT_FORMED, R.text("not_formed"));
        }
        if (Boolean.TRUE.equals(Refl.fieldOrNull(be, "disabledViaRedstone"))) {
            b.problem(BlockReason.MACHINE_DISABLED, R.text("redstone_off"));
        }
        Object recipe = Refl.call(be, "getCurrentRecipe");
        if (recipe != null && !Refl.bool(be, "canOutputRecipe", recipe)) {
            b.problem(BlockReason.MACHINE_OUTPUT_FULL, R.text("output_full"));
        }
        R.emptyEnergy(b, be);
        boolean working = Refl.bool(be, "isActivelyWorking");
        if (working && Refl.call(be, "getProgress") instanceof Float f) {
            b.note(R.percent(f));
        }
        return b.status(working ? MachineStatus.WORKING : b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.IDLE);
    }

    @Override
    public List<String> names() {
        return List.of(MACHINE + "#isAssembled|isActive", MACHINE + "@disabledViaRedstone",
                MACHINE + "#getCurrentRecipe", MACHINE + "#canOutputRecipe", MACHINE + "#isActivelyWorking",
                MACHINE + "#getProgress", CORE + "#getControllerPos", CORE + "#isEnabled");
    }
}
