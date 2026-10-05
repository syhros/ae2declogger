package com.syhros.ae2declogger.data;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import appeng.api.stacks.AEKey;

/**
 * One item or fluid inside a running job, with the same three numbers AE2's crafting status screen shows.
 *
 * @param stored    sitting in the crafting CPU
 * @param active    sent to machines, waiting for the result ("Crafting" in AE2)
 * @param pending   still to be crafted ("Scheduled" in AE2)
 * @param sinceMs   server time when this step last moved, 0 if unknown
 * @param recipeMs  known recipe duration in ms (from GregTech), -1 if unknown
 */
public record StepView(AEKey key, long stored, long active, long pending, JobState state, BlockReason reason,
        @Nullable Component detail, long sinceMs, long recipeMs, List<MachineRef> machines) {

    public StepView {
        machines = List.copyOf(machines);
    }

    public void write(FriendlyByteBuf buf) {
        AEKey.writeKey(buf, key);
        buf.writeVarLong(stored);
        buf.writeVarLong(active);
        buf.writeVarLong(pending);
        buf.writeEnum(state);
        buf.writeEnum(reason);
        buf.writeNullable(detail, FriendlyByteBuf::writeComponent);
        buf.writeLong(sinceMs);
        buf.writeLong(recipeMs);
        buf.writeCollection(machines, (b, m) -> m.write(b));
    }

    @Nullable
    public static StepView read(FriendlyByteBuf buf) {
        AEKey key = AEKey.readKey(buf);
        long stored = buf.readVarLong();
        long active = buf.readVarLong();
        long pending = buf.readVarLong();
        JobState state = buf.readEnum(JobState.class);
        BlockReason reason = buf.readEnum(BlockReason.class);
        Component detail = buf.readNullable(FriendlyByteBuf::readComponent);
        long since = buf.readLong();
        long recipeMs = buf.readLong();
        List<MachineRef> machines = buf.readList(MachineRef::read);
        if (key == null) {
            return null;
        }
        return new StepView(key, stored, active, pending, state, reason, detail, since, recipeMs, machines);
    }
}
