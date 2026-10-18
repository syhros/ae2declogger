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

    // Classes rather than records: logic/ also compiles for Java 8 (the 1.7.10, 1.12.2 and 1.16.5 builds).
    public static final class Delta<K, V> {
        private final Map<K, V> changed;
        private final List<K> removed;

        public Delta(Map<K, V> changed, List<K> removed) {
            this.changed = changed;
            this.removed = removed;
        }

        public Map<K, V> changed() {
            return changed;
        }

        public List<K> removed() {
            return removed;
        }

        public boolean isEmpty() {
            return changed.isEmpty() && removed.isEmpty();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Delta)) {
                return false;
            }
            Delta<?, ?> o = (Delta<?, ?>) other;
            return java.util.Objects.equals(changed, o.changed)
                    && java.util.Objects.equals(removed, o.removed);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(changed, removed);
        }

        @Override
        public String toString() {
            return "Delta[changed=" + changed + ", removed=" + removed + "]";
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
