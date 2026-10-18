package com.syhros.ae2declogger.client;

import java.io.File;

import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.net.Channel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;

/** Minecraft 1.7.10 client access for the shared client code. */
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

    public static UiScreen currentScreen() {
        return mc().currentScreen instanceof HostScreen ? ((HostScreen) mc().currentScreen).ui : null;
    }

    public static void chat(String message) {
        if (mc().thePlayer != null) {
            mc().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }

    /** 1.7.10 has no action bar: a chat line. */
    public static void status(String message) {
        chat(message);
    }

    public static void playBell() {
        mc().getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("note.pling"), 1.0f));
    }

    public static File gameDir() {
        return mc().mcDataDir;
    }

    public static String worldKey() {
        if (mc().theWorld == null) {
            return null;
        }
        if (mc().isSingleplayer() && mc().getIntegratedServer() != null) {
            return "sp_" + mc().getIntegratedServer().getFolderName();
        }
        ServerData server = mc().func_147104_D();
        return "mp_" + (server != null ? server.serverIP : "unknown");
    }

    public static String playerDim() {
        return mc().thePlayer == null ? null : String.valueOf(mc().thePlayer.dimension);
    }

    /** Feet position (the client player's posY is at eye level on 1.7.10). */
    public static double[] playerPos() {
        return mc().thePlayer == null ? null
                : new double[] { mc().thePlayer.posX, mc().thePlayer.boundingBox.minY, mc().thePlayer.posZ };
    }

    public static float playerYaw() {
        return mc().thePlayer == null ? 0 : mc().thePlayer.rotationYaw;
    }
}
