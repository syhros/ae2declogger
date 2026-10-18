package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.client.ui.UiScreen;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.data.LText;
import com.syhros.ae2declogger.data.MachineSnapshot;
import com.syhros.ae2declogger.net.Messages;

/** What the client does with server messages. Runs on the client thread. */
public final class ClientHandler implements Messages.ClientSink {

    @Override
    public void openMonitor(LText title) {
        ClientState.reset(title);
        ClientPlat.openScreen(new DecloggerScreen());
    }

    @Override
    public void update(Messages.MonitorUpdate update) {
        ClientState.apply(update);
    }

    @Override
    public void closed(LText reason) {
        ClientState.closed(reason);
    }

    @Override
    public void machine(MachineSnapshot snapshot, boolean open) {
        ClientState.machine = snapshot;
        UiScreen current = ClientPlat.currentScreen();
        if (current instanceof MachineViewerScreen && ((MachineViewerScreen) current).shows(snapshot)) {
            return;
        }
        if (open) {
            UiScreen parent = current instanceof DecloggerScreen ? current : null;
            if (parent != null) {
                ((DecloggerScreen) parent).keepSubscriptionForChild();
            }
            ClientPlat.openScreen(new MachineViewerScreen(parent, snapshot.pos));
        }
    }

    @Override
    public void alert(Messages.Alert alert) {
        String state = UiUtil.stateLabel(alert.state);
        String item = Lang.t("ae2declogger.amount_of", alert.output.displayName(), UiUtil.amount(alert.amount));
        String reason = UiUtil.reasonText(alert.reason, alert.detail);
        if (DeclogConfig.alertsChat()) {
            String msg = "§7" + Lang.t("ae2declogger.alert.chat", UiUtil.code(UiUtil.stateColor(alert.state))
                    + state + "§7", item, reason);
            if (alert.machine != null) {
                msg += " §b[" + alert.machine.pos.coords() + "]";
            }
            ClientPlat.chat(msg);
        }
        if (DeclogConfig.alertsSound()) {
            ClientPlat.playBell();
        }
    }
}
