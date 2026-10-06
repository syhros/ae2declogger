package com.syhros.ae2declogger.data;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Nbt;
import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.network.Bufs;

//#if MC >= 1.20.5
//$$ import net.minecraft.core.HolderLookup;
//#endif
//#if MC >= 26.1
//$$ import com.mojang.serialization.Codec;
//$$ import net.minecraft.nbt.NbtOps;
//$$ import net.minecraft.nbt.Tag;
//$$ import net.minecraft.network.chat.ComponentSerialization;
//#endif
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import appeng.api.stacks.AEKey;

/**
 * A finished (or cancelled) job, or one finished step of a job.
 *
 * @param finishedAtMs server wall-clock time when the job or step ended
 * @param partOf       for a step: the item the job was making; null for a whole job
 */
public record HistoryEntry(AEKey output, long amount, long durationMs, boolean completed, long finishedAtMs,
        Component cpuName, @Nullable AEKey partOf) {

    public HistoryEntry(AEKey output, long amount, long durationMs, boolean completed, long finishedAtMs,
            Component cpuName) {
        this(output, amount, durationMs, completed, finishedAtMs, cpuName, null);
    }

    /** One step of a bigger job (an intermediate item), not a job of its own. */
    public boolean isStep() {
        return partOf != null;
    }

    public void write(FriendlyByteBuf buf) {
        Bufs.writeKey(buf, output);
        buf.writeVarLong(amount);
        buf.writeVarLong(Math.max(0, durationMs));
        buf.writeBoolean(completed);
        buf.writeLong(finishedAtMs);
        Bufs.writeComponent(buf, cpuName);
        buf.writeBoolean(partOf != null);
        if (partOf != null) {
            Bufs.writeKey(buf, partOf);
        }
    }

    @Nullable
    public static HistoryEntry read(FriendlyByteBuf buf) {
        AEKey key = Bufs.readKey(buf);
        long amount = buf.readVarLong();
        long duration = buf.readVarLong();
        boolean completed = buf.readBoolean();
        long finished = buf.readLong();
        Component cpu = Bufs.readComponent(buf);
        AEKey parent = buf.readBoolean() ? Bufs.readKey(buf) : null;
        return key == null ? null : new HistoryEntry(key, amount, duration, completed, finished, cpu, parent);
    }

    //#if MC >= 26.1
    //$$ /** 26.1 AE2 writes keys only through codecs; these encode to and decode from NBT. */
    //$$ private static <T> Tag encode(Codec<T> codec, T value, HolderLookup.Provider registries) {
    //$$     return codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value).getOrThrow();
    //$$ }
    //$$
    //$$ @Nullable
    //$$ private static <T> T decode(Codec<T> codec, @Nullable Tag tag, HolderLookup.Provider registries) {
    //$$     return tag == null ? null
    //$$             : codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).result().orElse(null);
    //$$ }
    //#endif

    /** For saving with the world (see server.HistorySavedData). */
    //#if MC >= 26.1
    //$$ public CompoundTag toTag(HolderLookup.Provider registries) {
    //$$     CompoundTag t = new CompoundTag();
    //$$     t.put("key", encode(AEKey.CODEC, output, registries));
    //#elseif MC >= 1.20.5
    //$$ public CompoundTag toTag(HolderLookup.Provider registries) {
    //$$     CompoundTag t = new CompoundTag();
    //$$     t.put("key", output.toTagGeneric(registries));
    //#else
    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.put("key", output.toTagGeneric());
    //#endif
        t.putLong("amount", amount);
        t.putLong("ms", Math.max(0, durationMs));
        t.putBoolean("done", completed);
        t.putLong("at", finishedAtMs);
        //#if MC >= 26.1
        //$$ t.put("cpu", encode(ComponentSerialization.CODEC, cpuName, registries));
        //$$ if (partOf != null) {
        //$$     t.put("of", encode(AEKey.CODEC, partOf, registries));
        //$$ }
        //#elseif MC >= 1.20.5
        //$$ t.putString("cpu", Component.Serializer.toJson(cpuName, registries));
        //$$ if (partOf != null) {
        //$$     t.put("of", partOf.toTagGeneric(registries));
        //$$ }
        //#else
        t.putString("cpu", Component.Serializer.toJson(cpuName));
        if (partOf != null) {
            t.put("of", partOf.toTagGeneric());
        }
        //#endif
        return t;
    }

    /** Null when the item no longer exists (a removed mod). */
    @Nullable
    //#if MC >= 26.1
    //$$ public static HistoryEntry fromTag(CompoundTag t, HolderLookup.Provider registries) {
    //$$     AEKey key = decode(AEKey.CODEC, t.get("key"), registries);
    //#elseif MC >= 1.20.5
    //$$ public static HistoryEntry fromTag(CompoundTag t, HolderLookup.Provider registries) {
    //$$     AEKey key = AEKey.fromTagGeneric(registries, Nbt.compound(t, "key"));
    //#else
    public static HistoryEntry fromTag(CompoundTag t) {
        AEKey key = AEKey.fromTagGeneric(Nbt.compound(t, "key"));
    //#endif
        if (key == null) {
            return null;
        }
        Component cpu = null;
        try {
            //#if MC >= 26.1
            //$$ cpu = decode(ComponentSerialization.CODEC, t.get("cpu"), registries);
            //#elseif MC >= 1.20.5
            //$$ cpu = Component.Serializer.fromJson(Nbt.getString(t, "cpu"), registries);
            //#else
            cpu = Component.Serializer.fromJson(Nbt.getString(t, "cpu"));
            //#endif
        } catch (RuntimeException e) {
            // keep the entry, lose the name
        }
        //#if MC >= 26.1
        //$$ AEKey parent = decode(AEKey.CODEC, t.get("of"), registries);
        //#elseif MC >= 1.20.5
        //$$ AEKey parent = t.contains("of") ? AEKey.fromTagGeneric(registries, Nbt.compound(t, "of")) : null;
        //#else
        AEKey parent = t.contains("of") ? AEKey.fromTagGeneric(Nbt.compound(t, "of")) : null;
        //#endif
        return new HistoryEntry(key, Nbt.getLong(t, "amount"), Nbt.getLong(t, "ms"), Nbt.getBoolean(t, "done"),
                Nbt.getLong(t, "at"),
                cpu != null ? cpu : Text.empty(), parent);
    }
}
