package com.syhros.ae2declogger.net;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 1.7.10 networking: one message type per direction carrying a {@link Messages} payload. FML runs 1.7.10 handlers on
 * the network thread, so messages are queued and handled at the next tick on the right thread.
 */
public final class Channel {

    private static SimpleNetworkWrapper channel;
    private static final Queue<Runnable> SERVER_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Queue<Runnable> CLIENT_QUEUE = new ConcurrentLinkedQueue<>();

    private Channel() {
    }

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
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final byte[] payload = message.payload;
            SERVER_QUEUE.add(() -> Messages.handleServer(payload, player));
            return null;
        }
    }

    public static final class ToClient implements IMessageHandler<ClientPacket, IMessage> {
        @Override
        public IMessage onMessage(ClientPacket message, MessageContext ctx) {
            final byte[] payload = message.payload;
            CLIENT_QUEUE.add(() -> Messages.handleClient(payload));
            return null;
        }
    }

    public static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("ae2declogger");
        channel.registerMessage(ToServer.class, ServerPacket.class, 0, Side.SERVER);
        channel.registerMessage(ToClient.class, ClientPacket.class, 1, Side.CLIENT);
    }

    /** Server thread, every tick. */
    public static void drainServer() {
        Runnable r;
        while ((r = SERVER_QUEUE.poll()) != null) {
            r.run();
        }
    }

    /** Client thread, every tick. */
    public static void drainClient() {
        Runnable r;
        while ((r = CLIENT_QUEUE.poll()) != null) {
            r.run();
        }
    }

    public static void sendTo(Object player, byte[] payload) {
        channel.sendTo(new ClientPacket(payload), (EntityPlayerMP) player);
    }

    public static void sendToServer(byte[] payload) {
        channel.sendToServer(new ServerPacket(payload));
    }
}
