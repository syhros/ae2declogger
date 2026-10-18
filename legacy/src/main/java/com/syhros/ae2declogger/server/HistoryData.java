package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.net.Buf;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;

/**
 * History and stats saved with the world, so they survive leaving the world or restarting the server. Each version
 * keeps this in its own world saved-data class ({@code Plat.historyData()}) as one byte array in the encoding below.
 *
 * <p>
 * AE2 builds a new network object whenever a network changes or reloads, so a network is recognised by the blocks it
 * is made of: the positions of its ME controllers and crafting CPU blocks ("anchors"). A network that still contains
 * any of a saved record's anchors gets that record back.
 */
public final class HistoryData {

    private static final int FORMAT = 1;
    private static final int MAX_ANCHORS = 32;

    /** Called whenever something changed, so the world saves it. */
    private final Runnable dirty;
    private final List<Store> stores = new ArrayList<>();

    /** The saved history of one network, oldest entry first. */
    public final class Store {
        final List<LPos> anchors = new ArrayList<>();
        final List<HistoryEntry> entries = new ArrayList<>();

        public List<HistoryEntry> entries() {
            return entries;
        }

        void add(HistoryEntry entry, int capacity) {
            entries.add(entry);
            trim(capacity);
            dirty.run();
        }

        void clear() {
            entries.clear();
            dirty.run();
        }

        void trim(int capacity) {
            int over = entries.size() - Math.max(1, capacity);
            if (over > 0) {
                entries.subList(0, over).clear();
                dirty.run();
            }
        }
    }

    public HistoryData(Runnable dirty) {
        this.dirty = dirty;
    }

    /** The saved record of this network, or a new one. */
    public Store storeFor(IGrid grid) {
        Set<LPos> positions = new HashSet<>();
        List<LPos> anchors = new ArrayList<>();
        LPos any = null;
        try {
            for (IGridNode node : grid.getNodes()) {
                LPos pos = Ae.posOf(node);
                if (pos == null) {
                    continue;
                }
                positions.add(pos);
                if (any == null) {
                    any = pos;
                }
                if (Ae.isAnchor(node) && anchors.size() < MAX_ANCHORS) {
                    anchors.add(pos);
                }
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not list the blocks of a network", e);
        }
        if (anchors.isEmpty() && any != null) {
            anchors.add(any);
        }
        Store best = null;
        int bestHits = 0;
        for (Store store : stores) {
            int hits = 0;
            for (LPos a : store.anchors) {
                if (positions.contains(a)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                best = store;
                bestHits = hits;
            }
        }
        if (best == null) {
            best = new Store();
            stores.add(best);
        }
        if (!anchors.isEmpty() && !anchors.equals(best.anchors)) {
            best.anchors.clear();
            best.anchors.addAll(anchors);
            dirty.run();
        }
        return best;
    }

    public byte[] save() {
        Buf buf = Buf.create();
        buf.writeVarInt(FORMAT);
        List<Store> kept = new ArrayList<>();
        for (Store s : stores) {
            if (!s.entries.isEmpty()) {
                kept.add(s);
            }
        }
        buf.writeList(kept, (b, s) -> {
            b.writeList(s.anchors, (bb, p) -> p.write(bb));
            b.writeList(s.entries, (bb, e) -> e.write(bb));
        });
        return buf.toBytes();
    }

    public void load(byte[] bytes) {
        stores.clear();
        if (bytes == null || bytes.length == 0) {
            return;
        }
        try {
            Buf buf = Buf.of(bytes);
            if (buf.readVarInt() != FORMAT) {
                return;
            }
            int n = buf.readVarInt();
            for (int i = 0; i < n; i++) {
                Store store = new Store();
                store.anchors.addAll(buf.readList(LPos::read));
                store.entries.addAll(buf.readList(HistoryEntry::read));
                stores.add(store);
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.warn("Could not read the saved crafting history; starting a new one", e);
        }
    }
}
