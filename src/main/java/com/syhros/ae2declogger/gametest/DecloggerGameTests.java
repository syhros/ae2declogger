package com.syhros.ae2declogger.gametest;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
import com.syhros.ae2declogger.server.MachineInspector;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Runs inside a real (headless) server with AE2 loaded: builds small ME networks, starts crafting jobs and checks what
 * the analyser reports. Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(Ae2Declogger.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DecloggerGameTests {

    /** Fast timings so tests do not have to wait minutes: slow 0.5 s, stall 1.5 s, blocked 0.5 s. */
    private static final Thresholds FAST = new Thresholds(500, 1500, 500, 2.0, 500);
    private static final GridAnalyzer.Limits LIMITS = GridAnalyzer.Limits.DEFAULT;

    private DecloggerGameTests() {
    }

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
            helper.assertTrue(!jobs.isEmpty() && condition.test(jobs.get(0)),
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
    @GameTest(template = "empty", timeoutTicks = 1200)
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
                .thenWaitUntil(() -> helper.assertTrue(net.chestCount(Items.COBBLESTONE) == 4,
                        "expected 4 cobblestone pushed, got " + net.chestCount(Items.COBBLESTONE)))
                .thenExecute(() -> analyze(net, tracker)) // first look
                .thenWaitUntil(until(helper, net, tracker, j -> j.state() == JobState.STALLED, "STALLED"))
                .thenExecute(() -> {
                    GridAnalysis a = analyze(net, tracker);
                    helper.assertTrue(a.cpus() == 1 && a.busyCpus() == 1, "cpu counts " + a.cpus() + "/" + a.busyCpus());
                    helper.assertTrue(a.jobs().size() == 1, "expected one job, got " + a.jobs());
                    JobView job = a.jobs().get(0);
                    helper.assertTrue(job.output().equals(TestNetwork.STONE) && job.amount() == 4,
                            "wrong job output " + job.output() + " x" + job.amount());
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    helper.assertTrue(stone.active() == 4 && stone.pending() == 0,
                            "stone step amounts active=" + stone.active() + " pending=" + stone.pending());
                    helper.assertTrue(stone.state() == JobState.STALLED, "stone step state " + stone.state());
                    helper.assertTrue(stone.reason() == BlockReason.OUTPUT_NOT_RETURNING,
                            "stone step reason " + stone.reason());
                    helper.assertTrue(job.state() == JobState.STALLED, "job state " + job.state());
                    MachineRef machine = job.primaryMachine();
                    helper.assertTrue(machine != null && machine.pos().equals(net.abs(TestNetwork.CHEST)),
                            "primary machine should be the chest, got " + machine);
                    helper.assertTrue(a.machines().contains(GlobalPos.of(net.level().dimension(),
                            net.abs(TestNetwork.CHEST))), "chest missing from allowed viewer positions");

                    // Nothing changed: a second look must produce an identical row (so no network traffic).
                    GridAnalysis again = analyze(net, tracker);
                    helper.assertTrue(again.jobs().equals(a.jobs()), "job row changed without any change in game");

                    // Wire format round trip.
                    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                    job.write(buf);
                    JobView back = JobView.read(buf);
                    helper.assertTrue(job.equals(back), "JobView did not survive encode/decode");
                    helper.assertTrue(buf.readableBytes() == 0, "JobView left unread bytes");
                    seen.set(job);
                })
                .thenExecute(() -> {
                    // Read-only viewer on the chest.
                    MachineSnapshot snap = MachineInspector.snapshot(net.level(), net.abs(TestNetwork.CHEST),
                            Set.of(TestNetwork.COBBLE), 108);
                    helper.assertTrue(snap.sections().size() == 1, "chest sections " + snap.sections());
                    MachineSnapshot.Section section = snap.sections().get(0);
                    helper.assertTrue(section.kind() == MachineSnapshot.SectionKind.INVENTORY, "section kind");
                    helper.assertTrue(section.slots().size() == 27, "chest slots " + section.slots().size());
                    MachineSnapshot.Slot first = section.slots().get(0);
                    helper.assertTrue(first.stack() != null && first.stack().what().equals(TestNetwork.COBBLE)
                            && first.stack().amount() == 4, "first slot " + first.stack());
                    helper.assertTrue(first.has(MachineSnapshot.FLAG_PATTERN_INPUT), "pattern input not flagged");
                    helper.assertTrue(!first.has(MachineSnapshot.FLAG_FULL), "4 cobblestone is not a full stack");
                    // The snapshot must not have touched the chest.
                    helper.assertTrue(net.chestCount(Items.COBBLESTONE) == 4, "viewer changed the chest");

                    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                    snap.write(buf);
                    MachineSnapshot back = MachineSnapshot.read(buf);
                    helper.assertTrue(back.equals(snap), "MachineSnapshot did not survive encode/decode");
                })
                .thenExecute(() -> net.insert(TestNetwork.STONE, 4))
                .thenWaitUntil(() -> helper.assertFalse(net.cpu().isBusy(), "job still running"))
                .thenExecute(() -> {
                    GridAnalysis done = analyze(net, tracker);
                    helper.assertTrue(done.jobs().isEmpty(), "finished job still listed");
                    List<HistoryEntry> history = tracker.historySince(-1);
                    helper.assertTrue(history.size() == 1, "history " + history);
                    HistoryEntry entry = history.get(0);
                    helper.assertTrue(entry.completed(), "job should be recorded as complete");
                    helper.assertTrue(entry.output().equals(TestNetwork.STONE) && entry.amount() == 4,
                            "history entry " + entry);
                    helper.assertTrue(entry.durationMs() > 0, "duration " + entry.durationMs());
                })
                .thenSucceed();
    }

    /**
     * Blocking mode with the previous inputs still in the chest: the provider refuses to push, the step must become
     * BLOCKED with PROVIDER_BLOCKING after the blocked delay.
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void blockingModeIsReportedAsBlocked(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        net.blockingMode(true);
        ((ChestBlockEntity) helper.getBlockEntity(TestNetwork.CHEST)).setItem(0, new ItemStack(Items.COBBLESTONE));
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
                    helper.assertTrue(stone.pending() == 2 && stone.active() == 0,
                            "nothing should have been pushed: " + stone);
                })
                .thenWaitUntil(until(helper, net, tracker, j -> j.state() == JobState.BLOCKED, "BLOCKED"))
                .thenExecute(() -> {
                    GridAnalysis a = analyze(net, tracker);
                    JobView job = a.jobs().get(0);
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    helper.assertTrue(stone.state() == JobState.BLOCKED, "step state " + stone.state());
                    helper.assertTrue(stone.reason() == BlockReason.PROVIDER_BLOCKING, "step reason " + stone.reason());
                    helper.assertTrue(job.state() == JobState.BLOCKED, "job state " + job.state());
                    helper.assertTrue(job.reason() == BlockReason.PROVIDER_BLOCKING, "job reason " + job.reason());
                    boolean chestListed = stone.machines().stream()
                            .anyMatch(m -> m.pos().equals(net.abs(TestNetwork.CHEST)));
                    helper.assertTrue(chestListed, "chest not listed as the machine: " + stone.machines());
                    helper.assertTrue(net.chestCount(Items.COBBLESTONE) == 1, "chest contents changed");
                })
                .thenSucceed();
    }

    /**
     * A provider with nothing next to it accepts the pattern but cannot push anywhere (PUSH_REJECTED). Removing the
     * provider entirely must then give NO_PROVIDER.
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
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
                    helper.assertTrue(stone.state() == JobState.BLOCKED, "step state " + stone.state());
                    helper.assertTrue(stone.reason() == BlockReason.PUSH_REJECTED, "step reason " + stone.reason());
                    // Nothing to push into: the provider itself is listed.
                    helper.assertTrue(stone.machines().stream().anyMatch(m -> m.kind() == MachineRef.Kind.PROVIDER
                            && m.pos().equals(net.abs(TestNetwork.PROVIDER))), "provider not listed " + stone.machines());
                })
                .thenExecute(() -> helper.setBlock(TestNetwork.PROVIDER, Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(until(helper, net, tracker, j -> j.reason() == BlockReason.NO_PROVIDER, "NO_PROVIDER"))
                .thenExecute(() -> {
                    JobView job = analyze(net, tracker).jobs().get(0);
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    helper.assertTrue(stone.reason() == BlockReason.NO_PROVIDER, "step reason " + stone.reason());
                    helper.assertTrue(stone.state() == JobState.BLOCKED, "step state " + stone.state());
                })
                .thenSucceed();
    }

    /**
     * The network loses power mid-job. AE2 then leaves the CPU out of its CPU list; the monitor must keep showing the
     * job as CPU offline instead of dropping it and logging it as cancelled.
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
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
                .thenWaitUntil(() -> helper.assertTrue(net.chestCount(Items.COBBLESTONE) == 2, "not pushed yet"))
                .thenExecute(() -> analyze(net, tracker))
                .thenExecute(() -> helper.setBlock(TestNetwork.CELL, Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(until(helper, net, tracker,
                        j -> j.reason() == BlockReason.CPU_OFFLINE && j.state() == JobState.BLOCKED, "CPU offline"))
                .thenExecute(() -> {
                    GridAnalysis a = analyze(net, tracker);
                    helper.assertTrue(!a.powered(), "network should be unpowered");
                    helper.assertTrue(a.jobs().size() == 1, "offline CPU's job disappeared: " + a.jobs());
                    JobView job = a.jobs().get(0);
                    helper.assertTrue(job.reason() == BlockReason.CPU_OFFLINE, "job reason " + job.reason());
                    helper.assertTrue(job.state() == JobState.BLOCKED, "job state " + job.state());
                    helper.assertTrue(tracker.historySince(-1).isEmpty(),
                            "job was logged as finished: " + tracker.historySince(-1));
                })
                .thenSucceed();
    }

    /**
     * A pattern with two outputs: one came back, the other is still at the machine, and the provider is in blocking
     * mode. That is normal operation and must not be reported as blocked.
     */
    @GameTest(template = "empty", timeoutTicks = 1200)
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
                .thenWaitUntil(() -> helper.assertTrue(net.chestCount(Items.COBBLESTONE) == 1, "first push missing"))
                .thenExecute(() -> net.insert(TestNetwork.GRAVEL, 1)) // the gravel half comes back
                .thenIdle(5)
                .thenExecute(() -> analyze(net, tracker))
                .thenIdle(30)
                .thenExecute(() -> {
                    JobView job = analyze(net, tracker).jobs().get(0);
                    StepView gravel = step(helper, job, TestNetwork.GRAVEL);
                    helper.assertTrue(gravel.active() == 0 && gravel.pending() == 1, "gravel step " + gravel);
                    helper.assertTrue(gravel.reason() == BlockReason.MACHINE_BUSY, "gravel reason " + gravel.reason());
                    helper.assertTrue(gravel.state() != JobState.BLOCKED, "gravel step reported blocked");
                    StepView stone = step(helper, job, TestNetwork.STONE);
                    helper.assertTrue(stone.active() == 1, "stone still out: " + stone);
                })
                .thenSucceed();
    }

    /** A job the player cancels must show up in the history as cancelled, not complete. */
    @GameTest(template = "empty", timeoutTicks = 1200)
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
                .thenWaitUntil(() -> helper.assertTrue(net.chestCount(Items.COBBLESTONE) == 3, "not pushed yet"))
                .thenExecute(() -> analyze(net, tracker))
                .thenExecute(() -> net.cpu().cancelJob())
                .thenWaitUntil(() -> helper.assertFalse(net.cpu().isBusy(), "job still running"))
                .thenExecute(() -> {
                    helper.assertTrue(analyze(net, tracker).jobs().isEmpty(), "cancelled job still listed");
                    List<HistoryEntry> history = tracker.historySince(-1);
                    helper.assertTrue(history.size() == 1 && !history.get(0).completed(),
                            "cancelled job should be recorded as cancelled: " + history);
                })
                .thenSucceed();
    }

    /** Resolving a network from a block position, and the error for a non-ME block. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void gridSourceResolves(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, true);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> {
                    var ok = GridSource.resolveAt(net.level(), net.abs(TestNetwork.DRIVE), Direction.UP);
                    helper.assertTrue(ok.grid() != null && ok.error() == null, "drive did not resolve");
                    var chest = GridSource.resolveAt(net.level(), net.abs(TestNetwork.CHEST), null);
                    helper.assertTrue(chest.grid() == null && chest.error() != null, "chest resolved to a grid");
                    var air = GridSource.resolveAt(net.level(), net.abs(new BlockPos(7, 4, 7)), null);
                    helper.assertTrue(air.grid() == null && air.error() != null, "air resolved to a grid");
                })
                .thenSucceed();
    }

    /**
     * What the mod remembers per player (last network for the hotkey, alert network), keeping it on respawn, and
     * finding a block position for a network that resolves back to the same network.
     */
    @GameTest(template = "empty")
    public static void networkMemoryRoundTrip(GameTestHelper helper) {
        TestNetwork net = TestNetwork.build(helper, false);
        helper.startSequence()
                .thenWaitUntil(net::assertReady)
                .thenExecute(() -> {
                    Player player = helper.makeMockPlayer();
                    helper.assertTrue(NetworkMemory.last(player) == null && NetworkMemory.alerts(player) == null,
                            "a new player already has a remembered network");
                    GlobalPos pos = NetworkMemory.positionOf(net.grid(), null);
                    helper.assertTrue(pos != null, "no position found for the network");
                    var back = GridSource.resolveAt(net.level(), pos.pos(), null);
                    helper.assertTrue(back.grid() == net.grid(), "remembered position resolves to another network");

                    NetworkMemory.rememberLast(player, pos);
                    NetworkMemory.setAlerts(player, pos);
                    helper.assertTrue(pos.equals(NetworkMemory.last(player)), "last network did not round-trip");
                    helper.assertTrue(pos.equals(NetworkMemory.alerts(player)), "alert network did not round-trip");

                    Player respawned = helper.makeMockPlayer();
                    NetworkMemory.copy(player, respawned);
                    helper.assertTrue(pos.equals(NetworkMemory.last(respawned)), "memory lost on respawn");

                    NetworkMemory.setAlerts(player, null);
                    helper.assertTrue(NetworkMemory.alerts(player) == null, "alerts did not switch off");
                    helper.assertTrue(pos.equals(NetworkMemory.last(player)), "switching alerts off cleared last");
                })
                .thenSucceed();
    }

    /** Every packet type encodes and decodes without leftovers. */
    @GameTest(template = "empty")
    public static void packetsRoundTrip(GameTestHelper helper) {
        var open = new DeclogNetwork.OpenMonitor(true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        open.write(buf);
        helper.assertTrue(DeclogNetwork.OpenMonitor.read(buf).equals(open) && buf.readableBytes() == 0, "OpenMonitor");

        var req = new DeclogNetwork.RequestMachine(helper.getLevel().dimension(), new BlockPos(1, -60, 300000), true);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        req.write(buf);
        helper.assertTrue(DeclogNetwork.RequestMachine.read(buf).equals(req) && buf.readableBytes() == 0,
                "RequestMachine");

        var watch = new DeclogNetwork.WatchCraftingScreen(true);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        watch.write(buf);
        helper.assertTrue(DeclogNetwork.WatchCraftingScreen.read(buf).equals(watch) && buf.readableBytes() == 0,
                "WatchCraftingScreen");

        var alert = new DeclogNetwork.Alert(TestNetwork.STONE, 64, JobState.STALLED,
                BlockReason.MACHINE_NO_POWER, net.minecraft.network.chat.Component.literal("x"), null);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        alert.write(buf);
        helper.assertTrue(alert.equals(DeclogNetwork.Alert.read(buf)) && buf.readableBytes() == 0, "Alert");
        helper.succeed();
    }
}
