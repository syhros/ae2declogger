package com.syhros.ae2declogger.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.net.Buf;

/** One item inside a running job, with the three numbers AE2's crafting status screen shows. */
public final class StepView {

    public final LKey key;
    /** Sitting in the crafting CPU. */
    public final long stored;
    /** Sent to machines, waiting for the result ("Crafting" in AE2). */
    public final long active;
    /** Still to be crafted ("Scheduled" in AE2). */
    public final long pending;
    public final JobState state;
    public final BlockReason reason;
    public final LText detail;
    /** Server time when this step last moved, 0 if unknown. */
    public final long sinceMs;
    /** Known recipe duration in ms (from GregTech), -1 if unknown. */
    public final long recipeMs;
    public final List<MachineRef> machines;

    public StepView(LKey key, long stored, long active, long pending, JobState state, BlockReason reason,
            LText detail, long sinceMs, long recipeMs, List<MachineRef> machines) {
        this.key = Objects.requireNonNull(key);
        this.stored = stored;
        this.active = active;
        this.pending = pending;
        this.state = state;
        this.reason = reason;
        this.detail = detail;
        this.sinceMs = sinceMs;
        this.recipeMs = recipeMs;
        this.machines = Collections.unmodifiableList(new ArrayList<>(machines));
    }

    public void write(Buf buf) {
        key.write(buf);
        buf.writeVarLong(stored);
        buf.writeVarLong(active);
        buf.writeVarLong(pending);
        buf.writeEnum(state);
        buf.writeEnum(reason);
        buf.writeNullable(detail, (b, d) -> d.write(b));
        buf.writeLong(sinceMs);
        buf.writeLong(recipeMs);
        buf.writeList(machines, (b, m) -> m.write(b));
    }

    /** Null when the item is unknown on this side. */
    public static StepView read(Buf buf) {
        LKey key = LKey.read(buf);
        long stored = buf.readVarLong();
        long active = buf.readVarLong();
        long pending = buf.readVarLong();
        JobState state = buf.readEnum(JobState.class, JobState.IN_PROGRESS);
        BlockReason reason = buf.readEnum(BlockReason.class, BlockReason.UNKNOWN);
        LText detail = buf.readNullable(LText::read);
        long since = buf.readLong();
        long recipeMs = buf.readLong();
        List<MachineRef> machines = buf.readList(MachineRef::read);
        return key == null ? null
                : new StepView(key, stored, active, pending, state, reason, detail, since, recipeMs, machines);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof StepView)) {
            return false;
        }
        StepView s = (StepView) o;
        return key.equals(s.key) && stored == s.stored && active == s.active && pending == s.pending
                && state == s.state && reason == s.reason && Objects.equals(detail, s.detail) && sinceMs == s.sinceMs
                && recipeMs == s.recipeMs && machines.equals(s.machines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, stored, active, pending, state, reason, detail, sinceMs, recipeMs, machines);
    }
}
