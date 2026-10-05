package com.syhros.ae2declogger.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import appeng.api.stacks.AEKey;

/**
 * One busy crafting CPU and its job, as sent to the client. Times are absolute server timestamps so the row only
 * changes (and is only re-sent) when something actually happens.
 *
 * @param id               stable id for the CPU's current job
 * @param progress         0..1, rounded to 0.1 %
 * @param startedAtMs      server time the job started
 * @param lastActivityAtMs server time anything in the job last changed
 * @param expectedMs       expected total time from past runs of the same item, -1 if unknown
 * @param primaryMachine   machine to jump to from the job row, may be null
 */
public record JobView(long id, Component cpuName, int coProcessors, long cpuStorage, AEKey output, long amount,
        float progress, long startedAtMs, long lastActivityAtMs, long expectedMs, JobState state,
        BlockReason reason, @Nullable Component detail, @Nullable MachineRef primaryMachine, List<StepView> steps) {

    public JobView {
        steps = List.copyOf(steps);
        Objects.requireNonNull(output);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(id);
        buf.writeComponent(cpuName);
        buf.writeVarInt(coProcessors);
        buf.writeVarLong(cpuStorage);
        AEKey.writeKey(buf, output);
        buf.writeVarLong(amount);
        buf.writeFloat(progress);
        buf.writeLong(startedAtMs);
        buf.writeLong(lastActivityAtMs);
        buf.writeLong(expectedMs);
        buf.writeEnum(state);
        buf.writeEnum(reason);
        buf.writeNullable(detail, FriendlyByteBuf::writeComponent);
        buf.writeNullable(primaryMachine, (b, m) -> m.write(b));
        buf.writeCollection(steps, (b, s) -> s.write(b));
    }

    @Nullable
    public static JobView read(FriendlyByteBuf buf) {
        long id = buf.readLong();
        Component cpuName = buf.readComponent();
        int coProcessors = buf.readVarInt();
        long cpuStorage = buf.readVarLong();
        AEKey output = AEKey.readKey(buf);
        long amount = buf.readVarLong();
        float progress = buf.readFloat();
        long started = buf.readLong();
        long lastActivity = buf.readLong();
        long expected = buf.readLong();
        JobState state = buf.readEnum(JobState.class);
        BlockReason reason = buf.readEnum(BlockReason.class);
        Component detail = buf.readNullable(FriendlyByteBuf::readComponent);
        MachineRef primary = buf.readNullable(MachineRef::read);
        List<StepView> rawSteps = buf.readList(StepView::read);
        List<StepView> steps = new ArrayList<>(rawSteps.size());
        for (StepView s : rawSteps) {
            if (s != null) {
                steps.add(s);
            }
        }
        if (output == null) {
            return null;
        }
        return new JobView(id, cpuName, coProcessors, cpuStorage, output, amount, progress, started, lastActivity,
                expected, state, reason, detail, primary, steps);
    }
}
