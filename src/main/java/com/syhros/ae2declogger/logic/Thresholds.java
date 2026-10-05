package com.syhros.ae2declogger.logic;

/**
 * Timing limits for the stall detector, all in milliseconds.
 *
 * @param slowMs           no progress for this long marks a step SLOW when the recipe time is unknown
 * @param stallMs          no progress for this long marks a step STALLED when the recipe time is unknown
 * @param minStallMs       lower bound for the stall limit when the recipe time is known
 * @param recipeMultiplier a step with a known recipe time stalls after recipeTime * multiplier
 * @param blockedMs        a blocker has to persist this long before the step is reported BLOCKED
 */
public record Thresholds(long slowMs, long stallMs, long minStallMs, double recipeMultiplier, long blockedMs) {

    public static final Thresholds DEFAULT = new Thresholds(30_000, 120_000, 15_000, 6.0, 5_000);

    public Thresholds {
        if (slowMs < 0 || stallMs < 0 || minStallMs < 0 || blockedMs < 0) {
            throw new IllegalArgumentException("thresholds must not be negative");
        }
        if (recipeMultiplier < 1.0) {
            recipeMultiplier = 1.0;
        }
        if (stallMs < slowMs) {
            stallMs = slowMs;
        }
    }

    /** Builds thresholds from config values given in seconds. */
    public static Thresholds ofSeconds(int slow, int stall, int minStall, double recipeMultiplier, int blocked) {
        return new Thresholds(slow * 1000L, stall * 1000L, minStall * 1000L, recipeMultiplier, blocked * 1000L);
    }
}
