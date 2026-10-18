package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.LPos;

/** Result of one look at a network. */
public final class GridAnalysis {

    public final List<JobView> jobs;
    public final int cpus;
    public final int busyCpus;
    public final double storedPower;
    public final double maxPower;
    public final boolean powered;
    /** Every block referenced by the job rows; the machine viewer only opens these (or for operators). */
    public final Set<LPos> machines;
    public final long builtAtMs;

    public GridAnalysis(List<JobView> jobs, int cpus, int busyCpus, double storedPower, double maxPower,
            boolean powered, Set<LPos> machines, long builtAtMs) {
        this.jobs = Collections.unmodifiableList(new ArrayList<>(jobs));
        this.cpus = cpus;
        this.busyCpus = busyCpus;
        this.storedPower = storedPower;
        this.maxPower = maxPower;
        this.powered = powered;
        this.machines = Collections.unmodifiableSet(new HashSet<>(machines));
        this.builtAtMs = builtAtMs;
    }
}
