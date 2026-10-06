package com.syhros.ae2declogger.compat.mods;

import java.util.List;
import java.util.function.BooleanSupplier;

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
 * Mekanism machines and factories. Their recipe cache records why it could not run (Mekanism's own
 * {@code RecipeError}: not enough energy, input, output space...), the same warnings its GUI shows.
 */
public final class MekanismReader implements ModReader {

    private static final String TILE = "mekanism.common.tile.base.TileEntityMekanism";
    private static final String RECIPE_MACHINE = "mekanism.common.tile.prefab.TileEntityRecipeMachine";
    private static final String PROGRESS_MACHINE = "mekanism.common.tile.prefab.TileEntityProgressMachine";
    private static final String FACTORY = "mekanism.common.tile.factory.TileEntityFactory";
    private static final String ERROR = "mekanism.api.recipes.cache.CachedRecipe$OperationTracker$RecipeError";
    private static final String UTILS = "mekanism.common.util.MekanismUtils";

    /** Checked in this order: the most basic cause first. */
    private static final String[][] ERRORS = { { "NOT_ENOUGH_ENERGY", "not_enough_energy" },
            { "NOT_ENOUGH_ENERGY_REDUCED_RATE", "not_enough_energy" }, { "NOT_ENOUGH_OUTPUT_SPACE", "output_full" },
            { "INPUT_DOESNT_PRODUCE_OUTPUT", "no_recipe" }, { "NOT_ENOUGH_INPUT", "no_input" },
            { "NOT_ENOUGH_SECONDARY_INPUT", "no_input" }, { "NOT_ENOUGH_LEFT_INPUT", "no_input" },
            { "NOT_ENOUGH_RIGHT_INPUT", "no_input" } };

    @Override
    public String modId() {
        return "mekanism";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (!Refl.isA(be, TILE)) {
            return null;
        }
        MachineInfo.Builder b = R.builder(modId(), be);
        // 1.21 has canFunction() on the tile; older versions have the static MekanismUtils.canFunction(tile).
        boolean canFunction = Refl.has(be, "canFunction", 0) ? Refl.bool(be, "canFunction")
                : Boolean.TRUE.equals(Refl.callStatic(UTILS, "canFunction", be));
        if (!canFunction) {
            b.problem(BlockReason.MACHINE_DISABLED, R.text("redstone_off"));
        }
        if (Refl.isA(be, RECIPE_MACHINE)) {
            for (String[] e : ERRORS) {
                if (warning(Refl.call(be, "getWarningCheck", Refl.enumConst(ERROR, e[0])))) {
                    b.problem(reason(e[1]), R.text(e[1]));
                }
            }
        } else if (Refl.isA(be, FACTORY)) {
            int processes = (int) (Integer) Refl.field(Refl.field(be, "tier"), "processes");
            for (String[] e : ERRORS) {
                Object err = Refl.enumConst(ERROR, e[0]);
                for (int i = 0; i < processes; i++) {
                    if (warning(Refl.call(be, "getWarningCheck", err, i))) {
                        b.problem(reason(e[1]), R.text(e[1]));
                        break;
                    }
                }
            }
        }
        if (Refl.isA(be, PROGRESS_MACHINE)) {
            int required = (int) Refl.num(be, "getTicksRequired");
            if (required > 0) {
                b.progress((int) Refl.num(be, "getOperatingTicks"), required);
            }
        }
        R.emptyEnergy(b, be);
        b.status(Refl.bool(be, "getActive") ? MachineStatus.WORKING
                : b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.IDLE);
        return b.build();
    }

    private static boolean warning(@Nullable Object check) {
        return check instanceof BooleanSupplier s && s.getAsBoolean();
    }

    private static BlockReason reason(String key) {
        return switch (key) {
            case "not_enough_energy" -> BlockReason.MACHINE_NO_POWER;
            case "output_full" -> BlockReason.MACHINE_OUTPUT_FULL;
            case "no_recipe" -> BlockReason.MACHINE_NO_RECIPE;
            default -> BlockReason.MACHINE_WAITING;
        };
    }

    @Override
    public List<String> names() {
        // canFunction moved from MekanismUtils (static) onto the tile in 1.21; read() handles both.
        return List.of(TILE + "#getActive", TILE + "#getControlType",
                RECIPE_MACHINE + "#getWarningCheck", FACTORY + "#getWarningCheck", FACTORY + "@tier",
                "mekanism.common.tier.FactoryTier@processes", PROGRESS_MACHINE + "#getOperatingTicks",
                PROGRESS_MACHINE + "#getTicksRequired", ERROR + "@NOT_ENOUGH_ENERGY",
                ERROR + "@NOT_ENOUGH_OUTPUT_SPACE", ERROR + "@NOT_ENOUGH_INPUT", ERROR + "@INPUT_DOESNT_PRODUCE_OUTPUT");
    }
}
