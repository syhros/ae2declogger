package com.syhros.ae2declogger.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.logic.Thresholds;
import com.syhros.ae2declogger.server.DecloggerServer;
import com.syhros.ae2declogger.server.GridAnalysis;
import com.syhros.ae2declogger.server.GridAnalyzer;
import com.syhros.ae2declogger.server.GridSource;
import com.syhros.ae2declogger.server.GridTracker;
import com.syhros.ae2declogger.server.MachineInspector;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
//#if NEOFORGE
//$$ import net.neoforged.neoforge.server.ServerLifecycleHooks;
//#else
import net.minecraftforge.server.ServerLifecycleHooks;
//#endif
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
//#if NEOFORGE
//$$ import net.neoforged.bus.api.SubscribeEvent;
//$$ import net.neoforged.fml.common.EventBusSubscriber;
//$$ import net.neoforged.neoforge.event.tick.ServerTickEvent;
//#else
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
//#endif

import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;

/**
 * Production check on a real server (CI only, enabled with -Dae2declogger.serverSelfTest=true), for every Minecraft
 * version and with popular AE2 add-ons installed. It does not need the game test framework, which differs between
 * versions. Builds a network next to spawn and runs a two-step job (gravel to cobblestone to stone) whose "machine" is
 * a chest, standing in for the machine by inserting the results. Logs one PASS or FAIL line.
 *
 * <pre>
 * [energy cell][drive][CPU][pattern provider]
 *                          [chest]  (on top of the provider)
 * </pre>
 *
 * Checks: the job and its step are listed and point at the chest, the stall is detected with the right reason, the
 * machine viewer reads the chest, and the history gets the job and its cobblestone step, both from a tracker driven
 * by this test and from the background pass the mod runs for networks nobody watches.
 */
//#if NEOFORGE
//$$ @EventBusSubscriber(modid = Ae2Declogger.MOD_ID)
//#else
@Mod.EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
//#endif
public final class ServerSelfTest {

    private static final boolean ENABLED = Boolean.getBoolean("ae2declogger.serverSelfTest");
    /** Stop the server once the result is logged (the Gradle run; CI stops it itself). */
    private static final boolean EXIT = Boolean.getBoolean("ae2declogger.selfTestExit");
    private static MinecraftServer server;
    private static final Thresholds FAST = new Thresholds(500, 1500, 500, 2.0, 500);
    private static final AEItemKey GRAVEL = AEItemKey.of(Items.GRAVEL);
    private static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);
    private static final AEItemKey STONE = AEItemKey.of(Items.STONE);
    private static final int AMOUNT = 3;

    private static int stage;
    private static int ticks;
    private static int total;
    private static BlockPos origin = BlockPos.ZERO;
    private static Future<ICraftingPlan> plan;
    private static final GridTracker TRACKER = new GridTracker(20);
    private static final List<String> checks = new ArrayList<>();
    private static String last = "";

    private ServerSelfTest() {
    }

    @SubscribeEvent
    //#if NEOFORGE
    //$$ public static void onServerTick(ServerTickEvent.Post event) {
    //$$     if (!ENABLED || stage < 0) {
    //$$         return;
    //$$     }
    //#else
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || stage < 0) {
            return;
        }
    //#endif
        ticks++;
        total++;
        server = ServerLifecycleHooks.getCurrentServer();
        try {
            step(server);
        } catch (RuntimeException | LinkageError e) {
            Ae2Declogger.LOG.error("Server self-test crashed", e);
            done(false, "exception " + e);
        }
        if (stage >= 0 && total > 6000) {
            done(false, "timed out in stage " + stage + ", last: " + last);
        }
    }

    private static BlockPos drive() {
        return origin.east(1);
    }

    private static BlockPos provider() {
        return origin.east(3);
    }

    private static BlockPos chest() {
        return provider().above();
    }

    private static void step(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (stage) {
            case 0 -> {
                if (ticks < 40) {
                    return;
                }
                BlockPos spawn = level.getSharedSpawnPos();
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX() + 3, spawn.getZ() + 3);
                origin = new BlockPos(spawn.getX() + 3, y, spawn.getZ() + 3);
                level.setBlockAndUpdate(origin, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
                level.setBlockAndUpdate(drive(), AEBlocks.DRIVE.block().defaultBlockState());
                level.setBlockAndUpdate(origin.east(2), AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
                level.setBlockAndUpdate(provider(), AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
                level.setBlockAndUpdate(chest(), Blocks.CHEST.defaultBlockState());
                ((DriveBlockEntity) level.getBlockEntity(drive())).getInternalInventory()
                        .addItems(AEItems.ITEM_CELL_1K.stack());
                PatternProviderBlockEntity p = (PatternProviderBlockEntity) level.getBlockEntity(provider());
                p.getLogic().getPatternInv().addItems(TestNetwork.processingPattern(
                        new GenericStack[] { new GenericStack(COBBLE, 1) },
                        new GenericStack[] { new GenericStack(STONE, 1) }));
                p.getLogic().getPatternInv().addItems(TestNetwork.processingPattern(
                        new GenericStack[] { new GenericStack(GRAVEL, 1) },
                        new GenericStack[] { new GenericStack(COBBLE, 1) }));
                p.getLogic().getConfigManager().putSetting(Settings.BLOCKING_MODE, YesNo.NO);
                Ae2Declogger.LOG.info("Server self-test: built at {}", origin);
                next();
            }
            case 1 -> {
                IGrid grid = grid(level);
                if (grid == null || !grid.getEnergyService().isNetworkPowered()
                        || grid.getCraftingService().getCpus().isEmpty()
                        || !grid.getCraftingService().isCraftable(STONE)) {
                    last = "network not ready";
                    return;
                }
                grid.getStorageService().getInventory().insert(GRAVEL, 16, Actionable.MODULATE,
                        IActionSource.empty());
                next();
            }
            case 2 -> {
                if (ticks < 10) {
                    return;
                }
                IGrid grid = grid(level);
                IActionSource source = IActionSource.ofMachine(grid::getPivot);
                plan = grid.getCraftingService().beginCraftingCalculation(level, () -> source, STONE, AMOUNT,
                        CalculationStrategy.REPORT_MISSING_ITEMS);
                next();
            }
            case 3 -> {
                if (!plan.isDone()) {
                    return;
                }
                try {
                    var result = TestNetwork.submit(grid(level).getCraftingService(), plan.get());
                    if (!result.successful()) {
                        done(false, "job not accepted: " + result.errorCode());
                        return;
                    }
                } catch (Exception e) {
                    done(false, "plan failed: " + e);
                    return;
                }
                next();
            }
            case 4 -> {
                // The gravel went to the chest; nothing comes back on its own, so the job stalls.
                if (count(level, Items.GRAVEL) < AMOUNT) {
                    last = "waiting for the gravel push, chest has " + count(level, Items.GRAVEL);
                    return;
                }
                GridAnalysis a = analyze(level);
                if (a.jobs().isEmpty()) {
                    last = "no job listed";
                    return;
                }
                JobView job = a.jobs().get(0);
                last = job.state() + " / " + job.reason() + " / " + job.primaryMachine();
                if (job.state() != JobState.STALLED) {
                    return;
                }
                check(job.output().equals(STONE) && job.amount() == AMOUNT, "job " + job.output() + " x" + job.amount());
                check(job.reason() == BlockReason.OUTPUT_NOT_RETURNING, "stall reason " + job.reason());
                MachineRef m = job.primaryMachine();
                check(m != null && m.pos().equals(chest()), "stalled job points at " + m);
                MachineSnapshot snap = MachineInspector.snapshot(level, chest(), Set.of(GRAVEL), 108);
                boolean gravelSeen = snap.sections().stream().flatMap(s -> s.slots().stream())
                        .anyMatch(s -> s.stack() != null && s.stack().what().equals(GRAVEL));
                check(gravelSeen, "machine viewer does not show the pushed gravel");
                insert(level, COBBLE, AMOUNT); // the "machine" made the cobblestone
                next();
            }
            case 5 -> {
                if (count(level, Items.COBBLESTONE) < AMOUNT) {
                    last = "waiting for the cobblestone push, chest has " + count(level, Items.COBBLESTONE);
                    analyze(level);
                    return;
                }
                analyze(level);
                insert(level, STONE, AMOUNT);
                next();
            }
            case 6 -> {
                IGrid grid = grid(level);
                boolean busy = grid.getCraftingService().getCpus().stream().anyMatch(c -> c.isBusy());
                analyze(level);
                if (busy || ticks < 40) {
                    last = busy ? "job still running" : "letting the background pass catch up";
                    return;
                }
                checkHistory("test tracker", TRACKER.historySince(-1));
                checkHistory("background pass", DecloggerServer.trackerForTests(grid).historySince(-1));
                done(checks.isEmpty(), checks.isEmpty() ? "two-step job followed, stalled, finished and recorded"
                        : "problems: " + checks);
            }
            default -> {
            }
        }
    }

    private static void checkHistory(String who, List<HistoryEntry> history) {
        HistoryEntry job = history.stream().filter(e -> !e.isStep() && e.output().equals(STONE)).findFirst()
                .orElse(null);
        HistoryEntry step = history.stream().filter(e -> e.isStep() && e.output().equals(COBBLE)).findFirst()
                .orElse(null);
        check(job != null && job.completed() && job.amount() == AMOUNT, who + ": stone job entry " + job + " in "
                + history);
        check(step != null && step.amount() == AMOUNT && STONE.equals(step.partOf()), who + ": cobblestone step "
                + step + " in " + history);
    }

    private static GridAnalysis analyze(ServerLevel level) {
        return GridAnalyzer.analyze(grid(level), TRACKER, System.currentTimeMillis(), FAST,
                GridAnalyzer.Limits.DEFAULT);
    }

    private static void insert(ServerLevel level, AEItemKey key, long amount) {
        grid(level).getStorageService().getInventory().insert(key, amount, Actionable.MODULATE,
                IActionSource.empty());
    }

    private static int count(ServerLevel level, Item item) {
        if (!(level.getBlockEntity(chest()) instanceof Container chest)) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(item)) {
                n += chest.getItem(i).getCount();
            }
        }
        return n;
    }

    private static void check(boolean ok, String problem) {
        if (!ok) {
            checks.add(problem);
        }
    }

    private static IGrid grid(ServerLevel level) {
        return GridSource.resolveAt(level, drive(), null).grid();
    }

    private static void next() {
        stage++;
        ticks = 0;
    }

    private static void done(boolean pass, String message) {
        stage = -1;
        Ae2Declogger.LOG.info("AE2 DECLOGGER SERVER SELFTEST {}: {}", pass ? "PASS" : "FAIL", message);
        if (EXIT && server != null) {
            server.halt(false);
        }
    }
}
