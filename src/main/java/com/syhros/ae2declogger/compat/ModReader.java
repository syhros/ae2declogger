package com.syhros.ae2declogger.compat;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Reads machine status from one mod, by reflection (see {@link Refl}). Registered in {@link Machines}. */
public interface ModReader {

    /** The mod id this reader needs loaded. */
    String modId();

    /**
     * Status of the machine, or null when the block is not one of this mod's machines. Throwing a
     * {@link ReflectiveOperationException} or {@link LinkageError} switches this reader off: the installed version of
     * the mod no longer has the names it reads.
     */
    @Nullable
    MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException;

    /** Every class and member name this reader uses, in {@link Refl#missing} form, checked by the self-test. */
    List<String> names();
}
