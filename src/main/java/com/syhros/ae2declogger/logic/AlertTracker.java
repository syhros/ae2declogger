package com.syhros.ae2declogger.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides when to raise an alert: once when a job turns STALLED or BLOCKED, and again only after it has recovered
 * and broken down a second time. Keeps alerts from repeating every update.
 */
public final class AlertTracker<K> {

    private final Map<K, JobState> lastStates = new HashMap<>();

    /**
     * @param current state of every job that still exists
     * @return keys whose job just became a problem
     */
    public List<K> update(Map<K, JobState> current) {
        List<K> alerts = new ArrayList<>();
        for (Map.Entry<K, JobState> e : current.entrySet()) {
            JobState before = lastStates.get(e.getKey());
            JobState now = e.getValue();
            boolean wasProblem = before != null && before.isProblem();
            if (now != null && now.isProblem() && !wasProblem) {
                alerts.add(e.getKey());
            }
        }
        lastStates.clear();
        lastStates.putAll(current);
        return alerts;
    }

    public void reset() {
        lastStates.clear();
    }
}
