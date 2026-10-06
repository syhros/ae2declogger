package com.syhros.ae2declogger.gametest;

import java.lang.reflect.Field;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * A CPU shaped like Advanced AE's quantum computer threads ({@code AdvCraftingCPU}): not an AE2 cluster, a field
 * {@code cluster} for the multiblock, and its own crafting logic class with the same method names as AE2's and a
 * {@code job} field. It wraps a real AE2 CPU so the Declogger's reflective reader can be compared with AE2's own.
 */
final class FakeAddonCpu implements ICraftingCPU {

    private final CraftingCPUCluster cluster;
    public final Logic craftingLogic;

    FakeAddonCpu(CraftingCPUCluster cluster) {
        this.cluster = cluster;
        this.craftingLogic = new Logic(cluster.craftingLogic);
    }

    @Override
    public boolean isBusy() {
        return craftingLogic.hasJob();
    }

    @Override
    @Nullable
    public CraftingJobStatus getJobStatus() {
        return cluster.getJobStatus();
    }

    //#if MC >= 1.20
    @Override
    public void cancelJob() {
        cluster.cancelJob();
    }
    //#endif

    @Override
    public long getAvailableStorage() {
        return cluster.getAvailableStorage();
    }

    @Override
    public int getCoProcessors() {
        return cluster.getCoProcessors();
    }

    @Override
    @Nullable
    public Component getName() {
        return cluster.getName();
    }

    @Override
    public CpuSelectionMode getSelectionMode() {
        return cluster.getSelectionMode();
    }

    public boolean isActive() {
        return cluster.isActive();
    }

    /** Same public methods as AE2's CraftingCpuLogic, but a different class. */
    public static final class Logic {
        private final CraftingCpuLogic real;
        @SuppressWarnings("unused") // read by the Declogger through reflection, like Advanced AE's
        private Object job;

        Logic(CraftingCpuLogic real) {
            this.real = real;
            syncJob();
        }

        void syncJob() {
            try {
                Field f = CraftingCpuLogic.class.getDeclaredField("job");
                f.setAccessible(true);
                job = f.get(real);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        public boolean hasJob() {
            return real.hasJob();
        }

        @Nullable
        public ICraftingLink getLastLink() {
            return real.getLastLink();
        }

        //#if MC >= 1.20
        public long getLastModifiedOnTick() {
            return real.getLastModifiedOnTick();
        }
        //#endif

        public ElapsedTimeTracker getElapsedTimeTracker() {
            return real.getElapsedTimeTracker();
        }

        public void getAllItems(KeyCounter out) {
            real.getAllItems(out);
        }

        public long getStored(AEKey key) {
            return real.getStored(key);
        }

        public long getWaitingFor(AEKey key) {
            return real.getWaitingFor(key);
        }

        public long getPendingOutputs(AEKey key) {
            return real.getPendingOutputs(key);
        }
    }
}
