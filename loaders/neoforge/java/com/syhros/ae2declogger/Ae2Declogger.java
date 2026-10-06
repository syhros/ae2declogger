package com.syhros.ae2declogger;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.syhros.ae2declogger.compat.gtceu.GtCompat;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.network.Channel;
import com.syhros.ae2declogger.network.DeclogNetwork;
import com.syhros.ae2declogger.server.DecloggerServer;
import com.syhros.ae2declogger.server.NetworkMemory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** NeoForge entry point (the Forge one is in the shared sources). */
@Mod(Ae2Declogger.MOD_ID)
public final class Ae2Declogger {

    public static final String MOD_ID = "ae2declogger";
    public static final Logger LOG = LogUtils.getLogger();

    public Ae2Declogger(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::commonSetup);
        modBus.addListener(Channel::registerPayloads);

        container.registerConfig(ModConfig.Type.COMMON, DeclogConfig.COMMON_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, DeclogConfig.CLIENT_SPEC);

        DeclogNetwork.register();

        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onPlayerClone);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOG.info("AE2 Declogger ready (GregTech integration: {})", GtCompat.isActive() ? "on" : "off");
    }

    private void onServerTick(ServerTickEvent.Post event) {
        DecloggerServer.tick(event.getServer());
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DecloggerServer.onLogout(player);
        }
    }

    /** Keeps the remembered network and alert setting when a player respawns or changes dimension. */
    private void onPlayerClone(PlayerEvent.Clone event) {
        NetworkMemory.copy(event.getOriginal(), event.getEntity());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        DecloggerServer.onServerStopped();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
