package com.syhros.ae2declogger.server;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
//#if NEOFORGE
//$$ import net.neoforged.neoforge.capabilities.Capabilities;
//$$ import net.neoforged.neoforge.energy.IEnergyStorage;
//$$ import net.neoforged.neoforge.fluids.capability.IFluidHandler;
//$$ import net.neoforged.neoforge.items.IItemHandler;
//#else
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
//#endif

/**
 * Item, fluid and energy access of a block entity, the way the loader provides it: Forge's capabilities on the block
 * entity, NeoForge's block capabilities on the level. Read-only callers only.
 */
public final class Caps {

    private Caps() {
    }

    @Nullable
    public static IItemHandler items(BlockEntity be, @Nullable Direction side) {
        //#if NEOFORGE
        //$$ return be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //#else
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).resolve().orElse(null);
        //#endif
    }

    @Nullable
    public static IFluidHandler fluids(BlockEntity be, @Nullable Direction side) {
        //#if NEOFORGE
        //$$ return be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.FluidHandler.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //#else
        return be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).resolve().orElse(null);
        //#endif
    }

    @Nullable
    public static IEnergyStorage energy(BlockEntity be, @Nullable Direction side) {
        //#if NEOFORGE
        //$$ return be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //#else
        return be.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
        //#endif
    }
}
