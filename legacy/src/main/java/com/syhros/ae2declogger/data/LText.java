package com.syhros.ae2declogger.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.syhros.ae2declogger.net.Buf;

/**
 * Text sent from the server: a translation key with arguments, or a literal. Arguments are strings, numbers, other
 * texts or item keys (shown by their display name), so the client resolves everything in its own language. The
 * legacy versions have three different text component APIs; this is the one shape they share.
 */
public final class LText {

    /** Turns a translation key and resolved arguments into a string (the client's language). */
    public interface Lang {
        String translate(String key, Object... args);

        String itemName(LKey key);
    }

    private static final byte LITERAL = 0;
    private static final byte TRANSLATE = 1;
    private static final byte ARG_STRING = 0;
    private static final byte ARG_TEXT = 1;
    private static final byte ARG_KEY = 2;

    public static final LText EMPTY = literal("");

    private final boolean literal;
    private final String value;
    private final Object[] args;

    private LText(boolean literal, String value, Object[] args) {
        this.literal = literal;
        this.value = value;
        this.args = args;
    }

    public static LText literal(String text) {
        return new LText(true, text == null ? "" : text, new Object[0]);
    }

    /** Arguments: {@link LText}, {@link LKey}, or anything else (written as its string). */
    public static LText tr(String key, Object... args) {
        Object[] clean = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            clean[i] = a instanceof LText || a instanceof LKey ? a : String.valueOf(a);
        }
        return new LText(false, key, clean);
    }

    /** An item's display name. */
    public static LText item(LKey key) {
        return tr("%s", key);
    }

    public boolean isEmpty() {
        return literal && value.isEmpty();
    }

    public boolean isLiteral() {
        return literal;
    }

    /** The literal text, or the translation key. */
    public String value() {
        return value;
    }

    /** The arguments: {@link LText}, {@link LKey} or {@link String}. */
    public Object[] args() {
        return args.clone();
    }

    /** An item name text made by {@link #item}: its key, else null. */
    public LKey itemKey() {
        return !literal && "%s".equals(value) && args.length == 1 && args[0] instanceof LKey ? (LKey) args[0] : null;
    }

    public String resolve(Lang lang) {
        if (literal) {
            return value;
        }
        Object[] resolved = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof LText) {
                resolved[i] = ((LText) a).resolve(lang);
            } else if (a instanceof LKey) {
                resolved[i] = lang.itemName((LKey) a);
            } else {
                resolved[i] = a;
            }
        }
        if ("%s".equals(value) && resolved.length == 1) {
            return String.valueOf(resolved[0]);
        }
        return lang.translate(value, resolved);
    }

    public void write(Buf buf) {
        buf.writeByte(literal ? LITERAL : TRANSLATE);
        buf.writeString(value);
        if (literal) {
            return;
        }
        buf.writeVarInt(args.length);
        for (Object a : args) {
            if (a instanceof LText) {
                buf.writeByte(ARG_TEXT);
                ((LText) a).write(buf);
            } else if (a instanceof LKey) {
                buf.writeByte(ARG_KEY);
                ((LKey) a).write(buf);
            } else {
                buf.writeByte(ARG_STRING);
                buf.writeString((String) a);
            }
        }
    }

    public static LText read(Buf buf) {
        boolean lit = buf.readByte() == LITERAL;
        String value = buf.readString();
        if (lit) {
            return literal(value);
        }
        int n = buf.readVarInt();
        if (n < 0 || n > 64) {
            throw new IllegalStateException("too many text arguments: " + n);
        }
        List<Object> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte type = buf.readByte();
            if (type == ARG_TEXT) {
                args.add(read(buf));
            } else if (type == ARG_KEY) {
                LKey key = LKey.read(buf);
                args.add(key != null ? key : "?");
            } else {
                args.add(buf.readString());
            }
        }
        return new LText(false, value, args.toArray());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LText)) {
            return false;
        }
        LText t = (LText) o;
        return literal == t.literal && value.equals(t.value) && Arrays.equals(args, t.args);
    }

    @Override
    public int hashCode() {
        return value.hashCode() * 31 + Arrays.hashCode(args);
    }

    @Override
    public String toString() {
        return literal ? value : value + Arrays.toString(args);
    }
}
