package com.syhros.ae2declogger.data;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.network.Bufs;

//#if MC >= 1.20.5
//$$ import net.minecraft.core.HolderLookup;
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

    /** For saving with the world (see server.HistorySavedData). */
    //#if MC >= 1.20.5
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
        //#if MC >= 1.20.5
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
    //#if MC >= 1.20.5
    //$$ public static HistoryEntry fromTag(CompoundTag t, HolderLookup.Provider registries) {
    //$$     AEKey key = AEKey.fromTagGeneric(registries, t.getCompound("key"));
    //#else
    public static HistoryEntry fromTag(CompoundTag t) {
        AEKey key = AEKey.fromTagGeneric(t.getCompound("key"));
    //#endif
        if (key == null) {
            return null;
        }
        Component cpu = null;
        try {
            //#if MC >= 1.20.5
            //$$ cpu = Component.Serializer.fromJson(t.getString("cpu"), registries);
            //#else
            cpu = Component.Serializer.fromJson(t.getString("cpu"));
            //#endif
        } catch (RuntimeException e) {
            // keep the entry, lose the name
        }
        //#if MC >= 1.20.5
        //$$ AEKey parent = t.contains("of") ? AEKey.fromTagGeneric(registries, t.getCompound("of")) : null;
        //#else
        AEKey parent = t.contains("of") ? AEKey.fromTagGeneric(t.getCompound("of")) : null;
        //#endif
        return new HistoryEntry(key, t.getLong("amount"), t.getLong("ms"), t.getBoolean("done"), t.getLong("at"),
                cpu != null ? cpu : Text.empty(), parent);
    }
}
