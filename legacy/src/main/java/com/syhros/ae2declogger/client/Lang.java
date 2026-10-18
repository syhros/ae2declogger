package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LText;

/** Client-side text: translation keys in the player's language, and server texts resolved the same way. */
public final class Lang {

    public static final LText.Lang LANG = new LText.Lang() {
        @Override
        public String translate(String key, Object... args) {
            return ClientPlat.translate(key, args);
        }

        @Override
        public String itemName(LKey key) {
            return key.displayName();
        }
    };

    private Lang() {
    }

    public static String t(String key, Object... args) {
        return ClientPlat.translate(key, args);
    }

    public static String of(LText text) {
        return text == null ? "" : text.resolve(LANG);
    }
}
