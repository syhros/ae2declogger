package com.syhros.ae2declogger.gametest;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.compat.MachineInfo;
import com.syhros.ae2declogger.compat.Machines;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.server.MachineInspector;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
//#if NEOFORGE
//$$ import net.neoforged.bus.api.SubscribeEvent;
//$$ import net.neoforged.fml.common.EventBusSubscriber;
//$$ import net.neoforged.neoforge.event.tick.ServerTickEvent;
//$$ import net.neoforged.neoforge.items.IItemHandler;
//$$ import net.neoforged.neoforge.server.ServerLifecycleHooks;
//#else
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.server.ServerLifecycleHooks;
//#endif

/**
 * CI check of the machine status integrations (compat/), on a real server with the tech mods installed (enabled with
 * -Dae2declogger.modSelfTest=true). For every installed mod with a reader:
 * <ul>
 * <li>every class and member name the reader uses exists in the installed version;</li>
 * <li>one of its machines, placed without power (plus an input where that matters), is read by that reader and stays
 * switched on; where the outcome is certain (a Create machine with no rotation, a basin with nothing above it, an iron
 * furnace with ore and no fuel, an unpowered Mekanism machine with ore) the problem must be reported.</li>
 * </ul>
 * Each reading is logged, then one PASS or FAIL line.
 */
//#if NEOFORGE
//$$ @EventBusSubscriber(modid = Ae2Declogger.MOD_ID)
//#else
@Mod.EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
//#endif
public final class ModMachineSelfTest {

    private static final boolean ENABLED = Boolean.getBoolean("ae2declogger.modSelfTest");

    /**
     * mod id, block ids (the first registered is used), input item or null, expected problem or null (any reading
     * counts).
     */
    private record Case(String mod, String[] blocks, @Nullable String input, @Nullable BlockReason expect) {
    }

    private static final List<Case> CASES = List.of(
            new Case("mekanism", ids("mekanism:enrichment_chamber"), "minecraft:iron_ore", BlockReason.MACHINE_NO_POWER),
            new Case("enderio", ids("enderio:alloy_smelter"), "minecraft:iron_ingot", null),
            new Case("modern_industrialization", ids("modern_industrialization:electric_furnace"), "minecraft:raw_iron",
                    null),
            new Case("industrialforegoing", ids("industrialforegoing:dissolution_chamber"), null, null),
            new Case("create", ids("create:millstone"), null, BlockReason.MACHINE_NO_POWER),
            new Case("create", ids("create:basin"), null, BlockReason.MACHINE_NOT_FORMED),
            new Case("powah", ids("powah:energizing_orb"), null, null),
            new Case("oritech", ids("oritech:pulverizer_block", "oritech:pulverizer"), "minecraft:raw_iron", null),
            new Case("ironfurnaces", ids("ironfurnaces:iron_furnace"), "minecraft:raw_iron", BlockReason.MACHINE_NO_POWER));

    private static int ticks;
    private static boolean finished;
    private static BlockPos origin = BlockPos.ZERO;
    private static final List<BlockPos> placed = new ArrayList<>();
    private static final List<Case> placedCases = new ArrayList<>();

    private ModMachineSelfTest() {
    }

    private static String[] ids(String... ids) {
        return ids;
    }

    @SubscribeEvent
    //#if NEOFORGE
    //$$ public static void onServerTick(ServerTickEvent.Post event) {
    //$$     if (!ENABLED || finished) {
    //$$         return;
    //$$     }
    //#else
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || finished || event.phase != TickEvent.Phase.END) {
            return;
        }
    //#endif
        ticks++;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        try {
            if (ticks == 1) {
                prepare(server.overworld());
            } else if (ticks == 40) {
                place(server.overworld());
            } else if (ticks == 140) {
                check(server.overworld());
            }
        } catch (RuntimeException | LinkageError e) {
            Ae2Declogger.LOG.error("Mod machine self-test crashed", e);
            done(false, "exception " + e);
        }
    }

    private static void prepare(ServerLevel level) {
        //#if MC >= 26.1
        //$$ BlockPos spawn = level.getRespawnData().globalPos().pos();
        //#else
        BlockPos spawn = level.getSharedSpawnPos();
        //#endif
        // A row 12 blocks north of spawn, clear of the server self-test's network.
        origin = new BlockPos(spawn.getX(), 0, spawn.getZ() - 12);
        for (int dx = 0; dx <= CASES.size() * 3 + 16; dx += 16) {
            level.setChunkForced((origin.getX() + dx) >> 4, origin.getZ() >> 4, true);
        }
    }

    private static void place(ServerLevel level) {
        int x = origin.getX();
        for (Case c : CASES) {
            if (!Machines.isLoaded(c.mod())) {
                continue;
            }
            Block block = null;
            for (String id : c.blocks()) {
                block = Mc.block(id);
                if (block != null && !block.defaultBlockState().isAir()) {
                    break;
                }
                block = null;
            }
            if (block == null) {
                done(false, c.mod() + ": none of the blocks " + String.join(", ", c.blocks()) + " is registered");
                return;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, origin.getZ());
            BlockPos pos = new BlockPos(x, y, origin.getZ());
            level.setBlockAndUpdate(pos, block.defaultBlockState());
            if (c.input() != null) {
                insert(level.getBlockEntity(pos), c.input());
            }
            placed.add(pos);
            placedCases.add(c);
            x += 3;
        }
    }

    private static void insert(@Nullable BlockEntity be, String itemId) {
        Item item = Mc.item(itemId);
        if (be == null || item == null) {
            return;
        }
        if (be instanceof Container container && container.getContainerSize() > 0) {
            container.setItem(0, new ItemStack(item, 8));
            return;
        }
        IItemHandler items = MachineInspector.items(be);
        for (int i = 0; items != null && i < items.getSlots(); i++) {
            if (items.insertItem(i, new ItemStack(item, 8), false).isEmpty()) {
                return;
            }
        }
    }

    private static void check(ServerLevel level) {
        List<String> problems = new ArrayList<>();
        for (String missing : Machines.missingNames()) {
            problems.add("missing name " + missing);
        }
        for (int i = 0; i < placed.size(); i++) {
            Case c = placedCases.get(i);
            BlockPos pos = placed.get(i);
            String block = level.getBlockState(pos).getBlock().getName().getString();
            MachineInfo info = Machines.inspect(level, pos);
            if (info == null) {
                problems.add(c.mod() + ": " + block + " was not read by any integration");
                continue;
            }
            Ae2Declogger.LOG.info("Mod machine self-test {} {}: {} / {} / {}", c.mod(), block, info.status(),
                    info.problem(), info.detail() == null ? "-" : info.detail().getString());
            if (!info.source().equals(c.mod())) {
                problems.add(c.mod() + ": " + block + " was read by " + info.source());
            } else if (c.expect() != null && info.problem() != c.expect()) {
                problems.add(c.mod() + ": " + block + " reported " + info.problem() + ", expected " + c.expect());
            }
        }
        for (String reader : Machines.activeReaders()) {
            if (reader.endsWith("(switched off)")) {
                problems.add(reader);
            }
        }
        String mods = String.join(", ", Machines.activeReaders());
        if (problems.isEmpty()) {
            done(true, placed.size() + " machines read correctly; integrations on: " + mods);
        } else {
            done(false, String.join("; ", problems) + " (integrations: " + mods + ")");
        }
    }

    /** True while this test is enabled and has not logged its result. */
    static boolean pending() {
        return ENABLED && !finished;
    }

    private static void done(boolean pass, String message) {
        finished = true;
        Ae2Declogger.LOG.info("AE2 DECLOGGER MOD SELFTEST {}: {}", pass ? "PASS" : "FAIL", message);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (Boolean.getBoolean("ae2declogger.selfTestExit") && server != null && !ServerSelfTest.pending()) {
            server.halt(false);
        }
    }
}
