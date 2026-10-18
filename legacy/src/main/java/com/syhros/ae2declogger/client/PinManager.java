package com.syhros.ae2declogger.client;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.logic.Formatting;

/**
 * Locator pins, saved per world/server in {@code <game dir>/ae2declogger/pins/<world>.json}, plus one unsaved Go To
 * target. One of them can be "active": the HUD then shows the way to it. Same files as the modern versions.
 */
public final class PinManager {

    public static final int MAX_PINS = 64;
    public static final int PIN_COLOR = 0xFF3333;
    private static final long TARGET_LINGER_MS = 30_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Pin {
        public String id;
        public String label;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public int color;
        public long created;

        public LPos pos() {
            return new LPos(dimension, x, y, z);
        }
    }

    private static final class SaveFile {
        List<Pin> pins = new ArrayList<>();
        String active;
        boolean hidden;
    }

    private static final List<Pin> PINS = new ArrayList<>();
    private static String activeId;
    private static boolean hidden;
    private static Path file;
    private static Pin target;
    private static long targetReachedAtMs = -1;

    private PinManager() {
    }

    public static List<Pin> pins() {
        return Collections.unmodifiableList(PINS);
    }

    /** Saved pins plus the Go To target, for drawing. */
    public static List<Pin> visiblePins() {
        if (target == null) {
            return pins();
        }
        List<Pin> all = new ArrayList<>(PINS.size() + 1);
        all.add(target);
        all.addAll(PINS);
        return all;
    }

    /** Outlines a machine in red and shows the way to it, without adding it to the pin list. */
    public static void setTarget(String label, LPos pos) {
        Pin pin = new Pin();
        pin.id = "goto";
        pin.label = label;
        pin.dimension = pos.dim;
        pin.x = pos.x;
        pin.y = pos.y;
        pin.z = pos.z;
        pin.color = PIN_COLOR;
        pin.created = System.currentTimeMillis();
        target = pin;
        targetReachedAtMs = -1;
        hidden = false;
    }

    public static void clearTarget() {
        target = null;
        targetReachedAtMs = -1;
    }

    /** Called every client tick: the Go To outline goes away a while after the player reached it. */
    public static void tickTarget() {
        if (target == null) {
            return;
        }
        String dim = ClientPlat.playerDim();
        double[] p = ClientPlat.playerPos();
        if (dim == null || p == null) {
            return;
        }
        boolean near = dim.equals(target.dimension) && target.pos().distSq(p[0], p[1], p[2]) <= 6 * 6;
        long now = System.currentTimeMillis();
        if (near && targetReachedAtMs < 0) {
            targetReachedAtMs = now;
        }
        if (targetReachedAtMs >= 0 && now - targetReachedAtMs > TARGET_LINGER_MS) {
            clearTarget();
        }
    }

    public static Pin active() {
        if (target != null) {
            return target;
        }
        if (activeId == null) {
            return null;
        }
        for (Pin p : PINS) {
            if (p.id.equals(activeId)) {
                return p;
            }
        }
        return null;
    }

    public static boolean isHidden() {
        return hidden;
    }

    public static void toggleHidden() {
        hidden = !hidden;
        save();
    }

    /** Adds a pin, or renames an existing one at the same spot. */
    public static Pin add(String label, LPos pos) {
        for (Pin p : PINS) {
            if (p.dimension.equals(pos.dim) && p.x == pos.x && p.y == pos.y && p.z == pos.z) {
                p.label = label;
                save();
                return p;
            }
        }
        while (PINS.size() >= MAX_PINS) {
            PINS.remove(PINS.size() - 1);
        }
        Pin pin = new Pin();
        pin.id = UUID.randomUUID().toString();
        pin.label = label;
        pin.dimension = pos.dim;
        pin.x = pos.x;
        pin.y = pos.y;
        pin.z = pos.z;
        pin.color = PIN_COLOR;
        pin.created = System.currentTimeMillis();
        PINS.add(0, pin);
        save();
        return pin;
    }

    public static void setActive(Pin pin) {
        clearTarget();
        activeId = pin == null ? null : pin.id;
        if (pin != null) {
            hidden = false;
        }
        save();
    }

    public static void remove(Pin pin) {
        for (Iterator<Pin> it = PINS.iterator(); it.hasNext();) {
            if (it.next().id.equals(pin.id)) {
                it.remove();
            }
        }
        if (pin.id.equals(activeId)) {
            activeId = null;
        }
        save();
    }

    public static void clear() {
        PINS.clear();
        activeId = null;
        save();
    }

    public static void load() {
        PINS.clear();
        activeId = null;
        hidden = false;
        String key = ClientPlat.worldKey();
        file = key == null ? null
                : new File(ClientPlat.gameDir(), "ae2declogger/pins/" + Formatting.safeFileName(key) + ".json")
                        .toPath();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            SaveFile data = GSON.fromJson(reader, SaveFile.class);
            if (data != null && data.pins != null) {
                for (Pin p : data.pins) {
                    if (p != null && p.id != null && p.dimension != null) {
                        if (p.label == null) {
                            p.label = "";
                        }
                        PINS.add(p);
                    }
                }
                activeId = data.active;
                hidden = data.hidden;
            }
        } catch (IOException | JsonParseException e) {
            Ae2Declogger.LOG.warn("Could not read locator pins from {}", file, e);
        }
    }

    public static void save() {
        if (file == null) {
            return;
        }
        SaveFile data = new SaveFile();
        data.pins = new ArrayList<>(PINS);
        data.active = activeId;
        data.hidden = hidden;
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            Ae2Declogger.LOG.warn("Could not save locator pins to {}", file, e);
        }
    }

    public static void unload() {
        save();
        PINS.clear();
        activeId = null;
        clearTarget();
        file = null;
    }
}
