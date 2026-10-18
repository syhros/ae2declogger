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
        if (mc().getConnection() != null) {
            Channel.sendToServer(payload);
        }
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

    // ---------------------------------------------------------------- client self-test

    public static boolean onTitleScreen() {
        return mc().screen instanceof net.minecraft.client.gui.screen.MainMenuScreen;
    }

    /** A vanilla item by name ("stone"). */
    public static com.syhros.ae2declogger.data.LKey item(String name) {
        return com.syhros.ae2declogger.data.LKey.of(new net.minecraft.item.ItemStack(
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                        new net.minecraft.util.ResourceLocation("minecraft", name))));
    }

    public static void screenshot(String name) {
        net.minecraft.util.ScreenShotHelper.grab(mc().gameDirectory, name, mc().getWindow().getWidth(),
                mc().getWindow().getHeight(), mc().getMainRenderTarget(), msg -> {
                });
    }

    /** AE2 names the crafting screen overlay reads that this AE2 build lacks. */
    public static java.util.List<String> overlayMissingNames() {
        return OverlayCanvas.missingNames();
    }

    public static void quit() {
        mc().stop();
    }
}
