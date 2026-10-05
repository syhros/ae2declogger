package com.syhros.ae2declogger.item;

import com.syhros.ae2declogger.Ae2Declogger;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
            Ae2Declogger.MOD_ID);

    public static final RegistryObject<DecloggerItem> DECLOGGER = ITEMS.register("declogger",
            () -> new DecloggerItem(new Item.Properties().rarity(Rarity.UNCOMMON)));

    private ModItems() {
    }

    public static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(DECLOGGER);
        }
    }
}
