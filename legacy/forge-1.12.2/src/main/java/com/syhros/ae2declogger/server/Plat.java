package com.syhros.ae2declogger.server;

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

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

/** Minecraft 1.12.2 access for the shared server code: worlds, blocks, inventories, players, saved data. */
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

    static MinecraftServer server() {
        return FMLCommonHandler.instance().getMinecraftServerInstance();
    }

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
        return String.valueOf(world.provider.getDimension());
    }

    public static LPos posOf(TileEntity te) {
        World w = te.getWorld();
        if (w == null) {
            return null;
        }
        BlockPos p = te.getPos();
        return new LPos(dimOf(w), p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos bp(LPos pos) {
        return new BlockPos(pos.x, pos.y, pos.z);
    }

    public static boolean isLoaded(LPos pos) {
        WorldServer w = world(pos.dim);
        return w != null && w.isBlockLoaded(bp(pos));
    }

    public static TileEntity tileAt(LPos pos) {
        WorldServer w = world(pos.dim);
        if (w == null || !w.isBlockLoaded(bp(pos))) {
            return null;
        }
        return w.getTileEntity(bp(pos));
    }

    public static boolean hasTile(LPos pos) {
        return tileAt(pos) != null;
    }

    public static boolean isAir(LPos pos) {
        WorldServer w = world(pos.dim);
        return w == null || w.isAirBlock(bp(pos));
    }

    /** The block's name as its item (so the client shows it in its own language), else its registry name. */
    public static LText blockName(LPos pos) {
        WorldServer w = world(pos.dim);
        if (w == null) {
            return LText.literal("?");
        }
        BlockPos p = bp(pos);
        IBlockState state = w.getBlockState(p);
        ItemStack stack = ItemStack.EMPTY;
        try {
            stack = state.getBlock().getItem(w, p, state);
        } catch (RuntimeException e) {
            stack = ItemStack.EMPTY;
        }
        LKey key = LKey.of(stack);
        if (key != null) {
            return LText.item(key);
        }
        return LText.literal(String.valueOf(state.getBlock().getRegistryName()));
    }

    // ---------------------------------------------------------------- inventories

    private static IItemHandler items(TileEntity te, EnumFacing side) {
        try {
            if (te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side)) {
                return te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side);
            }
        } catch (RuntimeException e) {
            // treat as no inventory
        }
        return null;
    }

    private static IFluidHandler fluids(TileEntity te, EnumFacing side) {
        try {
            if (te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side)) {
                return te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side);
            }
        } catch (RuntimeException e) {
            // treat as no tank
        }
        return null;
    }

    private static IEnergyStorage energy(TileEntity te, EnumFacing side) {
        try {
            if (te.hasCapability(CapabilityEnergy.ENERGY, side)) {
                return te.getCapability(CapabilityEnergy.ENERGY, side);
            }
        } catch (RuntimeException e) {
            // treat as no energy
        }
        return null;
    }

    /** Items without naming a side, else from the first side that has any. */
    private static IItemHandler anyItems(TileEntity te) {
        IItemHandler h = items(te, null);
        for (int i = 0; h == null && i < 6; i++) {
            h = items(te, EnumFacing.byIndex(i));
        }
        return h;
    }

    private static IFluidHandler anyFluids(TileEntity te) {
        IFluidHandler h = fluids(te, null);
        for (int i = 0; h == null && i < 6; i++) {
            h = fluids(te, EnumFacing.byIndex(i));
        }
        return h;
    }

    /** Whether the block takes items or fluids on {@code side} (or is a plain inventory). */
    public static boolean hasInventory(TileEntity te, int side) {
        EnumFacing f = EnumFacing.byIndex(side);
        return items(te, f) != null || fluids(te, f) != null || te instanceof IInventory;
    }

    public static Problem genericProblem(LPos pos) {
        TileEntity te = tileAt(pos);
        if (te == null) {
            return null;
        }
        IItemHandler items = anyItems(te);
        if (items != null && isCompletelyFull(items)) {
            return new Problem(BlockReason.MACHINE_INPUT_FULL, LText.tr("ae2declogger.machine.inventory_full"));
        }
        IEnergyStorage fe = energy(te, null);
        if (fe != null && fe.getMaxEnergyStored() > 0 && fe.getEnergyStored() <= 0) {
            return new Problem(BlockReason.MACHINE_NO_POWER, LText.tr("ae2declogger.compat.energy_empty"));
        }
        return null;
    }

    private static boolean isCompletelyFull(IItemHandler h) {
        int slots = h.getSlots();
        if (slots == 0) {
            return false;
        }
        for (int i = 0; i < slots; i++) {
            ItemStack s = h.getStackInSlot(i);
            if (s.isEmpty() || s.getCount() < Math.min(h.getSlotLimit(i), s.getMaxStackSize())) {
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
        IItemHandler items = anyItems(te);
        if (items != null && items.getSlots() > 0) {
            List<MachineSnapshot.Slot> slots = new ArrayList<>();
            for (int i = 0; i < items.getSlots() && used < budget; i++, used++) {
                ItemStack s = items.getStackInSlot(i);
                long limit = Math.max(1, items.getSlotLimit(i));
                LKey key = LKey.of(s);
                byte flags = 0;
                if (key != null) {
                    limit = Math.min(limit, s.getMaxStackSize());
                    if (s.getCount() >= limit) {
                        flags |= MachineSnapshot.FLAG_FULL;
                    }
                    if (patternInputs.contains(key)) {
                        flags |= MachineSnapshot.FLAG_PATTERN_INPUT;
                    }
                }
                slots.add(new MachineSnapshot.Slot(key, null, key == null ? 0 : s.getCount(), limit, flags));
            }
            out.add(new MachineSnapshot.Section(MachineSnapshot.SectionKind.INVENTORY,
                    LText.tr("ae2declogger.viewer.section.inventory"), slots));
            if (isCompletelyFull(items)) {
                errors.add(LText.tr("ae2declogger.machine.inventory_full"));
            }
        }
        IFluidHandler fluids = anyFluids(te);
        IFluidTankProperties[] tanks = fluids != null ? fluids.getTankProperties() : null;
        if (tanks != null && tanks.length > 0 && used < budget) {
            List<MachineSnapshot.Slot> slots = new ArrayList<>();
            for (int i = 0; i < tanks.length && used < budget; i++, used++) {
                FluidStack f = tanks[i].getContents();
                long cap = Math.max(0, tanks[i].getCapacity());
                byte flags = 0;
                LText name = null;
                long amount = 0;
                if (f != null && f.amount > 0) {
                    name = LText.tr(f.getUnlocalizedName());
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
        return used;
    }

    public static Energy energy(LPos pos) {
        TileEntity te = tileAt(pos);
        IEnergyStorage fe = te != null ? energy(te, null) : null;
        return fe != null && fe.getMaxEnergyStored() > 0
                ? new Energy(fe.getEnergyStored(), fe.getMaxEnergyStored(), "FE") : null;
    }

    // ---------------------------------------------------------------- players

    public static UUID uuid(Object player) {
        return ((EntityPlayer) player).getUniqueID();
    }

    public static String playerName(Object player) {
        return ((EntityPlayer) player).getName();
    }

    public static List<Object> players() {
        MinecraftServer s = server();
        return s == null ? new ArrayList<>() : new ArrayList<Object>(s.getPlayerList().getPlayers());
    }

    public static Object player(UUID id) {
        MinecraftServer s = server();
        return s == null ? null : s.getPlayerList().getPlayerByUUID(id);
    }

    public static boolean isOp(Object player) {
        EntityPlayerMP p = (EntityPlayerMP) player;
        return p.server.getPlayerList().canSendCommands(p.getGameProfile());
    }

    public static String playerDim(Object player) {
        return dimOf(((EntityPlayer) player).world);
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

    public static Hit lookedAt(Object player) {
        EntityPlayerMP p = (EntityPlayerMP) player;
        double reach = p.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue();
        Vec3d eyes = new Vec3d(p.posX, p.posY + p.getEyeHeight(), p.posZ);
        Vec3d look = p.getLook(1.0f);
        Vec3d end = eyes.add(look.x * reach, look.y * reach, look.z * reach);
        RayTraceResult hit = p.world.rayTraceBlocks(eyes, end, false, true, false);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return null;
        }
        BlockPos b = hit.getBlockPos();
        return new Hit(new LPos(dimOf(p.world), b.getX(), b.getY(), b.getZ()), hit.sideHit.getIndex());
    }

    /** Chat or action-bar line; red for errors. */
    public static void message(Object player, LText text, boolean error) {
        ITextComponent c = component(text);
        if (error) {
            c.getStyle().setColor(TextFormatting.RED);
        }
        ((EntityPlayer) player).sendStatusMessage(c, true);
    }

    /** A chat component the client translates in its own language. */
    static ITextComponent component(LText text) {
        if (text.isLiteral()) {
            return new TextComponentString(text.value());
        }
        LKey item = text.itemKey();
        if (item != null) {
            return item.stack().getTextComponent();
        }
        Object[] args = text.args();
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof LText) {
                out[i] = component((LText) args[i]);
            } else if (args[i] instanceof LKey) {
                out[i] = ((LKey) args[i]).stack().getTextComponent();
            } else {
                out[i] = args[i];
            }
        }
        return new TextComponentTranslation(text.value(), out);
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
        public NBTTagCompound writeToNBT(NBTTagCompound tag) {
            tag.setByteArray("data", data.save());
            return tag;
        }
    }

    public static HistoryData historyData() {
        WorldServer overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            return null;
        }
        MapStorage storage = overworld.getMapStorage();
        SavedHistory saved = (SavedHistory) storage.getOrLoadData(SavedHistory.class, SavedHistory.NAME);
        if (saved == null) {
            saved = new SavedHistory(SavedHistory.NAME);
            storage.setData(SavedHistory.NAME, saved);
        }
        return saved.data;
    }
}
