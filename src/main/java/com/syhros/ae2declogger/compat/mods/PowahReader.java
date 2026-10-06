package com.syhros.ae2declogger.compat.mods;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.compat.MachineInfo;
import com.syhros.ae2declogger.compat.ModReader;
import com.syhros.ae2declogger.compat.Refl;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.server.Caps;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
//#if NEOFORGE
//$$ import net.neoforged.neoforge.energy.IEnergyStorage;
//$$ import net.neoforged.neoforge.items.IItemHandler;
//#else
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
//#endif

/**
 * Powah's Energizing Orb. It has no power of its own: the Energizing Rods aimed at it charge the recipe, so "no power"
 * means no rod links to this orb or the linked rods are empty. Classes were renamed from Tile to BlockEntity in 26.1.
 */
public final class PowahReader implements ModReader {

    private static final String[] ORB = { "owmii.powah.block.energizing.EnergizingOrbTile",
            "owmii.powah.block.energizing.EnergizingOrbBlockEntity" };
    private static final String[] ROD = { "owmii.powah.block.energizing.EnergizingRodTile",
            "owmii.powah.block.energizing.EnergizingRodBlockEntity" };
    /** Rods link to an orb within a few blocks. */
    private static final int ROD_SEARCH = 6;

    @Override
    public String modId() {
        return "powah";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (!Refl.isA(be, ORB)) {
            return null;
        }
        MachineInfo.Builder b = R.builder(modId(), be);
        Object recipe = Refl.call(be, "currRecipe");
        if (recipe == null) {
            if (hasItems(be)) {
                b.problem(BlockReason.MACHINE_NO_RECIPE, R.text("no_recipe"));
            }
            return b.status(b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.IDLE).build();
        }
        int rods = 0;
        long rodEnergy = 0;
        for (BlockEntity rod : Refl.nearby(level, pos, ROD_SEARCH, e -> Refl.isA(e, ROD))) {
            if (Refl.call(rod, "getOrbTile") == be) {
                rods++;
                IEnergyStorage fe = Caps.energy(rod, null);
                rodEnergy += fe == null ? 0 : fe.getEnergyStored();
            }
        }
        if (rods == 0 || rodEnergy <= 0) {
            b.problem(BlockReason.MACHINE_NO_POWER, R.text("no_rods"));
        }
        Object buffer = Refl.call(be, "getBuffer");
        long capacity = buffer == null ? 0 : Refl.num(buffer, "getCapacity");
        if (capacity > 0) {
            b.note(R.percent(Refl.num(buffer, "getStored") / (double) capacity));
        }
        return b.status(b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.WORKING).build();
    }

    private static boolean hasItems(BlockEntity be) {
        IItemHandler items = Caps.items(be, null);
        if (items == null) {
            return false;
        }
        for (int i = 0; i < items.getSlots(); i++) {
            if (!items.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> names() {
        String orb = String.join("|", ORB);
        return List.of(orb + "#currRecipe", orb + "#getBuffer", String.join("|", ROD) + "#getOrbTile",
                "owmii.powah.lib.logistics.energy.Energy#getStored", "owmii.powah.lib.logistics.energy.Energy#getCapacity");
    }
}
