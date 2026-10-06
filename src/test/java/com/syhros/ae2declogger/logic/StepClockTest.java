package com.syhros.ae2declogger.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StepClockTest {

    /** Patterns are named after their output; each makes 2 per craft except "rod" (1). */
    private final StepClock<String, String> clock = new StepClock<>();
    private final Map<String, Long> out = new HashMap<>();

    private List<StepClock.Done<String>> look(Map<String, Long> remaining, long now) {
        return clock.observe(remaining, p -> p, p -> p.equals("rod") ? 1 : 2, k -> out.getOrDefault(k, 0L), now);
    }

    @Test
    void stepIsTimedFromFirstPushToLastReturn() {
        assertTrue(look(Map.of("plate", 4L), 0).isEmpty());
        // Nothing pushed yet (waiting for ingredients): not started.
        assertTrue(look(Map.of("plate", 4L), 10_000).isEmpty());
        out.put("plate", 2L);
        assertTrue(look(Map.of("plate", 3L), 20_000).isEmpty()); // first push between 10s and 20s
        out.put("plate", 0L);
        List<StepClock.Done<String>> done = look(Map.of(), 50_000);
        assertEquals(1, done.size());
        assertEquals("plate", done.get(0).key());
        assertEquals(8, done.get(0).amount(), "4 crafts x 2 per craft");
        assertEquals(40_000, done.get(0).durationMs(), "from the look before the first push");
        assertEquals(0, clock.size());
    }

    @Test
    void stepStillOutAtAMachineIsNotDone() {
        look(Map.of("rod", 2L), 0);
        out.put("rod", 1L);
        assertTrue(look(Map.of(), 5_000).isEmpty(), "pushed but still out at the machine");
        out.put("rod", 0L);
        List<StepClock.Done<String>> done = look(Map.of(), 9_000);
        assertEquals(1, done.size());
        assertEquals(2, done.get(0).amount());
        assertEquals(9_000, done.get(0).durationMs());
    }

    @Test
    void stepFinishedBetweenTwoLooksCountsFromThePreviousLook() {
        look(Map.of("plate", 1L, "rod", 3L), 0);
        look(Map.of("plate", 1L, "rod", 3L), 30_000);
        List<StepClock.Done<String>> done = look(Map.of("rod", 3L), 31_000);
        assertEquals(1, done.size());
        assertEquals("plate", done.get(0).key());
        assertEquals(1_000, done.get(0).durationMs());
    }

    @Test
    void completedJobClosesOpenStepsAndCancelledDropsThem() {
        look(Map.of("plate", 2L), 0);
        out.put("plate", 2L);
        look(Map.of("plate", 1L), 4_000);
        List<StepClock.Done<String>> done = clock.finish(true, 10_000);
        assertEquals(1, done.size());
        assertEquals(4, done.get(0).amount());
        assertEquals(10_000, done.get(0).durationMs());

        look(Map.of("rod", 5L), 20_000);
        assertTrue(clock.finish(false, 30_000).isEmpty());
        assertEquals(0, clock.size());
    }

    @Test
    void addedCraftsAreCounted() {
        look(Map.of("rod", 2L), 0);
        look(Map.of("rod", 5L), 1_000); // the job grew
        look(Map.of("rod", 1L), 2_000);
        List<StepClock.Done<String>> done = look(Map.of(), 3_000);
        assertEquals(5, done.get(0).amount());
    }
}
