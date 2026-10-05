package com.syhros.ae2declogger.compat.gtceu;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * What the GregTech reader found at a position. Uses only this mod's types so callers never touch GT classes.
 *
 * @param controllerPos the multiblock controller when the block was a part (input bus, pattern buffer...), else null
 * @param progress      recipe progress in ticks, -1 if not running
 * @param duration      recipe length in ticks, -1 if not running
 * @param euStored      -1 if the machine has no EU storage
 */
public record GtMachineInfo(Component name, MachineStatus status, BlockReason problem, @Nullable Component detail,
        int progress, int duration, @Nullable BlockPos controllerPos, long euStored, long euCapacity, String tier,
        List<Component> errors) {

    public GtMachineInfo {
        errors = List.copyOf(errors);
    }
}
