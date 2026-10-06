package com.syhros.ae2declogger.server;

import java.util.List;
import java.util.Set;

import com.syhros.ae2declogger.data.MachineSnapshot;

import net.minecraft.world.item.ItemStack;
//#if NEOFORGE
//$$ import net.neoforged.neoforge.fluids.FluidStack;
//$$ import net.neoforged.neoforge.fluids.capability.IFluidHandler;
//$$ import net.neoforged.neoforge.items.IItemHandler;
//#else
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
//#endif

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * Copies inventory contents into viewer slots. Only reads (getStackInSlot / getFluidInTank); never extracts.
 */
public final class InventoryReader {

    private InventoryReader() {
    }

    /**
     * @param budget remaining slots allowed in the snapshot; the return value is how many were used
     */
    public static int readItems(IItemHandler handler, List<MachineSnapshot.Slot> out, Set<AEKey> patternInputs,
            boolean markFull, boolean markPatternInputs, int budget) {
        int used = 0;
        int slots = handler.getSlots();
        for (int i = 0; i < slots && used < budget; i++) {
            ItemStack stack = handler.getStackInSlot(i);
            long limit = Math.max(1, handler.getSlotLimit(i));
            byte flags = 0;
            GenericStack generic = null;
            if (!stack.isEmpty()) {
                AEItemKey key = AEItemKey.of(stack);
                if (key != null) {
                    generic = new GenericStack(key, stack.getCount());
                    long cap = Math.min(limit, stack.getMaxStackSize());
                    if (markFull && stack.getCount() >= cap) {
                        flags |= MachineSnapshot.FLAG_FULL;
                    }
                    if (markPatternInputs && patternInputs.contains(key)) {
                        flags |= MachineSnapshot.FLAG_PATTERN_INPUT;
                    }
                    limit = cap;
                }
            }
            out.add(new MachineSnapshot.Slot(generic, limit, flags));
            used++;
        }
        return used;
    }

    public static int readFluids(IFluidHandler handler, List<MachineSnapshot.Slot> out, Set<AEKey> patternInputs,
            boolean markFull, boolean markPatternInputs, int budget) {
        int used = 0;
        int tanks = handler.getTanks();
        for (int i = 0; i < tanks && used < budget; i++) {
            FluidStack fluid = handler.getFluidInTank(i);
            long capacity = Math.max(0, handler.getTankCapacity(i));
            byte flags = 0;
            GenericStack generic = null;
            if (!fluid.isEmpty()) {
                AEFluidKey key = AEFluidKey.of(fluid);
                generic = new GenericStack(key, fluid.getAmount());
                if (markFull && capacity > 0 && fluid.getAmount() >= capacity) {
                    flags |= MachineSnapshot.FLAG_FULL;
                }
                if (markPatternInputs && patternInputs.contains(key)) {
                    flags |= MachineSnapshot.FLAG_PATTERN_INPUT;
                }
            }
            out.add(new MachineSnapshot.Slot(generic, capacity, flags));
            used++;
        }
        return used;
    }

    /** True when every slot holds a full stack, i.e. nothing more can be inserted. */
    public static boolean isCompletelyFull(IItemHandler handler) {
        int slots = handler.getSlots();
        if (slots == 0) {
            return false;
        }
        for (int i = 0; i < slots; i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) {
                return false;
            }
            long cap = Math.min(handler.getSlotLimit(i), stack.getMaxStackSize());
            if (stack.getCount() < cap) {
                return false;
            }
        }
        return true;
    }
}
