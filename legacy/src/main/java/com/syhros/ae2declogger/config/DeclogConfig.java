package com.syhros.ae2declogger.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.logic.Thresholds;

/**
 * Settings for the legacy versions, in {@code config/ae2declogger.properties}. The three versions have three
 * different config APIs, so this is a plain properties file with the same settings and limits as the modern
 * {@code ae2declogger-common.toml} and {@code ae2declogger-client.toml}. Missing or invalid values fall back to the
 * default; the file is rewritten with every setting and its description.
 */
public final class DeclogConfig {

    private static final class Setting {
        final String key;
        final String comment;
        final double min;
        final double max;
        final Object def;
        Object value;

        Setting(String key, String comment, Object def, double min, double max) {
            this.key = key;
            this.comment = comment;
            this.def = def;
            this.value = def;
            this.min = min;
            this.max = max;
        }
    }

    private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();

    // Server (or the integrated server in single player)
    private static final Setting UPDATE_INTERVAL = i("updateIntervalTicks",
            "Ticks between updates while the monitor screen is open (20 ticks = 1 second).", 10, 5, 200);
    private static final Setting ALERT_INTERVAL = i("alertIntervalTicks",
            "Ticks between background checks for stall alerts.", 40, 20, 1200);
    private static final Setting MAX_JOBS = i("maxJobs",
            "Most crafting CPUs listed per network. Busy CPUs with problems are listed first.", 32, 1, 256);
    private static final Setting MAX_STEPS = i("maxStepsPerJob",
            "Most crafting steps sent per job. Problem steps are sent first.", 24, 1, 200);
    private static final Setting MAX_MACHINES = i("maxMachinesPerStep",
            "Most machines inspected and listed per crafting step.", 4, 1, 16);
    private static final Setting SLOW = i("slowAfterSeconds",
            "A step with no progress for this long is shown as Slow (yellow).", 30, 1, 86400);
    private static final Setting STALL = i("stallAfterSeconds",
            "A step with no progress for this long is shown as Stalled (red).", 120, 1, 86400);
    private static final Setting MIN_STALL = i("minStallSeconds",
            "When the recipe time is known (GregTech machines), a step stalls after recipeTime * recipeTimeMultiplier, "
                    + "but never sooner than this and never later than stallAfterSeconds.", 15, 1, 86400);
    private static final Setting RECIPE_MULT = d("recipeTimeMultiplier", "See minStallSeconds.", 6.0, 1.0, 1000.0);
    private static final Setting BLOCKED = i("blockedAfterSeconds",
            "A step that cannot start is reported Blocked once the problem has lasted this long.", 5, 0, 3600);
    private static final Setting HISTORY = i("historyEntries",
            "Finished jobs and job steps remembered per network for the History and Stats tabs (saved with the world).",
            1000, 1, 2000);
    private static final Setting VIEWER_SLOTS = i("machineViewerMaxSlots",
            "Most slots and tanks sent for one machine in the viewer.", 108, 9, 512);
    private static final Setting CROSS_DIM = b("allowCrossDimension",
            "Allow opening the monitor for a network in another dimension.", true);
    private static final Setting MAX_DISTANCE = i("maxLinkDistance",
            "Most blocks away from the remembered network the hotkey still opens the monitor. 0 = no limit.", 0, 0,
            1_000_000);

    // Client
    private static final Setting ALERTS_CHAT = b("alertsChat", "Alerts: post a chat message.", true);
    private static final Setting ALERTS_SOUND = b("alertsSound", "Alerts: play a bell sound.", true);
    private static final Setting TERMINAL_BUTTON = b("terminalButton",
            "Add a Declogger button to AE2 terminal and crafting status screens.", true);
    private static final Setting SHOW_ONLY_PROBLEMS = b("showOnlyProblems",
            "Start the monitor with the 'problems only' filter switched on.", false);
    private static final Setting PINS_IN_WORLD = b("pinsInWorld",
            "Draw a box on pinned machines, visible through walls.", true);
    private static final Setting PINS_HUD = b("pinsHud",
            "Show distance and direction to the pin you are navigating to.", true);
    private static final Setting PIN_DISTANCE = i("pinRenderDistance",
            "Do not draw pins further away than this many blocks.", 256, 16, 4096);
    private static final Setting HUD_Y = i("hudY",
            "Vertical position of the navigation line, in pixels from the top of the screen.", 4, 0, 1000);

    private DeclogConfig() {
    }

    private static Setting i(String key, String comment, int def, int min, int max) {
        return put(new Setting(key, comment, def, min, max));
    }

    private static Setting d(String key, String comment, double def, double min, double max) {
        return put(new Setting(key, comment, def, min, max));
    }

    private static Setting b(String key, String comment, boolean def) {
        return put(new Setting(key, comment, def, 0, 1));
    }

    private static Setting put(Setting s) {
        SETTINGS.put(s.key, s);
        return s;
    }

    /** Reads {@code dir/ae2declogger.properties} and writes it back with every setting. */
    public static synchronized void load(File dir) {
        File file = new File(dir, "ae2declogger.properties");
        Properties p = new Properties();
        if (file.isFile()) {
            try (InputStream in = Files.newInputStream(file.toPath())) {
                p.load(in);
            } catch (IOException e) {
                Ae2Declogger.LOG.warn("Could not read {}, using defaults", file, e);
            }
        }
        for (Setting s : SETTINGS.values()) {
            s.value = parse(s, p.getProperty(s.key));
        }
        try {
            if (!dir.isDirectory()) {
                Files.createDirectories(dir.toPath());
            }
            try (OutputStream out = Files.newOutputStream(file.toPath());
                    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                w.write("# AE2 Declogger settings. Delete a line to get its default back.\n");
                for (Setting s : SETTINGS.values()) {
                    w.write("\n# " + s.comment + "\n");
                    if (!(s.def instanceof Boolean)) {
                        w.write("# Range: " + fmt(s, s.min) + " to " + fmt(s, s.max) + ", default " + s.def + "\n");
                    } else {
                        w.write("# Default " + s.def + "\n");
                    }
                    w.write(s.key + "=" + s.value + "\n");
                }
            }
        } catch (IOException e) {
            Ae2Declogger.LOG.warn("Could not write {}", file, e);
        }
    }

    private static String fmt(Setting s, double v) {
        return s.def instanceof Integer ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static Object parse(Setting s, String raw) {
        if (raw == null) {
            return s.def;
        }
        raw = raw.trim();
        try {
            if (s.def instanceof Boolean) {
                if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                    return Boolean.parseBoolean(raw);
                }
                return s.def;
            }
            if (s.def instanceof Integer) {
                long v = Long.parseLong(raw);
                return (int) Math.max(s.min, Math.min(s.max, v));
            }
            double v = Double.parseDouble(raw);
            return Double.isNaN(v) ? s.def : Math.max(s.min, Math.min(s.max, v));
        } catch (NumberFormatException e) {
            return s.def;
        }
    }

    /** Names of every setting, for tests. */
    public static List<String> keys() {
        return new ArrayList<>(SETTINGS.keySet());
    }

    private static int intOf(Setting s) {
        return (Integer) s.value;
    }

    private static boolean boolOf(Setting s) {
        return (Boolean) s.value;
    }

    public static int updateIntervalTicks() {
        return intOf(UPDATE_INTERVAL);
    }

    public static int alertIntervalTicks() {
        return intOf(ALERT_INTERVAL);
    }

    public static int maxJobs() {
        return intOf(MAX_JOBS);
    }

    public static int maxStepsPerJob() {
        return intOf(MAX_STEPS);
    }

    public static int maxMachinesPerStep() {
        return intOf(MAX_MACHINES);
    }

    public static int historySize() {
        return intOf(HISTORY);
    }

    public static int machineViewerMaxSlots() {
        return intOf(VIEWER_SLOTS);
    }

    public static boolean allowCrossDimension() {
        return boolOf(CROSS_DIM);
    }

    public static int maxLinkDistance() {
        return intOf(MAX_DISTANCE);
    }

    public static Thresholds thresholds() {
        return Thresholds.ofSeconds(intOf(SLOW), intOf(STALL), intOf(MIN_STALL), (Double) RECIPE_MULT.value,
                intOf(BLOCKED));
    }

    public static boolean alertsChat() {
        return boolOf(ALERTS_CHAT);
    }

    public static boolean alertsSound() {
        return boolOf(ALERTS_SOUND);
    }

    public static boolean terminalButton() {
        return boolOf(TERMINAL_BUTTON);
    }

    public static boolean showOnlyProblems() {
        return boolOf(SHOW_ONLY_PROBLEMS);
    }

    public static boolean pinsInWorld() {
        return boolOf(PINS_IN_WORLD);
    }

    public static boolean pinsHud() {
        return boolOf(PINS_HUD);
    }

    public static int pinRenderDistance() {
        return intOf(PIN_DISTANCE);
    }

    public static int hudY() {
        return intOf(HUD_Y);
    }
}
