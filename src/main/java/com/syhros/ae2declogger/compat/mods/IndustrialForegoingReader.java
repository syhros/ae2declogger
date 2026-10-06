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
 * Industrial Foregoing machines (Titanium based). Processing machines say whether they can advance, but not why: no
 * recipe, missing input and a full output all look the same.
 */
public final class IndustrialForegoingReader implements ModReader {

    private static final String MACHINE = "com.buuz135.industrial.block.tile.IndustrialMachineTile";
    private static final String PROCESSING = "com.buuz135.industrial.block.tile.IndustrialProcessingTile";
    private static final String BAR = "com.hrznstudio.titanium.component.progress.ProgressBarComponent";

    @Override
    public String modId() {
        return "industrialforegoing";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (!Refl.isA(be, MACHINE)) {
            return null;
        }
        MachineInfo.Builder b = R.builder(modId(), be);
        Object redstone = Refl.call(be, "getRedstoneManager");
        if (redstone != null && !Refl.bool(redstone, "shouldWork")) {
            b.problem(BlockReason.MACHINE_DISABLED, R.text("redstone_off"));
        }
        R.emptyEnergy(b, be);
        if (Refl.isA(be, PROCESSING)) {
            boolean running = Refl.bool(be, "canIncrease");
            Object bar = Refl.call(be, "getProgressBar");
            if (bar != null) {
                b.progress((int) Refl.num(bar, "getProgress"), (int) Refl.num(bar, "getMaxProgress"));
            }
            if (!running) {
                b.problem(BlockReason.MACHINE_WAITING, R.text("cannot_process"));
            }
            b.status(running && !b.hasProblem() ? MachineStatus.WORKING : MachineStatus.UNKNOWN);
        }
        return b.build();
    }

    @Override
    public List<String> names() {
        return List.of(MACHINE + "#getRedstoneManager", "com.hrznstudio.titanium.block.redstone.RedstoneManager#shouldWork",
                PROCESSING + "#canIncrease", PROCESSING + "#getProgressBar", BAR + "#getProgress", BAR + "#getMaxProgress");
    }
}
