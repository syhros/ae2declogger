package com.syhros.ae2declogger.server;

import java.lang.ref.WeakReference;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.networking.GridHelper;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftingCPUMenu;

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
        long lastFocus = -1;
        /** Set while the player has AE2's crafting status screen open: no monitor screen, data for the overlay only. */
        @Nullable
        WeakReference<AbstractContainerMenu> overlayMenu;

        Subscription(GridSource source) {
            this.source = source;
        }

        boolean overlay() {
            return overlayMenu != null;
        }
    }

    private static final class AlertState {
        final AlertTracker<Long> tracker = new AlertTracker<>();
        // Weak so a network that was rebuilt or unloaded can be garbage collected.
        WeakReference<IGrid> grid = new WeakReference<>(null);
    }

    private static final int VIEWER_COOLDOWN_TICKS = 5;
    private static final Map<UUID, Long> LAST_VIEW_REQUEST = new HashMap<>();

    private DecloggerServer() {
    }

    // ---------------------------------------------------------------- monitor

    /**
     * The button in an AE2 terminal or crafting status screen ({@code fromTerminal}), or the hotkey: the ME block the
     * player is looking at, otherwise the network they opened last.
     */
    public static void openFromRequest(ServerPlayer player, boolean fromTerminal) {
        if (fromTerminal) {
            IGridNode node = menuNode(player.containerMenu);
            if (node != null) {
                Component title = Component.translatable("ae2declogger.monitor.title_terminal");
                // Close the terminal first so the client is not left with a dangling container.
                player.closeContainer();
                openMonitor(player, GridSource.ofNode(node, title));
                return;
            }
            player.displayClientMessage(Component.translatable("ae2declogger.error.no_terminal")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        HitResult hit = player.pick(player.getBlockReach(), 1.0f, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                && GridHelper.getNodeHost(player.level(), blockHit.getBlockPos()) != null) {
            GlobalPos pos = GlobalPos.of(player.level().dimension(), blockHit.getBlockPos());
            openMonitor(player, GridSource.ofLink(pos, blockHit.getDirection(),
                    Component.translatable("ae2declogger.monitor.title")));
            return;
        }
        GlobalPos last = NetworkMemory.last(player);
        if (last == null) {
            player.displayClientMessage(Component.translatable("ae2declogger.error.no_network")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        openMonitor(player, GridSource.ofLink(last, null, Component.translatable("ae2declogger.monitor.title")));
    }

    /** The grid node behind an open AE2 screen (terminal, wireless terminal, crafting CPU or status screen). */
    @Nullable
    static IGridNode menuNode(AbstractContainerMenu menu) {
        if (menu instanceof AEBaseMenu aeMenu) {
            Object target = aeMenu.getTarget();
            if (target instanceof IActionHost host) {
                return host.getActionableNode();
            }
            if (target instanceof appeng.api.networking.IInWorldGridNodeHost host) {
                return host.getGridNode(null);
            }
        }
        return null;
    }

    /**
     * AE2's crafting status screen opened ({@code watch}) or closed. While it is open the client gets the analysis for
     * that network so it can mark stuck entries and offer Inspect.
     */
    public static void watchCraftingScreen(ServerPlayer player, boolean watch) {
        Subscription current = MONITORS.get(player.getUUID());
        if (!watch) {
            if (current != null && current.overlay()) {
                MONITORS.remove(player.getUUID());
            }
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        IGridNode node = menu instanceof CraftingCPUMenu ? menuNode(menu) : null;
        if (node == null) {
            return;
        }
        GridSource source = GridSource.ofNode(node, Component.translatable("ae2declogger.monitor.title_terminal"));
        GridSource.Resolved resolved = source.resolve(player);
        if (resolved.grid() == null) {
            return;
        }
        Subscription sub = new Subscription(source);
        sub.overlayMenu = new WeakReference<>(menu);
        MONITORS.put(player.getUUID(), sub);
        sendUpdate(player, sub, resolved.grid(), true);
    }

    /** Job id of the CPU shown in an AE2 crafting CPU / status screen, or -1. */
    private static long focusedJob(Subscription sub, IGrid grid) {
        AbstractContainerMenu menu = sub.overlayMenu != null ? sub.overlayMenu.get() : null;
        if (!(menu instanceof CraftingCPUMenu)) {
            return -1;
        }
        try {
            if (CPU_FIELD == null) {
                CPU_FIELD = CraftingCPUMenu.class.getDeclaredField("cpu");
                CPU_FIELD.setAccessible(true);
            }
            Object cpu = CPU_FIELD.get(menu);
            if (cpu instanceof ICraftingCPU craftingCpu) {
                GridTracker.CpuTrack track = tracker(grid).track(craftingCpu);
                return track != null ? track.jobId : -1;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not read the CPU of the crafting screen", e);
        }
        return -1;
    }

    @Nullable
    private static java.lang.reflect.Field CPU_FIELD;

    public static void openMonitor(ServerPlayer player, GridSource source) {
        GridSource.Resolved resolved = source.resolve(player);
        if (resolved.error() != null) {
            player.displayClientMessage(resolved.error().copy().withStyle(ChatFormatting.RED), true);
            return;
        }
        Subscription sub = new Subscription(source);
        MONITORS.put(player.getUUID(), sub);
        GlobalPos remembered = NetworkMemory.positionOf(resolved.grid(), source.node());
        if (remembered != null) {
            NetworkMemory.rememberLast(player, remembered);
        }
        DeclogNetwork.sendTo(player, new DeclogNetwork.OpenMonitorScreen(source.title()));
        sendUpdate(player, sub, resolved.grid(), true);
    }

    public static void closeMonitor(ServerPlayer player) {
        MONITORS.remove(player.getUUID());
    }

    public static void onLogout(ServerPlayer player) {
        MONITORS.remove(player.getUUID());
        ALERTS.remove(player.getUUID());
        LAST_VIEW_REQUEST.remove(player.getUUID());
    }

    public static void onServerStopped() {
        MONITORS.clear();
        ALERTS.clear();
        TRACKERS.clear();
        LAST_VIEW_REQUEST.clear();
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
                } catch (RuntimeException | LinkageError ex) {
                    Ae2Declogger.LOG.error("AE2 Declogger update failed for {}", player.getGameProfile().getName(),
                            ex);
                    MONITORS.remove(e.getKey());
                    DeclogNetwork.sendTo(player, new DeclogNetwork.MonitorClosed(
                            Component.translatable("ae2declogger.error.internal")));
                }
            }
        }
        if (ticks % DeclogConfig.COMMON.alertIntervalTicks.get() == 0) {
            runAlerts(server);
        }
    }

    private static void sendUpdate(ServerPlayer player, Subscription sub, @Nullable IGrid known, boolean full) {
        if (sub.overlay() && player.containerMenu != sub.overlayMenu.get()) {
            // The crafting status screen was closed without telling us.
            MONITORS.remove(player.getUUID());
            return;
        }
        IGrid grid = known;
        if (grid == null) {
            GridSource.Resolved resolved = sub.source.resolve(player);
            if (resolved.error() != null) {
                MONITORS.remove(player.getUUID());
                if (!sub.overlay()) {
                    DeclogNetwork.sendTo(player, new DeclogNetwork.MonitorClosed(resolved.error()));
                }
                return;
            }
            grid = resolved.grid();
        }
        if (sub.grid != grid) {
            full = true;
            sub.grid = grid;
            sub.allowed.clear();
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
        boolean alerts = alertsOnFor(player, grid);
        long focus = focusedJob(sub, grid);

        boolean summaryChanged = sub.lastSummary == null || !sameIgnoringTime(sub.lastSummary, summary);
        if (!full && delta.isEmpty() && history.isEmpty() && !historyReset && !summaryChanged
                && alerts == sub.lastAlerts && focus == sub.lastFocus) {
            return;
        }
        sub.lastSummary = summary;
        sub.lastAlerts = alerts;
        sub.lastFocus = focus;

        long[] removed = delta.removed().stream().mapToLong(Long::longValue).toArray();
        long[] order = current.keySet().stream().mapToLong(Long::longValue).toArray();
        DeclogNetwork.sendTo(player, new DeclogNetwork.MonitorUpdate(full, summary,
                new ArrayList<>(delta.changed().values()), removed, order, history, historyReset, alerts, focus));
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
            try {
                alertsFor(server, player);
            } catch (RuntimeException | LinkageError ex) {
                // One broken network must not stop alerts for everyone else.
                Ae2Declogger.LOG.debug("Alert check failed for {}", player.getGameProfile().getName(), ex);
            }
        }
    }

    private static void alertsFor(MinecraftServer server, ServerPlayer player) {
        GlobalPos target = NetworkMemory.alerts(player);
        if (target == null) {
            ALERTS.remove(player.getUUID());
            return;
        }
        GridSource.Resolved resolved = GridSource.ofLink(target, null, Component.empty()).resolve(player);
        if (resolved.grid() == null) {
            return;
        }
        IGrid grid = resolved.grid();
        GridAnalysis analysis = analysis(grid, tracker(grid), server);
        AlertState state = ALERTS.computeIfAbsent(player.getUUID(), u -> new AlertState());
        if (state.grid.get() != grid) {
            state.tracker.reset();
            state.grid = new WeakReference<>(grid);
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

    /** Whether the player has alerts on for this network. */
    private static boolean alertsOnFor(ServerPlayer player, IGrid grid) {
        GlobalPos target = NetworkMemory.alerts(player);
        if (target == null) {
            return false;
        }
        return GridSource.ofLink(target, null, Component.empty()).resolve(player).grid() == grid;
    }

    /** The bell button in the monitor: alerts on or off for the network the monitor shows. */
    public static void toggleAlerts(ServerPlayer player) {
        Subscription sub = MONITORS.get(player.getUUID());
        if (sub == null || sub.grid == null) {
            return;
        }
        boolean enable = !alertsOnFor(player, sub.grid);
        if (enable) {
            GlobalPos pos = NetworkMemory.positionOf(sub.grid, sub.source.node());
            if (pos == null) {
                pos = sub.source.link();
            }
            if (pos == null) {
                player.displayClientMessage(Component.translatable("ae2declogger.error.alerts_no_position")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            NetworkMemory.setAlerts(player, pos);
        } else {
            NetworkMemory.setAlerts(player, null);
            ALERTS.remove(player.getUUID());
        }
        player.displayClientMessage(Component.translatable(
                enable ? "ae2declogger.alerts_on" : "ae2declogger.alerts_off"), true);
        sendUpdate(player, sub, null, false);
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
     * Sends a read-only snapshot of the block at {@code pos}. Allowed for blocks listed in the player's open monitor and
     * for operators.
     */
    public static void requestMachine(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos, boolean open) {
        ServerLevel level = player.server.getLevel(dimension);
        if (level == null) {
            return;
        }
        long tick = player.server.getTickCount();
        Long last = LAST_VIEW_REQUEST.get(player.getUUID());
        if (!open && last != null && tick - last < VIEWER_COOLDOWN_TICKS) {
            return; // refresh spam from a client
        }
        LAST_VIEW_REQUEST.put(player.getUUID(), tick);
        GlobalPos target = GlobalPos.of(dimension, pos);
        Subscription sub = MONITORS.get(player.getUUID());
        boolean listed = sub != null && sub.allowed.contains(target);
        if (!listed && !player.hasPermissions(2)) {
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

    /**
     * Players with the permission level may teleport anywhere (they could use /tp anyway). Creative players without
     * it may only teleport to machines their open monitor lists, so a modified client cannot jump to arbitrary
     * coordinates or make the server generate far-away chunks.
     */
    public static void teleport(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos) {
        var cfg = DeclogConfig.COMMON;
        boolean privileged = cfg.teleportEnabled.get() && player.hasPermissions(cfg.teleportPermissionLevel.get());
        Subscription sub = MONITORS.get(player.getUUID());
        boolean listed = sub != null && sub.allowed.contains(GlobalPos.of(dimension, pos));
        if (!canTeleport(player) || (!privileged && !listed)) {
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
