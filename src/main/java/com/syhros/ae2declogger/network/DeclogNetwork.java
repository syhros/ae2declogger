package com.syhros.ae2declogger.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.GridSummary;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.server.DecloggerServer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import appeng.api.stacks.AEKey;

/**
 * All packets. Client-bound handlers run through {@link ClientPacketHandler} so no client class is loaded on a
 * dedicated server.
 */
public final class DeclogNetwork {

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(Ae2Declogger.id("main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static int nextId;

    private DeclogNetwork() {
    }

    public static void register() {
        // Client -> server
        c2s(OpenMonitor.class, OpenMonitor::write, OpenMonitor::read,
                (msg, player) -> DecloggerServer.openFromRequest(player, msg.fromTerminal()));
        c2s(CloseMonitor.class, (m, b) -> {
        }, b -> new CloseMonitor(), (msg, player) -> DecloggerServer.closeMonitor(player));
        c2s(RequestMachine.class, RequestMachine::write, RequestMachine::read,
                (msg, player) -> DecloggerServer.requestMachine(player, msg.dimension(), msg.pos(), msg.open(),
                        false));
        c2s(Teleport.class, Teleport::write, Teleport::read,
                (msg, player) -> DecloggerServer.teleport(player, msg.dimension(), msg.pos()));
        c2s(ToggleAlerts.class, (m, b) -> {
        }, b -> new ToggleAlerts(), (msg, player) -> DecloggerServer.toggleAlerts(player));
        c2s(ClearHistory.class, (m, b) -> {
        }, b -> new ClearHistory(), (msg, player) -> DecloggerServer.clearHistory(player));

        // Server -> client
        s2c(OpenMonitorScreen.class, OpenMonitorScreen::write, OpenMonitorScreen::read);
        s2c(MonitorUpdate.class, MonitorUpdate::write, MonitorUpdate::read);
        s2c(MonitorClosed.class, MonitorClosed::write, MonitorClosed::read);
        s2c(MachineData.class, MachineData::write, MachineData::read);
        s2c(Alert.class, Alert::write, Alert::read);
    }

    private static <T> void c2s(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ServerPlayer> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((T msg, Supplier<NetworkEvent.Context> ctx) -> {
                    ServerPlayer player = ctx.get().getSender();
                    if (player != null) {
                        try {
                            handler.accept(msg, player);
                        } catch (RuntimeException e) {
                            Ae2Declogger.LOG.error("Error handling {} from {}", type.getSimpleName(),
                                    player.getGameProfile().getName(), e);
                        }
                    }
                })
                .add();
    }

    private static <T> void s2c(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((T msg, Supplier<NetworkEvent.Context> ctx) -> DistExecutor.unsafeRunWhenOn(
                        Dist.CLIENT, () -> () -> ClientPacketHandler.handle(msg)))
                .add();
    }

    public static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    // ---------------------------------------------------------------- client -> server

    /** Keybind or terminal button. */
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
            buf.writeResourceKey(dimension);
            buf.writeBlockPos(pos);
            buf.writeBoolean(open);
        }

        public static RequestMachine read(FriendlyByteBuf buf) {
            return new RequestMachine(buf.readResourceKey(Registries.DIMENSION), buf.readBlockPos(),
                    buf.readBoolean());
        }
    }

    public record Teleport(ResourceKey<Level> dimension, BlockPos pos) {
        public void write(FriendlyByteBuf buf) {
            buf.writeResourceKey(dimension);
            buf.writeBlockPos(pos);
        }

        public static Teleport read(FriendlyByteBuf buf) {
            return new Teleport(buf.readResourceKey(Registries.DIMENSION), buf.readBlockPos());
        }
    }

    public record ToggleAlerts() {
    }

    public record ClearHistory() {
    }

    // ---------------------------------------------------------------- server -> client

    public record OpenMonitorScreen(Component title) {
        public void write(FriendlyByteBuf buf) {
            buf.writeComponent(title);
        }

        public static OpenMonitorScreen read(FriendlyByteBuf buf) {
            return new OpenMonitorScreen(buf.readComponent());
        }
    }

    /**
     * @param full         replace everything the client has
     * @param changed      new or changed job rows
     * @param removed      ids of rows that are gone
     * @param order        ids of all current rows, in display order
     * @param history      finished jobs to add (oldest first), or the whole list when historyReset
     * @param alertsOn     whether the player's Declogger has alerts enabled
     */
    public record MonitorUpdate(boolean full, GridSummary summary, List<JobView> changed, long[] removed,
            long[] order, List<HistoryEntry> history, boolean historyReset, boolean alertsOn) {

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(full);
            summary.write(buf);
            buf.writeCollection(changed, (b, j) -> j.write(b));
            buf.writeLongArray(removed);
            buf.writeLongArray(order);
            buf.writeCollection(history, (b, h) -> h.write(b));
            buf.writeBoolean(historyReset);
            buf.writeBoolean(alertsOn);
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
            return new MonitorUpdate(full, summary, changed, removed, order, history, reset, alerts);
        }
    }

    public record MonitorClosed(Component reason) {
        public void write(FriendlyByteBuf buf) {
            buf.writeComponent(reason);
        }

        public static MonitorClosed read(FriendlyByteBuf buf) {
            return new MonitorClosed(buf.readComponent());
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
            AEKey.writeKey(buf, output);
            buf.writeVarLong(amount);
            buf.writeEnum(state);
            buf.writeEnum(reason);
            buf.writeNullable(detail, FriendlyByteBuf::writeComponent);
            buf.writeNullable(machine, (b, m) -> m.write(b));
        }

        @Nullable
        public static Alert read(FriendlyByteBuf buf) {
            AEKey key = AEKey.readKey(buf);
            long amount = buf.readVarLong();
            JobState state = buf.readEnum(JobState.class);
            BlockReason reason = buf.readEnum(BlockReason.class);
            Component detail = buf.readNullable(FriendlyByteBuf::readComponent);
            MachineRef machine = buf.readNullable(MachineRef::read);
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
