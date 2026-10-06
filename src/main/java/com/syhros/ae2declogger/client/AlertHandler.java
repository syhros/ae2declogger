package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.Text;
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
        Component item = Text.translatable("ae2declogger.amount_of", alert.output().getDisplayName(),
                UiUtil.amount(alert.output(), alert.amount()));
        Component reason = UiUtil.reasonText(alert.reason(), alert.detail());

        if (cfg.alertsChat.get()) {
            MutableComponent msg = Text.translatable("ae2declogger.alert.chat", state, item, reason)
                    .withStyle(ChatFormatting.GRAY);
            MachineRef machine = alert.machine();
            if (machine != null) {
                String coords = machine.pos().getX() + " " + machine.pos().getY() + " " + machine.pos().getZ();
                msg.append(" ").append(Text.literal("[" + coords + "]").withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(Text.copyOnClick(coords))
                        .withHoverEvent(Text.showText(Text.translatable("ae2declogger.alert.copy", machine.name())))));
            }
            Mc.message(mc.player, msg, false);
        }
        if (cfg.alertsSound.get()) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(bell(), 0.7f, 0.9f));
        }
        if (cfg.alertsToast.get()) {
            //#if MC >= 26.1
            //$$ SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            //#elseif MC >= 1.20.5
            //$$ SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            //#else
            SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            //#endif
                    Text.translatable("ae2declogger.alert.toast_title", state), item);
        }
    }

    private static net.minecraft.sounds.SoundEvent bell() {
        //#if MC >= 1.19.3
        return SoundEvents.NOTE_BLOCK_BELL.value();
        //#else
        //$$ return SoundEvents.NOTE_BLOCK_BELL;
        //#endif
    }
}
