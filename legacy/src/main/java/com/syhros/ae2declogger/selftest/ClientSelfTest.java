package com.syhros.ae2declogger.selftest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.client.ClientPlat;
import com.syhros.ae2declogger.client.ClientState;
import com.syhros.ae2declogger.client.DecloggerScreen;
import com.syhros.ae2declogger.client.MachineViewerScreen;
import com.syhros.ae2declogger.client.PinManager;
import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.data.GridSummary;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.net.Messages;

/**
 * Client check for CI ({@code -Dae2declogger.clientSelfTest=true}): from the title screen, fills the monitor with
 * made-up data, opens it on each tab and then the machine viewer, saves a screenshot of each
 * ({@code screenshots/declogger-*.png}) and quits. Proves every screen draws on this version without a world or
 * server. Logs one PASS or FAIL line.
 */
public final class ClientSelfTest {

    private static final boolean ENABLED = Boolean.getBoolean("ae2declogger.clientSelfTest");
    private static int stage;
    private static int ticks;
    private static final List<String> shots = new ArrayList<>();

    private ClientSelfTest() {
    }

    /** Called every client tick (end phase). */
    public static void tick() {
        if (!ENABLED || stage < 0) {
            return;
        }
        ticks++;
        try {
            step();
        } catch (RuntimeException | LinkageError e) {
            Ae2Declogger.LOG.error("Client self-test crashed", e);
            done(false, "exception " + e);
        }
    }

    private static void step() {
        switch (stage) {
            case 0:
                if (!ClientPlat.onTitleScreen() || ticks < 60) {
                    return;
                }
                fill();
                ClientPlat.openScreen(new DecloggerScreen());
                next();
                break;
            case 1:
            case 2:
            case 3:
            case 4: {
                // A few frames per tab, then the screenshot.
                UiScreen ui = ClientPlat.currentScreen();
                if (!(ui instanceof DecloggerScreen)) {
                    done(false, "monitor did not open, screen is " + ui);
                    return;
                }
                if (ticks == 2) {
                    ((DecloggerScreen) ui).testSwitchTab(stage - 1);
                }
                if (ticks == 12) {
                    shot("monitor-" + new String[] { "jobs", "history", "stats", "pins" }[stage - 1]);
                    next();
                }
                break;
            }
            case 5:
                if (ticks == 1) {
                    ClientState.machine = snapshot();
                    ClientPlat.openScreen(new MachineViewerScreen(null, MACHINE));
                }
                if (ticks == 12) {
                    shot("viewer");
                    next();
                }
                break;
            case 6:
                if (ticks == 1) {
                    ClientPlat.closeScreen();
                }
                if (ticks == 5) {
                    done(shots.size() == 5, "drew the monitor (4 tabs) and the machine viewer: " + shots);
                    ClientPlat.quit();
                }
                break;
            default:
        }
    }

    private static final LPos MACHINE = new LPos("0", 12, 64, -7);

    private static void shot(String name) {
        ClientPlat.screenshot("declogger-" + name + ".png");
        shots.add(name);
    }

    /** Made-up network: one stalled job with three steps, one running job, some history and a pin. */
    private static void fill() {
        LKey stone = ClientPlat.item("stone");
        LKey cobble = ClientPlat.item("cobblestone");
        LKey gravel = ClientPlat.item("gravel");
        LKey iron = ClientPlat.item("iron_ingot");
        long now = System.currentTimeMillis();
        MachineRef chest = new MachineRef(MACHINE, LText.item(ClientPlat.item("chest")), MachineRef.Kind.MACHINE,
                MachineStatus.UNKNOWN, BlockReason.NONE, null, -1, -1);
        MachineRef furnace = new MachineRef(new LPos("0", 14, 64, -7), LText.item(ClientPlat.item("furnace")),
                MachineRef.Kind.MACHINE, MachineStatus.WORKING, BlockReason.NONE, null, 40, 200);
        List<StepView> steps = Arrays.asList(
                new StepView(stone, 0, 3, 0, JobState.STALLED, BlockReason.OUTPUT_NOT_RETURNING, LText.item(stone),
                        now - 180_000, -1, Collections.singletonList(chest)),
                new StepView(cobble, 2, 0, 5, JobState.BLOCKED, BlockReason.MISSING_INGREDIENT, LText.item(gravel),
                        now - 60_000, -1, Collections.<MachineRef>emptyList()),
                new StepView(gravel, 4, 0, 0, JobState.IN_PROGRESS, BlockReason.NONE, null, now - 5_000, -1,
                        Collections.<MachineRef>emptyList()));
        JobView stalled = new JobView(1, LText.tr("ae2declogger.cpu.numbered", "1"), 2, 65536, stone, 64, 0.42f,
                now - 600_000, now - 180_000, 900_000, JobState.STALLED, BlockReason.OUTPUT_NOT_RETURNING,
                LText.item(stone), chest, steps);
        JobView running = new JobView(2, LText.literal("Main CPU"), 4, 262144, iron, 128, 0.8f, now - 90_000,
                now - 2_000, -1, JobState.IN_PROGRESS, BlockReason.NONE, null, furnace,
                Collections.singletonList(new StepView(iron, 10, 8, 20, JobState.IN_PROGRESS, BlockReason.NONE, null,
                        now - 2_000, 10_000, Collections.singletonList(furnace))));
        List<HistoryEntry> history = Arrays.asList(
                new HistoryEntry(stone, 32, 240_000, true, now - 3_600_000, LText.literal("Main CPU"), null),
                new HistoryEntry(cobble, 32, 120_000, true, now - 3_700_000, LText.literal("Main CPU"), stone),
                new HistoryEntry(iron, 64, 600_000, false, now - 7_200_000, LText.literal("Main CPU"), null));
        ClientState.reset(LText.tr("ae2declogger.monitor.title"));
        ClientState.apply(new Messages.MonitorUpdate(true, new GridSummary(LText.tr("ae2declogger.monitor.title"), 3,
                2, 1_250_000, 2_000_000, true, now), Arrays.asList(stalled, running), new long[0], new long[] { 1, 2 },
                history, true, true, -1));
        PinManager.add("Stone - Chest", MACHINE);
    }

    private static MachineSnapshot snapshot() {
        LKey gravel = ClientPlat.item("gravel");
        List<MachineSnapshot.Slot> slots = new ArrayList<>();
        slots.add(new MachineSnapshot.Slot(gravel, null, 3, 64, MachineSnapshot.FLAG_PATTERN_INPUT));
        slots.add(new MachineSnapshot.Slot(ClientPlat.item("cobblestone"), null, 64, 64, MachineSnapshot.FLAG_FULL));
        for (int i = 0; i < 25; i++) {
            slots.add(new MachineSnapshot.Slot(null, null, 0, 64, (byte) 0));
        }
        List<MachineSnapshot.Slot> tanks = Collections.singletonList(
                new MachineSnapshot.Slot(null, LText.literal("Water"), 8000, 16000, (byte) 0));
        return new MachineSnapshot(MACHINE, LText.item(ClientPlat.item("chest")), MachineStatus.WORKING,
                LText.literal("Smelting"), 60, 200, 4000, 10000, "EU", "LV",
                Collections.singletonList(LText.tr("ae2declogger.machine.inventory_full")),
                Arrays.asList(new MachineSnapshot.Section(MachineSnapshot.SectionKind.INVENTORY,
                        LText.tr("ae2declogger.viewer.section.inventory"), slots),
                        new MachineSnapshot.Section(MachineSnapshot.SectionKind.FLUIDS,
                                LText.tr("ae2declogger.viewer.section.fluids"), tanks)),
                false);
    }

    private static void next() {
        stage++;
        ticks = 0;
    }

    private static void done(boolean pass, String message) {
        stage = -1;
        Ae2Declogger.LOG.info("AE2 DECLOGGER CLIENT SELFTEST {}: {}", pass ? "PASS" : "FAIL", message);
    }
}
