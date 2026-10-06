package com.syhros.ae2declogger.compat.gtceu;

import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.MachineSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
//#if NEOFORGE
//$$ import net.neoforged.fml.ModList;
//#else
import net.minecraftforge.fml.ModList;
//#endif

import appeng.api.stacks.AEKey;

/**
 * Entry point for the optional GregTech CEu Modern integration. Every call is safe without GregTech installed and
 * survives a GregTech version whose API differs from the one this mod was built against: the first linkage error
 * switches the integration off and machines fall back to the generic reader.
 */
public final class GtCompat {

    private static final String MOD_ID = "gtceu";
    private static Boolean loaded;
    private static volatile boolean disabled;

    private GtCompat() {
    }

    public static boolean isActive() {
        if (loaded == null) {
            //#if MC == 1.20.1
            loaded = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
            //#else
            //$$ loaded = false; // only built against GregTech for 1.20.1 so far
            //#endif
        }
        return loaded && !disabled;
    }

    /** Status of the GT machine at {@code pos}, or null when it is not a GT machine (or GT is missing). */
    @Nullable
    public static GtMachineInfo inspect(Level level, BlockPos pos) {
        if (!isActive()) {
            return null;
        }
        try {
            return GtMachineReader.inspect(level, pos);
        } catch (LinkageError e) {
            disable(e);
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("GregTech inspection failed at {}", pos, e);
        }
        return null;
    }

    /** Controller positions for a GT multiblock part at {@code pos}. Empty for anything else. */
    public static List<BlockPos> controllersOf(Level level, BlockPos pos) {
        if (!isActive()) {
            return List.of();
        }
        try {
            return GtMachineReader.controllersOf(level, pos);
        } catch (LinkageError e) {
            disable(e);
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("GregTech controller lookup failed at {}", pos, e);
        }
        return List.of();
    }

    /**
     * Adds input/output sections for a GT machine to the viewer snapshot.
     *
     * @return true if GT handled the block, false to use the generic inventory reader
     */
    public static boolean fillSections(Level level, BlockPos pos, Set<AEKey> patternInputs, int maxSlots,
            List<MachineSnapshot.Section> out) {
        if (!isActive()) {
            return false;
        }
        try {
            return GtMachineReader.fillSections(level, pos, patternInputs, maxSlots, out);
        } catch (LinkageError e) {
            disable(e);
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("GregTech inventory read failed at {}", pos, e);
        }
        return false;
    }

    private static void disable(LinkageError e) {
        if (!disabled) {
            disabled = true;
            Ae2Declogger.LOG.warn("GregTech integration switched off: this GregTech version does not match the "
                    + "API AE2 Declogger was built against ({}). Machines will show generic info only.", e.toString());
        }
    }
}
