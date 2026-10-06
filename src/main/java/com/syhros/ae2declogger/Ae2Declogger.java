package com.syhros.ae2declogger;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.syhros.ae2declogger.compat.gtceu.GtCompat;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.network.DeclogNetwork;
import com.syhros.ae2declogger.server.DecloggerServer;
import com.syhros.ae2declogger.server.NetworkMemory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Ae2Declogger.MOD_ID)
public final class Ae2Declogger {

    public static final String MOD_ID = "ae2declogger";
    public static final Logger LOG = LogUtils.getLogger();

    public Ae2Declogger() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DeclogConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, DeclogConfig.CLIENT_SPEC);

        DeclogNetwork.register();

        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onLogout);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerClone);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOG.info("AE2 Declogger ready (GregTech integration: {})", GtCompat.isActive() ? "on" : "off");
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DecloggerServer.tick(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer());
        }
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        //#if MC >= 1.19
        if (event.getEntity() instanceof ServerPlayer player) {
        //#else
        //$$ if (event.getPlayer() instanceof ServerPlayer player) {
        //#endif
            DecloggerServer.onLogout(player);
        }
    }

    /** Keeps the remembered network and alert setting when a player respawns or changes dimension. */
    private void onPlayerClone(PlayerEvent.Clone event) {
        //#if MC >= 1.19
        NetworkMemory.copy(event.getOriginal(), event.getEntity());
        //#else
        //$$ NetworkMemory.copy(event.getOriginal(), event.getPlayer());
        //#endif
    }

    private void onServerStopped(ServerStoppedEvent event) {
        DecloggerServer.onServerStopped();
    }

    public static ResourceLocation id(String path) {
        //#if MC >= 1.21
        //$$ return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
        //#else
        return new ResourceLocation(MOD_ID, path);
        //#endif
    }
}
