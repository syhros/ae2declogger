package com.syhros.ae2declogger.data;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.logic.BlockReason;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A block involved in a crafting step: the pattern provider, the machine it feeds, or a multiblock controller.
 *
 * @param detail   extra text from the machine, e.g. GregTech's waiting reason; may be null
 * @param progress recipe progress in ticks, -1 if unknown
 * @param duration recipe length in ticks, -1 if unknown
 */
public record MachineRef(ResourceKey<Level> dimension, BlockPos pos, Component name, Kind kind,
        MachineStatus status, BlockReason problem, @Nullable Component detail, int progress, int duration) {

    public enum Kind {
        PROVIDER,
        MACHINE,
        CONTROLLER
    }

    public boolean hasProblem() {
        return problem != null && problem.isProblem();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceKey(dimension);
        buf.writeBlockPos(pos);
        buf.writeComponent(name);
        buf.writeEnum(kind);
        buf.writeEnum(status);
        buf.writeEnum(problem);
        buf.writeNullable(detail, FriendlyByteBuf::writeComponent);
        buf.writeVarInt(progress + 1);
        buf.writeVarInt(duration + 1);
    }

    public static MachineRef read(FriendlyByteBuf buf) {
        ResourceKey<Level> dim = buf.readResourceKey(Registries.DIMENSION);
        BlockPos pos = buf.readBlockPos();
        Component name = buf.readComponent();
        Kind kind = buf.readEnum(Kind.class);
        MachineStatus status = buf.readEnum(MachineStatus.class);
        BlockReason problem = buf.readEnum(BlockReason.class);
        Component detail = buf.readNullable(FriendlyByteBuf::readComponent);
        int progress = buf.readVarInt() - 1;
        int duration = buf.readVarInt() - 1;
        return new MachineRef(dim, pos, name, kind, status, problem, detail, progress, duration);
    }
}
