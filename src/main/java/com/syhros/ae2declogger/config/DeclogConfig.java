package com.syhros.ae2declogger.config;

import com.syhros.ae2declogger.logic.Thresholds;

//#if NEOFORGE
//$$ import net.neoforged.neoforge.common.ModConfigSpec;
//$$ import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
//$$ import net.neoforged.neoforge.common.ModConfigSpec.Builder;
//$$ import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
//$$ import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
//#else
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.common.ForgeConfigSpec.DoubleValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
//#endif

/**
 * Two config files:
 * <ul>
 * <li>{@code config/ae2declogger-common.toml}: detection timings, update rates, limits. Read by the
 * server (or the integrated server in single player).</li>
 * <li>{@code config/ae2declogger-client.toml}: what this player sees and hears.</li>
 * </ul>
 */
public final class DeclogConfig {

    //#if NEOFORGE
    //$$ public static final ModConfigSpec COMMON_SPEC;
    //$$ public static final ModConfigSpec CLIENT_SPEC;
    //#else
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;
    //#endif
    public static final Common COMMON;
    public static final Client CLIENT;

    static {
        Builder common = new Builder();
        COMMON = new Common(common);
        COMMON_SPEC = common.build();
        Builder client = new Builder();
        CLIENT = new Client(client);
        CLIENT_SPEC = client.build();
    }

    private DeclogConfig() {
    }

    public static final class Common {
        public final IntValue updateIntervalTicks;
        public final IntValue alertIntervalTicks;
        public final IntValue maxJobs;
        public final IntValue maxStepsPerJob;
        public final IntValue maxMachinesPerStep;

        public final IntValue slowAfterSeconds;
        public final IntValue stallAfterSeconds;
        public final IntValue minStallSeconds;
        public final DoubleValue recipeTimeMultiplier;
        public final IntValue blockedAfterSeconds;

        public final IntValue historySize;
        public final IntValue machineViewerMaxSlots;

        public final BooleanValue allowCrossDimension;
        public final IntValue maxLinkDistance;

        Common(Builder b) {
            b.comment("How often the server collects crafting data and how much it sends.").push("updates");
            updateIntervalTicks = b
                    .comment("Ticks between updates while the monitor screen is open (20 ticks = 1 second).")
                    .defineInRange("updateIntervalTicks", 10, 5, 200);
            alertIntervalTicks = b
                    .comment("Ticks between background checks for stall alerts (players who turned alerts on for a network).")
                    .defineInRange("alertIntervalTicks", 40, 20, 1200);
            maxJobs = b.comment("Most crafting CPUs listed per network. Busy CPUs with problems are listed first.")
                    .defineInRange("maxJobs", 32, 1, 256);
            maxStepsPerJob = b.comment("Most crafting steps sent per job. Problem steps are sent first.")
                    .defineInRange("maxStepsPerJob", 24, 1, 200);
            maxMachinesPerStep = b.comment("Most machines inspected and listed per crafting step.")
                    .defineInRange("maxMachinesPerStep", 4, 1, 16);
            b.pop();

            b.comment("When a job counts as slow, stalled or blocked.").push("detection");
            slowAfterSeconds = b.comment("A step with no progress for this long is shown as Slow (yellow).")
                    .defineInRange("slowAfterSeconds", 30, 1, 86400);
            stallAfterSeconds = b.comment("A step with no progress for this long is shown as Stalled (red).")
                    .defineInRange("stallAfterSeconds", 120, 1, 86400);
            minStallSeconds = b.comment(
                    "When the recipe time is known (GregTech machines), a step stalls after recipeTime * recipeTimeMultiplier,",
                    "but never sooner than this many seconds and never later than stallAfterSeconds.")
                    .defineInRange("minStallSeconds", 15, 1, 86400);
            recipeTimeMultiplier = b.comment("See minStallSeconds.")
                    .defineInRange("recipeTimeMultiplier", 6.0, 1.0, 1000.0);
            blockedAfterSeconds = b.comment(
                    "A step that cannot start (no pattern provider, provider busy, machine refusing inputs...)",
                    "is reported Blocked once the problem has lasted this long.")
                    .defineInRange("blockedAfterSeconds", 5, 0, 3600);
            b.pop();

            b.push("history");
            historySize = b.comment("Finished jobs and job steps remembered per network for the History and Stats tabs.",
                    "Saved with the world. Each job adds one entry per intermediate item it made.")
                    .defineInRange("historyEntries", 1000, 1, 2000);
            b.pop();

            b.comment("Read-only machine viewer.").push("machineViewer");
            machineViewerMaxSlots = b.comment("Most slots and tanks sent for one machine.")
                    .defineInRange("maxSlots", 108, 9, 512);
            b.pop();

            b.comment("Opening the monitor with the hotkey for the remembered network.").push("link");
            allowCrossDimension = b.comment("Allow opening the monitor for a network in another dimension.")
                    .define("allowCrossDimension", true);
            maxLinkDistance = b.comment("Most blocks away from the remembered network the hotkey still opens the monitor. 0 = no limit.")
                    .defineInRange("maxLinkDistance", 0, 0, 1_000_000);
            b.pop();
        }

        public Thresholds thresholds() {
            return Thresholds.ofSeconds(slowAfterSeconds.get(), stallAfterSeconds.get(), minStallSeconds.get(),
                    recipeTimeMultiplier.get(), blockedAfterSeconds.get());
        }
    }

    public static final class Client {
        public final BooleanValue alertsChat;
        public final BooleanValue alertsSound;
        public final BooleanValue alertsToast;
        public final BooleanValue terminalButton;
        public final BooleanValue craftingOverlay;
        public final BooleanValue pinsInWorld;
        public final BooleanValue pinsHud;
        public final IntValue pinRenderDistance;
        public final IntValue hudY;
        public final BooleanValue showOnlyProblems;

        Client(Builder b) {
            b.comment("Alerts when a job stalls or gets blocked. Turn alerts on or off per network with the ♪ button in the monitor.")
                    .push("alerts");
            alertsChat = b.comment("Post a chat message.").define("chat", true);
            alertsSound = b.comment("Play a bell sound.").define("sound", true);
            alertsToast = b.comment("Show a pop-up in the corner.").define("toast", true);
            b.pop();

            b.push("ui");
            terminalButton = b.comment("Add a Declogger button to AE2 terminal and crafting status screens.")
                    .define("terminalButton", true);
            craftingOverlay = b.comment("Mark stuck entries in AE2's crafting status screen and show the Inspect popup",
                    "when an entry is clicked.")
                    .define("craftingOverlay", true);
            showOnlyProblems = b.comment("Start the monitor with the 'problems only' filter switched on.")
                    .define("showOnlyProblems", false);
            b.pop();

            b.comment("Locator pins placed from the monitor.").push("pins");
            pinsInWorld = b.comment("Draw a box and label on pinned machines, visible through walls.")
                    .define("renderInWorld", true);
            pinsHud = b.comment("Show distance and direction to the pin you are navigating to.").define("hud", true);
            pinRenderDistance = b.comment("Do not draw pins further away than this many blocks.")
                    .defineInRange("renderDistance", 256, 16, 4096);
            hudY = b.comment("Vertical position of the navigation line, in pixels from the top of the screen.")
                    .defineInRange("hudY", 4, 0, 1000);
            b.pop();
        }
    }
}
