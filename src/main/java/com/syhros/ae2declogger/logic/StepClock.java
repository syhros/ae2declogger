package com.syhros.ae2declogger.logic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Times every step of one crafting job from repeated looks at its task list, so the History and Stats tabs can show how
 * long each intermediate item took, not only the item that was ordered.
 *
 * <p>
 * A step is one pattern of the job. It starts when its first craft is pushed (the number of crafts left drops, or its
 * output is out at a machine) and ends when nothing is left to push and nothing is out at a machine. A step that was
 * pushed and came back between two looks is counted from the previous look.
 *
 * @param <P> pattern identity
 * @param <K> item identity (an AE key in game, a string in tests)
 */
public final class StepClock<P, K> {

    /** A finished step: {@code amount} items of {@code key} made in {@code durationMs}. */
    // Classes rather than records: logic/ also compiles for Java 8 (the 1.7.10, 1.12.2 and 1.16.5 builds).
    public static final class Done<K> {
        private final K key;
        private final long amount;
        private final long durationMs;
        private final long finishedAtMs;

        public Done(K key, long amount, long durationMs, long finishedAtMs) {
            this.key = key;
            this.amount = amount;
            this.durationMs = durationMs;
            this.finishedAtMs = finishedAtMs;
        }

        public K key() {
            return key;
        }

        public long amount() {
            return amount;
        }

        public long durationMs() {
            return durationMs;
        }

        public long finishedAtMs() {
            return finishedAtMs;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Done)) {
                return false;
            }
            Done<?> o = (Done<?>) other;
            return java.util.Objects.equals(key, o.key)
                    && amount == o.amount
                    && durationMs == o.durationMs
                    && finishedAtMs == o.finishedAtMs;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(key, amount, durationMs, finishedAtMs);
        }

        @Override
        public String toString() {
            return "Done[key=" + key + ", amount=" + amount + ", durationMs=" + durationMs + ", finishedAtMs=" + finishedAtMs + "]";
        }
    }

    private static final class Step<K> {
        final K key;
        final long perCraft;
        final long firstSeenMs;
        long crafts;
        long lastRemaining;
        long startMs = -1;

        Step(K key, long perCraft, long firstSeenMs) {
            this.key = key;
            this.perCraft = Math.max(1, perCraft);
            this.firstSeenMs = firstSeenMs;
        }
    }

    private final Map<P, Step<K>> steps = new LinkedHashMap<>();
    private long lastLookMs = -1;

    /**
     * One look at the job.
     *
     * @param remaining   crafts each pattern still has to push; patterns with none left may be missing
     * @param outputOf    the item a pattern makes
     * @param perCraft    how many of that item one craft makes
     * @param outstanding how many of an item are out at machines right now
     * @return steps that finished since the last look
     */
    public List<Done<K>> observe(Map<P, Long> remaining, Function<P, K> outputOf, ToLongFunction<P> perCraft,
            ToLongFunction<K> outstanding, long nowMs) {
        for (Map.Entry<P, Long> e : remaining.entrySet()) {
            long left = Math.max(0, e.getValue());
            Step<K> step = steps.get(e.getKey());
            if (step == null) {
                K key = outputOf.apply(e.getKey());
                step = new Step<>(key, perCraft.applyAsLong(e.getKey()), nowMs);
                long out = Math.max(0, outstanding.applyAsLong(key));
                // Crafts already out at a machine when the job is first seen count too.
                step.crafts = left + (out + step.perCraft - 1) / step.perCraft;
                if (out > 0) {
                    step.startMs = nowMs;
                }
                step.lastRemaining = left;
                steps.put(e.getKey(), step);
                continue;
            }
            if (left > step.lastRemaining) {
                step.crafts += left - step.lastRemaining; // more crafts were added to the job
            }
            if (step.startMs < 0 && left < step.lastRemaining) {
                step.startMs = startOfChange(nowMs);
            }
            step.lastRemaining = left;
        }

        List<Done<K>> done = new ArrayList<>();
        Iterator<Map.Entry<P, Step<K>>> it = steps.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<P, Step<K>> e = it.next();
            Step<K> step = e.getValue();
            if (remaining.containsKey(e.getKey()) && remaining.get(e.getKey()) > 0) {
                if (step.startMs < 0 && outstanding.applyAsLong(step.key) > 0) {
                    step.startMs = startOfChange(nowMs);
                }
                continue;
            }
            step.lastRemaining = 0;
            if (outstanding.applyAsLong(step.key) > 0) {
                if (step.startMs < 0) {
                    step.startMs = startOfChange(nowMs);
                }
                continue;
            }
            long start = step.startMs >= 0 ? step.startMs : startOfChange(nowMs);
            done.add(new Done<>(step.key, step.crafts * step.perCraft, Math.max(0, nowMs - start), nowMs));
            it.remove();
        }
        lastLookMs = nowMs;
        return done;
    }

    /**
     * The job left its CPU. A completed job finished every step still open; a cancelled one finished none of them.
     */
    public List<Done<K>> finish(boolean completed, long nowMs) {
        List<Done<K>> done = new ArrayList<>();
        if (completed) {
            for (Step<K> step : steps.values()) {
                long start = step.startMs >= 0 ? step.startMs : step.firstSeenMs;
                done.add(new Done<>(step.key, step.crafts * step.perCraft, Math.max(0, nowMs - start), nowMs));
            }
        }
        steps.clear();
        return done;
    }

    /** Steps being timed. */
    public int size() {
        return steps.size();
    }

    /** Something changed between the previous look and now: count it from the previous look. */
    private long startOfChange(long nowMs) {
        return lastLookMs >= 0 ? lastLookMs : nowMs;
    }
}
