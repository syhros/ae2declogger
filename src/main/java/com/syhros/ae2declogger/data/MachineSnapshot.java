package com.syhros.ae2declogger.data;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.network.Bufs;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import appeng.api.stacks.GenericStack;

/**
 * Everything the read-only machine viewer shows. Built on the server, never linked to a real inventory, so the
 * client cannot move anything.
 *
 * @param energyUnit  "EU", "FE" or empty when the machine has no energy storage
 * @param tier        voltage tier name for GregTech machines, empty otherwise
 */
public record MachineSnapshot(ResourceKey<Level> dimension, BlockPos pos, Component name, MachineStatus status,
        @Nullable Component statusText, int progress, int duration, long energyStored, long energyCapacity,
        String energyUnit, String tier, List<Component> errors, List<Section> sections, boolean truncated) {

    /** Slot flags. */
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
     * @param capacity stack limit for items, tank size in mB for fluids
     */
    public record Slot(@Nullable GenericStack stack, long capacity, byte flags) {
        public boolean has(byte flag) {
            return (flags & flag) != 0;
        }
    }

    public record Section(SectionKind kind, Component title, List<Slot> slots) {
        public Section {
            slots = List.copyOf(slots);
        }
    }

    public MachineSnapshot {
        errors = List.copyOf(errors);
        sections = List.copyOf(sections);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceKey(dimension);
        buf.writeBlockPos(pos);
        Bufs.writeComponent(buf, name);
        buf.writeEnum(status);
        buf.writeNullable(statusText, Bufs::writeComponent);
        buf.writeVarInt(progress + 1);
        buf.writeVarInt(duration + 1);
        buf.writeVarLong(Math.max(0, energyStored));
        buf.writeVarLong(Math.max(0, energyCapacity));
        buf.writeUtf(energyUnit, 8);
        buf.writeUtf(tier, 16);
        buf.writeCollection(errors, Bufs::writeComponent);
        buf.writeCollection(sections, (b, s) -> {
            b.writeEnum(s.kind());
            Bufs.writeComponent(b, s.title());
            b.writeCollection(s.slots(), (bb, slot) -> {
                Bufs.writeStack(bb, slot.stack());
                bb.writeVarLong(Math.max(0, slot.capacity()));
                bb.writeByte(slot.flags());
            });
        });
        buf.writeBoolean(truncated);
    }

    public static MachineSnapshot read(FriendlyByteBuf buf) {
        ResourceKey<Level> dim = buf.readResourceKey(Mc.DIMENSIONS);
        BlockPos pos = buf.readBlockPos();
        Component name = Bufs.readComponent(buf);
        MachineStatus status = buf.readEnum(MachineStatus.class);
        Component statusText = buf.readNullable(Bufs::readComponent);
        int progress = buf.readVarInt() - 1;
        int duration = buf.readVarInt() - 1;
        long stored = buf.readVarLong();
        long capacity = buf.readVarLong();
        String unit = buf.readUtf(8);
        String tier = buf.readUtf(16);
        List<Component> errors = buf.readList(Bufs::readComponent);
        List<Section> sections = buf.readList(b -> {
            SectionKind kind = b.readEnum(SectionKind.class);
            Component title = Bufs.readComponent(b);
            List<Slot> slots = new ArrayList<>(b.readList(bb -> new Slot(Bufs.readStack(bb),
                    bb.readVarLong(), bb.readByte())));
            return new Section(kind, title, slots);
        });
        boolean truncated = buf.readBoolean();
        return new MachineSnapshot(dim, pos, name, status, statusText, progress, duration, stored, capacity, unit,
                tier, errors, sections, truncated);
    }
}
