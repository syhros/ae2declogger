package com.syhros.ae2declogger.gametest;

import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;

/** GameTestHelper.assertTrue/assertFalse, which only exist from 1.19.4. */
final class Asserts {

    private Asserts() {
    }

    static void isTrue(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    static void isFalse(GameTestHelper helper, boolean condition, String message) {
        isTrue(helper, !condition, message);
    }

    /** Cancels the job on a crafting CPU (ICraftingCPU.cancelJob() exists from AE2 15). */
    static void cancel(appeng.api.networking.crafting.ICraftingCPU cpu) {
        //#if MC >= 1.20
        cpu.cancelJob();
        //#else
        //$$ ((appeng.me.cluster.implementations.CraftingCPUCluster) cpu).cancel();
        //#endif
    }
}
