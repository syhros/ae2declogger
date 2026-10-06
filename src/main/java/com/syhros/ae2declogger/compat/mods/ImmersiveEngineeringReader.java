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
 * Immersive Engineering multiblocks (crusher, arc furnace, squeezer...). AE2 pushes into one of their IO blocks; every
 * block of the structure reaches the shared state: redstone control, energy buffer and the process queue (a full
 * queue takes nothing more). IE 10+ (1.20.1) has the multiblock API; 1.18/1.19 keep it on the master block entity.
 */
public final class ImmersiveEngineeringReader implements ModReader {

    private static final String MB_BE = "blusunrize.immersiveengineering.api.multiblocks.blocks.logic.IMultiblockBE";
    private static final String HELPER = "blusunrize.immersiveengineering.api.multiblocks.blocks.env.IMultiblockBEHelper";
    private static final String RS = "blusunrize.immersiveengineering.api.multiblocks.blocks.component.RedstoneControl$RSState";
    private static final String PROCESSOR = "blusunrize.immersiveengineering.common.blocks.multiblocks.process.MultiblockProcessor";
    private static final String OLD_PART = "blusunrize.immersiveengineering.common.blocks.generic.MultiblockPartBlockEntity";
    private static final String OLD_POWERED = "blusunrize.immersiveengineering.common.blocks.generic.PoweredMultiblockBlockEntity";

    @Override
    public String modId() {
        return "immersiveengineering";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (Refl.isA(be, MB_BE)) {
            return readNew(be);
        }
        if (Refl.isA(be, OLD_PART)) {
            return readOld(be);
        }
        return null;
    }

    private MachineInfo readNew(BlockEntity be) throws ReflectiveOperationException {
        MachineInfo.Builder b = R.builder(modId(), be);
        Object helper = Refl.call(be, "getHelper");
        Object state = helper == null ? null : Refl.call(helper, "getState");
        if (state == null) {
            return b.problem(BlockReason.MACHINE_NOT_FORMED, R.text("not_formed")).build();
        }
        Object rs = Refl.fieldOrNull(state, "rsState");
        if (rs != null && !Refl.bool(rs, "isEnabled", Refl.call(helper, "getContext"))) {
            b.problem(BlockReason.MACHINE_DISABLED, R.text("redstone_off"));
        }
        energy(b, Refl.fieldOrNull(state, "energy"));
        Object processor = Refl.fieldOfType(state, "MultiblockProcessor");
        if (processor != null) {
            queue(b, (int) Refl.num(processor, "getQueueSize"), (int) Refl.num(processor, "getMaxQueueSize"));
        }
        return b.build();
    }

    private MachineInfo readOld(BlockEntity be) throws ReflectiveOperationException {
        MachineInfo.Builder b = R.builder(modId(), be);
        Object master = Boolean.TRUE.equals(Refl.field(be, "formed")) ? Refl.call(be, "master") : null;
        if (!(master instanceof BlockEntity m)) {
            return b.problem(BlockReason.MACHINE_NOT_FORMED, R.text("not_formed")).build();
        }
        b.controller(m.getBlockPos());
        if (Refl.bool(m, "isRSDisabled")) {
            b.problem(BlockReason.MACHINE_DISABLED, R.text("redstone_off"));
        }
        if (Refl.isA(m, OLD_POWERED)) {
            energy(b, Refl.field(m, "energyStorage"));
            Object queue = Refl.field(m, "processQueue");
            queue(b, queue instanceof List<?> l ? l.size() : 0, (int) Refl.num(m, "getProcessQueueMaxLength"));
        }
        return b.build();
    }

    private static void energy(MachineInfo.Builder b, @Nullable Object storage) throws ReflectiveOperationException {
        if (storage != null && Refl.num(storage, "getMaxEnergyStored") > 0 && Refl.num(storage, "getEnergyStored") <= 0) {
            b.problem(BlockReason.MACHINE_NO_POWER, R.text("energy_empty"));
        }
    }

    private static void queue(MachineInfo.Builder b, int size, int max) {
        if (max > 0 && size >= max) {
            b.problem(BlockReason.MACHINE_INPUT_FULL, R.text("queue_full"));
        }
        b.status(size > 0 ? MachineStatus.WORKING : b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.IDLE);
    }

    @Override
    public List<String> names() {
        if (Refl.cls(MB_BE) != null) {
            return List.of(MB_BE + "#getHelper", HELPER + "#getState", HELPER + "#getContext", RS + "#isEnabled",
                    PROCESSOR + "#getQueueSize", PROCESSOR + "#getMaxQueueSize",
                    "blusunrize.immersiveengineering.common.blocks.multiblocks.logic.CrusherLogic$State@rsState",
                    "blusunrize.immersiveengineering.common.blocks.multiblocks.logic.CrusherLogic$State@energy");
        }
        return List.of(OLD_PART + "@formed", OLD_PART + "#master", OLD_PART + "#isRSDisabled",
                OLD_POWERED + "@energyStorage", OLD_POWERED + "@processQueue",
                OLD_POWERED + "#getProcessQueueMaxLength");
    }
}
