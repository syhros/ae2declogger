package com.syhros.ae2declogger.server;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;

/**
 * What the mod remembers per player, in the player's saved data: the last network they opened the monitor for (used
 * by the hotkey) and the network they get stall alerts for. A network is remembered by the position of one of its
 * blocks.
 */
public final class NetworkMemory {

    private static final String TAG = "ae2declogger";
    private static final String LAST = "last";
    private static final String ALERTS = "alerts";

    private NetworkMemory() {
    }

    public static void rememberLast(Player player, GlobalPos pos) {
        write(player, LAST, pos);
    }

    @Nullable
    public static GlobalPos last(Player player) {
        return read(player, LAST);
    }

    /** {@code null} turns alerts off. */
    public static void setAlerts(Player player, @Nullable GlobalPos pos) {
        write(player, ALERTS, pos);
    }

    @Nullable
    public static GlobalPos alerts(Player player) {
        return read(player, ALERTS);
    }

    /** Keeps the data when the player respawns (Forge does not copy it on its own). */
    public static void copy(Player from, Player to) {
        CompoundTag tag = from.getPersistentData().getCompound(TAG);
        if (!tag.isEmpty()) {
            to.getPersistentData().put(TAG, tag.copy());
        }
    }

    /**
     * Position of a block that belongs to the network: the preferred node's block if it has one, otherwise the first
     * block entity found on the network (drive, controller, crafting unit, interface...).
     */
    @Nullable
    public static GlobalPos positionOf(IGrid grid, @Nullable IGridNode preferred) {
        GlobalPos pos = preferred != null ? blockPos(preferred) : null;
        if (pos != null) {
            return pos;
        }
        try {
            for (IGridNode node : grid.getNodes()) {
                pos = blockPos(node);
                if (pos != null) {
                    return pos;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    @Nullable
    private static GlobalPos blockPos(IGridNode node) {
        if (node.getOwner() instanceof BlockEntity be && be.getLevel() != null && !be.isRemoved()) {
            return GlobalPos.of(be.getLevel().dimension(), be.getBlockPos());
        }
        return null;
    }

    private static void write(Player player, String key, @Nullable GlobalPos pos) {
        CompoundTag root = player.getPersistentData().getCompound(TAG);
        if (pos == null) {
            root.remove(key);
        } else {
            CompoundTag t = new CompoundTag();
            t.putString("dim", pos.dimension().location().toString());
            t.putLong("pos", pos.pos().asLong());
            root.put(key, t);
        }
        player.getPersistentData().put(TAG, root);
    }

    @Nullable
    private static GlobalPos read(Player player, String key) {
        CompoundTag root = player.getPersistentData().getCompound(TAG);
        if (!root.contains(key)) {
            return null;
        }
        CompoundTag t = root.getCompound(key);
        ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
        if (dim == null) {
            return null;
        }
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(t.getLong("pos")));
    }
}
