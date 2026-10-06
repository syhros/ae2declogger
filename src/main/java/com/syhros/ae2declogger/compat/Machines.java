package com.syhros.ae2declogger.compat;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.compat.gtceu.GtCompat;
import com.syhros.ae2declogger.compat.gtceu.GtMachineInfo;
import com.syhros.ae2declogger.compat.mods.CreateReader;
import com.syhros.ae2declogger.compat.mods.EnderIoReader;
import com.syhros.ae2declogger.compat.mods.ImmersiveEngineeringReader;
import com.syhros.ae2declogger.compat.mods.IndustrialForegoingReader;
import com.syhros.ae2declogger.compat.mods.IronFurnacesReader;
import com.syhros.ae2declogger.compat.mods.MekanismReader;
import com.syhros.ae2declogger.compat.mods.ModernIndustrializationReader;
import com.syhros.ae2declogger.compat.mods.OritechReader;
import com.syhros.ae2declogger.compat.mods.PowahReader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
//#if NEOFORGE
//$$ import net.neoforged.fml.ModList;
//#else
import net.minecraftforge.fml.ModList;
//#endif

/**
 * Machine status from the mod integrations: GregTech CEu (compiled against), then the reflection readers in
 * {@code compat.mods}. A reader whose mod is missing is skipped; one whose names no longer match the installed mod
 * switches itself off with one log line and machines fall back to the generic inventory and energy view.
 */
public final class Machines {

    private static final List<Entry> READERS = List.of(new Entry(new MekanismReader()),
            new Entry(new EnderIoReader()), new Entry(new ImmersiveEngineeringReader()),
            new Entry(new ModernIndustrializationReader()), new Entry(new IndustrialForegoingReader()),
            new Entry(new CreateReader()), new Entry(new PowahReader()), new Entry(new OritechReader()),
            new Entry(new IronFurnacesReader()));

    private Machines() {
    }

    private static final class Entry {
        final ModReader reader;
        Boolean loaded;
        volatile boolean disabled;

        Entry(ModReader reader) {
            this.reader = reader;
        }

        boolean active() {
            if (loaded == null) {
                loaded = isLoaded(reader.modId());
            }
            return loaded && !disabled;
        }
    }

    public static boolean isLoaded(String modId) {
        return ModList.get() != null && ModList.get().isLoaded(modId);
    }

    /** Status of the machine at {@code pos} from whichever integration knows it, or null. */
    @Nullable
    public static MachineInfo inspect(ServerLevel level, BlockPos pos) {
        GtMachineInfo gt = GtCompat.inspect(level, pos);
        if (gt != null) {
            return new MachineInfo("gtceu", gt.name(), gt.status(), gt.problem(), gt.detail(), gt.progress(),
                    gt.duration(), gt.controllerPos(), gt.euCapacity() > 0 ? gt.euStored() : 0, gt.euCapacity(),
                    gt.euCapacity() > 0 ? "EU" : "", gt.tier(), gt.errors());
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : read(level, pos, be);
    }

    /** Reads a block entity with the mod readers only (used by readers that resolve a multiblock controller). */
    @Nullable
    public static MachineInfo read(ServerLevel level, BlockPos pos, BlockEntity be) {
        for (Entry e : READERS) {
            if (!e.active()) {
                continue;
            }
            try {
                MachineInfo info = e.reader.read(level, pos, be);
                if (info != null) {
                    return info;
                }
            } catch (ReflectiveOperationException | LinkageError | ClassCastException ex) {
                disable(e, ex);
            } catch (RuntimeException ex) {
                Ae2Declogger.LOG.debug("{} machine read failed at {}", e.reader.modId(), pos, ex);
            }
        }
        return null;
    }

    private static void disable(Entry e, Throwable ex) {
        if (!e.disabled) {
            e.disabled = true;
            Ae2Declogger.LOG.warn("{} machine status switched off: this version of the mod does not have the names "
                    + "AE2 Declogger reads ({}). Its machines show generic info only.", e.reader.modId(), ex.toString());
        }
    }

    /** Self-test: for every loaded mod with a reader, the names it uses that the installed version lacks. */
    public static List<String> missingNames() {
        List<String> out = new ArrayList<>();
        for (Entry e : READERS) {
            if (isLoaded(e.reader.modId())) {
                for (String name : Refl.missing(e.reader.names())) {
                    out.add(e.reader.modId() + ": " + name);
                }
            }
        }
        return out;
    }

    /** Self-test: the mod ids that have a reader and are loaded, and whether each is still switched on. */
    public static List<String> activeReaders() {
        List<String> out = new ArrayList<>();
        for (Entry e : READERS) {
            if (isLoaded(e.reader.modId())) {
                out.add(e.reader.modId() + (e.disabled ? " (switched off)" : ""));
            }
        }
        return out;
    }
}
