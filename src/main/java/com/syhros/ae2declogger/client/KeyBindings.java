package com.syhros.ae2declogger.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
//#if NEOFORGE
//$$ import net.neoforged.neoforge.client.settings.KeyConflictContext;
//#else
import net.minecraftforge.client.settings.KeyConflictContext;
//#endif

/** Default keys. All can be changed under Options > Controls > Key Binds > AE2 Declogger. */
public final class KeyBindings {

    public static final String CATEGORY = "key.categories.ae2declogger";

    /** Opens the monitor for the first linked Declogger in your inventory. Default: K. */
    public static final KeyMapping OPEN_MONITOR = new KeyMapping("key.ae2declogger.open", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    /** Stops navigating to the active locator pin. Default: unbound. */
    public static final KeyMapping STOP_NAVIGATION = new KeyMapping("key.ae2declogger.stop_navigation",
            KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);

    /** Shows or hides all locator pins in the world. Default: unbound. */
    public static final KeyMapping TOGGLE_PINS = new KeyMapping("key.ae2declogger.toggle_pins",
            KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);

    private KeyBindings() {
    }
}
