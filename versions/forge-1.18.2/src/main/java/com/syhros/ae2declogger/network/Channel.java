package com.syhros.ae2declogger.network;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.syhros.ae2declogger.Ae2Declogger;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge 1.18.2 networking for {@link DeclogNetwork}: one SimpleChannel, messages numbered in registration order. Same
 * as the shared Forge version, except that 1.18 has no consumerMainThread, so handlers queue themselves on the main
 * thread.
 */
final class Channel {

    private static final String PROTOCOL = "4";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(Ae2Declogger.id("main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static int nextId;

    private Channel() {
    }

    static <T> void c2s(Class<T> type, String name, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ServerPlayer> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder)
                .decoder(decoder)
                .consumer((T msg, Supplier<NetworkEvent.Context> ctx) -> {
                    NetworkEvent.Context c = ctx.get();
                    c.enqueueWork(() -> {
                        ServerPlayer player = c.getSender();
                        if (player != null) {
                            handleOnServer(type, handler, msg, player);
                        }
                    });
                    c.setPacketHandled(true);
                })
                .add();
    }

    static <T> void s2c(Class<T> type, String name, BiConsumer<T, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, T> decoder) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder)
                .decoder(decoder)
                .consumer((T msg, Supplier<NetworkEvent.Context> ctx) -> {
                    NetworkEvent.Context c = ctx.get();
                    c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> ClientPacketHandler.handle(msg)));
                    c.setPacketHandled(true);
                })
                .add();
    }

    static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
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
