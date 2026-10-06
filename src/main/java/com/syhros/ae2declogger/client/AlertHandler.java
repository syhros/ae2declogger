package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.client.screen.UiUtil;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.network.DeclogNetwork;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

/** Shows a stall/block alert the way the client config asks for. */
public final class AlertHandler {

    private AlertHandler() {
    }

    public static void show(DeclogNetwork.Alert alert) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        var cfg = DeclogConfig.CLIENT;
        Component state = UiUtil.stateLabel(alert.state());
        Component item = Component.translatable("ae2declogger.amount_of", alert.output().getDisplayName(),
                UiUtil.amount(alert.output(), alert.amount()));
        Component reason = UiUtil.reasonText(alert.reason(), alert.detail());

        if (cfg.alertsChat.get()) {
            MutableComponent msg = Component.translatable("ae2declogger.alert.chat", state, item, reason)
                    .withStyle(ChatFormatting.GRAY);
            MachineRef machine = alert.machine();
            if (machine != null) {
                String coords = machine.pos().getX() + " " + machine.pos().getY() + " " + machine.pos().getZ();
                msg.append(" ").append(Component.literal("[" + coords + "]").withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, coords))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("ae2declogger.alert.copy", machine.name())))));
            }
            mc.player.displayClientMessage(msg, false);
        }
        if (cfg.alertsSound.get()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 0.7f, 0.9f));
        }
        if (cfg.alertsToast.get()) {
            //#if MC >= 1.20.5
            //$$ SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            //#else
            SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            //#endif
                    Component.translatable("ae2declogger.alert.toast_title", state), item);
        }
    }
}
