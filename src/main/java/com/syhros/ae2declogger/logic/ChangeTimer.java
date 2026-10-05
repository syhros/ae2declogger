package com.syhros.ae2declogger.logic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Tracks when a watched number last changed, per key. Used for "items in flight have not come back for N seconds"
 * and "this blocker has been there for N seconds".
 */
public final class ChangeTimer<K> {

    private static final class Slot {
        long value;
        long changedAt;
        long seenAt;
    }

    private final Map<K, Slot> slots = new HashMap<>();

    /**
     * Records the current value and returns the time it last changed.
     *
     * @param initialAgeMs how old to assume the value is when the key is first seen (lets the first look at a job
     *                     that has been sitting still for an hour already report it as stuck)
     */
    public long observe(K key, long value, long nowMs, long initialAgeMs) {
        Slot slot = slots.get(key);
        if (slot == null) {
            slot = new Slot();
            slot.value = value;
            slot.changedAt = nowMs - Math.max(0, initialAgeMs);
            slots.put(key, slot);
        } else if (slot.value != value) {
            slot.value = value;
            slot.changedAt = nowMs;
        }
        slot.seenAt = nowMs;
        return slot.changedAt;
    }

    /** ms since the value last changed, or 0 when the key has not been seen. */
    public long ageMs(K key, long nowMs) {
        Slot slot = slots.get(key);
        return slot == null ? 0 : Math.max(0, nowMs - slot.changedAt);
    }

    public boolean contains(K key) {
        return slots.containsKey(key);
    }

    public void remove(K key) {
        slots.remove(key);
    }

    /** Drops keys that were not observed since {@code cutoffMs}. */
    public void pruneNotSeenSince(long cutoffMs) {
        Iterator<Slot> it = slots.values().iterator();
        while (it.hasNext()) {
            if (it.next().seenAt < cutoffMs) {
                it.remove();
            }
        }
    }

    /** Drops every key not in {@code keep}. */
    public void retainOnly(Set<K> keep) {
        slots.keySet().retainAll(keep);
    }

    public int size() {
        return slots.size();
    }

    public void clear() {
        slots.clear();
    }
}
