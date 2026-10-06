package com.syhros.ae2declogger.compat.gtceu;

//#if MC == 1.20.1

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.ITieredMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMaintenanceMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.server.InventoryReader;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
//#if NEOFORGE
//$$ import net.neoforged.neoforge.fluids.capability.IFluidHandler;
//$$ import net.neoforged.neoforge.items.IItemHandler;
//#else
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
//#endif

import appeng.api.stacks.AEKey;

/**
 * The only class that touches GregTech CEu Modern directly. Loaded only when GregTech is installed (through
 * {@link GtCompat}). Read-only.
 */
final class GtMachineReader {

    private GtMachineReader() {
    }

    @Nullable
    static GtMachineInfo inspect(Level level, BlockPos pos) {
        MetaMachine machine = MetaMachine.getMachine(level, pos);
        if (machine == null) {
            return null;
        }
        Component name = level.getBlockState(pos).getBlock().getName();
        MetaMachine worker = machine;
        BlockPos controllerPos = null;
        List<Component> errors = new ArrayList<>();

        if (!(machine instanceof IRecipeLogicMachine) && machine instanceof IMultiPart part) {
            IMultiController controller = firstController(part);
            if (controller == null) {
                Component msg = Component.translatable("ae2declogger.gt.part_without_controller");
                errors.add(msg);
                return new GtMachineInfo(name, MachineStatus.NOT_FORMED, BlockReason.MACHINE_NOT_FORMED, msg, -1,
                        -1, null, -1, -1, "", errors);
            }
            worker = controller.self();
            controllerPos = worker.getPos();
            name = level.getBlockState(controllerPos).getBlock().getName();
        }

        MachineStatus status = MachineStatus.UNKNOWN;
        BlockReason problem = BlockReason.NONE;
        Component detail = null;
        int progress = -1;
        int duration = -1;

        boolean formed = true;
        if (worker instanceof IMultiController controller && !controller.isFormed()) {
            formed = false;
            status = MachineStatus.NOT_FORMED;
            problem = BlockReason.MACHINE_NOT_FORMED;
            detail = Component.translatable("ae2declogger.gt.not_formed");
            errors.add(detail);
        } else if (worker instanceof IRecipeLogicMachine recipeMachine) {
            RecipeLogic logic = recipeMachine.getRecipeLogic();
            switch (logic.getStatus()) {
                case WORKING -> {
                    status = MachineStatus.WORKING;
                    progress = logic.getProgress();
                    duration = logic.getDuration();
                }
                case WAITING -> {
                    status = MachineStatus.WAITING;
                    problem = BlockReason.MACHINE_WAITING;
                    progress = logic.getProgress();
                    duration = logic.getDuration();
                    detail = logic.getWaitingReason();
                    errors.add(detail != null
                            ? Component.translatable("ae2declogger.gt.waiting", detail)
                            : Component.translatable("ae2declogger.gt.waiting_unknown"));
                }
                case SUSPEND -> {
                    status = MachineStatus.SUSPENDED;
                    problem = BlockReason.MACHINE_DISABLED;
                    detail = Component.translatable("ae2declogger.gt.disabled");
                    errors.add(detail);
                }
                default -> {
                    status = MachineStatus.IDLE;
                    List<Component> failures = logic.getFailureReasons();
                    if (failures != null && !failures.isEmpty()) {
                        problem = BlockReason.MACHINE_NO_RECIPE;
                        detail = failures.get(0);
                        for (Component f : failures) {
                            errors.add(Component.translatable("ae2declogger.gt.recipe_failed", f));
                        }
                    }
                }
            }
            if (!logic.isWorkingEnabled() && status != MachineStatus.SUSPENDED) {
                status = MachineStatus.SUSPENDED;
                problem = BlockReason.stronger(problem, BlockReason.MACHINE_DISABLED);
                errors.add(Component.translatable("ae2declogger.gt.disabled"));
            }
        }

        if (formed && worker instanceof IMultiController controller) {
            for (IMultiPart p : controller.getParts()) {
                if (p instanceof IMaintenanceMachine maintenance && maintenance.hasMaintenanceProblems()) {
                    problem = BlockReason.stronger(problem, BlockReason.MACHINE_MAINTENANCE);
                    errors.add(Component.translatable("ae2declogger.gt.maintenance",
                            String.valueOf(maintenance.getNumMaintenanceProblems())));
                    break;
                }
            }
        }

        long euStored = -1;
        long euCapacity = -1;
        IEnergyContainer energy = energyOf(worker, level);
        if (energy != null) {
            euStored = energy.getEnergyStored();
            euCapacity = energy.getEnergyCapacity();
            long oneTick = Math.max(1, energy.getInputVoltage());
            if (euCapacity > 0 && status != MachineStatus.WORKING && euStored < oneTick) {
                Component msg = Component.translatable("ae2declogger.gt.no_energy", String.valueOf(euStored),
                        String.valueOf(euCapacity));
                errors.add(0, msg);
                problem = BlockReason.MACHINE_NO_POWER;
                detail = msg;
            }
        }

        String tier = "";
        if (worker instanceof ITieredMachine tiered) {
            int t = tiered.getTier();
            if (t >= 0 && t < GTValues.VN.length) {
                tier = GTValues.VN[t];
            }
        }

        // Full output buses are a common reason for a multiblock to wait.
        if (formed && worker instanceof IRecipeCapabilityHolder holder && outputsFull(holder)) {
            Component msg = Component.translatable("ae2declogger.gt.output_full");
            errors.add(msg);
            if (problem == BlockReason.NONE || problem == BlockReason.MACHINE_WAITING) {
                problem = BlockReason.MACHINE_OUTPUT_FULL;
                if (detail == null) {
                    detail = msg;
                }
            }
        }

        return new GtMachineInfo(name, status, problem, detail, progress, duration, controllerPos, euStored,
                euCapacity, tier, errors);
    }

    static List<BlockPos> controllersOf(Level level, BlockPos pos) {
        MetaMachine machine = MetaMachine.getMachine(level, pos);
        if (!(machine instanceof IMultiPart part)) {
            return List.of();
        }
        List<BlockPos> result = new ArrayList<>();
        for (IMultiController controller : part.getControllers()) {
            result.add(controller.self().getPos());
        }
        return result;
    }

    static boolean fillSections(Level level, BlockPos pos, Set<AEKey> patternInputs, int maxSlots,
            List<MachineSnapshot.Section> out) {
        MetaMachine machine = MetaMachine.getMachine(level, pos);
        if (machine == null) {
            return false;
        }
        MetaMachine worker = machine;
        if (!(machine instanceof IRecipeCapabilityHolder) && machine instanceof IMultiPart part) {
            IMultiController controller = firstController(part);
            if (controller != null) {
                worker = controller.self();
            }
        }
        if (!(worker instanceof IRecipeCapabilityHolder holder)) {
            return false;
        }
        Map<IO, Map<RecipeCapability<?>, List<IRecipeHandler<?>>>> flat = holder.getCapabilitiesFlat();
        int budget = maxSlots;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IO io : new IO[] { IO.IN, IO.OUT, IO.BOTH }) {
            Map<RecipeCapability<?>, List<IRecipeHandler<?>>> byCap = flat.get(io);
            if (byCap == null) {
                continue;
            }
            List<MachineSnapshot.Slot> items = new ArrayList<>();
            List<MachineSnapshot.Slot> fluids = new ArrayList<>();
            boolean output = io == IO.OUT;
            boolean input = io == IO.IN;
            for (List<IRecipeHandler<?>> handlers : byCap.values()) {
                for (IRecipeHandler<?> handler : handlers) {
                    if (budget <= 0 || !seen.add(handler)) {
                        continue;
                    }
                    if (handler instanceof IItemHandler itemHandler) {
                        budget -= InventoryReader.readItems(itemHandler, items, patternInputs, !input, !output,
                                budget);
                    } else if (handler instanceof IFluidHandler fluidHandler) {
                        budget -= InventoryReader.readFluids(fluidHandler, fluids, patternInputs, !input, !output,
                                budget);
                    }
                }
            }
            MachineSnapshot.SectionKind itemKind = input ? MachineSnapshot.SectionKind.INPUT
                    : output ? MachineSnapshot.SectionKind.OUTPUT : MachineSnapshot.SectionKind.INVENTORY;
            MachineSnapshot.SectionKind fluidKind = input ? MachineSnapshot.SectionKind.FLUID_INPUT
                    : output ? MachineSnapshot.SectionKind.FLUID_OUTPUT : MachineSnapshot.SectionKind.FLUIDS;
            if (!items.isEmpty()) {
                out.add(new MachineSnapshot.Section(itemKind, Component.translatable(
                        "ae2declogger.viewer.section." + itemKind.name().toLowerCase(java.util.Locale.ROOT)), items));
            }
            if (!fluids.isEmpty()) {
                out.add(new MachineSnapshot.Section(fluidKind, Component.translatable(
                        "ae2declogger.viewer.section." + fluidKind.name().toLowerCase(java.util.Locale.ROOT)), fluids));
            }
        }
        return true;
    }

    @Nullable
    private static IMultiController firstController(IMultiPart part) {
        for (IMultiController controller : part.getControllers()) {
            return controller;
        }
        return null;
    }

    @Nullable
    private static IEnergyContainer energyOf(MetaMachine machine, Level level) {
        if (machine instanceof WorkableElectricMultiblockMachine multi) {
            return multi.getEnergyContainer();
        }
        return GTCapabilityHelper.getEnergyContainer(level, machine.getPos(), null);
    }

    private static boolean outputsFull(IRecipeCapabilityHolder holder) {
        Map<RecipeCapability<?>, List<IRecipeHandler<?>>> outputs = holder.getCapabilitiesFlat().get(IO.OUT);
        if (outputs == null) {
            return false;
        }
        boolean sawAny = false;
        for (List<IRecipeHandler<?>> handlers : outputs.values()) {
            for (IRecipeHandler<?> handler : handlers) {
                if (handler instanceof IItemHandler itemHandler && itemHandler.getSlots() > 0) {
                    sawAny = true;
                    if (!InventoryReader.isCompletelyFull(itemHandler)) {
                        return false;
                    }
                }
            }
        }
        return sawAny;
    }
}
//#else
//$$ import java.util.List;
//$$ import java.util.Set;
//$$
//$$ import org.jetbrains.annotations.Nullable;
//$$
//$$ import com.syhros.ae2declogger.data.MachineSnapshot;
//$$
//$$ import net.minecraft.core.BlockPos;
//$$ import net.minecraft.world.level.Level;
//$$
//$$ import appeng.api.stacks.AEKey;
//$$
//$$ /** GregTech CEu Modern is only supported on 1.20.1 so far: nothing to read here. */
//$$ final class GtMachineReader {
//$$
//$$     private GtMachineReader() {
//$$     }
//$$
//$$     @Nullable
//$$     static GtMachineInfo inspect(Level level, BlockPos pos) {
//$$         return null;
//$$     }
//$$
//$$     static List<BlockPos> controllersOf(Level level, BlockPos pos) {
//$$         return List.of();
//$$     }
//$$
//$$     static boolean fillSections(Level level, BlockPos pos, Set<AEKey> patternInputs, int maxSlots,
//$$             List<MachineSnapshot.Section> out) {
//$$         return false;
//$$     }
//$$ }
//#endif
