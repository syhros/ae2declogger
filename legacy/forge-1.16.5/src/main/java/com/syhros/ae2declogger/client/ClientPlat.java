package com.syhros.ae2declogger.client;

import java.io.File;

import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.net.Channel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.StringTextComponent;

/** Minecraft 1.16.5 client access for the shared client code. */
public final class ClientPlat {

    private ClientPlat() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static String translate(String key, Object... args) {
        return I18n.get(key, args);
    }

    public static void send(byte[] payload) {
        Channel.sendToServer(payload);
    }

    public static void openScreen(UiScreen screen) {
        mc().setScreen(new HostScreen(screen));
    }

    public static void closeScreen() {
        mc().setScreen(null);
    }

    public static UiScreen currentScreen() {
        return mc().screen instanceof HostScreen ? ((HostScreen) mc().screen).ui : null;
    }

    public static void chat(String message) {
        mc().gui.getChat().addMessage(new StringTextComponent(message));
    }

    public static void status(String message) {
        if (mc().player != null) {
            mc().player.displayClientMessage(new StringTextComponent(message), true);
        }
    }

    public static void playBell() {
        mc().getSoundManager().play(SimpleSound.forUI(SoundEvents.NOTE_BLOCK_BELL, 0.9f));
    }

    public static File gameDir() {
        return mc().gameDirectory;
    }

    public static String worldKey() {
        if (mc().level == null) {
            return null;
        }
        if (mc().getSingleplayerServer() != null) {
            return "sp_" + mc().getSingleplayerServer().getWorldData().getLevelName();
        }
        ServerData server = mc().getCurrentServer();
        return "mp_" + (server != null ? server.ip : "unknown");
    }

    public static String playerDim() {
        return mc().player == null ? null : mc().player.level.dimension().location().toString();
    }

    public static double[] playerPos() {
        return mc().player == null ? null : new double[] { mc().player.getX(), mc().player.getY(), mc().player.getZ() };
    }

    public static float playerYaw() {
        return mc().player == null ? 0 : mc().player.yRot;
    }
}
