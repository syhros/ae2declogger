package com.syhros.ae2declogger;

import java.io.File;

import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.net.Channel;
import com.syhros.ae2declogger.server.DecloggerServer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 1.7.10 start-up: config, network, server events, then the client side when there is one. */
public final class Boot {

    private Boot() {
    }

    public static void preInit(File configDir) {
        DeclogConfig.load(configDir);
        Channel.register();
        FMLCommonHandler.instance().bus().register(new ServerEvents());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            com.syhros.ae2declogger.client.ClientBoot.init();
        }
    }

    public static void serverStopped() {
        DecloggerServer.onServerStopped();
    }

    /** Server tick (packets queued from the network thread first) and player logout. */
    public static final class ServerEvents {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            try {
                Channel.drainServer();
                DecloggerServer.tick();
                com.syhros.ae2declogger.selftest.ServerSelfTest.tick();
            } catch (RuntimeException | LinkageError e) {
                Ae2Declogger.LOG.error("AE2 Declogger server tick failed", e);
            }
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            DecloggerServer.onLogout(event.player);
        }
    }
}
