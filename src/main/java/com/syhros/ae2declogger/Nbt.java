package com.syhros.ae2declogger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
//#if MC < 1.21.5
import net.minecraft.nbt.Tag;
//#endif

/** Reading NBT the same way on every version (from 1.21.5 the getters return Optionals). Missing values read as 0/"". */
public final class Nbt {

    private Nbt() {
    }

    public static CompoundTag compound(CompoundTag tag, String key) {
        //#if MC >= 1.21.5
        //$$ return tag.getCompoundOrEmpty(key);
        //#else
        return tag.getCompound(key);
        //#endif
    }

    /** A list of compounds. */
    public static ListTag compounds(CompoundTag tag, String key) {
        //#if MC >= 1.21.5
        //$$ return tag.getListOrEmpty(key);
        //#else
        return tag.getList(key, Tag.TAG_COMPOUND);
        //#endif
    }

    public static long getLong(CompoundTag tag, String key) {
        //#if MC >= 1.21.5
        //$$ return tag.getLongOr(key, 0L);
        //#else
        return tag.getLong(key);
        //#endif
    }

    public static String getString(CompoundTag tag, String key) {
        //#if MC >= 1.21.5
        //$$ return tag.getStringOr(key, "");
        //#else
        return tag.getString(key);
        //#endif
    }

    public static boolean getBoolean(CompoundTag tag, String key) {
        //#if MC >= 1.21.5
        //$$ return tag.getBooleanOr(key, false);
        //#else
        return tag.getBoolean(key);
        //#endif
    }
}
