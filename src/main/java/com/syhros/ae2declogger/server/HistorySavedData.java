package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.HistoryEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.networking.ControllerBlockEntity;

/**
 * History and stats saved with the world ({@code data/ae2declogger_history.dat}), so they survive leaving the world or
 * restarting the server.
 *
 * <p>
 * AE2 builds a new network object whenever a network changes or reloads, so a network is recognised by the blocks it
 * is made of: the positions of its ME controllers and crafting CPU blocks ("anchors"). A network that still contains
 * any of a saved record's anchors gets that record back.
 */
public final class HistorySavedData extends SavedData {

    private static final String NAME = "ae2declogger_history";
    private static final int MAX_ANCHORS = 32;

    /** The saved history of one network, oldest entry first. */
    public static final class Store {
        final List<GlobalPos> anchors = new ArrayList<>();
        final List<HistoryEntry> entries = new ArrayList<>();
        private final HistorySavedData owner;

        Store(HistorySavedData owner) {
            this.owner = owner;
        }

        public List<HistoryEntry> entries() {
            return entries;
        }

        void add(HistoryEntry entry, int capacity) {
            entries.add(entry);
            trim(capacity);
            owner.setDirty();
        }

        void clear() {
            entries.clear();
            owner.setDirty();
        }

        void trim(int capacity) {
            int over = entries.size() - Math.max(1, capacity);
            if (over > 0) {
                entries.subList(0, over).clear();
                owner.setDirty();
            }
        }
    }

    private final List<Store> stores = new ArrayList<>();

    public static HistorySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(HistorySavedData::load, HistorySavedData::new,
                NAME);
    }

    /** The saved record of this network, or a new one. */
    public Store storeFor(IGrid grid) {
        Set<GlobalPos> positions = new HashSet<>();
        List<GlobalPos> anchors = new ArrayList<>();
        GlobalPos any = null;
        try {
            for (IGridNode node : grid.getNodes()) {
                GlobalPos pos = NetworkMemory.blockPos(node);
                if (pos == null) {
                    continue;
                }
                positions.add(pos);
                if (any == null) {
                    any = pos;
                }
                Object owner = node.getOwner();
                if ((owner instanceof ControllerBlockEntity || owner instanceof CraftingBlockEntity)
                        && anchors.size() < MAX_ANCHORS) {
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
            for (GlobalPos a : store.anchors) {
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
            best = new Store(this);
            stores.add(best);
        }
        if (!anchors.isEmpty() && !anchors.equals(best.anchors)) {
            best.anchors.clear();
            best.anchors.addAll(anchors);
            setDirty();
        }
        return best;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Store store : stores) {
            if (store.entries.isEmpty()) {
                continue;
            }
            CompoundTag s = new CompoundTag();
            ListTag anchors = new ListTag();
            for (GlobalPos pos : store.anchors) {
                CompoundTag p = new CompoundTag();
                p.putString("dim", pos.dimension().location().toString());
                p.putLong("pos", pos.pos().asLong());
                anchors.add(p);
            }
            s.put("anchors", anchors);
            ListTag entries = new ListTag();
            for (HistoryEntry e : store.entries) {
                entries.add(e.toTag());
            }
            s.put("entries", entries);
            list.add(s);
        }
        tag.put("networks", list);
        return tag;
    }

    public static HistorySavedData load(CompoundTag tag) {
        HistorySavedData data = new HistorySavedData();
        for (Tag t : tag.getList("networks", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) t;
            Store store = new Store(data);
            for (Tag a : s.getList("anchors", Tag.TAG_COMPOUND)) {
                GlobalPos pos = readPos((CompoundTag) a);
                if (pos != null) {
                    store.anchors.add(pos);
                }
            }
            for (Tag e : s.getList("entries", Tag.TAG_COMPOUND)) {
                try {
                    HistoryEntry entry = HistoryEntry.fromTag((CompoundTag) e);
                    if (entry != null) {
                        store.entries.add(entry);
                    }
                } catch (RuntimeException ex) {
                    Ae2Declogger.LOG.debug("Skipped an unreadable history entry", ex);
                }
            }
            data.stores.add(store);
        }
        return data;
    }

    @Nullable
    private static GlobalPos readPos(CompoundTag t) {
        ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
        if (dim == null) {
            return null;
        }
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(t.getLong("pos")));
    }
}
