package com.syhros.ae2declogger.server;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.logic.BlockReason;
import com.syhros.ae2declogger.net.Channel;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

/** Minecraft 1.7.10 access for the shared server code: worlds, blocks, inventories, players, saved data. */
public final class Plat {

    private Plat() {
    }

    /** A block the player looks at, and the side they look at. */
    public static final class Hit {
        public final LPos pos;
        public final int side;

        Hit(LPos pos, int side) {
            this.pos = pos;
            this.side = side;
        }
    }

    /** A problem found by looking at a block's inventory or energy. */
    public static final class Problem {
        public final BlockReason reason;
        public final LText detail;

        Problem(BlockReason reason, LText detail) {
            this.reason = reason;
            this.detail = detail;
        }
    }

    public static final class Energy {
        public final long stored;
        public final long capacity;
        public final String unit;

        Energy(long stored, long capacity, String unit) {
            this.stored = stored;
            this.capacity = capacity;
            this.unit = unit;
        }
    }

    // ---------------------------------------------------------------- worlds and blocks

    private static WorldServer world(String dim) {
        try {
            return DimensionManager.getWorld(Integer.parseInt(dim));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean dimensionExists(String dim) {
        try {
            return DimensionManager.isDimensionRegistered(Integer.parseInt(dim));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String dimOf(World world) {
        return String.valueOf(world.provider.dimensionId);
    }

    public static LPos posOf(TileEntity te) {
        World w = te.getWorldObj();
        return w == null ? null : new LPos(dimOf(w), te.xCoord, te.yCoord, te.zCoord);
    }

    public static boolean isLoaded(LPos pos) {
        WorldServer w = world(pos.dim);
        return w != null && pos.y >= 0 && pos.y < 256 && w.blockExists(pos.x, pos.y, pos.z);
    }

    public static TileEntity tileAt(LPos pos) {
        if (!isLoaded(pos)) {
            return null;
        }
        return world(pos.dim).getTileEntity(pos.x, pos.y, pos.z);
    }

    public static boolean hasTile(LPos pos) {
        return tileAt(pos) != null;
    }

    public static boolean isAir(LPos pos) {
        WorldServer w = world(pos.dim);
        return w == null || w.isAirBlock(pos.x, pos.y, pos.z);
    }

    /** The block's name as its item (so the client shows it in its own language), else its registry name. */
    public static LText blockName(LPos pos) {
        WorldServer w = world(pos.dim);
        if (w == null) {
            return LText.literal("?");
        }
        Block block = w.getBlock(pos.x, pos.y, pos.z);
        try {
            Item item = Item.getItemFromBlock(block);
            if (item != null) {
                LKey key = LKey.of(new ItemStack(item, 1, block.getDamageValue(w, pos.x, pos.y, pos.z)));
                if (key != null) {
                    return LText.item(key);
                }
            }
        } catch (RuntimeException e) {
            // fall through to the registry name
        }
        return LText.literal(String.valueOf(Block.blockRegistry.getNameForObject(block)));
    }

    // ---------------------------------------------------------------- inventories

    /** Whether the block takes items or fluids on {@code side}. */
    public static boolean hasInventory(TileEntity te, int side) {
        if (te instanceof ISidedInventory) {
            int[] slots = ((ISidedInventory) te).getAccessibleSlotsFromSide(side);
            if (slots != null && slots.length > 0) {
                return true;
            }
        } else if (te instanceof IInventory && ((IInventory) te).getSizeInventory() > 0) {
            return true;
        }
        if (te instanceof IFluidHandler) {
            FluidTankInfo[] tanks = ((IFluidHandler) te).getTankInfo(ForgeDirection.getOrientation(side));
            return tanks != null && tanks.length > 0;
        }
        return false;
    }

    public static Problem genericProblem(LPos pos) {
        TileEntity te = tileAt(pos);
        if (te == null) {
            return null;
        }
        if (te instanceof IInventory && isCompletelyFull((IInventory) te)) {
            return new Problem(BlockReason.MACHINE_INPUT_FULL, LText.tr("ae2declogger.machine.inventory_full"));
        }
        Energy e = rf(te);
        if (e != null && e.capacity > 0 && e.stored <= 0) {
            return new Problem(BlockReason.MACHINE_NO_POWER, LText.tr("ae2declogger.compat.energy_empty"));
        }
        return null;
    }

    private static boolean isCompletelyFull(IInventory inv) {
        int slots = inv.getSizeInventory();
        if (slots == 0) {
            return false;
        }
        for (int i = 0; i < slots; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s == null || s.stackSize < Math.min(inv.getInventoryStackLimit(), s.getMaxStackSize())) {
                return false;
            }
        }
        return true;
    }

    /** Adds the block's items and tanks to the viewer; returns how many slots were used. */
    public static int readInventory(LPos pos, Set<LKey> patternInputs, int budget,
            List<MachineSnapshot.Section> out, List<LText> errors) {
        TileEntity te = tileAt(pos);
        if (te == null || budget <= 0) {
            return 0;
        }
        int used = 0;
        if (te instanceof IInventory && ((IInventory) te).getSizeInventory() > 0) {
            IInventory inv = (IInventory) te;
            List<MachineSnapshot.Slot> slots = new ArrayList<>();
            for (int i = 0; i < inv.getSizeInventory() && used < budget; i++, used++) {
                ItemStack s = inv.getStackInSlot(i);
                long limit = Math.max(1, inv.getInventoryStackLimit());
                LKey key = LKey.of(s);
                byte flags = 0;
                if (key != null) {
                    limit = Math.min(limit, s.getMaxStackSize());
                    if (s.stackSize >= limit) {
                        flags |= MachineSnapshot.FLAG_FULL;
                    }
                    if (patternInputs.contains(key)) {
                        flags |= MachineSnapshot.FLAG_PATTERN_INPUT;
                    }
                }
                slots.add(new MachineSnapshot.Slot(key, null, key == null ? 0 : s.stackSize, limit, flags));
            }
            out.add(new MachineSnapshot.Section(MachineSnapshot.SectionKind.INVENTORY,
                    LText.tr("ae2declogger.viewer.section.inventory"), slots));
            if (isCompletelyFull(inv)) {
                errors.add(LText.tr("ae2declogger.machine.inventory_full"));
            }
        }
        if (te instanceof IFluidHandler && used < budget) {
            FluidTankInfo[] tanks = ((IFluidHandler) te).getTankInfo(ForgeDirection.UNKNOWN);
            if (tanks != null && tanks.length > 0) {
                List<MachineSnapshot.Slot> slots = new ArrayList<>();
                for (int i = 0; i < tanks.length && used < budget; i++, used++) {
                    FluidStack f = tanks[i] != null ? tanks[i].fluid : null;
                    long cap = tanks[i] != null ? Math.max(0, tanks[i].capacity) : 0;
                    byte flags = 0;
                    LText name = null;
                    long amount = 0;
                    if (f != null && f.amount > 0) {
                        name = LText.literal(f.getLocalizedName());
                        amount = f.amount;
                        if (cap > 0 && amount >= cap) {
                            flags |= MachineSnapshot.FLAG_FULL;
                        }
                    }
                    slots.add(new MachineSnapshot.Slot(null, name, amount, cap, flags));
                }
                out.add(new MachineSnapshot.Section(MachineSnapshot.SectionKind.FLUIDS,
                        LText.tr("ae2declogger.viewer.section.fluids"), slots));
            }
        }
        return used;
    }

    public static Energy energy(LPos pos) {
        TileEntity te = tileAt(pos);
        return te == null ? null : rf(te);
    }

    /** RF (CoFH energy API), read by method name so the API need not be present. */
    private static Energy rf(TileEntity te) {
        try {
            Method stored = te.getClass().getMethod("getEnergyStored", ForgeDirection.class);
            Method max = te.getClass().getMethod("getMaxEnergyStored", ForgeDirection.class);
            long s = ((Number) stored.invoke(te, ForgeDirection.UNKNOWN)).longValue();
            long m = ((Number) max.invoke(te, ForgeDirection.UNKNOWN)).longValue();
            return m > 0 ? new Energy(s, m, "RF") : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- players

    private static MinecraftServer server() {
        return MinecraftServer.getServer();
    }

    public static UUID uuid(Object player) {
        return ((EntityPlayer) player).getUniqueID();
    }

    public static String playerName(Object player) {
        return ((EntityPlayer) player).getCommandSenderName();
    }

    public static List<Object> players() {
        MinecraftServer s = server();
        return s == null ? new ArrayList<>() : new ArrayList<Object>(s.getConfigurationManager().playerEntityList);
    }

    public static Object player(UUID id) {
        for (Object p : players()) {
            if (((EntityPlayer) p).getUniqueID().equals(id)) {
                return p;
            }
        }
        return null;
    }

    public static boolean isOp(Object player) {
        return ((EntityPlayer) player).canCommandSenderUseCommand(2, "");
    }

    public static String playerDim(Object player) {
        return dimOf(((EntityPlayer) player).worldObj);
    }

    public static double[] playerPos(Object player) {
        EntityPlayer p = (EntityPlayer) player;
        return new double[] { p.posX, p.posY, p.posZ };
    }

    public static Object openContainer(Object player) {
        return ((EntityPlayer) player).openContainer;
    }

    public static void closeContainer(Object player) {
        ((EntityPlayerMP) player).closeScreen();
    }

    /** The block the player looks at (computed here: the look vector is client-only on 1.7.10). */
    public static Hit lookedAt(Object player) {
        EntityPlayerMP p = (EntityPlayerMP) player;
        double reach = p.theItemInWorldManager.getBlockReachDistance();
        double eyeY = p.posY + 1.62 - (p.isSneaking() ? 0.08 : 0);
        Vec3 eyes = Vec3.createVectorHelper(p.posX, eyeY, p.posZ);
        float yaw = p.rotationYaw;
        float pitch = p.rotationPitch;
        double f1 = Math.cos(-yaw * 0.017453292 - Math.PI);
        double f2 = Math.sin(-yaw * 0.017453292 - Math.PI);
        double f3 = -Math.cos(-pitch * 0.017453292);
        double f4 = Math.sin(-pitch * 0.017453292);
        Vec3 end = eyes.addVector(f2 * f3 * reach, f4 * reach, f1 * f3 * reach);
        MovingObjectPosition hit = p.worldObj.rayTraceBlocks(eyes, end);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        return new Hit(new LPos(dimOf(p.worldObj), hit.blockX, hit.blockY, hit.blockZ), hit.sideHit);
    }

    /** A chat line (1.7.10 has no action bar); red for errors. */
    public static void message(Object player, LText text, boolean error) {
        IChatComponent c = component(text);
        if (error) {
            c.getChatStyle().setColor(EnumChatFormatting.RED);
        }
        ((EntityPlayer) player).addChatComponentMessage(c);
    }

    /** A chat component the client translates in its own language. */
    static IChatComponent component(LText text) {
        if (text.isLiteral()) {
            return new ChatComponentText(text.value());
        }
        LKey item = text.itemKey();
        if (item != null) {
            return new ChatComponentText(item.displayName());
        }
        Object[] args = text.args();
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof LText) {
                out[i] = component((LText) args[i]);
            } else if (args[i] instanceof LKey) {
                out[i] = ((LKey) args[i]).displayName();
            } else {
                out[i] = args[i];
            }
        }
        return new ChatComponentTranslation(text.value(), out);
    }

    private static NBTTagCompound modData(Object player) {
        NBTTagCompound root = ((EntityPlayer) player).getEntityData();
        NBTTagCompound persisted = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        root.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        NBTTagCompound mine = persisted.getCompoundTag("ae2declogger");
        persisted.setTag("ae2declogger", mine);
        return mine;
    }

    public static String getPlayerData(Object player, String key) {
        NBTTagCompound t = modData(player);
        return t.hasKey(key) ? t.getString(key) : null;
    }

    public static void setPlayerData(Object player, String key, String value) {
        NBTTagCompound t = modData(player);
        if (value == null) {
            t.removeTag(key);
        } else {
            t.setString(key, value);
        }
    }

    public static void send(Object player, byte[] payload) {
        Channel.sendTo(player, payload);
    }

    // ---------------------------------------------------------------- saved data

    /** Saved history, stored with the overworld. */
    public static final class SavedHistory extends WorldSavedData {
        public static final String NAME = "ae2declogger_history";
        final HistoryData data = new HistoryData(this::markDirty);

        public SavedHistory(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            data.load(tag.getByteArray("data"));
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            tag.setByteArray("data", data.save());
        }
    }

    public static HistoryData historyData() {
        WorldServer overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            return null;
        }
        MapStorage storage = overworld.mapStorage;
        SavedHistory saved = (SavedHistory) storage.loadData(SavedHistory.class, SavedHistory.NAME);
        if (saved == null) {
            saved = new SavedHistory(SavedHistory.NAME);
            storage.setData(SavedHistory.NAME, saved);
        }
        return saved.data;
    }
}
