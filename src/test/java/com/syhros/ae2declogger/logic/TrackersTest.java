package com.syhros.ae2declogger.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TrackersTest {

    @Test
    void deltaSendsOnlyChangesAndRemovals() {
        DeltaTracker<Long, String> tracker = new DeltaTracker<>();
        Map<Long, String> first = new LinkedHashMap<>();
        first.put(1L, "a");
        first.put(2L, "b");
        var d1 = tracker.update(first);
        assertEquals(2, d1.changed().size());
        assertTrue(d1.removed().isEmpty());

        var d2 = tracker.update(first);
        assertTrue(d2.isEmpty(), "unchanged data must produce an empty delta");

        Map<Long, String> second = new LinkedHashMap<>();
        second.put(2L, "b2");
        second.put(3L, "c");
        var d3 = tracker.update(second);
        assertEquals(Set.of(2L, 3L), d3.changed().keySet());
        assertEquals(List.of(1L), d3.removed());

        tracker.reset();
        assertEquals(2, tracker.update(second).changed().size());
    }

    @Test
    void changeTimerReportsAgeSinceLastChange() {
        ChangeTimer<String> timer = new ChangeTimer<>();
        // observe() returns the timestamp of the last change.
        assertEquals(1_000, timer.observe("x", 5, 1_000, 0));
        assertEquals(1_000, timer.observe("x", 5, 4_000, 0));
        assertEquals(3_000, timer.ageMs("x", 4_000));
        assertEquals(4_500, timer.observe("x", 6, 4_500, 0));
        assertEquals(0, timer.ageMs("x", 4_500));
        assertEquals(0, timer.ageMs("missing", 4_500));
    }

    @Test
    void changeTimerUsesInitialAgeOnFirstSight() {
        ChangeTimer<String> timer = new ChangeTimer<>();
        timer.observe("job", 1, 100_000, 60_000);
        assertEquals(60_000, timer.ageMs("job", 100_000));
        // The initial age is ignored for keys already known.
        timer.observe("job", 1, 101_000, 999_999);
        assertEquals(61_000, timer.ageMs("job", 101_000));
    }

    @Test
    void changeTimerPrunes() {
        ChangeTimer<String> timer = new ChangeTimer<>();
        timer.observe("old", 1, 1_000, 0);
        timer.observe("new", 1, 5_000, 0);
        timer.pruneNotSeenSince(2_000);
        assertFalse(timer.contains("old"));
        assertTrue(timer.contains("new"));
        timer.retainOnly(Set.of());
        assertEquals(0, timer.size());
    }

    @Test
    void alertFiresOncePerBreakdown() {
        AlertTracker<Long> alerts = new AlertTracker<>();
        assertTrue(alerts.update(Map.of(1L, JobState.IN_PROGRESS)).isEmpty());
        assertEquals(List.of(1L), alerts.update(Map.of(1L, JobState.STALLED)));
        assertTrue(alerts.update(Map.of(1L, JobState.STALLED)).isEmpty());
        // STALLED -> BLOCKED is still the same breakdown.
        assertTrue(alerts.update(Map.of(1L, JobState.BLOCKED)).isEmpty());
        assertTrue(alerts.update(Map.of(1L, JobState.IN_PROGRESS)).isEmpty());
        assertEquals(List.of(1L), alerts.update(Map.of(1L, JobState.BLOCKED)));
    }

    @Test
    void alertForJobAlreadyStuckWhenFirstSeen() {
        AlertTracker<Long> alerts = new AlertTracker<>();
        assertEquals(List.of(7L), alerts.update(Map.of(7L, JobState.BLOCKED)));
    }

    @Test
    void historyKeepsNewestFirstAndTrims() {
        BoundedHistory<Integer> history = new BoundedHistory<>(3);
        for (int i = 1; i <= 5; i++) {
            history.add(i);
        }
        assertEquals(List.of(5, 4, 3), history.snapshot());
        history.setCapacity(2);
        assertEquals(List.of(5, 4), history.snapshot());
        history.setCapacity(0);
        assertEquals(1, history.capacity());
    }

    @Test
    void statsGroupAndAverageCompletedOnly() {
        var entries = List.of(
                new JobStats.Entry<>("steel", 64, 60_000, true),
                new JobStats.Entry<>("steel", 64, 120_000, true),
                new JobStats.Entry<>("steel", 64, 5_000, false),
                new JobStats.Entry<>("wire", 8, 10_000, true));
        var rows = JobStats.compute(entries);
        assertEquals("steel", rows.get(0).key());
        var steel = rows.get(0);
        assertEquals(3, steel.jobs());
        assertEquals(2, steel.completed());
        assertEquals(1, steel.cancelled());
        assertEquals(90_000, steel.avgDurationMs());
        assertEquals(60_000, steel.bestDurationMs());
        assertEquals(2.0 / 3.0, steel.successRate(), 1e-9);
        assertEquals(180_000.0 / 128, steel.msPerUnit(), 1e-9);

        assertEquals(90_000, JobStats.expectedMs(entries, "steel", 64));
        assertEquals(-1, JobStats.expectedMs(entries, "gold", 64));
        assertEquals(-1, JobStats.expectedMs(entries, "steel", 0));
    }

    @Test
    void statsForOnlyCancelledJobs() {
        var rows = JobStats.compute(List.of(new JobStats.Entry<>("x", 1, 100, false)));
        assertEquals(-1, rows.get(0).avgDurationMs());
        assertEquals(-1, rows.get(0).bestDurationMs());
        assertEquals(0.0, rows.get(0).successRate());
    }

    @Test
    void enumsLookUpSafely() {
        assertEquals(JobState.IN_PROGRESS, JobState.byOrdinal(-1));
        assertEquals(JobState.BLOCKED, JobState.byOrdinal(JobState.BLOCKED.ordinal()));
        assertEquals(BlockReason.UNKNOWN, BlockReason.byOrdinal(999));
        assertEquals(BlockReason.NO_PROVIDER,
                BlockReason.stronger(BlockReason.WAITING_INGREDIENTS, BlockReason.NO_PROVIDER));
        assertEquals(BlockReason.MACHINE_NO_POWER,
                BlockReason.stronger(BlockReason.MACHINE_NO_POWER, BlockReason.OUTPUT_NOT_RETURNING));
        assertEquals(BlockReason.NONE, BlockReason.stronger(null, null));
        assertEquals(JobState.BLOCKED, JobState.worst(JobState.STALLED, JobState.BLOCKED));
        assertEquals(JobState.SLOW, JobState.worst(null, JobState.SLOW));
        assertTrue(BlockReason.MACHINE_OUTPUT_FULL.isMachineReason());
        assertFalse(BlockReason.NO_PROVIDER.isMachineReason());
    }
}
