package com.syhros.ae2declogger.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.server.GridSource;
import com.syhros.ae2declogger.server.NetworkMemory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
 * Server half of the client self-test (dev only, enabled with -Dae2declogger.selfTest=true). Builds a demo network
 * next to the player with one job that stalls (stone: results never come back) and one that is blocked (glass:
 * provider in blocking mode, chest still holds sand).
 *
 * <pre>
 * row along +x at the player's feet level:
 * [energy cell][CPU 1][drive][CPU 2][provider A][provider B]
 *                                   [chest A]   [chest B]    (one block up)
 * </pre>
 */
//#if NEOFORGE
//$$ @EventBusSubscriber(modid = Ae2Declogger.MOD_ID)
//#else
@Mod.EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
//#endif
public final class SelfTestServer {

    static final boolean ENABLED = Boolean.getBoolean("ae2declogger.selfTest");

    static final int IDLE = 0;
    static final int BUILD_REQUESTED = 1;
    static final int WAITING_FOR_NETWORK = 2;
    static final int INSERTED = 3;
    static final int CALCULATING = 4;
    static final int RUNNING = 5;
    static final int COMPLETE_STONE_REQUESTED = 6;
    static final int STONE_COMPLETED = 7;
    /** The client asks for AE2's crafting CPU screen of the CPU running the stone job. */
    static final int OPEN_CPU_SCREEN = 10;
    static final int CPU_SCREEN_OPEN = 11;
    static final int FAILED = 99;

    static final AtomicInteger STAGE = new AtomicInteger(IDLE);
    static volatile String failure = "";
    static volatile BlockPos origin = BlockPos.ZERO;

    static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);
    static final AEItemKey STONE = AEItemKey.of(Items.STONE);
    static final AEItemKey SAND = AEItemKey.of(Items.SAND);
    static final AEItemKey GLASS = AEItemKey.of(Items.GLASS);

    private static final List<Future<ICraftingPlan>> PLANS = new ArrayList<>();
    private static int waitTicks;

    private SelfTestServer() {
    }

    static BlockPos cell() {
        return origin;
    }

    static BlockPos drive() {
        return origin.east(2);
    }

    static BlockPos providerA() {
        return origin.east(4);
    }

    static BlockPos providerB() {
        return origin.east(5);
    }

    static BlockPos chestA() {
        return providerA().above();
    }

    static BlockPos chestB() {
        return providerB().above();
    }

    @SubscribeEvent
    //#if NEOFORGE
    //$$ public static void onServerTick(ServerTickEvent.Post event) {
    //$$     if (!ENABLED) {
    //$$         return;
    //$$     }
    //#else
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }
    //#endif
        MinecraftServer server = event.getServer();
        try {
            step(server);
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.error("Self-test server step failed", e);
            failure = e.toString();
            STAGE.set(FAILED);
        }
    }

    private static void step(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (STAGE.get()) {
            case BUILD_REQUESTED -> {
                ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null
                        : server.getPlayerList().getPlayers().get(0);
                if (player == null) {
                    return;
                }
                build(level, player);
                waitTicks = 0;
                STAGE.set(WAITING_FOR_NETWORK);
            }
            case WAITING_FOR_NETWORK -> {
                IGrid grid = grid(level);
                if (++waitTicks > 400) {
                    fail("network never became ready");
                    return;
                }
                if (grid == null || !grid.getEnergyService().isNetworkPowered()
                        || grid.getCraftingService().getCpus().size() < 2
                        || !grid.getCraftingService().isCraftable(STONE)
                        || !grid.getCraftingService().isCraftable(GLASS)) {
                    return;
                }
                insert(grid, COBBLE, 32);
                insert(grid, SAND, 32);
                waitTicks = 0;
                STAGE.set(INSERTED);
            }
            case INSERTED -> {
                // AE2 plans against a storage snapshot refreshed once per tick.
                if (++waitTicks < 10) {
                    return;
                }
                IGrid grid = grid(level);
                PLANS.clear();
                // AE2 only looks at patterns when the requester has a grid node.
                IActionSource source = IActionSource.ofMachine(grid::getPivot);
                PLANS.add(grid.getCraftingService().beginCraftingCalculation(level, () -> source, STONE, 4,
                        CalculationStrategy.REPORT_MISSING_ITEMS));
                PLANS.add(grid.getCraftingService().beginCraftingCalculation(level, () -> source, GLASS, 2,
                        CalculationStrategy.REPORT_MISSING_ITEMS));
                waitTicks = 0;
                STAGE.set(CALCULATING);
            }
            case CALCULATING -> {
                if (++waitTicks > 400) {
                    fail("crafting calculation did not finish");
                    return;
                }
                for (Future<ICraftingPlan> f : PLANS) {
                    if (!f.isDone()) {
                        return;
                    }
                }
                IGrid grid = grid(level);
                for (Future<ICraftingPlan> f : PLANS) {
                    try {
                        ICraftingPlan plan = f.get();
                        var result = grid.getCraftingService().submitJob(plan, null, null, false,
                                IActionSource.empty());
                        if (!result.successful()) {
                            fail("job not accepted: " + result.errorCode() + "; missing "
                                    + TestNetwork.describe(plan.missingItems()) + "; network has "
                                    + TestNetwork.describe(grid.getStorageService().getCachedInventory()));
                            return;
                        }
                    } catch (Exception e) {
                        fail("plan failed: " + e);
                        return;
                    }
                }
                STAGE.set(RUNNING);
            }
            case OPEN_CPU_SCREEN -> {
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                if (openStoneCpuScreen(level, player)) {
                    STAGE.set(CPU_SCREEN_OPEN);
                } else {
                    fail("no crafting unit found for the CPU running the stone job");
                }
            }
            case COMPLETE_STONE_REQUESTED -> {
                IGrid grid = grid(level);
                if (grid != null) {
                    insert(grid, STONE, 4);
                }
                STAGE.set(STONE_COMPLETED);
            }
            default -> {
            }
        }
    }

    private static void build(ServerLevel level, ServerPlayer player) {
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.setDayTime(6000);
        BlockPos feet = player.blockPosition();
        origin = feet.offset(-2, 0, 5);

        level.setBlockAndUpdate(cell(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        level.setBlockAndUpdate(origin.east(1), AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
        level.setBlockAndUpdate(drive(), AEBlocks.DRIVE.block().defaultBlockState());
        level.setBlockAndUpdate(origin.east(3), AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
        level.setBlockAndUpdate(providerA(), AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
        level.setBlockAndUpdate(providerB(), AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
        level.setBlockAndUpdate(chestA(), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(chestB(), Blocks.CHEST.defaultBlockState());

        ((DriveBlockEntity) level.getBlockEntity(drive())).getInternalInventory().addItems(AEItems.ITEM_CELL_4K.stack());
        PatternProviderBlockEntity a = (PatternProviderBlockEntity) level.getBlockEntity(providerA());
        a.getLogic().getPatternInv().addItems(TestNetwork.processingPattern(
                new GenericStack[] { new GenericStack(COBBLE, 1) }, new GenericStack[] { new GenericStack(STONE, 1) }));
        a.getLogic().getConfigManager().putSetting(Settings.BLOCKING_MODE, YesNo.NO);
        PatternProviderBlockEntity b = (PatternProviderBlockEntity) level.getBlockEntity(providerB());
        b.getLogic().getPatternInv().addItems(TestNetwork.processingPattern(
                new GenericStack[] { new GenericStack(SAND, 1) }, new GenericStack[] { new GenericStack(GLASS, 1) }));
        b.getLogic().getConfigManager().putSetting(Settings.BLOCKING_MODE, YesNo.YES);
        ((ChestBlockEntity) level.getBlockEntity(chestB())).setItem(0, new ItemStack(Items.SAND, 3));

        // The hotkey opens the network the player used last; alerts on for it.
        GlobalPos network = GlobalPos.of(level.dimension(), drive());
        NetworkMemory.rememberLast(player, network);
        NetworkMemory.setAlerts(player, network);

        // Stand back so the whole row and both chests are in view.
        player.teleportTo(level, origin.getX() + 3.5, origin.getY(), origin.getZ() - 4.5, 0f, 20f);
    }

    /** Opens AE2's own crafting CPU screen for the CPU that runs the stone job, as right-clicking the CPU does. */
    private static boolean openStoneCpuScreen(ServerLevel level, ServerPlayer player) {
        for (BlockPos pos : new BlockPos[] { origin.east(1), origin.east(3) }) {
            if (level.getBlockEntity(pos) instanceof appeng.blockentity.crafting.CraftingBlockEntity unit
                    && unit.getCluster() != null) {
                var job = unit.getCluster().craftingLogic.getFinalJobOutput();
                if (job != null && job.what().equals(STONE)) {
                    return appeng.menu.MenuOpener.open(appeng.menu.me.crafting.CraftingCPUMenu.TYPE, player,
                            appeng.menu.locator.MenuLocators.forBlockEntity(unit));
                }
            }
        }
        return false;
    }

    @Nullable
    static IGrid grid(ServerLevel level) {
        return GridSource.resolveAt(level, drive(), null).grid();
    }

    private static void insert(IGrid grid, AEItemKey key, long amount) {
        grid.getStorageService().getInventory().insert(key, amount, Actionable.MODULATE, IActionSource.empty());
    }

    private static void fail(String message) {
        failure = message;
        STAGE.set(FAILED);
    }
}
