package com.syhros.ae2declogger.compat.mods;

import java.util.List;
import java.util.Optional;

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
 * Create. Its "power" is rotation: a machine that is not turning, is on an overstressed network or turns too slowly
 * does nothing. A basin is worked by the mixer or press above it, so the basin reports that machine's rotation.
 */
public final class CreateReader implements ModReader {

    private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private static final String BASIN = "com.simibubi.create.content.processing.basin.BasinBlockEntity";

    @Override
    public String modId() {
        return "create";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (Refl.isA(be, BASIN)) {
            MachineInfo.Builder b = R.builder(modId(), be);
            Object operator = Refl.call(be, "getOperator");
            if (operator instanceof Optional<?> o && o.isPresent() && o.get() instanceof BlockEntity op) {
                rotation(b, op);
            } else {
                b.problem(BlockReason.MACHINE_NOT_FORMED, R.text("no_operator"));
            }
            return b.build();
        }
        if (Refl.isA(be, KINETIC)) {
            MachineInfo.Builder b = R.builder(modId(), be);
            rotation(b, be);
            return b.build();
        }
        return null;
    }

    private static void rotation(MachineInfo.Builder b, BlockEntity kinetic) throws ReflectiveOperationException {
        if (Refl.call(kinetic, "getSpeed") instanceof Float speed && speed == 0) {
            b.problem(BlockReason.MACHINE_NO_POWER, R.text("no_rotation"));
        } else if (Refl.bool(kinetic, "isOverStressed")) {
            b.problem(BlockReason.MACHINE_NO_POWER, R.text("overstressed"));
        } else if (!Refl.bool(kinetic, "isSpeedRequirementFulfilled")) {
            b.problem(BlockReason.MACHINE_NO_POWER, R.text("too_slow"));
        } else {
            b.status(MachineStatus.UNKNOWN);
        }
    }

    @Override
    public List<String> names() {
        return List.of(KINETIC + "#getSpeed", KINETIC + "#isOverStressed", KINETIC + "#isSpeedRequirementFulfilled",
                BASIN + "#getOperator");
    }
}
