package com.syhros.ae2declogger.logic;

import java.util.Collection;

/**
 * Turns "how long since anything moved" into a {@link JobState}. Pure logic so it can be unit tested.
 */
public final class StallClassifier {

    private StallClassifier() {
    }

    /**
     * Stall limit for a step. With a known recipe time the limit scales with it (a 10 s recipe that has not
     * finished after a minute is stuck), clamped between {@code minStallMs} and {@code stallMs}.
     *
     * @param recipeMs recipe duration in ms, or a value &lt;= 0 when unknown
     */
    public static long stallThresholdMs(long recipeMs, Thresholds t) {
        if (recipeMs <= 0) {
            return t.stallMs();
        }
        long scaled = (long) Math.ceil(recipeMs * t.recipeMultiplier());
        return clamp(scaled, t.minStallMs(), Math.max(t.minStallMs(), t.stallMs()));
    }

    /** Slow limit: half the stall limit, never above the configured slow time. */
    public static long slowThresholdMs(long recipeMs, Thresholds t) {
        long stall = stallThresholdMs(recipeMs, t);
        if (recipeMs <= 0) {
            return Math.min(t.slowMs(), stall);
        }
        return Math.min(Math.min(t.slowMs(), stall), Math.max(recipeMs * 2, stall / 2));
    }

    /** State of a step that has items out at a machine and is waiting for the result. */
    public static JobState classifyActive(long idleMs, long recipeMs, Thresholds t) {
        if (idleMs >= stallThresholdMs(recipeMs, t)) {
            return JobState.STALLED;
        }
        if (idleMs >= slowThresholdMs(recipeMs, t)) {
            return JobState.SLOW;
        }
        return JobState.IN_PROGRESS;
    }

    /**
     * State of a step that still has to be started.
     *
     * @param reason       what currently stops the push, {@link BlockReason#NONE} if nothing
     * @param blockedForMs how long that reason has been present
     */
    public static JobState classifyPending(BlockReason reason, long blockedForMs, Thresholds t) {
        if (reason == null || !reason.isProblem()) {
            return JobState.IN_PROGRESS;
        }
        if (blockedForMs >= t.blockedMs()) {
            return JobState.BLOCKED;
        }
        return JobState.IN_PROGRESS;
    }

    /**
     * Combines step states into the job state. With step details the worst step decides; each step already carries
     * its own timing. Without details (an unknown CPU type) the time since the job last changed decides.
     */
    public static JobState classifyJob(long jobIdleMs, Collection<JobState> stepStates, boolean hasStepDetails,
            Thresholds t) {
        if (!hasStepDetails) {
            return classifyActive(jobIdleMs, -1, t);
        }
        JobState worst = JobState.IN_PROGRESS;
        for (JobState s : stepStates) {
            if (s != null && !s.isFinished()) {
                worst = JobState.worst(worst, s);
            }
        }
        return worst;
    }

    /**
     * A step whose machine reports it is working is moving, just maybe slowly: never STALLED. It turns SLOW once it
     * has been running longer than the usual stall limit for its recipe time.
     */
    public static JobState classifyWorking(long idleMs, long recipeMs, Thresholds t) {
        long limit = recipeMs > 0 ? Math.max((long) (recipeMs * t.recipeMultiplier()), t.slowMs()) : t.stallMs();
        return idleMs >= limit ? JobState.SLOW : JobState.IN_PROGRESS;
    }

    /**
     * A step that is already slow or stalled and whose machine reports a hard fault is blocked: waiting longer will
     * not help.
     */
    public static JobState classifyMachineFault(JobState state, BlockReason machineReason) {
        if (state == JobState.IN_PROGRESS || machineReason == null || !machineReason.isHardMachineFault()) {
            return state;
        }
        return JobState.worst(state, JobState.BLOCKED);
    }

    static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
