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
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Iron Furnaces. A furnace with something to smelt and no fuel burning is out of fuel; the factory augment runs on FE
 * instead. Generators are skipped: AE2 does not push recipes into them.
 */
public final class IronFurnacesReader implements ModReader {

    private static final String FURNACE = "ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase";

    @Override
    public String modId() {
        return "ironfurnaces";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (!Refl.isA(be, FURNACE)) {
            return null;
        }
        MachineInfo.Builder b = R.builder(modId(), be);
        if (Refl.bool(be, "isGenerator")) {
            return b.build();
        }
        if (Refl.bool(be, "isFactory")) {
            if (Refl.num(be, "getCapacity") > 0 && Refl.num(be, "getEnergy") <= 0) {
                b.problem(BlockReason.MACHINE_NO_POWER, R.text("energy_empty"));
            }
            return b.status(Refl.bool(be, "isFactoryCooking") ? MachineStatus.WORKING
                    : b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.IDLE).build();
        }
        boolean burning = Refl.bool(be, "isBurning");
        int total = (int) (Integer) Refl.field(be, "totalCookTime");
        if (burning && total > 0) {
            b.progress((int) (Integer) Refl.field(be, "cookTime"), total);
        }
        // Slot 0 is the input; something there and nothing burning means no fuel.
        if (!burning && be instanceof Container c && c.getContainerSize() > 0 && !c.getItem(0).isEmpty()) {
            b.problem(BlockReason.MACHINE_NO_POWER, R.text("no_fuel"));
        }
        return b.status(burning ? MachineStatus.WORKING : b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.IDLE)
                .build();
    }

    @Override
    public List<String> names() {
        return List.of(FURNACE + "#isGenerator", FURNACE + "#isFactory", FURNACE + "#isFactoryCooking",
                FURNACE + "#isBurning", FURNACE + "#getEnergy", FURNACE + "#getCapacity", FURNACE + "@cookTime",
                FURNACE + "@totalCookTime");
    }
}
