package com.syhros.ae2declogger.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.net.Buf;

/**
 * One busy crafting CPU and its job, as sent to the client. Times are absolute server timestamps so the row only
 * changes (and is only re-sent) when something actually happens.
 */
public final class JobView {

    /** Stable id for the CPU's current job. */
    public final long id;
    public final LText cpuName;
    public final int coProcessors;
    public final long cpuStorage;
    public final LKey output;
    public final long amount;
    /** 0..1, rounded to 0.1 %. */
    public final float progress;
    public final long startedAtMs;
    public final long lastActivityAtMs;
    /** Expected total time from past runs of the same item, -1 if unknown. */
    public final long expectedMs;
    public final JobState state;
    public final BlockReason reason;
    public final LText detail;
    /** Machine to jump to from the job row, may be null. */
    public final MachineRef primaryMachine;
    public final List<StepView> steps;

    public JobView(long id, LText cpuName, int coProcessors, long cpuStorage, LKey output, long amount,
            float progress, long startedAtMs, long lastActivityAtMs, long expectedMs, JobState state,
            BlockReason reason, LText detail, MachineRef primaryMachine, List<StepView> steps) {
        this.id = id;
        this.cpuName = cpuName;
        this.coProcessors = coProcessors;
        this.cpuStorage = cpuStorage;
        this.output = Objects.requireNonNull(output);
        this.amount = amount;
        this.progress = progress;
        this.startedAtMs = startedAtMs;
        this.lastActivityAtMs = lastActivityAtMs;
        this.expectedMs = expectedMs;
        this.state = state;
        this.reason = reason;
        this.detail = detail;
        this.primaryMachine = primaryMachine;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    public void write(Buf buf) {
        buf.writeLong(id);
        cpuName.write(buf);
        buf.writeVarInt(coProcessors);
        buf.writeVarLong(cpuStorage);
        output.write(buf);
        buf.writeVarLong(amount);
        buf.writeFloat(progress);
        buf.writeLong(startedAtMs);
        buf.writeLong(lastActivityAtMs);
        buf.writeLong(expectedMs);
        buf.writeEnum(state);
        buf.writeEnum(reason);
        buf.writeNullable(detail, (b, d) -> d.write(b));
        buf.writeNullable(primaryMachine, (b, m) -> m.write(b));
        buf.writeList(steps, (b, s) -> s.write(b));
    }

    /** Null when the output item is unknown on this side. */
    public static JobView read(Buf buf) {
        long id = buf.readLong();
        LText cpuName = LText.read(buf);
        int coProcessors = buf.readVarInt();
        long cpuStorage = buf.readVarLong();
        LKey output = LKey.read(buf);
        long amount = buf.readVarLong();
        float progress = buf.readFloat();
        long started = buf.readLong();
        long lastActivity = buf.readLong();
        long expected = buf.readLong();
        JobState state = buf.readEnum(JobState.class, JobState.IN_PROGRESS);
        BlockReason reason = buf.readEnum(BlockReason.class, BlockReason.UNKNOWN);
        LText detail = buf.readNullable(LText::read);
        MachineRef primary = buf.readNullable(MachineRef::read);
        List<StepView> steps = buf.readList(StepView::read);
        return output == null ? null
                : new JobView(id, cpuName, coProcessors, cpuStorage, output, amount, progress, started, lastActivity,
                        expected, state, reason, detail, primary, steps);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JobView)) {
            return false;
        }
        JobView j = (JobView) o;
        return id == j.id && cpuName.equals(j.cpuName) && coProcessors == j.coProcessors
                && cpuStorage == j.cpuStorage && output.equals(j.output) && amount == j.amount
                && Float.compare(progress, j.progress) == 0 && startedAtMs == j.startedAtMs
                && lastActivityAtMs == j.lastActivityAtMs && expectedMs == j.expectedMs && state == j.state
                && reason == j.reason && Objects.equals(detail, j.detail)
                && Objects.equals(primaryMachine, j.primaryMachine) && steps.equals(j.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, output, amount, progress, lastActivityAtMs, state, reason, steps);
    }
}
