package com.syhros.ae2declogger.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-item statistics over finished jobs and steps: how often it was crafted, average time, success rate.
 *
 * @param <K> item identity (an AE key in game, a string in tests)
 */
public final class JobStats<K> {

    /**
     * One history entry. {@code step} entries are one step of a bigger job (an intermediate item), timed from its first
     * craft to its last; the others are whole jobs, timed from the order to the end.
     */
    // Classes rather than records: logic/ also compiles for Java 8 (the 1.7.10, 1.12.2 and 1.16.5 builds).
    public static final class Entry<K> {
        private final K key;
        private final long amount;
        private final long durationMs;
        private final boolean completed;
        private final boolean step;

        public Entry(K key, long amount, long durationMs, boolean completed, boolean step) {
            this.key = key;
            this.amount = amount;
            this.durationMs = durationMs;
            this.completed = completed;
            this.step = step;
        }

        public Entry(K key, long amount, long durationMs, boolean completed) {
            this(key, amount, durationMs, completed, false);
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

        public boolean completed() {
            return completed;
        }

        public boolean step() {
            return step;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry<?> o = (Entry<?>) other;
            return java.util.Objects.equals(key, o.key)
                    && amount == o.amount
                    && durationMs == o.durationMs
                    && completed == o.completed
                    && step == o.step;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(key, amount, durationMs, completed, step);
        }

        @Override
        public String toString() {
            return "Entry[key=" + key + ", amount=" + amount + ", durationMs=" + durationMs + ", completed=" + completed + ", step=" + step + "]";
        }
    }

    /**
     * Statistics for one item. The first fields cover jobs that ordered it; {@code steps}, {@code stepAvgMs} and
     * {@code stepMsPerUnit} cover the times it was made as a step of another job (-1 when there are none).
     */
    public static final class Row<K> {
        private final K key;
        private final int jobs;
        private final int completed;
        private final int cancelled;
        private final long totalAmount;
        private final long avgDurationMs;
        private final long bestDurationMs;
        private final double msPerUnit;
        private final int steps;
        private final long stepAvgMs;
        private final double stepMsPerUnit;

        public Row(K key, int jobs, int completed, int cancelled, long totalAmount, long avgDurationMs, long bestDurationMs, double msPerUnit, int steps, long stepAvgMs, double stepMsPerUnit) {
            this.key = key;
            this.jobs = jobs;
            this.completed = completed;
            this.cancelled = cancelled;
            this.totalAmount = totalAmount;
            this.avgDurationMs = avgDurationMs;
            this.bestDurationMs = bestDurationMs;
            this.msPerUnit = msPerUnit;
            this.steps = steps;
            this.stepAvgMs = stepAvgMs;
            this.stepMsPerUnit = stepMsPerUnit;
        }

        public K key() {
            return key;
        }

        public int jobs() {
            return jobs;
        }

        public int completed() {
            return completed;
        }

        public int cancelled() {
            return cancelled;
        }

        public long totalAmount() {
            return totalAmount;
        }

        public long avgDurationMs() {
            return avgDurationMs;
        }

        public long bestDurationMs() {
            return bestDurationMs;
        }

        public double msPerUnit() {
            return msPerUnit;
        }

        public int steps() {
            return steps;
        }

        public long stepAvgMs() {
            return stepAvgMs;
        }

        public double stepMsPerUnit() {
            return stepMsPerUnit;
        }

        /** Completed / all, 0..1. */
        public double successRate() {
            return jobs == 0 ? 0 : (double) completed / jobs;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Row)) {
                return false;
            }
            Row<?> o = (Row<?>) other;
            return java.util.Objects.equals(key, o.key)
                    && jobs == o.jobs
                    && completed == o.completed
                    && cancelled == o.cancelled
                    && totalAmount == o.totalAmount
                    && avgDurationMs == o.avgDurationMs
                    && bestDurationMs == o.bestDurationMs
                    && Double.compare(msPerUnit, o.msPerUnit) == 0
                    && steps == o.steps
                    && stepAvgMs == o.stepAvgMs
                    && Double.compare(stepMsPerUnit, o.stepMsPerUnit) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(key, jobs, completed, cancelled, totalAmount, avgDurationMs, bestDurationMs, msPerUnit, steps, stepAvgMs, stepMsPerUnit);
        }

        @Override
        public String toString() {
            return "Row[key=" + key + ", jobs=" + jobs + ", completed=" + completed + ", cancelled=" + cancelled + ", totalAmount=" + totalAmount + ", avgDurationMs=" + avgDurationMs + ", bestDurationMs=" + bestDurationMs + ", msPerUnit=" + msPerUnit + ", steps=" + steps + ", stepAvgMs=" + stepAvgMs + ", stepMsPerUnit=" + stepMsPerUnit + "]";
        }
    }

    private JobStats() {
    }

    /**
     * Groups entries by key. Average and best times only count completed jobs. Rows are sorted by how often the item
     * was made (jobs and steps).
     */
    public static <K> List<Row<K>> compute(Iterable<Entry<K>> entries) {
        Map<K, long[]> acc = new LinkedHashMap<>();
        // acc: jobs, completed, cancelled, totalAmount, completedDuration, best, completedAmount,
        //      steps, stepDuration, stepAmount
        for (Entry<K> e : entries) {
            long[] a = acc.computeIfAbsent(e.key(), k -> new long[] { 0, 0, 0, 0, 0, Long.MAX_VALUE, 0, 0, 0, 0 });
            a[3] += Math.max(0, e.amount());
            if (e.step()) {
                a[7]++;
                a[8] += Math.max(0, e.durationMs());
                a[9] += Math.max(0, e.amount());
                continue;
            }
            a[0]++;
            if (e.completed()) {
                a[1]++;
                a[4] += Math.max(0, e.durationMs());
                a[5] = Math.min(a[5], Math.max(0, e.durationMs()));
                a[6] += Math.max(0, e.amount());
            } else {
                a[2]++;
            }
        }
        List<Row<K>> rows = new ArrayList<>();
        for (Map.Entry<K, long[]> e : acc.entrySet()) {
            long[] a = e.getValue();
            long avg = a[1] == 0 ? -1 : a[4] / a[1];
            long best = a[5] == Long.MAX_VALUE ? -1 : a[5];
            double perUnit = a[6] == 0 ? -1 : (double) a[4] / a[6];
            long stepAvg = a[7] == 0 ? -1 : a[8] / a[7];
            double stepPerUnit = a[9] == 0 ? -1 : (double) a[8] / a[9];
            rows.add(new Row<>(e.getKey(), (int) a[0], (int) a[1], (int) a[2], a[3], avg, best, perUnit, (int) a[7],
                    stepAvg, stepPerUnit));
        }
        rows.sort(Comparator.<Row<K>>comparingInt(r -> r.jobs() + r.steps()).reversed());
        return rows;
    }

    /**
     * Expected duration for a new job of {@code amount} units based on past completed jobs for the same key (steps of
     * other jobs don't count: they leave out the chain below them).
     *
     * @return expected ms, or -1 when there is no history
     */
    public static <K> long expectedMs(Iterable<Entry<K>> entries, K key, long amount) {
        long totalMs = 0;
        long totalAmount = 0;
        for (Entry<K> e : entries) {
            if (e.completed() && !e.step() && e.key().equals(key) && e.amount() > 0) {
                totalMs += e.durationMs();
                totalAmount += e.amount();
            }
        }
        if (totalAmount == 0 || amount <= 0) {
            return -1;
        }
        return Math.round((double) totalMs / totalAmount * amount);
    }
}
