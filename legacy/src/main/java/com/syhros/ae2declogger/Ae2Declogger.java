package com.syhros.ae2declogger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//#if MC >= 1.16
import net.minecraftforge.fml.common.Mod;
//#elseif MC >= 1.12
//$$ import net.minecraftforge.fml.common.Mod;
//$$ import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
//$$ import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
//#else
//$$ import cpw.mods.fml.common.Mod;
//$$ import cpw.mods.fml.common.event.FMLPreInitializationEvent;
//$$ import cpw.mods.fml.common.event.FMLServerStoppedEvent;
//#endif

/**
 * AE2 Declogger for the legacy versions (1.16.5, 1.12.2, GTNH 1.7.10). Start-up is in each version's {@code Boot}.
 */
//#if MC >= 1.16
@Mod(Ae2Declogger.MOD_ID)
//#elseif MC >= 1.12
//$$ @Mod(modid = Ae2Declogger.MOD_ID, useMetadata = true, dependencies = "required-after:appliedenergistics2", acceptedMinecraftVersions = "[1.12.2]")
//#else
//$$ @Mod(modid = Ae2Declogger.MOD_ID, useMetadata = true, dependencies = "required-after:appliedenergistics2;after:gregtech", acceptedMinecraftVersions = "[1.7.10]")
//#endif
public final class Ae2Declogger {

    public static final String MOD_ID = "ae2declogger";
    public static final Logger LOG = LogManager.getLogger("AE2 Declogger");

    //#if MC >= 1.16
    public Ae2Declogger() {
        Boot.construct();
    }
    //#else
    //$$ @Mod.EventHandler
    //$$ public void preInit(FMLPreInitializationEvent event) {
    //$$     Boot.preInit(event.getModConfigurationDirectory());
    //$$ }
    //$$
    //$$ @Mod.EventHandler
    //$$ public void serverStopped(FMLServerStoppedEvent event) {
    //$$     Boot.serverStopped();
    //$$ }
    //#endif
}
