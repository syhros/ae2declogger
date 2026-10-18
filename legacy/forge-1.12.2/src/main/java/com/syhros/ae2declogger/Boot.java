package com.syhros.ae2declogger;

import java.io.File;

import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.net.Channel;
import com.syhros.ae2declogger.server.DecloggerServer;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** 1.12.2 start-up: config, network, server events, then the client side when there is one. */
public final class Boot {

    private Boot() {
    }

    public static void preInit(File configDir) {
        DeclogConfig.load(configDir);
        Channel.register();
        MinecraftForge.EVENT_BUS.register(new ServerEvents());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            com.syhros.ae2declogger.client.ClientBoot.init();
        }
    }

    public static void serverStopped() {
        DecloggerServer.onServerStopped();
    }

    /** Server tick and player logout. */
    public static final class ServerEvents {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                try {
                    DecloggerServer.tick();
                } catch (RuntimeException | LinkageError e) {
                    Ae2Declogger.LOG.error("AE2 Declogger server tick failed", e);
                }
            }
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            DecloggerServer.onLogout(event.player);
        }
    }
}
