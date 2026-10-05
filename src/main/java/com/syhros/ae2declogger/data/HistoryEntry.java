package com.syhros.ae2declogger.data;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import appeng.api.stacks.AEKey;

/**
 * A finished (or cancelled) job.
 *
 * @param finishedAtMs server wall-clock time when the job ended
 */
public record HistoryEntry(AEKey output, long amount, long durationMs, boolean completed, long finishedAtMs,
        Component cpuName) {

    public void write(FriendlyByteBuf buf) {
        AEKey.writeKey(buf, output);
        buf.writeVarLong(amount);
        buf.writeVarLong(Math.max(0, durationMs));
        buf.writeBoolean(completed);
        buf.writeLong(finishedAtMs);
        buf.writeComponent(cpuName);
    }

    @Nullable
    public static HistoryEntry read(FriendlyByteBuf buf) {
        AEKey key = AEKey.readKey(buf);
        long amount = buf.readVarLong();
        long duration = buf.readVarLong();
        boolean completed = buf.readBoolean();
        long finished = buf.readLong();
        Component cpu = buf.readComponent();
        return key == null ? null : new HistoryEntry(key, amount, duration, completed, finished, cpu);
    }
}
