package com.syhros.ae2declogger.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers what was last sent to one client and works out which entries changed. Used so the server only sends
 * job rows that actually changed instead of the full list every update.
 */
public final class DeltaTracker<K, V> {

    private final Map<K, V> lastSent = new HashMap<>();

    public record Delta<K, V>(Map<K, V> changed, List<K> removed) {
        public boolean isEmpty() {
            return changed.isEmpty() && removed.isEmpty();
        }
    }

    /**
     * Compares {@code current} with what was sent before and records {@code current} as sent.
     *
     * @param current every entry the client should now have, in display order
     */
    public Delta<K, V> update(Map<K, V> current) {
        Map<K, V> changed = new LinkedHashMap<>();
        for (Map.Entry<K, V> e : current.entrySet()) {
            V previous = lastSent.get(e.getKey());
            if (previous == null || !Objects.equals(previous, e.getValue())) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        List<K> removed = new ArrayList<>();
        for (K key : lastSent.keySet()) {
            if (!current.containsKey(key)) {
                removed.add(key);
            }
        }
        lastSent.clear();
        lastSent.putAll(current);
        return new Delta<>(changed, removed);
    }

    /** Forget everything so the next {@link #update} sends the full set. */
    public void reset() {
        lastSent.clear();
    }

    public int size() {
        return lastSent.size();
    }
}
