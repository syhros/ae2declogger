package com.syhros.ae2declogger;

import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.net.Channel;
import com.syhros.ae2declogger.server.DecloggerServer;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/** 1.16.5 start-up: config, network, server events, then the client side when there is one. */
public final class Boot {

    private Boot() {
    }

    public static void construct() {
        DeclogConfig.load(FMLPaths.CONFIGDIR.get().toFile());
        Channel.register();
        MinecraftForge.EVENT_BUS.register(new ServerEvents());
        if (FMLEnvironment.dist.isClient()) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(Boot::clientSetup);
        }
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(com.syhros.ae2declogger.client.ClientBoot::init);
    }

    /** Server tick, player logout, server stop. */
    public static final class ServerEvents {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                try {
                    DecloggerServer.tick();
                    com.syhros.ae2declogger.selftest.ServerSelfTest.tick();
                } catch (RuntimeException | LinkageError e) {
                    Ae2Declogger.LOG.error("AE2 Declogger server tick failed", e);
                }
            }
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            DecloggerServer.onLogout(event.getPlayer());
        }

        @SubscribeEvent
        public void onStopped(FMLServerStoppedEvent event) {
            DecloggerServer.onServerStopped();
        }
    }
}
