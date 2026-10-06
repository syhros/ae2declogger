package com.syhros.ae2declogger.gametest;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.logic.Thresholds;
import com.syhros.ae2declogger.network.DeclogNetwork;
import com.syhros.ae2declogger.server.GridAnalysis;
import com.syhros.ae2declogger.server.GridAnalyzer;
import com.syhros.ae2declogger.server.GridSource;
import com.syhros.ae2declogger.server.NetworkMemory;
import com.syhros.ae2declogger.server.GridTracker;
import com.syhros.ae2declogger.server.HistorySavedData;
import com.syhros.ae2declogger.server.MachineInspector;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
//#if MC >= 1.20.5
//$$ import net.minecraft.core.component.DataComponents;
//#endif
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
//#if MC < 26.1
import net.minecraft.gametest.framework.GameTest;
//#endif
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
//#if MC >= 26.1
//#elseif NEOFORGE
//$$ import net.neoforged.neoforge.gametest.GameTestHolder;
//$$ import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
//#else
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
//#endif

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Runs inside a real (headless) server with AE2 loaded: builds small ME networks, starts crafting jobs and checks what
 * the analyser reports. Run with {@code ./gradlew runGameTestServer}. On Minecraft 26.1 the annotations are gone and
 * GameTests261 registers every public static test method here instead.
 */
//#if MC < 26.1
@GameTestHolder(Ae2Declogger.MOD_ID)
@PrefixGameTestTemplate(false)
//#endif
public final class DecloggerGameTests {

    /** Fast timings so tests do not have to wait minutes: slow 0.5 s, stall 1.5 s, blocked 0.5 s. */
    private static final Thresholds FAST = new Thresholds(500, 1500, 500, 2.0, 500);
    private static final GridAnalyzer.Limits LIMITS = GridAnalyzer.Limits.DEFAULT;

    private DecloggerGameTests() {
    }

    /**
     * Tick budget for tests that wait for wall-clock thresholds. The 1.21+ test server ticks as fast as it can (a few
     * hundred ticks a second), so the budget is generous; the tests still end as soon as the state is reached.
     */
    private static final int TIMEOUT = 24000;

    private static GridAnalysis analyze(TestNetwork net, GridTracker tracker) {
        return GridAnalyzer.analyze(net.requireGrid(), tracker, System.currentTimeMillis(), FAST, LIMITS);
    }

    /**
     * Thresholds are wall-clock based while the test server may tick faster or slower than 20 TPS, so tests wait for
     * a state instead of a fixed number of ticks.
     */
    private static Runnable until(GameTestHelper helper, TestNetwork net, GridTracker tracker,
            java.util.function.Predicate<JobView> condition, String what) {
        return () -> {
            List<JobView> jobs = analyze(net, tracker).jobs();
            Asserts.isTrue(helper, !jobs.isEmpty() && condition.test(jobs.get(0)),
                    "waiting for " + what + ", have " + (jobs.isEmpty() ? "no job" : jobs.get(0).state() + " / "
                            + jobs.get(0).reason()));
        };
    }

    private static StepView step(GameTestHelper helper, JobView job, Object key) {
        for (StepView s : job.steps()) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        helper.fail("no step for " + key + " in " + job.steps());
        return null;
    }

    /**
     * Items go out to a chest and never come back: the job must show as STALLED, waiting for stone, pointing at the
     * chest. Then the result is inserted and the job must land in the history as COMPLETE.
     */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void stalledJobIsReportedAndCompletes(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        GridTracker tracker = new GridTracker(10);
        AtomicReference<JobView> seen = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 16))
                // AE2 plans against a storage snapshot refreshed once per tick.
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(4))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenWaitUntil(() -> Asserts.isTrue(helper, net.chestCount(Items.COBBLESTONE) == 4,
                        "expected 4 cobblestone pushed, got " + net.chestCount(Items.COBBLESTONE)))
                .thenExecute(() -> analyze(net, tracker)) // first look
                .thenWaitUntil(until(helper, net, tracker, j -> j.state() == JobState.STALLED, "STALLED"))
                .thenExecute(() -> {
                    GridAnalysis a = analyze(net, tracker);
                    Asserts.isTrue(helper, a.cpus() == 1 && a.busyCpus() == 1, "cpu counts " + a.cpus() + "/" + a.busyCpus());
                    Asserts.isTrue(helper, a.jobs().size() == 1, "expected one job, got " + a.jobs());
                    JobView job = a.jobs().get(0);
                    Asserts.isTrue(helper, job.output().equals(TestNetwork.STONE) && job.amount() == 4,
                            "wrong job output " + job.output() + " x" + job.amount());
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    Asserts.isTrue(helper, stone.active() == 4 && stone.pending() == 0,
                            "stone step amounts active=" + stone.active() + " pending=" + stone.pending());
                    Asserts.isTrue(helper, stone.state() == JobState.STALLED, "stone step state " + stone.state());
                    Asserts.isTrue(helper, stone.reason() == BlockReason.OUTPUT_NOT_RETURNING,
                            "stone step reason " + stone.reason());
                    Asserts.isTrue(helper, job.state() == JobState.STALLED, "job state " + job.state());
                    MachineRef machine = job.primaryMachine();
                    Asserts.isTrue(helper, machine != null && machine.pos().equals(net.abs(TestNetwork.CHEST)),
                            "primary machine should be the chest, got " + machine);
                    Asserts.isTrue(helper, a.machines().contains(GlobalPos.of(net.level().dimension(),
                            net.abs(TestNetwork.CHEST))), "chest missing from allowed viewer positions");

                    // Nothing changed: a second look must produce an identical row (so no network traffic).
                    GridAnalysis again = analyze(net, tracker);
                    Asserts.isTrue(helper, again.jobs().equals(a.jobs()), "job row changed without any change in game");

                    // Wire format round trip.
                    FriendlyByteBuf buf = buffer(helper);
                    job.write(buf);
                    JobView back = JobView.read(buf);
                    Asserts.isTrue(helper, job.equals(back), "JobView did not survive encode/decode");
                    Asserts.isTrue(helper, buf.readableBytes() == 0, "JobView left unread bytes");
                    seen.set(job);
                })
                .thenExecute(() -> {
                    // Read-only viewer on the chest.
                    MachineSnapshot snap = MachineInspector.snapshot(net.level(), net.abs(TestNetwork.CHEST),
                            Set.of(TestNetwork.COBBLE), 108);
                    Asserts.isTrue(helper, snap.sections().size() == 1, "chest sections " + snap.sections());
                    MachineSnapshot.Section section = snap.sections().get(0);
                    Asserts.isTrue(helper, section.kind() == MachineSnapshot.SectionKind.INVENTORY, "section kind");
                    Asserts.isTrue(helper, section.slots().size() == 27, "chest slots " + section.slots().size());
                    MachineSnapshot.Slot first = section.slots().get(0);
                    Asserts.isTrue(helper, first.stack() != null && first.stack().what().equals(TestNetwork.COBBLE)
                            && first.stack().amount() == 4, "first slot " + first.stack());
                    Asserts.isTrue(helper, first.has(MachineSnapshot.FLAG_PATTERN_INPUT), "pattern input not flagged");
                    Asserts.isTrue(helper, !first.has(MachineSnapshot.FLAG_FULL), "4 cobblestone is not a full stack");
                    // The snapshot must not have touched the chest.
                    Asserts.isTrue(helper, net.chestCount(Items.COBBLESTONE) == 4, "viewer changed the chest");

                    FriendlyByteBuf buf = buffer(helper);
                    snap.write(buf);
                    MachineSnapshot back = MachineSnapshot.read(buf);
                    Asserts.isTrue(helper, back.equals(snap), "MachineSnapshot did not survive encode/decode");
                })
                .thenExecute(() -> net.insert(TestNetwork.STONE, 4))
                .thenWaitUntil(() -> Asserts.isFalse(helper, net.cpu().isBusy(), "job still running"))
                .thenExecute(() -> {
                    GridAnalysis done = analyze(net, tracker);
                    Asserts.isTrue(helper, done.jobs().isEmpty(), "finished job still listed");
                    List<HistoryEntry> history = tracker.historySince(-1);
                    Asserts.isTrue(helper, history.size() == 1, "history " + history);
                    HistoryEntry entry = history.get(0);
                    Asserts.isTrue(helper, entry.completed(), "job should be recorded as complete");
                    Asserts.isTrue(helper, entry.output().equals(TestNetwork.STONE) && entry.amount() == 4,
                            "history entry " + entry);
                    Asserts.isTrue(helper, entry.durationMs() > 0, "duration " + entry.durationMs());
                })
                .thenSucceed();
    }

    /**
     * Blocking mode with the previous inputs still in the chest: the provider refuses to push, the step must become
     * BLOCKED with PROVIDER_BLOCKING after the blocked delay.
     */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void blockingModeIsReportedAsBlocked(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        net.blockingMode(true);
        (TestNetwork.blockEntity(helper, TestNetwork.CHEST, ChestBlockEntity.class)).setItem(0, new ItemStack(Items.COBBLESTONE));
        GridTracker tracker = new GridTracker(10);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 16))
                // AE2 plans against a storage snapshot refreshed once per tick.
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(2))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenIdle(10)
                .thenExecute(() -> {
                    GridAnalysis first = analyze(net, tracker);
                    JobView job = first.jobs().get(0);
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    Asserts.isTrue(helper, stone.pending() == 2 && stone.active() == 0,
                            "nothing should have been pushed: " + stone);
                })
                .thenWaitUntil(until(helper, net, tracker, j -> j.state() == JobState.BLOCKED, "BLOCKED"))
                .thenExecute(() -> {
                    GridAnalysis a = analyze(net, tracker);
                    JobView job = a.jobs().get(0);
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    Asserts.isTrue(helper, stone.state() == JobState.BLOCKED, "step state " + stone.state());
                    Asserts.isTrue(helper, stone.reason() == BlockReason.PROVIDER_BLOCKING, "step reason " + stone.reason());
                    Asserts.isTrue(helper, job.state() == JobState.BLOCKED, "job state " + job.state());
                    Asserts.isTrue(helper, job.reason() == BlockReason.PROVIDER_BLOCKING, "job reason " + job.reason());
                    boolean chestListed = stone.machines().stream()
                            .anyMatch(m -> m.pos().equals(net.abs(TestNetwork.CHEST)));
                    Asserts.isTrue(helper, chestListed, "chest not listed as the machine: " + stone.machines());
                    Asserts.isTrue(helper, net.chestCount(Items.COBBLESTONE) == 1, "chest contents changed");
                })
                .thenSucceed();
    }

    /**
     * A provider with nothing next to it accepts the pattern but cannot push anywhere (PUSH_REJECTED). Removing the
     * provider entirely must then give NO_PROVIDER.
     */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void noTargetThenNoProvider(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, false);
        GridTracker tracker = new GridTracker(10);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 16))
                // AE2 plans against a storage snapshot refreshed once per tick.
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(2))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenIdle(5)
                .thenExecute(() -> analyze(net, tracker))
                .thenWaitUntil(until(helper, net, tracker, j -> j.state() == JobState.BLOCKED, "BLOCKED"))
                .thenExecute(() -> {
                    JobView job = analyze(net, tracker).jobs().get(0);
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    Asserts.isTrue(helper, stone.state() == JobState.BLOCKED, "step state " + stone.state());
                    Asserts.isTrue(helper, stone.reason() == BlockReason.PUSH_REJECTED, "step reason " + stone.reason());
                    // Nothing to push into: the provider itself is listed.
                    Asserts.isTrue(helper, stone.machines().stream().anyMatch(m -> m.kind() == MachineRef.Kind.PROVIDER
                            && m.pos().equals(net.abs(TestNetwork.PROVIDER))), "provider not listed " + stone.machines());
                })
                .thenExecute(() -> helper.setBlock(TestNetwork.PROVIDER, Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(until(helper, net, tracker, j -> j.reason() == BlockReason.NO_PROVIDER, "NO_PROVIDER"))
                .thenExecute(() -> {
                    JobView job = analyze(net, tracker).jobs().get(0);
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    Asserts.isTrue(helper, stone.reason() == BlockReason.NO_PROVIDER, "step reason " + stone.reason());
                    Asserts.isTrue(helper, stone.state() == JobState.BLOCKED, "step state " + stone.state());
                })
                .thenSucceed();
    }

    /**
     * The network loses power mid-job. AE2 then leaves the CPU out of its CPU list; the monitor must keep showing the
     * job as CPU offline instead of dropping it and logging it as cancelled.
     */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void offlineCpuStaysListed(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        GridTracker tracker = new GridTracker(10);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 16))
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(2))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenWaitUntil(() -> Asserts.isTrue(helper, net.chestCount(Items.COBBLESTONE) == 2, "not pushed yet"))
                .thenExecute(() -> analyze(net, tracker))
                .thenExecute(() -> helper.setBlock(TestNetwork.CELL, Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(until(helper, net, tracker,
                        j -> j.reason() == BlockReason.CPU_OFFLINE && j.state() == JobState.BLOCKED, "CPU offline"))
                .thenExecute(() -> {
                    GridAnalysis a = analyze(net, tracker);
                    Asserts.isTrue(helper, !a.powered(), "network should be unpowered");
                    Asserts.isTrue(helper, a.jobs().size() == 1, "offline CPU's job disappeared: " + a.jobs());
                    JobView job = a.jobs().get(0);
                    Asserts.isTrue(helper, job.reason() == BlockReason.CPU_OFFLINE, "job reason " + job.reason());
                    Asserts.isTrue(helper, job.state() == JobState.BLOCKED, "job state " + job.state());
                    Asserts.isTrue(helper, tracker.historySince(-1).isEmpty(),
                            "job was logged as finished: " + tracker.historySince(-1));
                })
                .thenSucceed();
    }

    /**
     * A pattern with two outputs: one came back, the other is still at the machine, and the provider is in blocking
     * mode. That is normal operation and must not be reported as blocked.
     */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void multiOutputWaitsForMachine(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true, true);
        net.blockingMode(true);
        GridTracker tracker = new GridTracker(10);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 16))
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(2))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenWaitUntil(() -> Asserts.isTrue(helper, net.chestCount(Items.COBBLESTONE) == 1, "first push missing"))
                .thenExecute(() -> net.insert(TestNetwork.GRAVEL, 1)) // the gravel half comes back
                .thenIdle(5)
                .thenExecute(() -> analyze(net, tracker))
                .thenIdle(30)
                .thenExecute(() -> {
                    JobView job = analyze(net, tracker).jobs().get(0);
                    StepView gravel = step(helper, job, TestNetwork.GRAVEL);
                    Asserts.isTrue(helper, gravel.active() == 0 && gravel.pending() == 1, "gravel step " + gravel);
                    Asserts.isTrue(helper, gravel.reason() == BlockReason.MACHINE_BUSY, "gravel reason " + gravel.reason());
                    Asserts.isTrue(helper, gravel.state() != JobState.BLOCKED, "gravel step reported blocked");
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    Asserts.isTrue(helper, stone.active() == 1, "stone still out: " + stone);
                })
                .thenSucceed();
    }

    /** A job the player cancels must show up in the history as cancelled, not complete. */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void cancelledJobIsRecordedAsCancelled(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        GridTracker tracker = new GridTracker(10);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 16))
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(3))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenWaitUntil(() -> Asserts.isTrue(helper, net.chestCount(Items.COBBLESTONE) == 3, "not pushed yet"))
                .thenExecute(() -> analyze(net, tracker))
                .thenExecute(() -> Asserts.cancel(net.cpu()))
                .thenWaitUntil(() -> Asserts.isFalse(helper, net.cpu().isBusy(), "job still running"))
                .thenExecute(() -> {
                    Asserts.isTrue(helper, analyze(net, tracker).jobs().isEmpty(), "cancelled job still listed");
                    List<HistoryEntry> history = tracker.historySince(-1);
                    Asserts.isTrue(helper, history.size() == 1 && !history.get(0).completed(),
                            "cancelled job should be recorded as cancelled: " + history);
                })
                .thenSucceed();
    }

    /**
     * An add-on CPU shaped like Advanced AE's quantum computer threads is read through reflection exactly like AE2's
     * own CPU, gets its multiblock's number, and when it disappears after its job its crafting link decides whether
     * the job counts as done or cancelled.
     */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void addonCpuFinishedJobIsDone(GameTestHelper helper) {
        addonCpuTest(helper, false);
    }

    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void addonCpuCancelledJobIsCancelled(GameTestHelper helper) {
        addonCpuTest(helper, true);
    }

    private static void addonCpuTest(GameTestHelper helper, boolean cancel) {
        TestNetwork net = TestNetwork.build(helper, true);
        GridTracker nativeTracker = new GridTracker(10);
        GridTracker addonTracker = new GridTracker(10);
        AtomicReference<FakeAddonCpu> addon = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 16))
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(4))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenWaitUntil(() -> Asserts.isTrue(helper, net.chestCount(Items.COBBLESTONE) == 4,
                        "expected 4 cobblestone pushed, got " + net.chestCount(Items.COBBLESTONE)))
                .thenExecute(() -> addon.set(new FakeAddonCpu((CraftingCPUCluster) net.cpu())))
                // Both trackers take a first look, then the add-on CPU's job must be seen to stall on its own.
                .thenExecute(() -> {
                    long now = System.currentTimeMillis();
                    GridAnalyzer.analyzeCpuForTests(net.requireGrid(), nativeTracker, net.cpu(), now, FAST, LIMITS);
                    GridAnalyzer.analyzeCpuForTests(net.requireGrid(), addonTracker, addon.get(), now, FAST, LIMITS);
                })
                // Both trackers look at every tick, at the same moment, so they see the same changes (a tracker that
                // looked only at the start would restart its stall timer on a change the other already saw).
                .thenWaitUntil(() -> {
                    long now = System.currentTimeMillis();
                    JobView real = GridAnalyzer.analyzeCpuForTests(net.requireGrid(), nativeTracker, net.cpu(), now,
                            FAST, LIMITS);
                    JobView read = GridAnalyzer.analyzeCpuForTests(net.requireGrid(), addonTracker, addon.get(), now,
                            FAST, LIMITS);
                    Asserts.isTrue(helper, read.state() == JobState.STALLED && real.state() == JobState.STALLED,
                            "waiting for both jobs to stall, have add-on " + read.state() + ", AE2 " + real.state());
                })
                .thenExecute(() -> {
                    CraftingCPUCluster cluster = (CraftingCPUCluster) net.cpu();
                    FakeAddonCpu addon2 = addon.get();
                    long now = System.currentTimeMillis();
                    JobView real = GridAnalyzer.analyzeCpuForTests(net.requireGrid(), nativeTracker, cluster, now,
                            FAST, LIMITS);
                    JobView read = GridAnalyzer.analyzeCpuForTests(net.requireGrid(), addonTracker, addon2, now,
                            FAST, LIMITS);
                    StepView realStone = step(helper, real, TestNetwork.STONE);
                    StepView readStone = step(helper, read, TestNetwork.STONE);
                    Asserts.isTrue(helper, readStone.active() == realStone.active() && readStone.active() == 4
                            && readStone.stored() == realStone.stored() && readStone.pending() == realStone.pending(),
                            "add-on CPU amounts differ: " + readStone + " vs " + realStone);
                    Asserts.isTrue(helper, readStone.machines().stream()
                            .anyMatch(m -> m.pos().equals(net.abs(TestNetwork.CHEST))),
                            "add-on CPU step does not point at the chest: " + readStone.machines());
                    Asserts.isTrue(helper, read.state() == real.state() && readStone.reason() == realStone.reason(),
                            "add-on CPU " + read.state() + "/" + readStone.reason() + " vs AE2 CPU " + real.state()
                                    + "/" + realStone.reason());
                    Asserts.isTrue(helper, read.progress() == real.progress(),
                            "progress " + read.progress() + " vs " + real.progress());
                    Asserts.isTrue(helper, addonTracker.cpuNumber(addon2) == addonTracker.cpuNumber(cluster),
                            "add-on CPU should share its multiblock's number");
                })
                .thenExecute(() -> {
                    if (cancel) {
                        Asserts.cancel(net.cpu());
                    } else {
                        net.insert(TestNetwork.STONE, 4);
                    }
                })
                .thenWaitUntil(() -> Asserts.isFalse(helper, net.cpu().isBusy(), "job still running"))
                .thenExecute(() -> {
                    // The add-on CPU object is gone after its job, as Advanced AE removes its thread CPUs.
                    addonTracker.retainCpus(Set.<ICraftingCPU>of(), System.currentTimeMillis());
                    List<HistoryEntry> history = addonTracker.historySince(-1);
                    Asserts.isTrue(helper, history.size() == 1, "expected one history entry: " + history);
                    Asserts.isTrue(helper, history.get(0).completed() == !cancel, (cancel
                            ? "cancelled job on a vanished add-on CPU must count as cancelled: "
                            : "finished job on a vanished add-on CPU must count as done: ") + history);
                })
                .thenSucceed();
    }

    /** Resolving a network from a block position, and the error for a non-ME block. */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = 400)
    //#endif
    public static void gridSourceResolves(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> {
                    var ok = GridSource.resolveAt(net.level(), net.abs(TestNetwork.DRIVE), Direction.UP);
                    Asserts.isTrue(helper, ok.grid() != null && ok.error() == null, "drive did not resolve");
                    var chest = GridSource.resolveAt(net.level(), net.abs(TestNetwork.CHEST), null);
                    Asserts.isTrue(helper, chest.grid() == null && chest.error() != null, "chest resolved to a grid");
                    var air = GridSource.resolveAt(net.level(), net.abs(new BlockPos(7, 4, 7)), null);
                    Asserts.isTrue(helper, air.grid() == null && air.error() != null, "air resolved to a grid");
                })
                .thenSucceed();
    }

    /**
     * What the mod remembers per player (last network for the hotkey, alert network), keeping it on respawn, and
     * finding a block position for a network that resolves back to the same network.
     */
    //#if MC < 26.1
    @GameTest(template = "empty")
    //#endif
    public static void networkMemoryRoundTrip(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, false);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> {
                    Player player = mockPlayer(helper);
                    Asserts.isTrue(helper, NetworkMemory.last(player) == null && NetworkMemory.alerts(player) == null,
                            "a new player already has a remembered network");
                    GlobalPos pos = NetworkMemory.positionOf(net.grid(), null);
                    Asserts.isTrue(helper, pos != null, "no position found for the network");
                    var back = GridSource.resolveAt(net.level(), pos.pos(), null);
                    Asserts.isTrue(helper, back.grid() == net.grid(), "remembered position resolves to another network");

                    NetworkMemory.rememberLast(player, pos);
                    NetworkMemory.setAlerts(player, pos);
                    Asserts.isTrue(helper, pos.equals(NetworkMemory.last(player)), "last network did not round-trip");
                    Asserts.isTrue(helper, pos.equals(NetworkMemory.alerts(player)), "alert network did not round-trip");

                    Player respawned = mockPlayer(helper);
                    NetworkMemory.copy(player, respawned);
                    Asserts.isTrue(helper, pos.equals(NetworkMemory.last(respawned)), "memory lost on respawn");

                    NetworkMemory.setAlerts(player, null);
                    Asserts.isTrue(helper, NetworkMemory.alerts(player) == null, "alerts did not switch off");
                    Asserts.isTrue(helper, pos.equals(NetworkMemory.last(player)), "switching alerts off cleared last");
                })
                .thenSucceed();
    }

    /** Every packet type encodes and decodes without leftovers. */
    //#if MC < 26.1
    @GameTest(template = "empty")
    //#endif
    public static void packetsRoundTrip(GameTestHelper helper) {
        var open = new DeclogNetwork.OpenMonitor(true);
        FriendlyByteBuf buf = buffer(helper);
        open.write(buf);
        Asserts.isTrue(helper, DeclogNetwork.OpenMonitor.read(buf).equals(open) && buf.readableBytes() == 0, "OpenMonitor");

        var req = new DeclogNetwork.RequestMachine(helper.getLevel().dimension(), new BlockPos(1, -60, 300000), true);
        buf = buffer(helper);
        req.write(buf);
        Asserts.isTrue(helper, DeclogNetwork.RequestMachine.read(buf).equals(req) && buf.readableBytes() == 0,
                "RequestMachine");

        var watch = new DeclogNetwork.WatchCraftingScreen(true);
        buf = buffer(helper);
        watch.write(buf);
        Asserts.isTrue(helper, DeclogNetwork.WatchCraftingScreen.read(buf).equals(watch) && buf.readableBytes() == 0,
                "WatchCraftingScreen");

        var alert = new DeclogNetwork.Alert(TestNetwork.STONE, 64, JobState.STALLED,
                BlockReason.MACHINE_NO_POWER, Text.literal("x"), null);
        buf = buffer(helper);
        alert.write(buf);
        Asserts.isTrue(helper, alert.equals(DeclogNetwork.Alert.read(buf)) && buf.readableBytes() == 0, "Alert");
        helper.succeed();
    }

    /**
     * A job with an intermediate step (gravel to cobblestone to stone), followed only by the light background pass
     * that runs for networks nobody watches: the history gets the stone job and, as its own entry, the cobblestone
     * step marked as part of it.
     */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void stepsOfAJobAreRecorded(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true, false, true);
        GridTracker tracker = new GridTracker(10);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.GRAVEL, 16))
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(3))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenWaitUntil(() -> Asserts.isTrue(helper, net.chestCount(Items.GRAVEL) == 3,
                        "expected 3 gravel pushed, got " + net.chestCount(Items.GRAVEL)))
                .thenExecute(() -> record(net, tracker))
                .thenIdle(10)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 3)) // the "machine" made the cobblestone
                .thenWaitUntil(() -> Asserts.isTrue(helper, net.chestCount(Items.COBBLESTONE) == 3,
                        "expected 3 cobblestone pushed, got " + net.chestCount(Items.COBBLESTONE)))
                .thenExecute(() -> {
                    record(net, tracker);
                    List<HistoryEntry> history = tracker.historySince(-1);
                    Asserts.isTrue(helper, history.size() == 1, "cobblestone step should be recorded: " + history);
                    HistoryEntry step = history.get(0);
                    Asserts.isTrue(helper, step.isStep() && step.output().equals(TestNetwork.COBBLE)
                            && step.amount() == 3 && TestNetwork.STONE.equals(step.partOf()) && step.completed(),
                            "step entry " + step);
                    Asserts.isTrue(helper, step.durationMs() > 0, "step duration " + step.durationMs());
                })
                .thenExecute(() -> net.insert(TestNetwork.STONE, 3))
                .thenWaitUntil(() -> Asserts.isFalse(helper, net.cpu().isBusy(), "job still running"))
                .thenExecute(() -> {
                    record(net, tracker);
                    List<HistoryEntry> history = tracker.historySince(-1);
                    Asserts.isTrue(helper, history.size() == 2, "expected the step and the job: " + history);
                    HistoryEntry job = history.get(1);
                    Asserts.isTrue(helper, !job.isStep() && job.output().equals(TestNetwork.STONE) && job.amount() == 3
                            && job.completed(), "job entry " + job);
                })
                .thenSucceed();
    }

    /** History is saved with the world and comes back for the same network, steps included. */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = 400)
    //#endif
    public static void historyIsSavedWithTheWorld(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> {
                    HistorySavedData data = new HistorySavedData();
                    GridTracker tracker = new GridTracker(10);
                    tracker.attach(data.storeFor(net.requireGrid()));
                    HistoryEntry job = new HistoryEntry(TestNetwork.STONE, 4, 12_000, true, 1_000,
                            Text.literal("CPU 1"));
                    HistoryEntry step = new HistoryEntry(TestNetwork.COBBLE, 4, 3_000, true, 900,
                            Text.literal("CPU 1"), TestNetwork.STONE);
                    tracker.addHistory(step);
                    tracker.addHistory(job);

                    //#if MC >= 1.20.5
                    //$$ var registries = helper.getLevel().registryAccess();
                    //$$ CompoundTag saved = data.save(new CompoundTag(), registries);
                    //$$ HistorySavedData loaded = HistorySavedData.load(saved, registries);
                    //#else
                    CompoundTag saved = data.save(new CompoundTag());
                    HistorySavedData loaded = HistorySavedData.load(saved);
                    //#endif
                    GridTracker again = new GridTracker(10);
                    again.attach(loaded.storeFor(net.requireGrid()));
                    List<HistoryEntry> back = again.historySince(-1);
                    Asserts.isTrue(helper, back.equals(List.of(step, job)), "history after save and load: " + back);
                })
                .thenSucceed();
    }

    /**
     * A pattern ingredient the CPU holds with different item data than the pattern lists (a used GregTech file or
     * hammer has other damage data) is not missing when the pattern accepts it, as in AE2's own push. Here the CPU
     * holds plain cobblestone (nowhere to push it) and the pattern lists cobblestone with a name.
     */
    //#if MC < 26.1
    @GameTest(template = "empty", timeoutTicks = TIMEOUT)
    //#endif
    public static void ingredientWithOtherDataIsNotMissing(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, false);
        ItemStack named = new ItemStack(Items.COBBLESTONE);
        //#if MC >= 1.20.5
        //$$ named.set(DataComponents.CUSTOM_NAME, Text.literal("Used tool"));
        //#else
        named.setHoverName(Text.literal("Used tool"));
        //#endif
        AEItemKey listed = AEItemKey.of(named);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> net.insert(TestNetwork.COBBLE, 4))
                .thenIdle(5)
                .thenExecute(() -> net.startCalculation(1))
                .thenWaitUntil(net::assertPlanReady)
                .thenExecute(net::submit)
                .thenIdle(20)
                .thenExecute(() -> {
                    ICraftingCPU cpu = net.cpu();
                    Asserts.isTrue(helper, cpu.isBusy(), "job should be waiting with its cobblestone in the CPU");
                    AEKey accepted = GridAnalyzer.firstMissingInputForTests(cpu, pattern(listed, true), net.level());
                    Asserts.isTrue(helper, accepted == null, "accepted ingredient reported missing: " + accepted);
                    AEKey refused = GridAnalyzer.firstMissingInputForTests(cpu, pattern(listed, false), net.level());
                    Asserts.isTrue(helper, listed.equals(refused), "refused ingredient should be missing, got " + refused);
                })
                .thenSucceed();
    }

    /** One input of {@code listed}; {@code acceptsAnyCobble} decides whether plain cobblestone fits it. */
    private static IPatternDetails pattern(AEItemKey listed, boolean acceptsAnyCobble) {
        IPatternDetails.IInput input = new IPatternDetails.IInput() {
            @Override
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] { new GenericStack(listed, 1) };
            }

            @Override
            public long getMultiplier() {
                return 1;
            }

            @Override
            public boolean isValid(AEKey key, net.minecraft.world.level.Level level) {
                return acceptsAnyCobble ? key instanceof AEItemKey item && item.getItem() == Items.COBBLESTONE
                        : key.equals(listed);
            }

            @Override
            public AEKey getRemainingKey(AEKey template) {
                return null;
            }
        };
        return new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(AEItems.PROCESSING_PATTERN.stack());
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] { input };
            }

            @Override
            //#if MC >= 1.21
            //$$ public List<GenericStack> getOutputs() {
            //$$     return List.of(new GenericStack(TestNetwork.STONE, 1));
            //$$ }
            //#else
            public GenericStack[] getOutputs() {
                return new GenericStack[] { new GenericStack(TestNetwork.STONE, 1) };
            }
            //#endif
        };
    }

    /** A packet buffer like the game uses (registry-aware from 1.20.5). */
    private static FriendlyByteBuf buffer(GameTestHelper helper) {
        //#if MC >= 1.20.5
        //$$ return new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(),
        //$$         helper.getLevel().registryAccess());
        //#else
        return new FriendlyByteBuf(Unpooled.buffer());
        //#endif
    }

    private static Player mockPlayer(GameTestHelper helper) {
        //#if MC >= 1.20.5
        //$$ return helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        //#else
        return helper.makeMockPlayer();
        //#endif
    }

    /**
     * Add-on pattern providers that copy AE2's design under their own classes (Advanced AE's advanced pattern
     * provider is not an AE2 PatternProviderLogic) are read by method name: which sides they push into, blocking mode
     * and crafting lock. One without any of those methods is assumed to push into every side.
     */
    //#if MC < 26.1
    @GameTest(template = "empty")
    //#endif
    public static void addonPatternProviderIsReadByName(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> {
                    var provider = (appeng.api.networking.crafting.ICraftingProvider) net.providerLogic();
                    String read = GridAnalyzer.describeAddonProviderForTests(new FakeAddonProviderHost(), provider);
                    Asserts.isTrue(helper, read.equals("sides=[north, up] blocking=true lock=LOCK_UNTIL_PULSE"),
                            "add-on provider read as " + read);
                    String bare = GridAnalyzer.describeAddonProviderForTests(new Object(), provider);
                    Asserts.isTrue(helper, bare.startsWith("sides=[down, east, north, south, up, west]"),
                            "provider without a target list read as " + bare);
                })
                .thenSucceed();
    }

    /** Shaped like Advanced AE's AdvPatternProviderLogicHost: getTargets() and getLogic(), no AE2 interface. */
    public static final class FakeAddonProviderHost {
        public java.util.EnumSet<net.minecraft.core.Direction> getTargets() {
            return java.util.EnumSet.of(net.minecraft.core.Direction.UP, net.minecraft.core.Direction.NORTH);
        }

        public FakeAddonProviderLogic getLogic() {
            return new FakeAddonProviderLogic();
        }
    }

    /** Shaped like Advanced AE's AdvPatternProviderLogic. */
    public static final class FakeAddonProviderLogic {
        public boolean isBlocking() {
            return true;
        }

        public appeng.api.config.LockCraftingMode getCraftingLockedReason() {
            return appeng.api.config.LockCraftingMode.LOCK_UNTIL_PULSE;
        }

        public GenericStack getUnlockStack() {
            return null;
        }
    }

    private static void record(TestNetwork net, GridTracker tracker) {
        GridAnalyzer.record(net.requireGrid(), tracker, System.currentTimeMillis());
    }
}
