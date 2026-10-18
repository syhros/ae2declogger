package com.syhros.ae2declogger.selftest;

import java.util.concurrent.Future;

import com.syhros.ae2declogger.data.LKey;
import com.syhros.ae2declogger.data.LPos;

import net.minecraft.block.Block;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.items.IItemHandler;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.IInterfaceHost;
import appeng.me.helpers.BaseActionSource;
import appeng.tile.storage.TileDrive;

/** 1.12.2 world access for the server self-test: blocks, cells, patterns, jobs. */
final class TestWorld {

    static final String CREATIVE_ENERGY_CELL = "appliedenergistics2:creative_energy_cell";
    static final String DRIVE = "appliedenergistics2:drive";
    static final String CRAFTING_STORAGE_1K = "appliedenergistics2:crafting_storage_1k";
    static final String INTERFACE = "appliedenergistics2:interface";
    static final String CHEST = "minecraft:chest";
    static final String GRAVEL = "minecraft:gravel";
    static final String COBBLE = "minecraft:cobblestone";
    static final String STONE = "minecraft:stone";

    private TestWorld() {
    }

    private static WorldServer overworld() {
        return DimensionManager.getWorld(0);
    }

    private static BlockPos bp(LPos p) {
        return new BlockPos(p.x, p.y, p.z);
    }

    static LPos origin() {
        WorldServer w = overworld();
        BlockPos spawn = w.getSpawnPoint();
        BlockPos top = w.getHeight(spawn.add(3, 0, 3));
        return new LPos("0", top.getX(), top.getY(), top.getZ());
    }

    static void place(LPos pos, String id) {
        Block block = Block.REGISTRY.getObject(new ResourceLocation(id));
        overworld().setBlockState(bp(pos), block.getDefaultState());
    }

    private static ItemStack stack(String id, int count) {
        Item item = Item.REGISTRY.getObject(new ResourceLocation(id));
        return new ItemStack(item, count);
    }

    static LKey key(String id) {
        return LKey.of(stack(id, 1));
    }

    private static TileEntity tile(LPos pos) {
        return overworld().getTileEntity(bp(pos));
    }

    static void insertCell(LPos drive) {
        IItemHandler inv = ((TileDrive) tile(drive)).getInternalInventory();
        inv.insertItem(0, stack("appliedenergistics2:storage_cell_1k", 1), false);
    }

    /** A processing pattern (1 input -> 1 output) in the interface's next free pattern slot; blocking mode off. */
    static void addPattern(LPos iface, String in, String out) {
        ItemStack pattern = stack("appliedenergistics2:encoded_pattern", 1);
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList ins = new NBTTagList();
        ins.appendTag(stack(in, 1).writeToNBT(new NBTTagCompound()));
        NBTTagList outs = new NBTTagList();
        outs.appendTag(stack(out, 1).writeToNBT(new NBTTagCompound()));
        tag.setTag("in", ins);
        tag.setTag("out", outs);
        tag.setBoolean("crafting", false);
        tag.setBoolean("substitute", false);
        pattern.setTagCompound(tag);
        IInterfaceHost host = (IInterfaceHost) tile(iface);
        IItemHandler patterns = host.getInterfaceDuality().getPatterns();
        for (int i = 0; i < patterns.getSlots(); i++) {
            if (patterns.getStackInSlot(i).isEmpty()) {
                patterns.insertItem(i, pattern, false);
                break;
            }
        }
        host.getInterfaceDuality().getConfigManager().putSetting(Settings.BLOCK, YesNo.NO);
    }

    private static IItemStorageChannel items() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }

    private static IAEItemStack ae(String id, long amount) {
        IAEItemStack s = items().createStack(stack(id, 1));
        s.setStackSize(amount);
        return s;
    }

    static boolean ready(IGrid grid) {
        IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        return energy.isNetworkPowered() && !crafting.getCpus().isEmpty() && !crafting.getCraftingFor(ae(STONE, 1), null, 0, overworld()).isEmpty();
    }

    static void inject(IGrid grid, String id, long amount) {
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        storage.getInventory(items()).injectItems(ae(id, amount), Actionable.MODULATE, new BaseActionSource());
    }

    static Future<ICraftingJob> beginCrafting(IGrid grid, String id, long amount) {
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        return crafting.beginCraftingJob(overworld(), grid, new BaseActionSource(), ae(id, amount), null);
    }

    /** Null when the job started, else why not. */
    static String submit(IGrid grid, ICraftingJob job) {
        if (job.isSimulation()) {
            return "plan is missing items";
        }
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        ICraftingLink link = crafting.submitJob(job, null, null, false, new BaseActionSource());
        return link != null ? null : "submitJob returned no link";
    }

    static boolean anyBusy(IGrid grid) {
        for (ICraftingCPU cpu : ((ICraftingGrid) grid.getCache(ICraftingGrid.class)).getCpus()) {
            if (cpu.isBusy()) {
                return true;
            }
        }
        return false;
    }

    static int count(LPos chest, String id) {
        TileEntity te = tile(chest);
        if (!(te instanceof IInventory)) {
            return 0;
        }
        Item item = Item.REGISTRY.getObject(new ResourceLocation(id));
        IInventory inv = (IInventory) te;
        int n = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.getItem() == item) {
                n += s.getCount();
            }
        }
        return n;
    }

    static void stopServer() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            server.initiateShutdown();
        }
    }
}
