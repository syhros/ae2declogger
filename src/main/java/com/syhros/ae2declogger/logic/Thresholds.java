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
// A class rather than a record: logic/ also compiles for Java 8 (the 1.7.10, 1.12.2 and 1.16.5 builds).
public final class Thresholds {

    public static final Thresholds DEFAULT = new Thresholds(30_000, 120_000, 15_000, 6.0, 5_000);

    private final long slowMs;
    private final long stallMs;
    private final long minStallMs;
    private final double recipeMultiplier;
    private final long blockedMs;

    public Thresholds(long slowMs, long stallMs, long minStallMs, double recipeMultiplier, long blockedMs) {
        if (slowMs < 0 || stallMs < 0 || minStallMs < 0 || blockedMs < 0) {
            throw new IllegalArgumentException("thresholds must not be negative");
        }
        this.slowMs = slowMs;
        this.stallMs = Math.max(stallMs, slowMs);
        this.minStallMs = minStallMs;
        this.recipeMultiplier = Math.max(1.0, recipeMultiplier);
        this.blockedMs = blockedMs;
    }

    public long slowMs() {
        return slowMs;
    }

    public long stallMs() {
        return stallMs;
    }

    public long minStallMs() {
        return minStallMs;
    }

    public double recipeMultiplier() {
        return recipeMultiplier;
    }

    public long blockedMs() {
        return blockedMs;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Thresholds)) {
            return false;
        }
        Thresholds o = (Thresholds) other;
        return slowMs == o.slowMs && stallMs == o.stallMs && minStallMs == o.minStallMs
                && Double.compare(recipeMultiplier, o.recipeMultiplier) == 0 && blockedMs == o.blockedMs;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(slowMs, stallMs, minStallMs, recipeMultiplier, blockedMs);
    }

    @Override
    public String toString() {
        return "Thresholds[slowMs=" + slowMs + ", stallMs=" + stallMs + ", minStallMs=" + minStallMs
                + ", recipeMultiplier=" + recipeMultiplier + ", blockedMs=" + blockedMs + "]";
    }

    /** Builds thresholds from config values given in seconds. */
    public static Thresholds ofSeconds(int slow, int stall, int minStall, double recipeMultiplier, int blocked) {
        return new Thresholds(slow * 1000L, stall * 1000L, minStall * 1000L, recipeMultiplier, blocked * 1000L);
    }
}
