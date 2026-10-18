package com.syhros.ae2declogger.data;

import com.syhros.ae2declogger.net.Buf;

/** A finished (or cancelled) job, or one finished step of a job. Saved with the world in the same encoding. */
public final class HistoryEntry {

    public final LKey output;
    public final long amount;
    public final long durationMs;
    public final boolean completed;
    /** Server wall-clock time when the job or step ended. */
    public final long finishedAtMs;
    public final LText cpuName;
    /** For a step: the item the job was making; null for a whole job. */
    public final LKey partOf;

    public HistoryEntry(LKey output, long amount, long durationMs, boolean completed, long finishedAtMs,
            LText cpuName, LKey partOf) {
        this.output = output;
        this.amount = amount;
        this.durationMs = durationMs;
        this.completed = completed;
        this.finishedAtMs = finishedAtMs;
        this.cpuName = cpuName;
        this.partOf = partOf;
    }

    /** One step of a bigger job (an intermediate item), not a job of its own. */
    public boolean isStep() {
        return partOf != null;
    }

    public void write(Buf buf) {
        output.write(buf);
        buf.writeVarLong(amount);
        buf.writeVarLong(Math.max(0, durationMs));
        buf.writeBoolean(completed);
        buf.writeLong(finishedAtMs);
        cpuName.write(buf);
        buf.writeNullable(partOf, (b, k) -> k.write(b));
    }

    /** Null when the item no longer exists (a removed mod). */
    public static HistoryEntry read(Buf buf) {
        LKey key = LKey.read(buf);
        long amount = buf.readVarLong();
        long duration = buf.readVarLong();
        boolean completed = buf.readBoolean();
        long finished = buf.readLong();
        LText cpu = LText.read(buf);
        LKey parent = buf.readBoolean() ? LKey.read(buf) : null;
        return key == null ? null : new HistoryEntry(key, amount, duration, completed, finished, cpu, parent);
    }
}
