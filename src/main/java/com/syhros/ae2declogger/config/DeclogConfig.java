package com.syhros.ae2declogger.config;

import com.syhros.ae2declogger.logic.Thresholds;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Two config files:
 * <ul>
 * <li>{@code config/ae2declogger-common.toml}: detection timings, update rates, limits, teleport rules. Read by the
 * server (or the integrated server in single player).</li>
 * <li>{@code config/ae2declogger-client.toml}: what this player sees and hears.</li>
 * </ul>
 */
public final class DeclogConfig {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        ForgeConfigSpec.Builder common = new ForgeConfigSpec.Builder();
        COMMON = new Common(common);
        COMMON_SPEC = common.build();
        ForgeConfigSpec.Builder client = new ForgeConfigSpec.Builder();
        CLIENT = new Client(client);
        CLIENT_SPEC = client.build();
    }

    private DeclogConfig() {
    }

    public static final class Common {
        public final ForgeConfigSpec.IntValue updateIntervalTicks;
        public final ForgeConfigSpec.IntValue alertIntervalTicks;
        public final ForgeConfigSpec.IntValue maxJobs;
        public final ForgeConfigSpec.IntValue maxStepsPerJob;
        public final ForgeConfigSpec.IntValue maxMachinesPerStep;

        public final ForgeConfigSpec.IntValue slowAfterSeconds;
        public final ForgeConfigSpec.IntValue stallAfterSeconds;
        public final ForgeConfigSpec.IntValue minStallSeconds;
        public final ForgeConfigSpec.DoubleValue recipeTimeMultiplier;
        public final ForgeConfigSpec.IntValue blockedAfterSeconds;

        public final ForgeConfigSpec.IntValue historySize;
        public final ForgeConfigSpec.IntValue machineViewerMaxSlots;
        public final ForgeConfigSpec.IntValue machineViewerRange;

        public final ForgeConfigSpec.BooleanValue allowCrossDimension;
        public final ForgeConfigSpec.IntValue maxLinkDistance;

        public final ForgeConfigSpec.BooleanValue teleportEnabled;
        public final ForgeConfigSpec.IntValue teleportPermissionLevel;
        public final ForgeConfigSpec.BooleanValue teleportInCreative;

        Common(ForgeConfigSpec.Builder b) {
            b.comment("How often the server collects crafting data and how much it sends.").push("updates");
            updateIntervalTicks = b
                    .comment("Ticks between updates while the monitor screen is open (20 ticks = 1 second).")
                    .defineInRange("updateIntervalTicks", 10, 5, 200);
            alertIntervalTicks = b
                    .comment("Ticks between background checks for stall alerts (players carrying a linked Declogger).")
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
            historySize = b.comment("Finished jobs remembered per network for the History and Stats tabs.")
                    .defineInRange("historySize", 50, 1, 1000);
            b.pop();

            b.comment("Read-only machine viewer.").push("machineViewer");
            machineViewerMaxSlots = b.comment("Most slots and tanks sent for one machine.")
                    .defineInRange("maxSlots", 108, 9, 512);
            machineViewerRange = b.comment(
                    "Shift-right-clicking a block with the Declogger opens the viewer if it is within this many blocks.",
                    "Machines listed in the monitor can always be viewed.")
                    .defineInRange("directViewRange", 8, 1, 64);
            b.pop();

            b.comment("Linking the Declogger item to a network.").push("link");
            allowCrossDimension = b.comment("Allow opening the monitor for a network in another dimension.")
                    .define("allowCrossDimension", true);
            maxLinkDistance = b.comment("Most blocks away from the linked network the monitor can be opened. 0 = no limit.")
                    .defineInRange("maxLinkDistance", 0, 0, 1_000_000);
            b.pop();

            b.comment("Teleport button in the monitor and pin list.").push("teleport");
            teleportEnabled = b.comment("Show and allow the Teleport button.").define("enabled", true);
            teleportPermissionLevel = b.comment("Permission level needed to teleport (2 = operators / cheats on).")
                    .defineInRange("permissionLevel", 2, 0, 4);
            teleportInCreative = b.comment("Players in creative mode may always teleport.")
                    .define("allowInCreative", true);
            b.pop();
        }

        public Thresholds thresholds() {
            return Thresholds.ofSeconds(slowAfterSeconds.get(), stallAfterSeconds.get(), minStallSeconds.get(),
                    recipeTimeMultiplier.get(), blockedAfterSeconds.get());
        }
    }

    public static final class Client {
        public final ForgeConfigSpec.BooleanValue alertsChat;
        public final ForgeConfigSpec.BooleanValue alertsSound;
        public final ForgeConfigSpec.BooleanValue alertsToast;
        public final ForgeConfigSpec.BooleanValue terminalButton;
        public final ForgeConfigSpec.BooleanValue pinsInWorld;
        public final ForgeConfigSpec.BooleanValue pinsHud;
        public final ForgeConfigSpec.IntValue pinRenderDistance;
        public final ForgeConfigSpec.IntValue hudY;
        public final ForgeConfigSpec.BooleanValue showOnlyProblems;

        Client(ForgeConfigSpec.Builder b) {
            b.comment("Alerts when a job stalls or gets blocked. Turn alerts on or off per item with sneak + right-click.")
                    .push("alerts");
            alertsChat = b.comment("Post a chat message.").define("chat", true);
            alertsSound = b.comment("Play a bell sound.").define("sound", true);
            alertsToast = b.comment("Show a pop-up in the corner.").define("toast", true);
            b.pop();

            b.push("ui");
            terminalButton = b.comment("Add a Declogger button to AE2 terminal and crafting status screens.")
                    .define("terminalButton", true);
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
