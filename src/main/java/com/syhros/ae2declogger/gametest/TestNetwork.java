package com.syhros.ae2declogger.gametest;

import java.util.concurrent.Future;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.server.GridSource;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;

/**
 * A minimal ME network for game tests, built in a row (relative coordinates):
 *
 * <pre>
 * y=1: [creative energy cell][drive + 1k cell][1k crafting storage][pattern provider]
 * y=2:                                                              [chest] (optional)
 * </pre>
 *
 * The pattern provider holds one processing pattern: 1 cobblestone -> 1 stone. Nothing ever turns the cobblestone
 * pushed into the chest into stone, so jobs stall until the test inserts stone itself.
 */
final class TestNetwork {

    static final BlockPos CELL = new BlockPos(1, 1, 1);
    static final BlockPos DRIVE = new BlockPos(2, 1, 1);
    static final BlockPos CPU = new BlockPos(3, 1, 1);
    static final BlockPos PROVIDER = new BlockPos(4, 1, 1);
    static final BlockPos CHEST = new BlockPos(4, 2, 1);

    static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);
    static final AEItemKey STONE = AEItemKey.of(Items.STONE);

    final GameTestHelper helper;
    @Nullable
    Future<ICraftingPlan> plan;

    private TestNetwork(GameTestHelper helper) {
        this.helper = helper;
    }

    static final AEItemKey GRAVEL = AEItemKey.of(Items.GRAVEL);

    static TestNetwork build(GameTestHelper helper, boolean withChest) {
        return build(helper, withChest, false);
    }

    /** @param multiOutput pattern makes stone and gravel from one cobblestone */
    static TestNetwork build(GameTestHelper helper, boolean withChest, boolean multiOutput) {
        return build(helper, withChest, multiOutput, false);
    }

    /**
     * @param multiOutput pattern makes stone and gravel from one cobblestone
     * @param twoSteps    a second pattern makes the cobblestone from gravel, so a stone job has an intermediate step
     */
    static TestNetwork build(GameTestHelper helper, boolean withChest, boolean multiOutput, boolean twoSteps) {
        helper.setBlock(CELL, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        helper.setBlock(DRIVE, AEBlocks.DRIVE.block().defaultBlockState());
        helper.setBlock(CPU, AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
        helper.setBlock(PROVIDER, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
        if (withChest) {
            helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState());
        }

        DriveBlockEntity drive = (DriveBlockEntity) helper.getBlockEntity(DRIVE);
        drive.getInternalInventory().addItems(AEItems.ITEM_CELL_1K.stack());

        PatternProviderBlockEntity provider = (PatternProviderBlockEntity) helper.getBlockEntity(PROVIDER);
        GenericStack[] outputs = multiOutput
                ? new GenericStack[] { new GenericStack(STONE, 1), new GenericStack(GRAVEL, 1) }
                : new GenericStack[] { new GenericStack(STONE, 1) };
        ItemStack pattern = processingPattern(
                new GenericStack[] { new GenericStack(COBBLE, 1) }, outputs);
        provider.getLogic().getPatternInv().addItems(pattern);
        if (twoSteps) {
            provider.getLogic().getPatternInv().addItems(processingPattern(
                    new GenericStack[] { new GenericStack(GRAVEL, 1) }, new GenericStack[] { new GenericStack(COBBLE, 1) }));
        }
        provider.getLogic().getConfigManager().putSetting(Settings.BLOCKING_MODE, YesNo.NO);
        return new TestNetwork(helper);
    }

    ServerLevel level() {
        return helper.getLevel();
    }

    BlockPos abs(BlockPos relative) {
        return helper.absolutePos(relative);
    }

    @Nullable
    IGrid grid() {
        return GridSource.resolveAt(level(), abs(DRIVE), null).grid();
    }

    IGrid requireGrid() {
        IGrid grid = grid();
        Asserts.isTrue(helper, grid != null, "network not formed yet");
        return grid;
    }

    /** Throws until the network is powered, has a CPU and knows the pattern. */
    void assertReady() {
        IGrid grid = requireGrid();
        Asserts.isTrue(helper, grid.getEnergyService().isNetworkPowered(), "network not powered yet");
        Asserts.isTrue(helper, !grid.getCraftingService().getCpus().isEmpty(), "no crafting CPU yet");
        Asserts.isTrue(helper, grid.getCraftingService().isCraftable(STONE), "pattern not registered yet");
    }

    void insert(AEItemKey key, long amount) {
        long inserted = requireGrid().getStorageService().getInventory().insert(key, amount, Actionable.MODULATE,
                IActionSource.empty());
        Asserts.isTrue(helper, inserted == amount, "could only insert " + inserted + " of " + amount + " " + key);
    }

    void startCalculation(long amount) {
        IGrid grid = requireGrid();
        // AE2 only looks at patterns when the requester has a grid node, so plan as a machine on this grid.
        IActionSource source = IActionSource.ofMachine(grid::getPivot);
        plan = grid.getCraftingService().beginCraftingCalculation(level(), () -> source, STONE, amount,
                CalculationStrategy.REPORT_MISSING_ITEMS);
    }

    void assertPlanReady() {
        Asserts.isTrue(helper, plan != null && plan.isDone(), "crafting calculation still running");
    }

    void submit() {
        try {
            ICraftingPlan p = plan.get();
            Asserts.isTrue(helper, !p.simulation(), "plan is missing " + describe(p.missingItems()) + "; used "
                    + describe(p.usedItems()) + "; network has " + describe(requireGrid().getStorageService()
                            .getCachedInventory()) + "; craftable stone=" + requireGrid().getCraftingService()
                                    .isCraftable(STONE));
            var result = submit(requireGrid().getCraftingService(), p);
            Asserts.isTrue(helper, result.successful(), "job was not accepted: " + result.errorCode());
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    static String describe(appeng.api.stacks.KeyCounter counter) {
        StringBuilder sb = new StringBuilder("[");
        for (var e : counter) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(e.getKey().getId()).append(" x").append(e.getLongValue());
        }
        return sb.append(']').toString();
    }

    ICraftingCPU cpu() {
        return requireGrid().getCraftingService().getCpus().iterator().next();
    }

    int chestCount(net.minecraft.world.item.Item item) {
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(CHEST);
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack s = chest.getItem(i);
            if (s.is(item)) {
                count += s.getCount();
            }
        }
        return count;
    }

    void blockingMode(boolean on) {
        PatternProviderBlockEntity provider = (PatternProviderBlockEntity) helper.getBlockEntity(PROVIDER);
        provider.getLogic().getConfigManager().putSetting(Settings.BLOCKING_MODE, on ? YesNo.YES : YesNo.NO);
    }

    /** Starts a planned job with no requester (AE2 11 names it trySubmitJob). */
    static appeng.api.networking.crafting.ICraftingSubmitResult submit(
            appeng.api.networking.crafting.ICraftingService crafting, ICraftingPlan plan) {
        //#if MC >= 1.19
        return crafting.submitJob(plan, null, null, false, IActionSource.empty());
        //#else
        //$$ return crafting.trySubmitJob(plan, null, null, false, IActionSource.empty());
        //#endif
    }

    /** A processing pattern item (AE2 19 takes lists instead of arrays). */
    static ItemStack processingPattern(GenericStack[] inputs, GenericStack[] outputs) {
        //#if MC >= 1.21
        //$$ return PatternDetailsHelper.encodeProcessingPattern(java.util.Arrays.asList(inputs),
        //$$         java.util.Arrays.asList(outputs));
        //#else
        return PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
        //#endif
    }

    /** The pattern provider's logic (an AE2 crafting provider). */
    Object providerLogic() {
        return ((PatternProviderBlockEntity) helper.getBlockEntity(PROVIDER)).getLogic();
    }
}
