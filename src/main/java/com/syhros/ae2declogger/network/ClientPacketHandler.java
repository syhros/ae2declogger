package com.syhros.ae2declogger.network;

import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.client.AlertHandler;
import com.syhros.ae2declogger.client.ClientState;
import com.syhros.ae2declogger.client.screen.DecloggerScreen;
import com.syhros.ae2declogger.client.screen.MachineViewerScreen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Client-side packet handling. Only loaded on the physical client. */
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void handle(Object message) {
        Minecraft mc = Minecraft.getInstance();
        if (message == null || mc.player == null) {
            return;
        }
        if (message instanceof DeclogNetwork.OpenMonitorScreen open) {
            ClientState.reset(open.title());
            if (!(mc.screen instanceof DecloggerScreen)) {
                mc.setScreen(new DecloggerScreen());
            }
        } else if (message instanceof DeclogNetwork.MonitorUpdate update) {
            ClientState.apply(update);
        } else if (message instanceof DeclogNetwork.MonitorClosed closed) {
            ClientState.closed(closed.reason());
            if (!(mc.screen instanceof DecloggerScreen) && !(mc.screen instanceof MachineViewerScreen)) {
                Mc.message(mc.player, closed.reason().copy().withStyle(ChatFormatting.RED), true);
            }
        } else if (message instanceof DeclogNetwork.MachineData data) {
            ClientState.machine = data.snapshot();
            if (data.open() && !(mc.screen instanceof MachineViewerScreen viewer && viewer.shows(data.snapshot()))) {
                Screen parent = mc.screen instanceof DecloggerScreen monitor ? monitor : null;
                if (parent instanceof DecloggerScreen monitor) {
                    monitor.keepSubscriptionForChild();
                }
                mc.setScreen(new MachineViewerScreen(parent, data.snapshot().dimension(), data.snapshot().pos()));
            }
        } else if (message instanceof DeclogNetwork.Alert alert) {
            AlertHandler.show(alert);
        }
    }
}
