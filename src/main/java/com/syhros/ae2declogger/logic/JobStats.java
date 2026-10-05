package com.syhros.ae2declogger.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-item statistics over finished jobs: how often it was crafted, average time, success rate.
 *
 * @param <K> item identity (an AE key in game, a string in tests)
 */
public final class JobStats<K> {

    public record Entry<K>(K key, long amount, long durationMs, boolean completed) {
    }

    public record Row<K>(K key, int jobs, int completed, int cancelled, long totalAmount, long avgDurationMs,
            long bestDurationMs, double msPerUnit) {

        /** Completed / all, 0..1. */
        public double successRate() {
            return jobs == 0 ? 0 : (double) completed / jobs;
        }
    }

    private JobStats() {
    }

    /** Groups entries by key. Average and best times only count completed jobs. Rows are sorted by job count. */
    public static <K> List<Row<K>> compute(Iterable<Entry<K>> entries) {
        Map<K, long[]> acc = new LinkedHashMap<>();
        // acc: jobs, completed, cancelled, totalAmount, completedDuration, best, completedAmount
        for (Entry<K> e : entries) {
            long[] a = acc.computeIfAbsent(e.key(), k -> new long[] { 0, 0, 0, 0, 0, Long.MAX_VALUE, 0 });
            a[0]++;
            a[3] += Math.max(0, e.amount());
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
            rows.add(new Row<>(e.getKey(), (int) a[0], (int) a[1], (int) a[2], a[3], avg, best, perUnit));
        }
        rows.sort(Comparator.<Row<K>>comparingInt(Row::jobs).reversed());
        return rows;
    }

    /**
     * Expected duration for a new job of {@code amount} units based on past completed jobs for the same key.
     *
     * @return expected ms, or -1 when there is no history
     */
    public static <K> long expectedMs(Iterable<Entry<K>> entries, K key, long amount) {
        long totalMs = 0;
        long totalAmount = 0;
        for (Entry<K> e : entries) {
            if (e.completed() && e.key().equals(key) && e.amount() > 0) {
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
