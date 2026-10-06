package com.syhros.ae2declogger.compat.mods;

import java.util.Locale;

import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.compat.MachineInfo;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.server.Caps;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
//#if NEOFORGE
//$$ import net.neoforged.neoforge.energy.IEnergyStorage;
//#else
import net.minecraftforge.energy.IEnergyStorage;
//#endif

/** Shared bits of the mod readers: names, the common problem texts, and the energy check. */
final class R {

    private R() {
    }

    static MachineInfo.Builder builder(String modId, BlockEntity be) {
        return MachineInfo.builder(modId, be.getBlockState().getBlock().getName());
    }

    /** {@code ae2declogger.compat.<key>}: the problem texts shared by every integration. */
    static Component text(String key, Object... args) {
        return Text.translatable("ae2declogger.compat." + key, args);
    }

    static Component percent(double fraction) {
        return text("progress", String.format(Locale.ROOT, "%.0f", Math.max(0, Math.min(1, fraction)) * 100));
    }

    /** Sets "no power" when the block has an FE buffer and it is empty. */
    static void emptyEnergy(MachineInfo.Builder b, BlockEntity be) {
        IEnergyStorage fe = Caps.energy(be, null);
        if (fe != null && fe.getMaxEnergyStored() > 0 && fe.getEnergyStored() <= 0) {
            b.problem(BlockReason.MACHINE_NO_POWER, text("energy_empty"));
        }
    }
}
