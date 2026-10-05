package com.syhros.ae2declogger.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class StallClassifierTest {

    private static final Thresholds T = Thresholds.DEFAULT; // slow 30s, stall 120s, min 15s, x6, blocked 5s

    @Test
    void unknownRecipeTimeUsesConfiguredLimits() {
        assertEquals(120_000, StallClassifier.stallThresholdMs(-1, T));
        assertEquals(30_000, StallClassifier.slowThresholdMs(-1, T));
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyActive(29_999, -1, T));
        assertEquals(JobState.SLOW, StallClassifier.classifyActive(30_000, -1, T));
        assertEquals(JobState.SLOW, StallClassifier.classifyActive(119_999, -1, T));
        assertEquals(JobState.STALLED, StallClassifier.classifyActive(120_000, -1, T));
    }

    @Test
    void tenSecondRecipeStallsAfterAMinute() {
        // The prompt's example: a 10 s recipe that is still running after 2+ minutes is stalled.
        long recipe = 10_000;
        assertEquals(60_000, StallClassifier.stallThresholdMs(recipe, T));
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyActive(9_000, recipe, T));
        assertEquals(JobState.SLOW, StallClassifier.classifyActive(30_000, recipe, T));
        assertEquals(JobState.STALLED, StallClassifier.classifyActive(125_000, recipe, T));
    }

    @Test
    void shortRecipesStillGetTheMinimumStallTime() {
        // 1 s recipe * 6 = 6 s, below the 15 s floor.
        assertEquals(15_000, StallClassifier.stallThresholdMs(1_000, T));
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyActive(1_500, 1_000, T));
        assertEquals(JobState.STALLED, StallClassifier.classifyActive(15_000, 1_000, T));
    }

    @Test
    void longRecipesAreCappedAtTheStallLimit() {
        // 10 minute recipe would scale to an hour; capped at 120 s so a dead machine is still caught.
        assertEquals(120_000, StallClassifier.stallThresholdMs(600_000, T));
        assertTrue(StallClassifier.slowThresholdMs(600_000, T) <= StallClassifier.stallThresholdMs(600_000, T));
    }

    @Test
    void slowLimitNeverExceedsStallLimit() {
        Thresholds odd = new Thresholds(500_000, 100_000, 1_000, 2.0, 0);
        for (long recipe : new long[] { -1, 0, 50, 5_000, 1_000_000 }) {
            assertTrue(StallClassifier.slowThresholdMs(recipe, odd) <= StallClassifier.stallThresholdMs(recipe, odd),
                    "recipe " + recipe);
        }
    }

    @Test
    void pendingStepNeedsAProblemThatPersists() {
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyPending(BlockReason.NONE, 999_999, T));
        assertEquals(JobState.IN_PROGRESS,
                StallClassifier.classifyPending(BlockReason.WAITING_INGREDIENTS, 999_999, T));
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyPending(BlockReason.NO_PROVIDER, 4_999, T));
        assertEquals(JobState.BLOCKED, StallClassifier.classifyPending(BlockReason.NO_PROVIDER, 5_000, T));
    }

    @Test
    void jobTakesWorstStep() {
        assertEquals(JobState.IN_PROGRESS,
                StallClassifier.classifyJob(1_000, List.of(JobState.IN_PROGRESS, JobState.IN_PROGRESS), true, T));
        assertEquals(JobState.BLOCKED, StallClassifier.classifyJob(1_000,
                List.of(JobState.SLOW, JobState.BLOCKED, JobState.STALLED), true, T));
        // With step details, idle time alone does not stall a job (a long recipe can run for minutes).
        assertEquals(JobState.IN_PROGRESS,
                StallClassifier.classifyJob(600_000, List.of(JobState.IN_PROGRESS), true, T));
        // Finished states in the list are ignored.
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyJob(0, List.of(JobState.COMPLETE), true, T));
    }

    @Test
    void jobWithoutDetailsUsesIdleTime() {
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyJob(1_000, List.of(), false, T));
        assertEquals(JobState.SLOW, StallClassifier.classifyJob(40_000, List.of(), false, T));
        assertEquals(JobState.STALLED, StallClassifier.classifyJob(180_000, List.of(), false, T));
    }

    @Test
    void workingMachineIsNeverStalled() {
        // 5 minute recipe, 10 minutes without a result: slow, not stalled.
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyWorking(60_000, 300_000, T));
        assertEquals(JobState.SLOW, StallClassifier.classifyWorking(1_800_001, 300_000, T));
        // Unknown recipe time falls back to the stall limit, still only SLOW.
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyWorking(60_000, -1, T));
        assertEquals(JobState.SLOW, StallClassifier.classifyWorking(120_000, -1, T));
        // Very short recipes use the slow limit as the floor.
        assertEquals(JobState.IN_PROGRESS, StallClassifier.classifyWorking(20_000, 1_000, T));
    }

    @Test
    void hardMachineFaultsBlockSoftOnesDoNot() {
        assertEquals(JobState.BLOCKED,
                StallClassifier.classifyMachineFault(JobState.SLOW, BlockReason.MACHINE_NO_POWER));
        assertEquals(JobState.BLOCKED,
                StallClassifier.classifyMachineFault(JobState.STALLED, BlockReason.MACHINE_NOT_FORMED));
        assertEquals(JobState.BLOCKED,
                StallClassifier.classifyMachineFault(JobState.SLOW, BlockReason.MACHINE_OUTPUT_FULL));
        // Soft machine states keep the time-based state.
        assertEquals(JobState.SLOW, StallClassifier.classifyMachineFault(JobState.SLOW, BlockReason.MACHINE_WAITING));
        assertEquals(JobState.STALLED,
                StallClassifier.classifyMachineFault(JobState.STALLED, BlockReason.MACHINE_NO_RECIPE));
        // A step that is still within its normal time is left alone.
        assertEquals(JobState.IN_PROGRESS,
                StallClassifier.classifyMachineFault(JobState.IN_PROGRESS, BlockReason.MACHINE_NO_POWER));
    }

    @Test
    void thresholdsRejectNegativeAndFixInvertedValues() {
        Thresholds fixed = new Thresholds(60_000, 10_000, 0, 0.5, 0);
        assertEquals(60_000, fixed.stallMs());
        assertEquals(1.0, fixed.recipeMultiplier());
        try {
            new Thresholds(-1, 0, 0, 1, 0);
            throw new AssertionError("expected exception");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
