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
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
//#if MC >= 1.19.2
import net.minecraftforge.common.capabilities.ForgeCapabilities;
//#else
//$$ import net.minecraftforge.energy.CapabilityEnergy;
//$$ import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
//$$ import net.minecraftforge.items.CapabilityItemHandler;
//#endif
//#endif

/**
 * Item, fluid and energy access of a block entity, the way the loader provides it: Forge's capabilities on the block
 * entity, NeoForge's block capabilities on the level. NeoForge 26.1 moved to resource handlers; they are wrapped in the
 * old interfaces, which the callers read.
 */
public final class Caps {

    private Caps() {
    }

    @Nullable
    public static IItemHandler items(BlockEntity be, @Nullable Direction side) {
        //#if MC >= 26.1
        //$$ var raw = be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.Item.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //$$ return raw == null ? null : IItemHandler.of(raw);
        //#elseif NEOFORGE
        //$$ return be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //#elseif MC >= 1.19.2
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).resolve().orElse(null);
        //#else
        //$$ return be.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side).resolve().orElse(null);
        //#endif
    }

    @Nullable
    public static IFluidHandler fluids(BlockEntity be, @Nullable Direction side) {
        //#if MC >= 26.1
        //$$ var raw = be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.Fluid.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //$$ return raw == null ? null : IFluidHandler.of(raw);
        //#elseif NEOFORGE
        //$$ return be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.FluidHandler.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //#elseif MC >= 1.19.2
        return be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).resolve().orElse(null);
        //#else
        //$$ return be.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side).resolve().orElse(null);
        //#endif
    }

    @Nullable
    public static IEnergyStorage energy(BlockEntity be, @Nullable Direction side) {
        //#if MC >= 26.1
        //$$ var raw = be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.Energy.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //$$ return raw == null ? null : IEnergyStorage.of(raw);
        //#elseif NEOFORGE
        //$$ return be.getLevel() == null ? null : be.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK,
        //$$         be.getBlockPos(), be.getBlockState(), be, side);
        //#elseif MC >= 1.19.2
        return be.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
        //#else
        //$$ return be.getCapability(CapabilityEnergy.ENERGY, side).resolve().orElse(null);
        //#endif
    }
}
