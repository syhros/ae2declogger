package com.syhros.ae2declogger.data;

import org.jetbrains.annotations.Nullable;

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
        AEKey.writeKey(buf, output);
        buf.writeVarLong(amount);
        buf.writeVarLong(Math.max(0, durationMs));
        buf.writeBoolean(completed);
        buf.writeLong(finishedAtMs);
        buf.writeComponent(cpuName);
        buf.writeBoolean(partOf != null);
        if (partOf != null) {
            AEKey.writeKey(buf, partOf);
        }
    }

    @Nullable
    public static HistoryEntry read(FriendlyByteBuf buf) {
        AEKey key = AEKey.readKey(buf);
        long amount = buf.readVarLong();
        long duration = buf.readVarLong();
        boolean completed = buf.readBoolean();
        long finished = buf.readLong();
        Component cpu = buf.readComponent();
        AEKey parent = buf.readBoolean() ? AEKey.readKey(buf) : null;
        return key == null ? null : new HistoryEntry(key, amount, duration, completed, finished, cpu, parent);
    }

    /** For saving with the world (see server.HistorySavedData). */
    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.put("key", output.toTagGeneric());
        t.putLong("amount", amount);
        t.putLong("ms", Math.max(0, durationMs));
        t.putBoolean("done", completed);
        t.putLong("at", finishedAtMs);
        t.putString("cpu", Component.Serializer.toJson(cpuName));
        if (partOf != null) {
            t.put("of", partOf.toTagGeneric());
        }
        return t;
    }

    /** Null when the item no longer exists (a removed mod). */
    @Nullable
    public static HistoryEntry fromTag(CompoundTag t) {
        AEKey key = AEKey.fromTagGeneric(t.getCompound("key"));
        if (key == null) {
            return null;
        }
        Component cpu = null;
        try {
            cpu = Component.Serializer.fromJson(t.getString("cpu"));
        } catch (RuntimeException e) {
            // keep the entry, lose the name
        }
        AEKey parent = t.contains("of") ? AEKey.fromTagGeneric(t.getCompound("of")) : null;
        return new HistoryEntry(key, t.getLong("amount"), t.getLong("ms"), t.getBoolean("done"), t.getLong("at"),
                cpu != null ? cpu : Component.empty(), parent);
    }
}
