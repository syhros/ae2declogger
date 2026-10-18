package com.syhros.ae2declogger.net;

import java.util.Collections;
import java.util.List;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.GridSummary;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.server.DecloggerServer;

/**
 * Every packet of the legacy versions. Each version's {@code Channel} carries one kind of message per direction, a
 * byte array; the first byte here says which message it is. Server-side handlers run on the server thread (the channel
 * makes sure of it); client-bound messages go to the {@link ClientSink} the client registered, so no client class is
 * touched on a dedicated server.
 */
public final class Messages {

    // client -> server
    static final byte OPEN_MONITOR = 1;
    static final byte CLOSE_MONITOR = 2;
    static final byte REQUEST_MACHINE = 3;
    static final byte TOGGLE_ALERTS = 4;
    static final byte CLEAR_HISTORY = 5;
    static final byte WATCH_CRAFTING_SCREEN = 6;
    // server -> client
    static final byte OPEN_MONITOR_SCREEN = 20;
    static final byte MONITOR_UPDATE = 21;
    static final byte MONITOR_CLOSED = 22;
    static final byte MACHINE_DATA = 23;
    static final byte ALERT = 24;

    /** What the client does with server messages (set by the client side at start-up). */
    public interface ClientSink {
        void openMonitor(LText title);

        void update(MonitorUpdate update);

        void closed(LText reason);

        void machine(MachineSnapshot snapshot, boolean open);

        void alert(Alert alert);
    }

    private static volatile ClientSink clientSink;

    private Messages() {
    }

    public static void setClientSink(ClientSink sink) {
        clientSink = sink;
    }

    // ---------------------------------------------------------------- client -> server

    public static byte[] openMonitor(boolean fromTerminal) {
        Buf b = start(OPEN_MONITOR);
        b.writeBoolean(fromTerminal);
        return b.toBytes();
    }

    public static byte[] closeMonitor() {
        return start(CLOSE_MONITOR).toBytes();
    }

    public static byte[] requestMachine(LPos pos, boolean open) {
        Buf b = start(REQUEST_MACHINE);
        pos.write(b);
        b.writeBoolean(open);
        return b.toBytes();
    }

    public static byte[] toggleAlerts() {
        return start(TOGGLE_ALERTS).toBytes();
    }

    public static byte[] clearHistory() {
        return start(CLEAR_HISTORY).toBytes();
    }

    public static byte[] watchCraftingScreen(boolean watch) {
        Buf b = start(WATCH_CRAFTING_SCREEN);
        b.writeBoolean(watch);
        return b.toBytes();
    }

    /** Runs a client message on the server thread. {@code player} is the version's server player. */
    public static void handleServer(byte[] payload, Object player) {
        try {
            Buf b = Buf.of(payload);
            switch (b.readByte()) {
                case OPEN_MONITOR:
                    DecloggerServer.openFromRequest(player, b.readBoolean());
                    break;
                case CLOSE_MONITOR:
                    DecloggerServer.closeMonitor(player);
                    break;
                case REQUEST_MACHINE: {
                    LPos pos = LPos.read(b);
                    DecloggerServer.requestMachine(player, pos, b.readBoolean());
                    break;
                }
                case TOGGLE_ALERTS:
                    DecloggerServer.toggleAlerts(player);
                    break;
                case CLEAR_HISTORY:
                    DecloggerServer.clearHistory(player);
                    break;
                case WATCH_CRAFTING_SCREEN:
                    DecloggerServer.watchCraftingScreen(player, b.readBoolean());
                    break;
                default:
                    // unknown message from a newer client: ignore
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.warn("Bad AE2 Declogger packet from a client", e);
        }
    }

    // ---------------------------------------------------------------- server -> client

    public static byte[] openMonitorScreen(LText title) {
        Buf b = start(OPEN_MONITOR_SCREEN);
        title.write(b);
        return b.toBytes();
    }

    public static byte[] monitorClosed(LText reason) {
        Buf b = start(MONITOR_CLOSED);
        reason.write(b);
        return b.toBytes();
    }

    public static byte[] machineData(MachineSnapshot snapshot, boolean open) {
        Buf b = start(MACHINE_DATA);
        snapshot.write(b);
        b.writeBoolean(open);
        return b.toBytes();
    }

    /**
     * One monitor update.
     *
     * <ul>
     * <li>{@code full}: replace everything the client has</li>
     * <li>{@code changed}: new or changed job rows; {@code removed}: ids of rows that are gone</li>
     * <li>{@code order}: ids of all current rows, in display order</li>
     * <li>{@code history}: finished jobs to add (oldest first), or the whole list when {@code historyReset}</li>
     * <li>{@code focusJob}: job of the CPU shown in the AE2 crafting status screen the player has open, or -1</li>
     * </ul>
     */
    public static final class MonitorUpdate {
        public final boolean full;
        public final GridSummary summary;
        public final List<JobView> changed;
        public final long[] removed;
        public final long[] order;
        public final List<HistoryEntry> history;
        public final boolean historyReset;
        public final boolean alertsOn;
        public final long focusJob;

        public MonitorUpdate(boolean full, GridSummary summary, List<JobView> changed, long[] removed, long[] order,
                List<HistoryEntry> history, boolean historyReset, boolean alertsOn, long focusJob) {
            this.full = full;
            this.summary = summary;
            this.changed = Collections.unmodifiableList(changed);
            this.removed = removed;
            this.order = order;
            this.history = Collections.unmodifiableList(history);
            this.historyReset = historyReset;
            this.alertsOn = alertsOn;
            this.focusJob = focusJob;
        }

        public byte[] encode() {
            Buf b = start(MONITOR_UPDATE);
            b.writeBoolean(full);
            summary.write(b);
            b.writeList(changed, (bb, j) -> j.write(bb));
            b.writeLongArray(removed);
            b.writeLongArray(order);
            b.writeList(history, (bb, h) -> h.write(bb));
            b.writeBoolean(historyReset);
            b.writeBoolean(alertsOn);
            b.writeLong(focusJob);
            return b.toBytes();
        }

        static MonitorUpdate decode(Buf b) {
            boolean full = b.readBoolean();
            GridSummary summary = GridSummary.read(b);
            List<JobView> changed = b.readList(JobView::read);
            long[] removed = b.readLongArray();
            long[] order = b.readLongArray();
            List<HistoryEntry> history = b.readList(HistoryEntry::read);
            boolean reset = b.readBoolean();
            boolean alerts = b.readBoolean();
            long focus = b.readLong();
            return new MonitorUpdate(full, summary, changed, removed, order, history, reset, alerts, focus);
        }
    }

    /** A job stalled or got blocked on a network the player has alerts on for. */
    public static final class Alert {
        public final LKey output;
        public final long amount;
        public final JobState state;
        public final BlockReason reason;
        public final LText detail;
        public final MachineRef machine;

        public Alert(LKey output, long amount, JobState state, BlockReason reason, LText detail, MachineRef machine) {
            this.output = output;
            this.amount = amount;
            this.state = state;
            this.reason = reason;
            this.detail = detail;
            this.machine = machine;
        }

        public byte[] encode() {
            Buf b = start(ALERT);
            output.write(b);
            b.writeVarLong(amount);
            b.writeEnum(state);
            b.writeEnum(reason);
            b.writeNullable(detail, (bb, d) -> d.write(bb));
            b.writeNullable(machine, (bb, m) -> m.write(bb));
            return b.toBytes();
        }

        static Alert decode(Buf b) {
            LKey key = LKey.read(b);
            long amount = b.readVarLong();
            JobState state = b.readEnum(JobState.class, JobState.STALLED);
            BlockReason reason = b.readEnum(BlockReason.class, BlockReason.UNKNOWN);
            LText detail = b.readNullable(LText::read);
            MachineRef machine = b.readNullable(MachineRef::read);
            return key == null ? null : new Alert(key, amount, state, reason, detail, machine);
        }
    }

    /** Runs a server message on the client thread. */
    public static void handleClient(byte[] payload) {
        ClientSink sink = clientSink;
        if (sink == null) {
            return;
        }
        try {
            Buf b = Buf.of(payload);
            switch (b.readByte()) {
                case OPEN_MONITOR_SCREEN:
                    sink.openMonitor(LText.read(b));
                    break;
                case MONITOR_UPDATE:
                    sink.update(MonitorUpdate.decode(b));
                    break;
                case MONITOR_CLOSED:
                    sink.closed(LText.read(b));
                    break;
                case MACHINE_DATA: {
                    MachineSnapshot s = MachineSnapshot.read(b);
                    sink.machine(s, b.readBoolean());
                    break;
                }
                case ALERT: {
                    Alert a = Alert.decode(b);
                    if (a != null) {
                        sink.alert(a);
                    }
                    break;
                }
                default:
                    // unknown message from a newer server: ignore
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.warn("Bad AE2 Declogger packet from the server", e);
        }
    }

    private static Buf start(byte id) {
        Buf b = Buf.create();
        b.writeByte(id);
        return b;
    }
}
