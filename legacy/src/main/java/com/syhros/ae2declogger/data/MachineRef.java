package com.syhros.ae2declogger.data;

import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.net.Buf;

/** A block involved in a crafting step: the interface, the machine it feeds, or a multiblock controller. */
public final class MachineRef {

    public enum Kind {
        PROVIDER,
        MACHINE,
        CONTROLLER
    }

    public final LPos pos;
    public final LText name;
    public final Kind kind;
    public final MachineStatus status;
    public final BlockReason problem;
    /** Extra text from the machine, e.g. GregTech's reason; may be null. */
    public final LText detail;
    /** Recipe progress in ticks, -1 if unknown. */
    public final int progress;
    /** Recipe length in ticks, -1 if unknown. */
    public final int duration;

    public MachineRef(LPos pos, LText name, Kind kind, MachineStatus status, BlockReason problem, LText detail,
            int progress, int duration) {
        this.pos = pos;
        this.name = name;
        this.kind = kind;
        this.status = status;
        this.problem = problem == null ? BlockReason.NONE : problem;
        this.detail = detail;
        this.progress = progress;
        this.duration = duration;
    }

    public MachineRef withProblem(BlockReason p) {
        return new MachineRef(pos, name, kind, status, p, detail, progress, duration);
    }

    public boolean hasProblem() {
        return problem.isProblem();
    }

    public void write(Buf buf) {
        pos.write(buf);
        name.write(buf);
        buf.writeEnum(kind);
        buf.writeEnum(status);
        buf.writeEnum(problem);
        buf.writeNullable(detail, (b, d) -> d.write(b));
        buf.writeVarInt(progress + 1);
        buf.writeVarInt(duration + 1);
    }

    public static MachineRef read(Buf buf) {
        LPos pos = LPos.read(buf);
        LText name = LText.read(buf);
        Kind kind = buf.readEnum(Kind.class, Kind.MACHINE);
        MachineStatus status = buf.readEnum(MachineStatus.class, MachineStatus.UNKNOWN);
        BlockReason problem = buf.readEnum(BlockReason.class, BlockReason.UNKNOWN);
        LText detail = buf.readNullable(LText::read);
        int progress = buf.readVarInt() - 1;
        int duration = buf.readVarInt() - 1;
        return new MachineRef(pos, name, kind, status, problem, detail, progress, duration);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MachineRef)) {
            return false;
        }
        MachineRef m = (MachineRef) o;
        return pos.equals(m.pos) && name.equals(m.name) && kind == m.kind && status == m.status
                && problem == m.problem && java.util.Objects.equals(detail, m.detail) && progress == m.progress
                && duration == m.duration;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(pos, name, kind, status, problem, detail, progress, duration);
    }
}
