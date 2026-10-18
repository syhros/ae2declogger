package com.syhros.ae2declogger.data;

import java.io.DataInputStream;
import java.io.IOException;

import com.syhros.ae2declogger.net.Buf;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
//#if MC >= 1.16
import net.minecraft.nbt.CompoundNBT;
//#else
//$$ import net.minecraft.nbt.NBTTagCompound;
//#endif
//#if MC < 1.12
//$$ import net.minecraft.nbt.NBTSizeTracker;
//#endif

/**
 * An item, without an amount: what the crafting CPU makes or holds. All three legacy AE2 versions track crafting by
 * item (fluids travel as items there), so this wraps one item stack of size 1. Equal when item, damage and NBT match.
 * Encoded through the stack's NBT, so saved history survives a restart.
 */
public final class LKey {

    private final ItemStack stack;
    private final int hash;

    private LKey(ItemStack stack) {
        this.stack = stack;
        this.hash = computeHash(stack);
    }

    /** Null for an empty stack. */
    public static LKey of(ItemStack stack) {
        if (isEmpty(stack)) {
            return null;
        }
        ItemStack copy = stack.copy();
        //#if MC >= 1.12
        copy.setCount(1);
        //#else
        //$$ copy.stackSize = 1;
        //#endif
        return new LKey(copy);
    }

    /** A copy of the item, one of it. */
    public ItemStack stack() {
        return stack.copy();
    }

    public static boolean isEmpty(ItemStack stack) {
        //#if MC >= 1.12
        return stack == null || stack.isEmpty();
        //#else
        //$$ return stack == null || stack.getItem() == null || stack.stackSize <= 0;
        //#endif
    }

    /** The item's name in the current language (client side, or the server's language). */
    public String displayName() {
        try {
            //#if MC >= 1.16
            return stack.getHoverName().getString();
            //#else
            //$$ return stack.getDisplayName();
            //#endif
        } catch (RuntimeException e) {
            return String.valueOf(stack.getItem());
        }
    }

    /**
     * Same item, ignoring NBT (and damage for items with durability): how AE2 matches an ingredient when it pushes a
     * pattern, so a used GregTech tool still counts.
     */
    public boolean sameKind(LKey other) {
        if (other == null || stack.getItem() != other.stack.getItem()) {
            return false;
        }
        //#if MC >= 1.16
        return true;
        //#else
        //$$ return stack.isItemStackDamageable() || stack.getItemDamage() == other.stack.getItemDamage();
        //#endif
    }

    private static int computeHash(ItemStack s) {
        int h = System.identityHashCode(s.getItem());
        //#if MC < 1.16
        //$$ h = h * 31 + s.getItemDamage();
        //#endif
        //#if MC >= 1.16
        CompoundNBT tag = s.getTag();
        //#else
        //$$ NBTTagCompound tag = s.getTagCompound();
        //#endif
        return h * 31 + (tag == null ? 0 : tag.hashCode());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LKey)) {
            return false;
        }
        LKey k = (LKey) o;
        if (hash != k.hash || stack.getItem() != k.stack.getItem()) {
            return false;
        }
        //#if MC >= 1.16
        return ItemStack.tagMatches(stack, k.stack);
        //#else
        //$$ return stack.getItemDamage() == k.stack.getItemDamage() && ItemStack.areItemStackTagsEqual(stack, k.stack);
        //#endif
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return String.valueOf(stack);
    }

    public void write(Buf buf) {
        //#if MC >= 1.16
        CompoundNBT tag = stack.save(new CompoundNBT());
        //#else
        //$$ NBTTagCompound tag = stack.writeToNBT(new NBTTagCompound());
        //#endif
        Buf body = Buf.create();
        try (ByteBufOutputStream out = new ByteBufOutputStream(body.raw)) {
            CompressedStreamTools.write(tag, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        buf.writeBytes(body.toBytes());
    }

    /** Null when the item no longer exists (a removed mod) or the data is unreadable. */
    public static LKey read(Buf buf) {
        byte[] bytes = buf.readBytes();
        try (DataInputStream in = new DataInputStream(new ByteBufInputStream(Buf.of(bytes).raw))) {
            //#if MC >= 1.16
            CompoundNBT tag = CompressedStreamTools.read(in);
            return of(ItemStack.of(tag));
            //#elseif MC >= 1.12
            //$$ NBTTagCompound tag = CompressedStreamTools.read(in);
            //$$ return of(new ItemStack(tag));
            //#else
            //$$ NBTTagCompound tag = CompressedStreamTools.func_152456_a(in, NBTSizeTracker.field_152451_a);
            //$$ return of(ItemStack.loadItemStackFromNBT(tag));
            //#endif
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
