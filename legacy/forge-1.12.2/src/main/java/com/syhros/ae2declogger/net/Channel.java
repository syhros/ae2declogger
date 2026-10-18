package com.syhros.ae2declogger.net;

import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/** 1.12.2 networking: one message type per direction carrying a {@link Messages} payload. */
public final class Channel {

    private static SimpleNetworkWrapper channel;
    /** Runs a task on the client thread; set by the client side at start-up. */
    public static volatile Consumer<Runnable> clientExecutor;

    private Channel() {
    }

    /** The bytes of one {@link Messages} message. */
    public abstract static class Packet implements IMessage {
        byte[] payload = new byte[0];

        @Override
        public void fromBytes(ByteBuf buf) {
            payload = new Buf(buf).readBytes();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            new Buf(buf).writeBytes(payload);
        }
    }

    /** Client to server. */
    public static final class ServerPacket extends Packet {
        public ServerPacket() {
        }

        ServerPacket(byte[] payload) {
            this.payload = payload;
        }
    }

    /** Server to client. */
    public static final class ClientPacket extends Packet {
        public ClientPacket() {
        }

        ClientPacket(byte[] payload) {
            this.payload = payload;
        }
    }

    public static final class ToServer implements IMessageHandler<ServerPacket, IMessage> {
        @Override
        public IMessage onMessage(ServerPacket message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> Messages.handleServer(message.payload, player));
            return null;
        }
    }

    public static final class ToClient implements IMessageHandler<ClientPacket, IMessage> {
        @Override
        public IMessage onMessage(ClientPacket message, MessageContext ctx) {
            Consumer<Runnable> exec = clientExecutor;
            if (exec != null) {
                exec.accept(() -> Messages.handleClient(message.payload));
            }
            return null;
        }
    }

    public static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("ae2declogger");
        channel.registerMessage(ToServer.class, ServerPacket.class, 0, Side.SERVER);
        channel.registerMessage(ToClient.class, ClientPacket.class, 1, Side.CLIENT);
    }

    public static void sendTo(Object player, byte[] payload) {
        channel.sendTo(new ClientPacket(payload), (EntityPlayerMP) player);
    }

    public static void sendToServer(byte[] payload) {
        channel.sendToServer(new ServerPacket(payload));
    }
}
