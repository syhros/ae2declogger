package com.syhros.ae2declogger;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
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

    /** Clicking the text copies {@code value} (chat click events became records in 1.21.5). */
    public static ClickEvent copyOnClick(String value) {
        //#if MC >= 1.21.5
        //$$ return new ClickEvent.CopyToClipboard(value);
        //#else
        return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value);
        //#endif
    }

    public static ClickEvent openFileOnClick(String path) {
        //#if MC >= 1.21.5
        //$$ return new ClickEvent.OpenFile(path);
        //#else
        return new ClickEvent(ClickEvent.Action.OPEN_FILE, path);
        //#endif
    }

    public static HoverEvent showText(net.minecraft.network.chat.Component text) {
        //#if MC >= 1.21.5
        //$$ return new HoverEvent.ShowText(text);
        //#else
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
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
