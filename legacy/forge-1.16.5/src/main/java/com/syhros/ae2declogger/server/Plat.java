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

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

/** Minecraft 1.16.5 access for the shared server code: worlds, blocks, inventories, players, saved data. */
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

    private static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    private static ServerWorld world(String dim) {
        MinecraftServer s = server();
        ResourceLocation id = ResourceLocation.tryParse(dim);
        if (s == null || id == null) {
            return null;
        }
        return s.getLevel(RegistryKey.create(Registry.DIMENSION_REGISTRY, id));
    }

    public static boolean dimensionExists(String dim) {
        return world(dim) != null;
    }

    public static String dimOf(World world) {
        return world.dimension().location().toString();
    }

    public static LPos posOf(TileEntity te) {
        World w = te.getLevel();
        if (w == null) {
            return null;
        }
        BlockPos p = te.getBlockPos();
        return new LPos(dimOf(w), p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos bp(LPos pos) {
        return new BlockPos(pos.x, pos.y, pos.z);
    }

    public static boolean isLoaded(LPos pos) {
        ServerWorld w = world(pos.dim);
        return w != null && w.hasChunkAt(bp(pos));
    }

    public static TileEntity tileAt(LPos pos) {
        ServerWorld w = world(pos.dim);
        if (w == null || !w.hasChunkAt(bp(pos))) {
            return null;
        }
        return w.getBlockEntity(bp(pos));
    }

    public static boolean hasTile(LPos pos) {
        return tileAt(pos) != null;
    }

    public static boolean isAir(LPos pos) {
        ServerWorld w = world(pos.dim);
        return w == null || w.isEmptyBlock(bp(pos));
    }

    /** The block's name as its item (so the client shows it in its own language), else its registry name. */
    public static LText blockName(LPos pos) {
        ServerWorld w = world(pos.dim);
        if (w == null) {
            return LText.literal("?");
        }
        BlockPos p = bp(pos);
        BlockState state = w.getBlockState(p);
        try {
            LKey key = LKey.of(state.getBlock().getCloneItemStack(w, p, state));
            if (key != null) {
                return LText.item(key);
            }
        } catch (RuntimeException e) {
            // fall through to the registry name
        }
        return LText.literal(String.valueOf(state.getBlock().getRegistryName()));
    }

    // ---------------------------------------------------------------- inventories

    private static IItemHandler items(TileEntity te, Direction side) {
        try {
            return te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IFluidHandler fluids(TileEntity te, Direction side) {
        try {
            return te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, side).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IEnergyStorage energy(TileEntity te, Direction side) {
        try {
            return te.getCapability(CapabilityEnergy.ENERGY, side).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static IItemHandler anyItems(TileEntity te) {
        IItemHandler h = items(te, null);
        for (int i = 0; h == null && i < 6; i++) {
            h = items(te, Direction.from3DDataValue(i));
        }
        return h;
    }

    private static IFluidHandler anyFluids(TileEntity te) {
        IFluidHandler h = fluids(te, null);
        for (int i = 0; h == null && i < 6; i++) {
            h = fluids(te, Direction.from3DDataValue(i));
        }
        return h;
    }

    /** Whether the block takes items or fluids on {@code side}. */
    public static boolean hasInventory(TileEntity te, int side) {
        Direction d = Direction.from3DDataValue(side);
        return items(te, d) != null || fluids(te, d) != null || te instanceof IInventory;
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
        if (fluids != null && fluids.getTanks() > 0 && used < budget) {
            List<MachineSnapshot.Slot> slots = new ArrayList<>();
            for (int i = 0; i < fluids.getTanks() && used < budget; i++, used++) {
                FluidStack f = fluids.getFluidInTank(i);
                long cap = Math.max(0, fluids.getTankCapacity(i));
                byte flags = 0;
                LText name = null;
                long amount = 0;
                if (!f.isEmpty()) {
                    name = LText.tr(f.getTranslationKey());
                    amount = f.getAmount();
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
        return ((PlayerEntity) player).getUUID();
    }

    public static String playerName(Object player) {
        return ((PlayerEntity) player).getGameProfile().getName();
    }

    public static List<Object> players() {
        MinecraftServer s = server();
        return s == null ? new ArrayList<>() : new ArrayList<Object>(s.getPlayerList().getPlayers());
    }

    public static Object player(UUID id) {
        MinecraftServer s = server();
        return s == null ? null : s.getPlayerList().getPlayer(id);
    }

    public static boolean isOp(Object player) {
        return ((PlayerEntity) player).hasPermissions(2);
    }

    public static String playerDim(Object player) {
        return dimOf(((PlayerEntity) player).level);
    }

    public static double[] playerPos(Object player) {
        PlayerEntity p = (PlayerEntity) player;
        return new double[] { p.getX(), p.getY(), p.getZ() };
    }

    public static Object openContainer(Object player) {
        return ((PlayerEntity) player).containerMenu;
    }

    public static void closeContainer(Object player) {
        ((ServerPlayerEntity) player).closeContainer();
    }

    public static Hit lookedAt(Object player) {
        ServerPlayerEntity p = (ServerPlayerEntity) player;
        double reach = p.getAttribute(ForgeMod.REACH_DISTANCE.get()).getValue();
        RayTraceResult hit = p.pick(reach, 1.0f, false);
        if (!(hit instanceof BlockRayTraceResult) || hit.getType() != RayTraceResult.Type.BLOCK) {
            return null;
        }
        BlockRayTraceResult b = (BlockRayTraceResult) hit;
        BlockPos pos = b.getBlockPos();
        return new Hit(new LPos(dimOf(p.level), pos.getX(), pos.getY(), pos.getZ()),
                b.getDirection().get3DDataValue());
    }

    /** Action-bar line; red for errors. */
    public static void message(Object player, LText text, boolean error) {
        IFormattableTextComponent c = component(text);
        if (error) {
            c = c.withStyle(TextFormatting.RED);
        }
        ((PlayerEntity) player).displayClientMessage(c, true);
    }

    /** A text component the client translates in its own language. */
    static IFormattableTextComponent component(LText text) {
        if (text.isLiteral()) {
            return new StringTextComponent(text.value());
        }
        LKey item = text.itemKey();
        if (item != null) {
            return item.stack().getHoverName().copy();
        }
        Object[] args = text.args();
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof LText) {
                out[i] = component((LText) args[i]);
            } else if (args[i] instanceof LKey) {
                ITextComponent name = ((LKey) args[i]).stack().getHoverName();
                out[i] = name;
            } else {
                out[i] = args[i];
            }
        }
        return new TranslationTextComponent(text.value(), out);
    }

    private static CompoundNBT modData(Object player) {
        CompoundNBT root = ((PlayerEntity) player).getPersistentData();
        CompoundNBT persisted = root.getCompound(PlayerEntity.PERSISTED_NBT_TAG);
        root.put(PlayerEntity.PERSISTED_NBT_TAG, persisted);
        CompoundNBT mine = persisted.getCompound("ae2declogger");
        persisted.put("ae2declogger", mine);
        return mine;
    }

    public static String getPlayerData(Object player, String key) {
        CompoundNBT t = modData(player);
        return t.contains(key) ? t.getString(key) : null;
    }

    public static void setPlayerData(Object player, String key, String value) {
        CompoundNBT t = modData(player);
        if (value == null) {
            t.remove(key);
        } else {
            t.putString(key, value);
        }
    }

    public static void send(Object player, byte[] payload) {
        Channel.sendTo(player, payload);
    }

    // ---------------------------------------------------------------- saved data

    /** Saved history, stored with the overworld. */
    public static final class SavedHistory extends WorldSavedData {
        public static final String NAME = "ae2declogger_history";
        final HistoryData data = new HistoryData(this::setDirty);

        public SavedHistory() {
            super(NAME);
        }

        @Override
        public void load(CompoundNBT tag) {
            data.load(tag.getByteArray("data"));
        }

        @Override
        public CompoundNBT save(CompoundNBT tag) {
            tag.putByteArray("data", data.save());
            return tag;
        }
    }

    public static HistoryData historyData() {
        MinecraftServer s = server();
        if (s == null) {
            return null;
        }
        return s.overworld().getDataStorage().computeIfAbsent(SavedHistory::new, SavedHistory.NAME).data;
    }
}
