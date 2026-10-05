package com.syhros.ae2declogger.server;

import java.util.List;
import java.util.Set;

import net.minecraft.core.GlobalPos;

import com.syhros.ae2declogger.data.JobView;

/**
 * Result of one look at a network.
 *
 * @param machines every block referenced by the job rows; the machine viewer only opens these (or blocks next to the
 *                 player)
 */
public record GridAnalysis(List<JobView> jobs, int cpus, int busyCpus, double storedPower, double maxPower,
        boolean powered, Set<GlobalPos> machines, long builtAtMs) {

    public GridAnalysis {
        jobs = List.copyOf(jobs);
        machines = Set.copyOf(machines);
    }
}
