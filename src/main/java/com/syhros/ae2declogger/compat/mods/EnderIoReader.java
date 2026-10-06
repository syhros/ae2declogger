package com.syhros.ae2declogger.compat.mods;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.compat.MachineInfo;
import com.syhros.ae2declogger.compat.ModReader;
import com.syhros.ae2declogger.compat.Refl;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Ender IO machines. They keep a set of {@code MachineState}s (no power, output full, redstone, no capacitor...), the
 * icons on the machine's GUI, each with its own text. The package moved between 1.20.1 and 1.21.1.
 */
public final class EnderIoReader implements ModReader {

    private static final String[] MACHINE = { "com.enderio.enderio.foundation.block.entity.MachineBlockEntity",
            "com.enderio.machines.common.blockentity.base.MachineBlockEntity" };
    private static final String[] STATE = { "com.enderio.enderio.foundation.state.MachineState",
            "com.enderio.machines.common.blockentity.MachineState" };

    /** State name to reason, most basic first. */
    private static final String[][] ORDER = { { "REDSTONE", "MACHINE_DISABLED" }, { "NO_CAPACITOR", "MACHINE_DISABLED" },
            { "NO_POWER", "MACHINE_NO_POWER" }, { "FULL_OUTPUT", "MACHINE_OUTPUT_FULL" },
            { "FULL_TANK", "MACHINE_OUTPUT_FULL" }, { "NOT_SOULBOUND", "MACHINE_NO_RECIPE" },
            { "EMPTY_INPUT", "MACHINE_WAITING" }, { "NO_SOURCE", "MACHINE_WAITING" },
            { "EMPTY_TANK", "MACHINE_WAITING" } };

    @Override
    public String modId() {
        return "enderio";
    }

    @Override
    @Nullable
    public MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) throws ReflectiveOperationException {
        if (!Refl.isA(be, MACHINE)) {
            return null;
        }
        MachineInfo.Builder b = R.builder(modId(), be);
        Map<String, Object> states = new HashMap<>();
        if (Refl.call(be, "getMachineStates") instanceof Set<?> set) {
            for (Object s : set) {
                String name = Refl.constName(s);
                if (name != null) {
                    states.put(name, s);
                }
            }
        }
        for (String[] o : ORDER) {
            Object s = states.get(o[0]);
            if (s != null) {
                b.problem(BlockReason.valueOf(o[1]), Refl.call(s, "component") instanceof Component c ? c : null);
            }
        }
        b.status(states.containsKey("ACTIVE") ? MachineStatus.WORKING
                : b.hasProblem() ? MachineStatus.UNKNOWN : MachineStatus.IDLE);
        return b.build();
    }

    @Override
    public List<String> names() {
        String machine = String.join("|", MACHINE);
        String state = String.join("|", STATE);
        return List.of(machine + "#getMachineStates", state + "#component", state + "@NO_POWER",
                state + "@FULL_OUTPUT", state + "@REDSTONE", state + "@ACTIVE");
    }
}
