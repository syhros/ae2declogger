package com.syhros.ae2declogger.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.data.GridSummary;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.item.DecloggerItem;
import com.syhros.ae2declogger.logic.AlertTracker;
import com.syhros.ae2declogger.logic.DeltaTracker;
import com.syhros.ae2declogger.logic.JobState;
import com.syhros.ae2declogger.network.DeclogNetwork;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.AEBaseMenu;

/**
 * Server side of the mod: who is watching which network, sending them updates, background stall alerts, the machine
 * viewer and teleports. Everything runs on the server thread.
 */
public final class DecloggerServer {

    private static final int MAX_ALLOWED_POSITIONS = 4096;

    private static final Map<UUID, Subscription> MONITORS = new HashMap<>();
    private static final Map<IGrid, GridTracker> TRACKERS = new WeakHashMap<>();
    private static final Map<UUID, AlertState> ALERTS = new HashMap<>();
    private static long ticks;

    private static final class Subscription {
        final GridSource source;
        final DeltaTracker<Long, JobView> delta = new DeltaTracker<>();
        final Set<GlobalPos> allowed = new HashSet<>();
        @Nullable
        IGrid grid;
        long historyVersionSent = -1;
        int historyEpochSent = -1;
        @Nullable
        GridSummary lastSummary;
        boolean lastAlerts;

        Subscription(GridSource source) {
            this.source = source;
        }
    }

    private static final class AlertState {
        final AlertTracker<Long> tracker = new AlertTracker<>();
        @Nullable
        IGrid grid;
    }

    private DecloggerServer() {
    }

    // ---------------------------------------------------------------- monitor

    /** Keybind (linked item in inventory) or the button in an AE2 terminal. */
    public static void openFromRequest(ServerPlayer player, boolean fromTerminal) {
        if (fromTerminal) {
            if (player.containerMenu instanceof AEBaseMenu menu && menu.getTarget() instanceof IActionHost host) {
                IGridNode node = host.getActionableNode();
                if (node != null) {
                    Component title = Component.translatable("ae2declogger.monitor.title_terminal");
                    // Close the terminal first so the client is not left with a dangling container.
                    player.closeContainer();
                    openMonitor(player, GridSource.ofNode(node, title));
                    return;
                }
            }
            player.displayClientMessage(Component.translatable("ae2declogger.error.no_terminal")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        ItemStack stack = DecloggerItem.findLinked(player, false);
        if (stack.isEmpty()) {
            player.displayClientMessage(Component.translatable("ae2declogger.error.no_item")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        openMonitor(player, DecloggerItem.sourceOf(stack, null));
    }

    public static void openMonitor(ServerPlayer player, GridSource source) {
        GridSource.Resolved resolved = source.resolve(player);
        if (resolved.error() != null) {
            player.displayClientMessage(resolved.error().copy().withStyle(ChatFormatting.RED), true);
            return;
        }
        Subscription sub = new Subscription(source);
        MONITORS.put(player.getUUID(), sub);
        DeclogNetwork.sendTo(player, new DeclogNetwork.OpenMonitorScreen(source.title()));
        sendUpdate(player, sub, resolved.grid(), true);
    }

    public static void closeMonitor(ServerPlayer player) {
        MONITORS.remove(player.getUUID());
    }

    public static void onLogout(ServerPlayer player) {
        MONITORS.remove(player.getUUID());
        ALERTS.remove(player.getUUID());
    }

    public static void onServerStopped() {
        MONITORS.clear();
        ALERTS.clear();
        TRACKERS.clear();
        ticks = 0;
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        int interval = DeclogConfig.COMMON.updateIntervalTicks.get();
        if (!MONITORS.isEmpty() && ticks % interval == 0) {
            for (Map.Entry<UUID, Subscription> e : new ArrayList<>(MONITORS.entrySet())) {
                ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
                if (player == null) {
                    MONITORS.remove(e.getKey());
                    continue;
                }
                try {
                    sendUpdate(player, e.getValue(), null, false);
                } catch (RuntimeException ex) {
                    Ae2Declogger.LOG.error("AE2 Declogger update failed for {}", player.getGameProfile().getName(),
                            ex);
                    MONITORS.remove(e.getKey());
                    DeclogNetwork.sendTo(player, new DeclogNetwork.MonitorClosed(
                            Component.translatable("ae2declogger.error.internal")));
                }
            }
        }
        if (ticks % DeclogConfig.COMMON.alertIntervalTicks.get() == 0) {
            try {
                runAlerts(server);
            } catch (RuntimeException ex) {
                Ae2Declogger.LOG.error("AE2 Declogger alert check failed", ex);
            }
        }
    }

    private static void sendUpdate(ServerPlayer player, Subscription sub, @Nullable IGrid known, boolean full) {
        IGrid grid = known;
        if (grid == null) {
            GridSource.Resolved resolved = sub.source.resolve(player);
            if (resolved.error() != null) {
                MONITORS.remove(player.getUUID());
                DeclogNetwork.sendTo(player, new DeclogNetwork.MonitorClosed(resolved.error()));
                return;
            }
            grid = resolved.grid();
        }
        if (sub.grid != grid) {
            full = true;
            sub.grid = grid;
        }
        if (full) {
            sub.delta.reset();
            sub.lastSummary = null;
        }
        GridTracker tracker = tracker(grid);
        GridAnalysis analysis = analysis(grid, tracker, player.server);

        Map<Long, JobView> current = new LinkedHashMap<>();
        for (JobView job : analysis.jobs()) {
            current.put(job.id(), job);
        }
        DeltaTracker.Delta<Long, JobView> delta = sub.delta.update(current);

        boolean historyReset = full || sub.historyEpochSent != tracker.historyEpoch();
        List<HistoryEntry> history = tracker.historySince(historyReset ? -1 : sub.historyVersionSent);
        sub.historyVersionSent = tracker.historyVersion();
        sub.historyEpochSent = tracker.historyEpoch();

        if (sub.allowed.size() > MAX_ALLOWED_POSITIONS) {
            sub.allowed.clear();
        }
        sub.allowed.addAll(analysis.machines());

        GridSummary summary = new GridSummary(sub.source.title(), analysis.cpus(), analysis.busyCpus(),
                analysis.storedPower(), analysis.maxPower(), analysis.powered(), System.currentTimeMillis(),
                canTeleport(player));
        boolean alerts = !DecloggerItem.findLinked(player, true).isEmpty();

        boolean summaryChanged = sub.lastSummary == null || !sameIgnoringTime(sub.lastSummary, summary);
        if (!full && delta.isEmpty() && history.isEmpty() && !historyReset && !summaryChanged
                && alerts == sub.lastAlerts) {
            return;
        }
        sub.lastSummary = summary;
        sub.lastAlerts = alerts;

        long[] removed = delta.removed().stream().mapToLong(Long::longValue).toArray();
        long[] order = current.keySet().stream().mapToLong(Long::longValue).toArray();
        DeclogNetwork.sendTo(player, new DeclogNetwork.MonitorUpdate(full, summary,
                new ArrayList<>(delta.changed().values()), removed, order, history, historyReset, alerts));
    }

    private static boolean sameIgnoringTime(GridSummary a, GridSummary b) {
        return a.cpus() == b.cpus() && a.busyCpus() == b.busyCpus() && a.powered() == b.powered()
                && a.canTeleport() == b.canTeleport() && Objects.equals(a.networkName(), b.networkName())
                && Math.abs(a.storedPower() - b.storedPower()) < 1.0 && Math.abs(a.maxPower() - b.maxPower()) < 1.0;
    }

    static GridTracker tracker(IGrid grid) {
        GridTracker tracker = TRACKERS.computeIfAbsent(grid,
                g -> new GridTracker(DeclogConfig.COMMON.historySize.get()));
        tracker.setHistorySize(DeclogConfig.COMMON.historySize.get());
        tracker.lastUsedMs = System.currentTimeMillis();
        return tracker;
    }

    private static GridAnalysis analysis(IGrid grid, GridTracker tracker, MinecraftServer server) {
        long tick = server.getTickCount();
        if (tracker.cached != null && tracker.cachedTick == tick) {
            return tracker.cached;
        }
        var cfg = DeclogConfig.COMMON;
        GridAnalysis analysis = GridAnalyzer.analyze(grid, tracker, System.currentTimeMillis(), cfg.thresholds(),
                new GridAnalyzer.Limits(cfg.maxJobs.get(), cfg.maxStepsPerJob.get(), cfg.maxMachinesPerStep.get()));
        tracker.cached = analysis;
        tracker.cachedTick = tick;
        return analysis;
    }

    // ---------------------------------------------------------------- alerts

    private static void runAlerts(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack stack = DecloggerItem.findLinked(player, true);
            if (stack.isEmpty()) {
                ALERTS.remove(player.getUUID());
                continue;
            }
            GridSource.Resolved resolved = DecloggerItem.sourceOf(stack, null).resolve(player);
            if (resolved.grid() == null) {
                continue;
            }
            IGrid grid = resolved.grid();
            GridAnalysis analysis = analysis(grid, tracker(grid), server);
            AlertState state = ALERTS.computeIfAbsent(player.getUUID(), u -> new AlertState());
            if (state.grid != grid) {
                state.tracker.reset();
                state.grid = grid;
            }
            Map<Long, JobState> states = new HashMap<>();
            Map<Long, JobView> byId = new HashMap<>();
            for (JobView job : analysis.jobs()) {
                states.put(job.id(), job.state());
                byId.put(job.id(), job);
            }
            for (Long id : state.tracker.update(states)) {
                JobView job = byId.get(id);
                if (job != null) {
                    DeclogNetwork.sendTo(player, new DeclogNetwork.Alert(job.output(), job.amount(), job.state(),
                            job.reason(), job.detail(), job.primaryMachine()));
                }
            }
        }
    }

    public static void toggleAlerts(ServerPlayer player) {
        ItemStack stack = DecloggerItem.findLinked(player, false);
        if (stack.isEmpty()) {
            player.displayClientMessage(Component.translatable("ae2declogger.error.no_item")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        boolean enabled = !DecloggerItem.alertsEnabled(stack);
        DecloggerItem.setAlerts(stack, enabled);
        player.displayClientMessage(Component.translatable(
                enabled ? "ae2declogger.item.alerts_on" : "ae2declogger.item.alerts_off"), true);
        Subscription sub = MONITORS.get(player.getUUID());
        if (sub != null) {
            sendUpdate(player, sub, null, false);
        }
    }

    public static void clearHistory(ServerPlayer player) {
        Subscription sub = MONITORS.get(player.getUUID());
        if (sub == null || sub.grid == null) {
            return;
        }
        tracker(sub.grid).clearHistory();
        sendUpdate(player, sub, null, false);
    }

    // ---------------------------------------------------------------- machine viewer

    /**
     * Sends a read-only snapshot of the block at {@code pos}. Allowed for blocks listed in the player's open monitor,
     * blocks within reach, and for operators.
     *
     * @param direct true when the player shift-clicked the block with the item (range check applies)
     */
    public static void requestMachine(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos, boolean open,
            boolean direct) {
        ServerLevel level = player.server.getLevel(dimension);
        if (level == null) {
            return;
        }
        GlobalPos target = GlobalPos.of(dimension, pos);
        Subscription sub = MONITORS.get(player.getUUID());
        boolean listed = sub != null && sub.allowed.contains(target);
        int range = DeclogConfig.COMMON.machineViewerRange.get();
        boolean near = player.level().dimension().equals(dimension)
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= (double) range * range;
        if (!listed && !near && !player.hasPermissions(2)) {
            if (open) {
                player.displayClientMessage(Component.translatable("ae2declogger.error.viewer_not_allowed")
                        .withStyle(ChatFormatting.RED), true);
            }
            return;
        }
        Set<AEKey> inputs = sub != null && sub.grid != null ? patternInputsFor(sub.grid, target) : Set.of();
        MachineSnapshot snapshot = MachineInspector.snapshot(level, pos, inputs,
                DeclogConfig.COMMON.machineViewerMaxSlots.get());
        DeclogNetwork.sendTo(player, new DeclogNetwork.MachineData(snapshot, open));
    }

    /** Inputs of every pattern whose provider pushes into {@code target}; used to highlight slots in the viewer. */
    private static Set<AEKey> patternInputsFor(IGrid grid, GlobalPos target) {
        Set<AEKey> result = new HashSet<>();
        try {
            ProviderIndex index = ProviderIndex.build(grid);
            for (IGridNode node : grid.getNodes()) {
                var provider = node.getService(appeng.api.networking.crafting.ICraftingProvider.class);
                if (provider == null) {
                    continue;
                }
                ProviderIndex.ProviderInfo info = index.get(provider);
                if (info == null || !info.level().dimension().equals(target.dimension())
                        || !(info.targets().contains(target.pos()) || info.pos().equals(target.pos()))) {
                    continue;
                }
                for (IPatternDetails pattern : provider.getAvailablePatterns()) {
                    for (IPatternDetails.IInput input : pattern.getInputs()) {
                        for (GenericStack option : input.getPossibleInputs()) {
                            result.add(option.what());
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not collect pattern inputs", e);
        }
        return result;
    }

    // ---------------------------------------------------------------- teleport

    public static boolean canTeleport(ServerPlayer player) {
        var cfg = DeclogConfig.COMMON;
        if (!cfg.teleportEnabled.get()) {
            return false;
        }
        return player.hasPermissions(cfg.teleportPermissionLevel.get())
                || (cfg.teleportInCreative.get() && player.isCreative());
    }

    public static void teleport(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos) {
        if (!canTeleport(player)) {
            player.displayClientMessage(Component.translatable("ae2declogger.error.teleport_denied")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        ServerLevel level = player.server.getLevel(dimension);
        if (level == null || !level.getWorldBorder().isWithinBounds(pos)) {
            return;
        }
        BlockPos spot = SafeSpot.find(level, pos);
        player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, player.getYRot(),
                player.getXRot());
        player.displayClientMessage(Component.translatable("ae2declogger.teleported", pos.getX(), pos.getY(),
                pos.getZ()), true);
    }

    /** Visible for game tests. */
    public static int watcherCount() {
        return MONITORS.size();
    }

    /** Visible for game tests. */
    public static GridTracker trackerForTests(IGrid grid) {
        return tracker(grid);
    }
}
