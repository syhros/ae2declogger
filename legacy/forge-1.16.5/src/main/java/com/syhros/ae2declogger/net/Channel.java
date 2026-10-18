package com.syhros.ae2declogger.net;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;

/** 1.16.5 networking: one message type per direction carrying a {@link Messages} payload. */
public final class Channel {

    private static final String VERSION = "1";
    private static SimpleChannel channel;

    private Channel() {
    }

    /** Client to server. */
    public static final class ServerPacket {
        final byte[] payload;

        ServerPacket(byte[] payload) {
            this.payload = payload;
        }
    }

    /** Server to client. */
    public static final class ClientPacket {
        final byte[] payload;

        ClientPacket(byte[] payload) {
            this.payload = payload;
        }
    }

    public static void register() {
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation("ae2declogger", "main"), () -> VERSION,
                VERSION::equals, VERSION::equals);
        channel.registerMessage(0, ServerPacket.class, (m, b) -> new Buf(b).writeBytes(m.payload),
                b -> new ServerPacket(new Buf(b).readBytes()), Channel::onServer,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(1, ClientPacket.class, (m, b) -> new Buf(b).writeBytes(m.payload),
                b -> new ClientPacket(new Buf(b).readBytes()), Channel::onClient,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void onServer(ServerPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayerEntity player = ctx.get().getSender();
        if (player != null) {
            ctx.get().enqueueWork(() -> Messages.handleServer(msg.payload, player));
        }
        ctx.get().setPacketHandled(true);
    }

    private static void onClient(ClientPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> Messages.handleClient(msg.payload));
        ctx.get().setPacketHandled(true);
    }

    public static void sendTo(Object player, byte[] payload) {
        channel.send(PacketDistributor.PLAYER.with(() -> (ServerPlayerEntity) player), new ClientPacket(payload));
    }

    public static void sendToServer(byte[] payload) {
        channel.sendToServer(new ServerPacket(payload));
    }
}
