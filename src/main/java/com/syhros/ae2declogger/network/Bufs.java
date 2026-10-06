package com.syhros.ae2declogger.network;

import java.util.function.BiConsumer;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Mc;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
//#if MC >= 1.20.5
//$$ import net.minecraft.network.RegistryFriendlyByteBuf;
//$$ import net.minecraft.network.chat.ComponentSerialization;
//#endif

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * Reads and writes the parts of packets that differ between Minecraft versions: text components, AE2 keys and stacks.
 * From 1.20.5 these need the registries, which the buffers the packets are written to carry
 * ({@code RegistryFriendlyByteBuf}).
 */
public final class Bufs {

    private Bufs() {
    }

    public static void writeComponent(FriendlyByteBuf buf, Component c) {
        //#if MC >= 1.20.5
        //$$ ComponentSerialization.TRUSTED_STREAM_CODEC.encode(registries(buf), c);
        //#else
        buf.writeComponent(c);
        //#endif
    }

    public static Component readComponent(FriendlyByteBuf buf) {
        //#if MC >= 1.20.5
        //$$ return ComponentSerialization.TRUSTED_STREAM_CODEC.decode(registries(buf));
        //#else
        return buf.readComponent();
        //#endif
    }

    public static void writeKey(FriendlyByteBuf buf, AEKey key) {
        //#if MC >= 1.20.5
        //$$ AEKey.writeKey(registries(buf), key);
        //#else
        AEKey.writeKey(buf, key);
        //#endif
    }

    @Nullable
    public static AEKey readKey(FriendlyByteBuf buf) {
        //#if MC >= 1.20.5
        //$$ return AEKey.readKey(registries(buf));
        //#else
        return AEKey.readKey(buf);
        //#endif
    }

    public static void writeStack(FriendlyByteBuf buf, @Nullable GenericStack stack) {
        //#if MC >= 1.20.5
        //$$ GenericStack.writeBuffer(stack, registries(buf));
        //#else
        GenericStack.writeBuffer(stack, buf);
        //#endif
    }

    @Nullable
    public static GenericStack readStack(FriendlyByteBuf buf) {
        //#if MC >= 1.20.5
        //$$ return GenericStack.readBuffer(registries(buf));
        //#else
        return GenericStack.readBuffer(buf);
        //#endif
    }

    public static <T> void writeNullable(FriendlyByteBuf buf, @Nullable T value, BiConsumer<FriendlyByteBuf, T> writer) {
        buf.writeBoolean(value != null);
        if (value != null) {
            writer.accept(buf, value);
        }
    }

    @Nullable
    public static <T> T readNullable(FriendlyByteBuf buf, Function<FriendlyByteBuf, T> reader) {
        return buf.readBoolean() ? reader.apply(buf) : null;
    }

    public static void writeDimension(FriendlyByteBuf buf, ResourceKey<Level> dimension) {
        buf.writeResourceLocation(dimension.location());
    }

    public static ResourceKey<Level> readDimension(FriendlyByteBuf buf) {
        return Mc.dimension(buf.readResourceLocation());
    }

    //#if MC >= 1.20.5
    //$$ /** Packets are encoded into registry-aware buffers; anything else is a bug in the caller. */
    //$$ private static RegistryFriendlyByteBuf registries(FriendlyByteBuf buf) {
    //$$     if (buf instanceof RegistryFriendlyByteBuf r) {
    //$$         return r;
    //$$     }
    //$$     throw new IllegalArgumentException("AE2 Declogger packets need a registry-aware buffer");
    //$$ }
    //#endif
}
