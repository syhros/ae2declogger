package com.syhros.ae2declogger.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge networking for {@link DeclogNetwork}: every message is a payload type named after it
 * ({@code ae2declogger:<name>}), wrapping the message record. Handlers run on the main thread.
 */
public final class Channel {

    private static final String PROTOCOL = "4";

    /** A message travelling as a NeoForge payload. */
    record Packet<T>(CustomPacketPayload.Type<Packet<T>> id, T message) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return id;
        }
    }

    private record Entry<T>(Class<T> messageClass, CustomPacketPayload.Type<Packet<T>> id,
            StreamCodec<RegistryFriendlyByteBuf, Packet<T>> codec, @Nullable BiConsumer<T, ServerPlayer> serverHandler) {
    }

    private static final List<Entry<?>> ENTRIES = new ArrayList<>();
    private static final Map<Class<?>, Entry<?>> BY_CLASS = new HashMap<>();

    private Channel() {
    }

    static <T> void c2s(Class<T> type, String name, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ServerPlayer> handler) {
        add(type, name, encoder, decoder, handler);
    }

    static <T> void s2c(Class<T> type, String name, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder) {
        add(type, name, encoder, decoder, null);
    }

    private static <T> void add(Class<T> type, String name, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder, @Nullable BiConsumer<T, ServerPlayer> handler) {
        CustomPacketPayload.Type<Packet<T>> id = new CustomPacketPayload.Type<>(Ae2Declogger.id(name));
        StreamCodec<RegistryFriendlyByteBuf, Packet<T>> codec = StreamCodec.of(
                (buf, packet) -> encoder.accept(packet.message(), buf),
                buf -> new Packet<>(id, decoder.apply(buf)));
        Entry<T> entry = new Entry<>(type, id, codec, handler);
        ENTRIES.add(entry);
        BY_CLASS.put(type, entry);
    }

    /** Mod bus event: registers every message added by {@link DeclogNetwork#register()}. */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);
        for (Entry<?> entry : ENTRIES) {
            register(registrar, entry);
        }
    }

    private static <T> void register(PayloadRegistrar registrar, Entry<T> entry) {
        BiConsumer<T, ServerPlayer> handler = entry.serverHandler();
        if (handler != null) {
            registrar.playToServer(entry.id(), entry.codec(), (packet, ctx) -> {
                if (ctx.player() instanceof ServerPlayer player) {
                    handleOnServer(entry.messageClass(), handler, packet.message(), player);
                }
            });
        } else {
            registrar.playToClient(entry.id(), entry.codec(),
                    (packet, ctx) -> ClientPacketHandler.handle(packet.message()));
        }
    }

    static void sendTo(ServerPlayer player, Object message) {
        PacketDistributor.sendToPlayer(player, wrap(message));
    }

    static void sendToServer(Object message) {
        PacketDistributor.sendToServer(wrap(message));
    }

    @SuppressWarnings("unchecked")
    private static <T> Packet<T> wrap(T message) {
        Entry<T> entry = (Entry<T>) BY_CLASS.get(message.getClass());
        if (entry == null) {
            throw new IllegalArgumentException("Not a registered AE2 Declogger message: " + message.getClass());
        }
        return new Packet<>(entry.id(), message);
    }

    static <T> void handleOnServer(Class<T> type, BiConsumer<T, ServerPlayer> handler, T msg, ServerPlayer player) {
        try {
            handler.accept(msg, player);
        } catch (RuntimeException | LinkageError e) {
            Ae2Declogger.LOG.error("Error handling {} from {}", type.getSimpleName(), player.getGameProfile().getName(),
                    e);
        }
    }
}
