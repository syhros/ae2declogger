package com.syhros.ae2declogger.client;

import java.io.File;

import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.net.Channel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.text.TextComponentString;

/** Minecraft 1.12.2 client access for the shared client code. */
public final class ClientPlat {

    private ClientPlat() {
    }

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    public static String translate(String key, Object... args) {
        return I18n.format(key, args);
    }

    public static void send(byte[] payload) {
        Channel.sendToServer(payload);
    }

    public static void openScreen(UiScreen screen) {
        mc().displayGuiScreen(new HostScreen(screen));
    }

    public static void closeScreen() {
        mc().displayGuiScreen(null);
    }

    /** The shared screen on display, or null. */
    public static UiScreen currentScreen() {
        return mc().currentScreen instanceof HostScreen ? ((HostScreen) mc().currentScreen).ui : null;
    }

    public static void chat(String message) {
        if (mc().player != null) {
            mc().player.sendMessage(new TextComponentString(message));
        }
    }

    /** A short line above the hotbar. */
    public static void status(String message) {
        if (mc().player != null) {
            mc().player.sendStatusMessage(new TextComponentString(message), true);
        }
    }

    public static void playBell() {
        mc().getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.BLOCK_NOTE_BELL, 0.9f));
    }

    public static File gameDir() {
        return mc().gameDir;
    }

    /** Name of the current world or server, for per-world pin files; null when not in a world. */
    public static String worldKey() {
        if (mc().world == null) {
            return null;
        }
        if (mc().isSingleplayer() && mc().getIntegratedServer() != null) {
            return "sp_" + mc().getIntegratedServer().getFolderName();
        }
        ServerData server = mc().getCurrentServerData();
        return "mp_" + (server != null ? server.serverIP : "unknown");
    }

    public static String playerDim() {
        return mc().player == null ? null : String.valueOf(mc().player.dimension);
    }

    public static double[] playerPos() {
        return mc().player == null ? null : new double[] { mc().player.posX, mc().player.posY, mc().player.posZ };
    }

    public static float playerYaw() {
        return mc().player == null ? 0 : mc().player.rotationYaw;
    }
}
