package com.syhros.ae2declogger.compat;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.data.MachineStatus;
import com.syhros.ae2declogger.logic.BlockReason;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * What a mod integration (GregTech, Mekanism, ...) read from a machine. Uses only this mod's types, so callers never
 * touch another mod's classes.
 *
 * @param source        mod id of the integration that read it
 * @param controllerPos the multiblock controller when the block was a part (hatch, bus, core...), else null
 * @param progress      recipe progress in ticks, -1 if unknown
 * @param duration      recipe length in ticks, -1 if unknown
 * @param energyUnit    unit of {@code energyStored} for energy the generic FE reader cannot see (EU), else empty
 */
public record MachineInfo(String source, Component name, MachineStatus status, BlockReason problem,
        @Nullable Component detail, int progress, int duration, @Nullable BlockPos controllerPos, long energyStored,
        long energyCapacity, String energyUnit, String tier, List<Component> errors) {

    public MachineInfo {
        errors = List.copyOf(errors);
    }

    public static Builder builder(String source, Component name) {
        return new Builder(source, name);
    }

    /** Readers fill this in steps; the first problem set wins, so readers check the most basic cause first. */
    public static final class Builder {
        private final String source;
        private Component name;
        private MachineStatus status = MachineStatus.UNKNOWN;
        private BlockReason problem = BlockReason.NONE;
        @Nullable
        private Component detail;
        private int progress = -1;
        private int duration = -1;
        @Nullable
        private BlockPos controllerPos;
        private long energyStored;
        private long energyCapacity;
        private String energyUnit = "";
        private String tier = "";
        private final List<Component> errors = new ArrayList<>();

        private Builder(String source, Component name) {
            this.source = source;
            this.name = name;
        }

        public Builder name(Component name) {
            this.name = name;
            return this;
        }

        public Builder status(MachineStatus status) {
            this.status = status;
            return this;
        }

        /** Sets the problem (and its text) unless an earlier, more basic one is already set. */
        public Builder problem(BlockReason problem, @Nullable Component detail) {
            if (this.problem == BlockReason.NONE) {
                this.problem = problem;
                this.detail = detail;
            }
            if (detail != null) {
                errors.add(detail);
            }
            return this;
        }

        public boolean hasProblem() {
            return problem != BlockReason.NONE;
        }

        /** Text shown when there is no problem (progress, for example). */
        public Builder note(Component detail) {
            if (this.detail == null) {
                this.detail = detail;
            }
            return this;
        }

        public Builder progress(int progress, int duration) {
            this.progress = progress;
            this.duration = duration;
            return this;
        }

        public Builder controller(@Nullable BlockPos pos) {
            this.controllerPos = pos;
            return this;
        }

        public Builder energy(long stored, long capacity, String unit) {
            this.energyStored = stored;
            this.energyCapacity = capacity;
            this.energyUnit = unit;
            return this;
        }

        public Builder tier(String tier) {
            this.tier = tier;
            return this;
        }

        public MachineInfo build() {
            MachineStatus s = status;
            if (problem != BlockReason.NONE && s == MachineStatus.UNKNOWN) {
                s = problem == BlockReason.MACHINE_NOT_FORMED ? MachineStatus.NOT_FORMED
                        : problem == BlockReason.MACHINE_DISABLED ? MachineStatus.SUSPENDED : MachineStatus.WAITING;
            }
            return new MachineInfo(source, name, s, problem, detail, progress, duration, controllerPos, energyStored,
                    energyCapacity, energyUnit, tier, errors);
        }
    }
}
