package com.syhros.ae2declogger.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.syhros.ae2declogger.net.Buf;

/**
 * Everything the read-only machine viewer shows. Built on the server, never linked to a real inventory, so the
 * client cannot move anything.
 */
public final class MachineSnapshot {

    public static final byte FLAG_FULL = 1;
    public static final byte FLAG_PATTERN_INPUT = 2;
    public static final byte FLAG_EMPTY_INPUT = 4;

    public enum SectionKind {
        INPUT,
        OUTPUT,
        INVENTORY,
        FLUID_INPUT,
        FLUID_OUTPUT,
        FLUIDS
    }

    /**
     * One slot or tank. Items have {@code item}; fluids have {@code fluid} (their name) and an amount in mB.
     *
     * @param capacity stack limit for items, tank size in mB for fluids
     */
    public static final class Slot {
        public final LKey item;
        public final LText fluid;
        public final long amount;
        public final long capacity;
        public final byte flags;

        public Slot(LKey item, LText fluid, long amount, long capacity, byte flags) {
            this.item = item;
            this.fluid = fluid;
            this.amount = amount;
            this.capacity = capacity;
            this.flags = flags;
        }

        public boolean isEmpty() {
            return item == null && fluid == null;
        }

        public boolean has(byte flag) {
            return (flags & flag) != 0;
        }

        void write(Buf buf) {
            buf.writeNullable(item, (b, k) -> k.write(b));
            buf.writeNullable(fluid, (b, t) -> t.write(b));
            buf.writeVarLong(Math.max(0, amount));
            buf.writeVarLong(Math.max(0, capacity));
            buf.writeByte(flags);
        }

        static Slot read(Buf buf) {
            LKey item = buf.readNullable(LKey::read);
            LText fluid = buf.readNullable(LText::read);
            return new Slot(item, fluid, buf.readVarLong(), buf.readVarLong(), buf.readByte());
        }
    }

    public static final class Section {
        public final SectionKind kind;
        public final LText title;
        public final List<Slot> slots;

        public Section(SectionKind kind, LText title, List<Slot> slots) {
            this.kind = kind;
            this.title = title;
            this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
        }
    }

    public final LPos pos;
    public final LText name;
    public final MachineStatus status;
    public final LText statusText;
    public final int progress;
    public final int duration;
    public final long energyStored;
    public final long energyCapacity;
    /** "EU", "RF", "FE" or empty when the machine has no energy storage. */
    public final String energyUnit;
    /** Voltage tier name for GregTech machines, empty otherwise. */
    public final String tier;
    public final List<LText> errors;
    public final List<Section> sections;
    public final boolean truncated;

    public MachineSnapshot(LPos pos, LText name, MachineStatus status, LText statusText, int progress, int duration,
            long energyStored, long energyCapacity, String energyUnit, String tier, List<LText> errors,
            List<Section> sections, boolean truncated) {
        this.pos = pos;
        this.name = name;
        this.status = status;
        this.statusText = statusText;
        this.progress = progress;
        this.duration = duration;
        this.energyStored = energyStored;
        this.energyCapacity = energyCapacity;
        this.energyUnit = energyUnit;
        this.tier = tier;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        this.truncated = truncated;
    }

    public void write(Buf buf) {
        pos.write(buf);
        name.write(buf);
        buf.writeEnum(status);
        buf.writeNullable(statusText, (b, t) -> t.write(b));
        buf.writeVarInt(progress + 1);
        buf.writeVarInt(duration + 1);
        buf.writeVarLong(Math.max(0, energyStored));
        buf.writeVarLong(Math.max(0, energyCapacity));
        buf.writeString(energyUnit);
        buf.writeString(tier);
        buf.writeList(errors, (b, t) -> t.write(b));
        buf.writeList(sections, (b, s) -> {
            b.writeEnum(s.kind);
            s.title.write(b);
            b.writeList(s.slots, (bb, slot) -> slot.write(bb));
        });
        buf.writeBoolean(truncated);
    }

    public static MachineSnapshot read(Buf buf) {
        LPos pos = LPos.read(buf);
        LText name = LText.read(buf);
        MachineStatus status = buf.readEnum(MachineStatus.class, MachineStatus.UNKNOWN);
        LText statusText = buf.readNullable(LText::read);
        int progress = buf.readVarInt() - 1;
        int duration = buf.readVarInt() - 1;
        long stored = buf.readVarLong();
        long capacity = buf.readVarLong();
        String unit = buf.readString();
        String tier = buf.readString();
        List<LText> errors = buf.readList(LText::read);
        List<Section> sections = buf.readList(b -> {
            SectionKind kind = b.readEnum(SectionKind.class, SectionKind.INVENTORY);
            LText title = LText.read(b);
            return new Section(kind, title, b.readList(Slot::read));
        });
        boolean truncated = buf.readBoolean();
        return new MachineSnapshot(pos, name, status, statusText, progress, duration, stored, capacity, unit, tier,
                errors, sections, truncated);
    }
}
