package com.syhros.ae2declogger.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.client.ClientState;
import com.syhros.ae2declogger.client.CraftingScreenOverlay;
import com.syhros.ae2declogger.client.pins.PinManager;
import com.syhros.ae2declogger.client.screen.DecloggerScreen;
import com.syhros.ae2declogger.client.screen.MachineViewerScreen;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.data.StepView;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.network.DeclogNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
//#if MC >= 1.19.3
import net.minecraft.core.registries.Registries;
//#endif
import net.minecraft.world.Difficulty;
//#if MC < 26.1
import net.minecraft.world.level.GameRules;
//#endif
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
//#if MC >= 1.19.3
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
//#endif
//#if MC >= 1.19
import net.minecraft.world.level.levelgen.presets.WorldPresets;
//#endif
//#if NEOFORGE
//$$ import net.neoforged.api.distmarker.Dist;
//$$ import net.neoforged.bus.api.SubscribeEvent;
//$$ import net.neoforged.fml.common.EventBusSubscriber;
//$$ import net.neoforged.neoforge.client.event.ClientTickEvent;
//#else
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
//#endif

import appeng.client.gui.me.crafting.CraftingCPUScreen;

/**
 * Automated client run (dev only): creates a flat creative world, lets {@link SelfTestServer} build a demo network,
 * then drives the real screens through real packets, checks what they show and saves screenshots to
 * {@code run/selftest/screenshots}. Writes PASS/FAIL to {@code run/selftest/selftest-result.txt} and quits.
 *
 * <p>
 * Run with {@code xvfb-run ./gradlew runSelfTestClient} (or without xvfb on a desktop).
 */
//#if NEOFORGE
//$$ @EventBusSubscriber(modid = Ae2Declogger.MOD_ID, value = Dist.CLIENT)
//#else
@Mod.EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
//#endif
public final class ClientSelfTest {

    private record Action(String name, BooleanSupplier ready, Runnable run, int timeoutTicks) {
    }

    private static final List<Action> ACTIONS = new ArrayList<>();
    private static final List<Integer> DELAYS = new ArrayList<>();
    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> LOG = new ArrayList<>();
    private static int index;
    private static int waited;
    private static int delay;
    private static boolean finished;

    private ClientSelfTest() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static void then(String name, int delayTicks, BooleanSupplier ready, Runnable run) {
        ACTIONS.add(new Action(name, ready, run, 3600 + delayTicks));
        DELAYS.add(delayTicks);
    }

    private static void check(boolean ok, String what) {
        LOG.add((ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            FAILURES.add(what);
        }
    }

    private static void screenshot(String name) {
        Minecraft mc = mc();
        //#if MC >= 26.1
        //$$ Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, msg -> LOG.add("shot " + name));
        //#else
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> LOG.add("shot " + name));
        //#endif
    }

    @Nullable
    private static DecloggerScreen monitor() {
        return mc().screen instanceof DecloggerScreen s ? s : null;
    }

    private static JobView job(String outputName) {
        for (JobView j : ClientState.JOBS.values()) {
            if (j.output().getDisplayName().getString().equals(outputName)) {
                return j;
            }
        }
        return null;
    }

    private static void plan() {
        // First launch shows the accessibility onboarding screen instead of the title screen; any menu will do.
        then("main menu", 40, () -> mc().getOverlay() == null && mc().level == null && mc().screen != null, () -> {
            LOG.add("menu screen: " + mc().screen.getClass().getSimpleName());
            // No "Move with WASD" tutorial pop-up in the screenshots.
            mc().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            var cfg = DeclogConfig.COMMON;
            cfg.slowAfterSeconds.set(1);
            cfg.stallAfterSeconds.set(3);
            cfg.minStallSeconds.set(1);
            cfg.blockedAfterSeconds.set(1);
            cfg.updateIntervalTicks.set(5);
            cfg.alertIntervalTicks.set(20);
            //#if MC >= 1.19
            mc().options.guiScale().set(2);
            //#else
            //$$ mc().options.guiScale = 2;
            //#endif
            //#if MC >= 26.1
            //$$ mc().resizeGui();
            //#else
            mc().resizeDisplay();
            //#endif
            String name = "declogger-selftest-" + System.currentTimeMillis();
            //#if MC >= 26.1
            //$$ LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
            //$$         new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
            //$$         WorldDataConfiguration.DEFAULT);
            //$$ mc().createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1234L, false, false),
            //$$         registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
            //$$                 .value().createWorldDimensions(), null);
            //#elseif MC >= 1.19.3
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                    new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(1234L, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
                    //#if MC >= 1.20.5
                    //$$ , null
                    //#endif
            );
            //#elseif MC >= 1.19
            //$$ LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
            //$$         new GameRules(), net.minecraft.world.level.DataPackConfig.DEFAULT);
            //$$ var registries = net.minecraft.core.RegistryAccess.builtinCopy().freeze();
            //$$ var worldGen = registries.registryOrThrow(net.minecraft.core.Registry.WORLD_PRESET_REGISTRY)
            //$$         .getHolderOrThrow(WorldPresets.FLAT).value().createWorldGenSettings(1234L, false, false);
            //$$ mc().createWorldOpenFlows().createFreshLevel(name, settings, registries, worldGen);
            //#endif
            //#if MC < 1.19
            //$$ LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
            //$$         new GameRules(), net.minecraft.world.level.DataPackConfig.DEFAULT);
            //$$ var registries = net.minecraft.core.RegistryAccess.builtinCopy();
            //$$ mc().createLevel(name, settings, registries,
            //$$         net.minecraft.world.level.levelgen.WorldGenSettings.makeDefault(registries));
            //#endif
        });
        then("in world", 60, () -> mc().level != null && mc().player != null && mc().screen == null
                && mc().getSingleplayerServer() != null, () -> SelfTestServer.STAGE.set(SelfTestServer.BUILD_REQUESTED));
        then("jobs running", 0, () -> SelfTestServer.STAGE.get() >= SelfTestServer.RUNNING, () -> {
            check(SelfTestServer.STAGE.get() != SelfTestServer.FAILED, "server setup: " + SelfTestServer.failure);
        });
        // Let both jobs get stuck (stall limit 3 s, blocked limit 1 s), then open AE2's own crafting CPU screen for the
        // stone job, as right-clicking the CPU does.
        then("open AE2 crafting CPU screen", 140, () -> true,
                () -> SelfTestServer.STAGE.set(SelfTestServer.OPEN_CPU_SCREEN));
        then("CPU screen matched to the stone job", 0,
                () -> mc().screen instanceof CraftingCPUScreen<?> && ClientState.JOBS.containsKey(ClientState.focusJob),
                () -> {
                    JobView focused = ClientState.JOBS.get(ClientState.focusJob);
                    check(focused.output().equals(SelfTestServer.Keys.STONE),
                            "the screen's CPU is matched to the stone job");
                });
        then("stuck stone entry is framed", 10, () -> CraftingScreenOverlay.testHasFrame(SelfTestServer.Keys.STONE),
                () -> screenshot("01-ae2-crafting-screen-marked"));
        then("click the stone entry", 0, () -> true, () -> check(
                CraftingScreenOverlay.testClickEntry(SelfTestServer.Keys.STONE), "clicking the stone entry selects it"));
        then("Inspect popup shown", 0, CraftingScreenOverlay::testPopupShown,
                () -> screenshot("02-ae2-inspect-popup"));
        then("press Inspect", 2, () -> true,
                () -> check(CraftingScreenOverlay.testClickInspect(), "Inspect button clicked"));
        then("monitor opened on the stone step", 0,
                () -> monitor() != null && ClientState.summary != null && ClientState.pendingFocusJob < 0, () -> {
                    JobView selected = ClientState.JOBS.get(monitor().testSelectedJob());
                    check(selected != null && selected.output().equals(SelfTestServer.Keys.STONE),
                            "Inspect selected the stone job");
                    check(SelfTestServer.Keys.STONE.equals(monitor().testSelectedStep()), "Inspect selected the stone step");
                });
        then("monitor from Inspect shot", 5, () -> true, () -> screenshot("03-monitor-from-inspect"));
        then("close monitor", 5, () -> true, () -> mc().screen.onClose());
        // The hotkey opens the network the player used last.
        then("open monitor", 10, () -> mc().screen == null,
                () -> DeclogNetwork.sendToServer(new DeclogNetwork.OpenMonitor(false)));
        then("monitor shows jobs", 30, () -> monitor() != null && ClientState.JOBS.size() >= 2, () -> {
            JobView stone = job("Stone");
            JobView glass = job("Glass");
            check(stone != null, "stone job listed");
            check(glass != null, "glass job listed");
            if (stone != null) {
                check(stone.state() == JobState.STALLED, "stone job is STALLED (was " + stone.state() + ")");
                check(stone.reason() == BlockReason.OUTPUT_NOT_RETURNING,
                        "stone job reason OUTPUT_NOT_RETURNING (was " + stone.reason() + ")");
                check(stone.primaryMachine() != null
                        && stone.primaryMachine().pos().equals(SelfTestServer.chestA()), "stone job points at chest A");
            }
            if (glass != null) {
                check(glass.state() == JobState.BLOCKED, "glass job is BLOCKED (was " + glass.state() + ")");
                check(glass.reason() == BlockReason.PROVIDER_BLOCKING,
                        "glass job reason PROVIDER_BLOCKING (was " + glass.reason() + ")");
            }
            check(ClientState.summary != null && ClientState.summary.cpus() == 2, "summary shows 2 CPUs");
            monitor().testSelectJobNamed("Stone");
            monitor().testSelectStep(0);
            screenshot("04-monitor-stalled-job");
        });
        then("select blocked job", 10, () -> monitor() != null, () -> {
            monitor().testSelectJobNamed("Glass");
            monitor().testSelectStep(0);
        });
        then("screenshot blocked job", 5, () -> true, () -> screenshot("05-monitor-blocked-job"));
        then("open viewer", 0, () -> monitor() != null, () -> {
            monitor().testSelectJobNamed("Stone");
            monitor().testSelectStep(0);
            monitor().testPress("view");
        });
        then("viewer shows chest", 30, () -> mc().screen instanceof MachineViewerScreen && ClientState.machine != null,
                () -> {
                    MachineSnapshot snap = ClientState.machine;
                    check(snap.pos().equals(SelfTestServer.chestA()), "viewer opened chest A");
                    boolean hasCobble = snap.sections().stream().flatMap(s -> s.slots().stream())
                            .anyMatch(s -> s.stack() != null && s.stack().what().equals(SelfTestServer.Keys.COBBLE)
                                    && s.stack().amount() == 4 && s.has(MachineSnapshot.FLAG_PATTERN_INPUT));
                    check(hasCobble, "viewer shows 4 cobblestone flagged as pattern input");
                    screenshot("06-machine-viewer");
                });
        then("back to monitor", 0, () -> true, () -> mc().screen.onClose());
        then("monitor again", 10, () -> monitor() != null, () -> {
            check(true, "Back returned to the monitor");
        });
        then("finish stone job", 0, () -> true,
                () -> SelfTestServer.STAGE.set(SelfTestServer.COMPLETE_STONE_REQUESTED));
        then("history updated", 20, () -> !ClientState.HISTORY.isEmpty(), () -> {
            var entry = ClientState.HISTORY.get(0);
            check(entry.completed() && entry.output().equals(SelfTestServer.Keys.STONE) && entry.amount() == 4,
                    "history has the completed stone job");
            check(job("Stone") == null, "finished job left the job list");
            monitor().testSwitchTab(1);
        });
        then("history tab", 5, () -> true, () -> screenshot("07-history"));
        then("stats tab", 5, () -> monitor() != null, () -> monitor().testSwitchTab(2));
        then("stats shot", 5, () -> true, () -> screenshot("08-stats"));
        then("go to blocked machine", 5, () -> monitor() != null, () -> {
            monitor().testSwitchTab(0);
            monitor().testSelectJobNamed("Glass");
            monitor().testSelectStep(0);
            monitor().testPress("goto");
        });
        then("Go To outline shown, monitor closed", 40, () -> mc().screen == null, () -> {
            PinManager.Pin target = PinManager.target();
            check(target != null && target.pos().equals(SelfTestServer.chestB()), "Go To outlines chest B");
            check(PinManager.active() == target, "Go To shows the way to chest B");
            check(PinManager.pins().isEmpty(), "Go To does not save a pin");
            screenshot("09-world-goto-outline-and-hud");
        });
        then("reopen monitor", 0, () -> true,
                () -> DeclogNetwork.sendToServer(new DeclogNetwork.OpenMonitor(false)));
        then("pin blocked machine", 20, () -> monitor() != null, () -> {
            monitor().testSwitchTab(0);
            monitor().testSelectJobNamed("Glass");
            monitor().testSelectStep(0);
            monitor().testPress("pin");
            check(PinManager.pins().size() == 1 && PinManager.pins().get(0).pos().equals(SelfTestServer.chestB()),
                    "Pin saves chest B");
            check(!PinManager.pins().isEmpty() && PinManager.pins().get(0).color == PinManager.PIN_COLOR,
                    "pins are red");
        });
        then("pins tab", 5, () -> monitor() != null, () -> monitor().testSwitchTab(3));
        then("pins shot", 5, () -> true, () -> screenshot("10-pins"));
        then("close", 5, () -> true, () -> mc().screen.onClose());
        then("unsubscribed", 20, () -> mc().screen == null, () -> {
            check(!PinManager.pins().isEmpty(), "pins kept after closing");
            JobView glass = job("Glass");
            check(glass != null, "glass job still known client-side");
            if (glass != null && !glass.steps().isEmpty()) {
                StepView s = glass.steps().get(0);
                check(s.pending() == 2 && s.active() == 0, "glass step pending 2, active 0");
            }
        });
    }

    @SubscribeEvent
    //#if NEOFORGE
    //$$ public static void onClientTick(ClientTickEvent.Post event) {
    //$$     if (!SelfTestServer.ENABLED || finished) {
    //$$         return;
    //$$     }
    //#else
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!SelfTestServer.ENABLED || event.phase != TickEvent.Phase.END || finished) {
            return;
        }
    //#endif
        if (ACTIONS.isEmpty()) {
            plan();
            LOG.add("self-test started");
        }
        if (index >= ACTIONS.size()) {
            finish();
            return;
        }
        if (delay > 0) {
            delay--;
            return;
        }
        Action action = ACTIONS.get(index);
        if (++waited > action.timeoutTicks()) {
            check(false, "timed out waiting for: " + action.name());
            finish();
            return;
        }
        if (SelfTestServer.STAGE.get() == SelfTestServer.FAILED) {
            check(false, "server side failed: " + SelfTestServer.failure);
            finish();
            return;
        }
        boolean ready;
        try {
            ready = action.ready().getAsBoolean();
        } catch (RuntimeException e) {
            ready = false;
        }
        if (!ready) {
            return;
        }
        LOG.add("step " + action.name() + " after " + waited + " ticks");
        try {
            action.run().run();
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.error("Self-test step {} failed", action.name(), e);
            check(false, "step '" + action.name() + "' threw " + e);
        }
        index++;
        waited = 0;
        delay = index < DELAYS.size() ? DELAYS.get(index) : 0;
    }

    private static void finish() {
        finished = true;
        boolean pass = FAILURES.isEmpty();
        StringBuilder sb = new StringBuilder(pass ? "PASS\n" : "FAIL\n");
        for (String line : LOG) {
            sb.append(line).append('\n');
        }
        Path out = mc().gameDirectory.toPath().resolve("selftest-result.txt");
        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Ae2Declogger.LOG.error("Could not write self-test result", e);
        }
        Ae2Declogger.LOG.info("Client self-test {}:\n{}", pass ? "PASSED" : "FAILED", sb);
        mc().stop();
    }
}
