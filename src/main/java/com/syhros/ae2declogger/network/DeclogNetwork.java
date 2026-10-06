package com.syhros.ae2declogger.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.data.GridSummary;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.server.DecloggerServer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEKey;

/**
 * All packets. The loader's networking is in {@link Channel}; client-bound handlers run through
 * {@link ClientPacketHandler} so no client class is loaded on a dedicated server.
 */
public final class DeclogNetwork {

    private DeclogNetwork() {
    }

    public static void register() {
        // Client -> server
        Channel.c2s(OpenMonitor.class, "open_monitor", OpenMonitor::write, OpenMonitor::read,
                (msg, player) -> DecloggerServer.openFromRequest(player, msg.fromTerminal()));
        Channel.c2s(CloseMonitor.class, "close_monitor", (m, b) -> {
        }, b -> new CloseMonitor(), (msg, player) -> DecloggerServer.closeMonitor(player));
        Channel.c2s(RequestMachine.class, "request_machine", RequestMachine::write, RequestMachine::read,
                (msg, player) -> DecloggerServer.requestMachine(player, msg.dimension(), msg.pos(), msg.open()));
        Channel.c2s(ToggleAlerts.class, "toggle_alerts", (m, b) -> {
        }, b -> new ToggleAlerts(), (msg, player) -> DecloggerServer.toggleAlerts(player));
        Channel.c2s(ClearHistory.class, "clear_history", (m, b) -> {
        }, b -> new ClearHistory(), (msg, player) -> DecloggerServer.clearHistory(player));
        Channel.c2s(WatchCraftingScreen.class, "watch_crafting_screen", WatchCraftingScreen::write,
                WatchCraftingScreen::read, (msg, player) -> DecloggerServer.watchCraftingScreen(player, msg.watch()));

        // Server -> client
        Channel.s2c(OpenMonitorScreen.class, "open_monitor_screen", OpenMonitorScreen::write, OpenMonitorScreen::read);
        Channel.s2c(MonitorUpdate.class, "monitor_update", MonitorUpdate::write, MonitorUpdate::read);
        Channel.s2c(MonitorClosed.class, "monitor_closed", MonitorClosed::write, MonitorClosed::read);
        Channel.s2c(MachineData.class, "machine_data", MachineData::write, MachineData::read);
        Channel.s2c(Alert.class, "alert", Alert::write, Alert::read);
    }

    public static void sendTo(ServerPlayer player, Object message) {
        Channel.sendTo(player, message);
    }

    public static void sendToServer(Object message) {
        Channel.sendToServer(message);
    }

    // ---------------------------------------------------------------- client -> server

    /** Hotkey, or the button / Inspect in an AE2 screen ({@code fromTerminal}). */
    public record OpenMonitor(boolean fromTerminal) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(fromTerminal);
        }

        public static OpenMonitor read(FriendlyByteBuf buf) {
            return new OpenMonitor(buf.readBoolean());
        }
    }

    public record CloseMonitor() {
    }

    public record RequestMachine(ResourceKey<Level> dimension, BlockPos pos, boolean open) {
        public void write(FriendlyByteBuf buf) {
            Bufs.writeDimension(buf, dimension);
            buf.writeBlockPos(pos);
            buf.writeBoolean(open);
        }

        public static RequestMachine read(FriendlyByteBuf buf) {
            return new RequestMachine(Bufs.readDimension(buf), buf.readBlockPos(),
                    buf.readBoolean());
        }
    }

    public record ToggleAlerts() {
    }

    public record ClearHistory() {
    }

    /** AE2's crafting status screen opened ({@code watch}) or closed. */
    public record WatchCraftingScreen(boolean watch) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(watch);
        }

        public static WatchCraftingScreen read(FriendlyByteBuf buf) {
            return new WatchCraftingScreen(buf.readBoolean());
        }
    }

    // ---------------------------------------------------------------- server -> client

    public record OpenMonitorScreen(Component title) {
        public void write(FriendlyByteBuf buf) {
            Bufs.writeComponent(buf, title);
        }

        public static OpenMonitorScreen read(FriendlyByteBuf buf) {
            return new OpenMonitorScreen(Bufs.readComponent(buf));
        }
    }

    /**
     * @param full         replace everything the client has
     * @param changed      new or changed job rows
     * @param removed      ids of rows that are gone
     * @param order        ids of all current rows, in display order
     * @param history      finished jobs to add (oldest first), or the whole list when historyReset
     * @param alertsOn     whether the player has alerts on for this network
     * @param focusJob     job of the CPU shown in the AE2 crafting status screen the player has open, or -1
     */
    public record MonitorUpdate(boolean full, GridSummary summary, List<JobView> changed, long[] removed,
            long[] order, List<HistoryEntry> history, boolean historyReset, boolean alertsOn, long focusJob) {

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(full);
            summary.write(buf);
            buf.writeCollection(changed, (b, j) -> j.write(b));
            buf.writeLongArray(removed);
            buf.writeLongArray(order);
            buf.writeCollection(history, (b, h) -> h.write(b));
            buf.writeBoolean(historyReset);
            buf.writeBoolean(alertsOn);
            buf.writeLong(focusJob);
        }

        public static MonitorUpdate read(FriendlyByteBuf buf) {
            boolean full = buf.readBoolean();
            GridSummary summary = GridSummary.read(buf);
            List<JobView> changed = dropNulls(buf.readList(JobView::read));
            long[] removed = buf.readLongArray();
            long[] order = buf.readLongArray();
            List<HistoryEntry> history = dropNulls(buf.readList(HistoryEntry::read));
            boolean reset = buf.readBoolean();
            boolean alerts = buf.readBoolean();
            long focus = buf.readLong();
            return new MonitorUpdate(full, summary, changed, removed, order, history, reset, alerts, focus);
        }
    }

    public record MonitorClosed(Component reason) {
        public void write(FriendlyByteBuf buf) {
            Bufs.writeComponent(buf, reason);
        }

        public static MonitorClosed read(FriendlyByteBuf buf) {
            return new MonitorClosed(Bufs.readComponent(buf));
        }
    }

    /** @param open open the viewer screen, or just refresh it if already open */
    public record MachineData(MachineSnapshot snapshot, boolean open) {
        public void write(FriendlyByteBuf buf) {
            snapshot.write(buf);
            buf.writeBoolean(open);
        }

        public static MachineData read(FriendlyByteBuf buf) {
            return new MachineData(MachineSnapshot.read(buf), buf.readBoolean());
        }
    }

    public record Alert(AEKey output, long amount, JobState state, BlockReason reason, @Nullable Component detail,
            @Nullable MachineRef machine) {
        public void write(FriendlyByteBuf buf) {
            Bufs.writeKey(buf, output);
            buf.writeVarLong(amount);
            buf.writeEnum(state);
            buf.writeEnum(reason);
            Bufs.writeNullable(buf, detail, Bufs::writeComponent);
            Bufs.writeNullable(buf, machine, (b, m) -> m.write(b));
        }

        @Nullable
        public static Alert read(FriendlyByteBuf buf) {
            AEKey key = Bufs.readKey(buf);
            long amount = buf.readVarLong();
            JobState state = buf.readEnum(JobState.class);
            BlockReason reason = buf.readEnum(BlockReason.class);
            Component detail = Bufs.readNullable(buf, Bufs::readComponent);
            MachineRef machine = Bufs.readNullable(buf, MachineRef::read);
            return key == null ? null : new Alert(key, amount, state, reason, detail, machine);
        }
    }

    private static <T> List<T> dropNulls(List<T> list) {
        List<T> result = new ArrayList<>(list.size());
        for (T t : list) {
            if (t != null) {
                result.add(t);
            }
        }
        return result;
    }
}
