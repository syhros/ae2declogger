package com.syhros.ae2declogger;

import net.minecraft.network.chat.MutableComponent;
//#if MC >= 1.19
import net.minecraft.network.chat.Component;
//#else
//$$ import net.minecraft.network.chat.TextComponent;
//$$ import net.minecraft.network.chat.TranslatableComponent;
//#endif

/** Text components the same way on every version (1.19 replaced TextComponent/TranslatableComponent). */
public final class Text {

    private Text() {
    }

    public static MutableComponent translatable(String key, Object... args) {
        //#if MC >= 1.19
        return Component.translatable(key, args);
        //#else
        //$$ return new TranslatableComponent(key, args);
        //#endif
    }

    public static MutableComponent literal(String text) {
        //#if MC >= 1.19
        return Component.literal(text);
        //#else
        //$$ return new TextComponent(text);
        //#endif
    }

    public static MutableComponent empty() {
        //#if MC >= 1.19
        return Component.empty();
        //#else
        //$$ return new TextComponent("");
        //#endif
    }
}
