package com.syhros.ae2declogger.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Newest-first list that drops the oldest entry once it is full.
 */
public final class BoundedHistory<T> {

    private final Deque<T> entries = new ArrayDeque<>();
    private int capacity;

    public BoundedHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(T entry) {
        entries.addFirst(entry);
        trim();
    }

    public synchronized void setCapacity(int capacity) {
        this.capacity = Math.max(1, capacity);
        trim();
    }

    public synchronized int capacity() {
        return capacity;
    }

    public synchronized List<T> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void trim() {
        while (entries.size() > capacity) {
            entries.removeLast();
        }
    }
}
