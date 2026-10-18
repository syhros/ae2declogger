package com.syhros.ae2declogger.selftest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
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

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingJob;

/**
 * Check on a real development server, the same as the modern versions' server self-test (enabled with
 * {@code -Dae2declogger.serverSelfTest=true}; CI runs it for each legacy version). Builds a network next to spawn and
 * runs a two-step job (gravel to cobblestone to stone) whose "machine" is a chest, standing in for the machine by
 * inserting the results. Logs one PASS or FAIL line.
 *
 * <pre>
 * [creative energy cell][drive + 1k cell][1k crafting storage][interface]
 *                                                              [chest]  (on top of the interface)
 * </pre>
 */
public final class ServerSelfTest {

    private static final boolean ENABLED = Boolean.getBoolean("ae2declogger.serverSelfTest");
    /** Stop the server once the result is logged. */
    private static final boolean EXIT = Boolean.getBoolean("ae2declogger.selfTestExit");
    private static final Thresholds FAST = new Thresholds(500, 1500, 500, 2.0, 500);
    private static final int AMOUNT = 3;

    private static int stage;
    private static int ticks;
    private static int total;
    private static LPos origin;
    private static Future<ICraftingJob> plan;
    private static final GridTracker TRACKER = new GridTracker(20);
    private static final List<String> checks = new ArrayList<>();
    private static String last = "";

    private ServerSelfTest() {
    }

    public static boolean enabled() {
        return ENABLED && stage >= 0;
    }

    /** Called at the end of every server tick. */
    public static void tick() {
        if (!enabled()) {
            return;
        }
        ticks++;
        total++;
        try {
            step();
        } catch (RuntimeException | LinkageError e) {
            Ae2Declogger.LOG.error("Server self-test crashed", e);
            done(false, "exception " + e);
        }
        if (stage >= 0 && total > 6000) {
            done(false, "timed out in stage " + stage + ", last: " + last);
        }
    }

    private static LPos drive() {
        return origin.offset(5);
    }

    private static LPos cpu() {
        return drive().offset(5);
    }

    private static LPos iface() {
        return cpu().offset(5);
    }

    private static LPos chest() {
        return iface().offset(1);
    }

    private static void step() {
        switch (stage) {
            case 0: {
                if (ticks < 40) {
                    return;
                }
                origin = TestWorld.origin();
                TestWorld.place(origin, TestWorld.CREATIVE_ENERGY_CELL);
                TestWorld.place(drive(), TestWorld.DRIVE);
                TestWorld.place(cpu(), TestWorld.CRAFTING_STORAGE_1K);
                TestWorld.place(iface(), TestWorld.INTERFACE);
                TestWorld.place(chest(), TestWorld.CHEST);
                TestWorld.insertCell(drive());
                TestWorld.addPattern(iface(), TestWorld.COBBLE, TestWorld.STONE);
                TestWorld.addPattern(iface(), TestWorld.GRAVEL, TestWorld.COBBLE);
                Ae2Declogger.LOG.info("Server self-test: built at {}", origin);
                next();
                break;
            }
            case 1: {
                IGrid grid = grid();
                if (grid == null || !TestWorld.ready(grid)) {
                    last = "network not ready";
                    return;
                }
                TestWorld.inject(grid, TestWorld.GRAVEL, 16);
                next();
                break;
            }
            case 2: {
                if (ticks < 10) {
                    return;
                }
                plan = TestWorld.beginCrafting(grid(), TestWorld.STONE, AMOUNT);
                next();
                break;
            }
            case 3: {
                if (!plan.isDone()) {
                    return;
                }
                try {
                    String error = TestWorld.submit(grid(), plan.get());
                    if (error != null) {
                        done(false, "job not accepted: " + error);
                        return;
                    }
                } catch (Exception e) {
                    done(false, "plan failed: " + e);
                    return;
                }
                next();
                break;
            }
            case 4: {
                // The gravel went to the chest; nothing comes back on its own, so the job stalls.
                if (TestWorld.count(chest(), TestWorld.GRAVEL) < AMOUNT) {
                    last = "waiting for the gravel push, chest has " + TestWorld.count(chest(), TestWorld.GRAVEL);
                    analyze();
                    return;
                }
                GridAnalysis a = analyze();
                if (a.jobs.isEmpty()) {
                    last = "no job listed";
                    return;
                }
                JobView job = a.jobs.get(0);
                last = job.state + " / " + job.reason + " / " + (job.primaryMachine == null ? null
                        : job.primaryMachine.pos);
                if (job.state != JobState.STALLED) {
                    return;
                }
                LKey stone = TestWorld.key(TestWorld.STONE);
                check(job.output.equals(stone) && job.amount == AMOUNT, "job " + job.output + " x" + job.amount);
                check(job.reason == BlockReason.OUTPUT_NOT_RETURNING, "stall reason " + job.reason);
                MachineRef m = job.primaryMachine;
                check(m != null && m.pos.equals(chest()), "stalled job points at " + (m == null ? null : m.pos));
                MachineSnapshot snap = MachineInspector.snapshot(chest(),
                        Collections.singleton(TestWorld.key(TestWorld.GRAVEL)), 108);
                boolean gravelSeen = false;
                for (MachineSnapshot.Section s : snap.sections) {
                    for (MachineSnapshot.Slot slot : s.slots) {
                        gravelSeen |= TestWorld.key(TestWorld.GRAVEL).equals(slot.item);
                    }
                }
                check(gravelSeen, "machine viewer does not show the pushed gravel");
                TestWorld.inject(grid(), TestWorld.COBBLE, AMOUNT); // the "machine" made the cobblestone
                next();
                break;
            }
            case 5: {
                if (TestWorld.count(chest(), TestWorld.COBBLE) < AMOUNT) {
                    last = "waiting for the cobblestone push, chest has " + TestWorld.count(chest(), TestWorld.COBBLE);
                    analyze();
                    return;
                }
                analyze();
                TestWorld.inject(grid(), TestWorld.STONE, AMOUNT);
                next();
                break;
            }
            case 6: {
                IGrid grid = grid();
                boolean busy = TestWorld.anyBusy(grid);
                analyze();
                if (busy || ticks < 40) {
                    last = busy ? "job still running" : "letting the background pass catch up";
                    return;
                }
                // Every name read by reflection exists in this AE2 (and GregTech, on GTNH) version.
                for (String name : com.syhros.ae2declogger.server.Ae.names()) {
                    check(!name.startsWith("missing:"), "AE2 " + name);
                }
                for (String name : com.syhros.ae2declogger.compat.Machines.names()) {
                    check(!name.startsWith("missing:"), "machine reader " + name);
                }
                Ae2Declogger.LOG.info("Server self-test: reflected names {} / {}", com.syhros.ae2declogger.server.Ae.names(),
                        com.syhros.ae2declogger.compat.Machines.names());
                checkHistory("test tracker", TRACKER.historySince(-1));
                checkHistory("background pass", DecloggerServer.trackerForTests(grid).historySince(-1));
                done(checks.isEmpty(), checks.isEmpty() ? "two-step job followed, stalled, finished and recorded"
                        : "problems: " + checks);
                break;
            }
            default:
        }
    }

    private static void checkHistory(String who, List<HistoryEntry> history) {
        LKey stone = TestWorld.key(TestWorld.STONE);
        LKey cobble = TestWorld.key(TestWorld.COBBLE);
        HistoryEntry job = null;
        HistoryEntry step = null;
        for (HistoryEntry e : history) {
            if (!e.isStep() && e.output.equals(stone) && job == null) {
                job = e;
            }
            if (e.isStep() && e.output.equals(cobble) && step == null) {
                step = e;
            }
        }
        check(job != null && job.completed && job.amount == AMOUNT, who + ": stone job entry missing or wrong ("
                + history.size() + " entries)");
        check(step != null && step.amount == AMOUNT && stone.equals(step.partOf),
                who + ": cobblestone step missing or wrong (" + history.size() + " entries)");
    }

    private static GridAnalysis analyze() {
        return GridAnalyzer.analyze(grid(), TRACKER, System.currentTimeMillis(), FAST, GridAnalyzer.Limits.DEFAULT);
    }

    private static IGrid grid() {
        return GridSource.resolveAt(drive(), -1).grid;
    }

    private static void check(boolean ok, String problem) {
        if (!ok) {
            checks.add(problem);
        }
    }

    private static void next() {
        stage++;
        ticks = 0;
    }

    private static void done(boolean pass, String message) {
        stage = -1;
        Ae2Declogger.LOG.info("AE2 DECLOGGER SERVER SELFTEST {}: {}", pass ? "PASS" : "FAIL", message);
        if (EXIT) {
            TestWorld.stopServer();
        }
    }
}
