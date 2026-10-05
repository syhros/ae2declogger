package com.syhros.ae2declogger.client.pins;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.logic.Formatting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Locator pins, saved per world/server in {@code <game dir>/ae2declogger/pins/<world>.json}, plus one unsaved Go To
 * target. One of them can be "active": the HUD then shows the way to it.
 */
public final class PinManager {

    public static final int MAX_PINS = 64;
    /** Pins and the Go To outline are red. */
    public static final int PIN_COLOR = 0xFF3333;
    /** After reaching the Go To target, its outline stays this long. */
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

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        @Nullable
        public ResourceKey<Level> dimensionKey() {
            ResourceLocation rl = ResourceLocation.tryParse(dimension == null ? "" : dimension);
            return rl == null ? null : ResourceKey.create(Registries.DIMENSION, rl);
        }
    }

    private static final class SaveFile {
        List<Pin> pins = new ArrayList<>();
        String active;
        boolean hidden;
    }

    private static final List<Pin> PINS = new ArrayList<>();
    @Nullable
    private static String activeId;
    private static boolean hidden;
    @Nullable
    private static Path file;
    /** Go To target: outlined and navigated to, but not saved and not in the pin list. */
    @Nullable
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

    @Nullable
    public static Pin target() {
        return target;
    }

    /** Outlines a machine in red and shows the way to it, without adding it to the pin list. */
    public static void setTarget(String label, ResourceKey<Level> dimension, BlockPos pos) {
        Pin pin = new Pin();
        pin.id = "goto";
        pin.label = label;
        pin.dimension = dimension.location().toString();
        pin.x = pos.getX();
        pin.y = pos.getY();
        pin.z = pos.getZ();
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
    public static void tickTarget(@Nullable net.minecraft.world.entity.player.Player player) {
        if (target == null || player == null) {
            return;
        }
        boolean sameDim = player.level().dimension().location().toString().equals(target.dimension);
        boolean near = sameDim && player.blockPosition().distSqr(target.pos()) <= 5 * 5;
        long now = System.currentTimeMillis();
        if (near && targetReachedAtMs < 0) {
            targetReachedAtMs = now;
        }
        if (targetReachedAtMs >= 0 && now - targetReachedAtMs > TARGET_LINGER_MS) {
            clearTarget();
        }
    }

    @Nullable
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

    /** Adds a pin, or renames an existing one at the same spot. Returns the pin. */
    public static Pin add(String label, ResourceKey<Level> dimension, BlockPos pos) {
        String dim = dimension.location().toString();
        for (Pin p : PINS) {
            if (p.dimension.equals(dim) && p.x == pos.getX() && p.y == pos.getY() && p.z == pos.getZ()) {
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
        pin.dimension = dim;
        pin.x = pos.getX();
        pin.y = pos.getY();
        pin.z = pos.getZ();
        pin.color = PIN_COLOR;
        pin.created = System.currentTimeMillis();
        PINS.add(0, pin);
        save();
        return pin;
    }

    public static void setActive(@Nullable Pin pin) {
        clearTarget();
        activeId = pin == null ? null : pin.id;
        if (pin != null) {
            hidden = false;
        }
        save();
    }

    public static void remove(Pin pin) {
        PINS.removeIf(p -> p.id.equals(pin.id));
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

    // ---- persistence ----

    public static void load() {
        PINS.clear();
        activeId = null;
        hidden = false;
        file = fileForCurrentWorld();
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

    @Nullable
    private static Path fileForCurrentWorld() {
        Minecraft mc = Minecraft.getInstance();
        String key;
        if (mc.getSingleplayerServer() != null) {
            key = "sp_" + Formatting.safeFileName(mc.getSingleplayerServer().getWorldData().getLevelName());
        } else {
            ServerData server = mc.getCurrentServer();
            key = "mp_" + Formatting.safeFileName(server != null ? server.ip : "unknown");
        }
        return mc.gameDirectory.toPath().resolve("ae2declogger").resolve("pins").resolve(key + ".json");
    }
}
