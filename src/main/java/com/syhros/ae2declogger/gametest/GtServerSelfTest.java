package com.syhros.ae2declogger.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.compat.gtceu.GtCompat;
import com.syhros.ae2declogger.compat.gtceu.GtMachineInfo;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.Thresholds;
import com.syhros.ae2declogger.server.GridAnalysis;
import com.syhros.ae2declogger.server.GridAnalyzer;
import com.syhros.ae2declogger.server.GridSource;
import com.syhros.ae2declogger.server.GridTracker;
import com.syhros.ae2declogger.server.MachineInspector;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.crafting.PatternDetailsHelper;
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
 * Production check of the GregTech integration (CI only, enabled with -Dae2declogger.gtSelfTest=true on a real Forge
 * server with AE2 and GregTech CEu Modern). Feeds an unpowered LV Electric Furnace from a pattern provider and expects
 * the analyser to report "Machine has no power" through GregTech's own API. Logs one PASS or FAIL line.
 *
 * <pre>
 * [energy cell][drive][CPU][pattern provider]
 *                          [LV electric furnace]  (on top of the provider)
 * </pre>
 */
@Mod.EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GtServerSelfTest {

    private static final boolean ENABLED = Boolean.getBoolean("ae2declogger.gtSelfTest");
    private static final ResourceLocation FURNACE_ID = new ResourceLocation("gtceu", "lv_electric_furnace");
    private static final Thresholds FAST = new Thresholds(500, 1500, 500, 2.0, 500);
    private static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);
    private static final AEItemKey STONE = AEItemKey.of(Items.STONE);

    private static int stage;
    private static int ticks;
    private static BlockPos origin = BlockPos.ZERO;
    private static Future<ICraftingPlan> plan;
    private static final GridTracker TRACKER = new GridTracker(10);
    private static String last = "";

    private GtServerSelfTest() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || stage < 0) {
            return;
        }
        ticks++;
        try {
            step(event.getServer());
        } catch (RuntimeException | LinkageError e) {
            Ae2Declogger.LOG.error("GT self-test crashed", e);
            done(false, "exception " + e);
        }
        if (stage >= 0 && ticks > 2400) {
            done(false, "timed out in stage " + stage + ", last: " + last);
        }
    }

    private static BlockPos cell() {
        return origin;
    }

    private static BlockPos drive() {
        return origin.east(1);
    }

    private static BlockPos provider() {
        return origin.east(3);
    }

    private static BlockPos furnace() {
        return provider().above();
    }

    private static void step(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (stage) {
            case 0 -> {
                if (ticks < 40) {
                    return;
                }
                if (!GtCompat.isActive()) {
                    done(false, "GregTech integration is not active");
                    return;
                }
                Block furnaceBlock = BuiltInRegistries.BLOCK.get(FURNACE_ID);
                if (furnaceBlock == Blocks.AIR) {
                    done(false, "block " + FURNACE_ID + " not registered");
                    return;
                }
                BlockPos spawn = level.getSharedSpawnPos();
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX() + 3, spawn.getZ() + 3);
                origin = new BlockPos(spawn.getX() + 3, y, spawn.getZ() + 3);
                level.setBlockAndUpdate(cell(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
                level.setBlockAndUpdate(drive(), AEBlocks.DRIVE.block().defaultBlockState());
                level.setBlockAndUpdate(origin.east(2), AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
                level.setBlockAndUpdate(provider(), AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
                level.setBlockAndUpdate(furnace(), furnaceBlock.defaultBlockState());
                ((DriveBlockEntity) level.getBlockEntity(drive())).getInternalInventory()
                        .addItems(AEItems.ITEM_CELL_1K.stack());
                PatternProviderBlockEntity p = (PatternProviderBlockEntity) level.getBlockEntity(provider());
                p.getLogic().getPatternInv().addItems(PatternDetailsHelper.encodeProcessingPattern(
                        new GenericStack[] { new GenericStack(COBBLE, 1) },
                        new GenericStack[] { new GenericStack(STONE, 1) }));
                p.getLogic().getConfigManager().putSetting(Settings.BLOCKING_MODE, YesNo.NO);
                Ae2Declogger.LOG.info("GT self-test: built at {}", origin);
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
                grid.getStorageService().getInventory().insert(COBBLE, 16, Actionable.MODULATE,
                        IActionSource.empty());
                next();
            }
            case 2 -> {
                if (ticks < 10) {
                    return;
                }
                IGrid grid = grid(level);
                IActionSource source = IActionSource.ofMachine(grid::getPivot);
                plan = grid.getCraftingService().beginCraftingCalculation(level, () -> source, STONE, 2,
                        CalculationStrategy.REPORT_MISSING_ITEMS);
                next();
            }
            case 3 -> {
                if (!plan.isDone()) {
                    return;
                }
                try {
                    var result = grid(level).getCraftingService().submitJob(plan.get(), null, null, false,
                            IActionSource.empty());
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
                GridAnalysis a = GridAnalyzer.analyze(grid(level), TRACKER, System.currentTimeMillis(), FAST,
                        GridAnalyzer.Limits.DEFAULT);
                if (a.jobs().isEmpty()) {
                    last = "no job listed";
                    return;
                }
                JobView job = a.jobs().get(0);
                last = job.state() + " / " + job.reason() + " / " + job.primaryMachine();
                if (job.reason() != BlockReason.MACHINE_NO_POWER) {
                    return;
                }
                List<String> problems = new ArrayList<>();
                MachineRef m = job.primaryMachine();
                if (m == null || !m.pos().equals(furnace())) {
                    problems.add("primary machine is not the furnace: " + m);
                }
                GtMachineInfo info = GtCompat.inspect(level, furnace());
                if (info == null) {
                    problems.add("GtCompat.inspect returned null");
                } else {
                    if (!"LV".equals(info.tier())) {
                        problems.add("tier " + info.tier());
                    }
                    if (info.euCapacity() <= 0) {
                        problems.add("no EU capacity");
                    }
                    if (info.problem() != BlockReason.MACHINE_NO_POWER) {
                        problems.add("inspect problem " + info.problem());
                    }
                }
                MachineSnapshot snap = MachineInspector.snapshot(level, furnace(), Set.of(COBBLE), 108);
                if (!"EU".equals(snap.energyUnit())) {
                    problems.add("energy unit '" + snap.energyUnit() + "'");
                }
                boolean hasInput = snap.sections().stream()
                        .anyMatch(s -> s.kind() == MachineSnapshot.SectionKind.INPUT);
                boolean hasOutput = snap.sections().stream()
                        .anyMatch(s -> s.kind() == MachineSnapshot.SectionKind.OUTPUT);
                if (!hasInput || !hasOutput) {
                    problems.add("sections " + snap.sections().stream().map(s -> s.kind().name()).toList());
                }
                boolean cobbleInside = snap.sections().stream().flatMap(s -> s.slots().stream())
                        .anyMatch(s -> s.stack() != null && s.stack().what().equals(COBBLE));
                if (!cobbleInside) {
                    problems.add("pushed cobblestone not visible in the furnace");
                }
                done(problems.isEmpty(), "job " + job.state() + " / " + job.reason() + " detail="
                        + (job.detail() == null ? "-" : job.detail().getString()) + " machine=" + m
                        + (problems.isEmpty() ? "" : " problems=" + problems));
            }
            default -> {
            }
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
        Ae2Declogger.LOG.info("AE2 DECLOGGER GT SELFTEST {}: {}", pass ? "PASS" : "FAIL", message);
    }
}
