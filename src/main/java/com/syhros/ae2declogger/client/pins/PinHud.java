package com.syhros.ae2declogger.client.pins;

import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.client.screen.Gfx;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.logic.Formatting;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** The navigation line on the HUD for the active pin. */
public final class PinHud {

    private PinHud() {
    }

    /** HUD line: "Pin: Label  x y z  ↑ 42m ▲3". Called by the loader's HUD overlay (see ClientEvents). */
    public static void render(Gfx graphics, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null || !DeclogConfig.CLIENT.pinsHud.get()) {
            return;
        }
        PinManager.Pin pin = PinManager.active();
        if (pin == null) {
            return;
        }
        Player player = mc.player;
        Component line;
        int color = 0xFF000000 | pin.color;
        if (!Mc.dimensionId(mc.level.dimension()).equals(pin.dimension)) {
            line = Text.translatable("ae2declogger.hud.other_dimension", pin.label, pin.dimension);
        } else {
            BlockPos p = pin.pos();
            double dx = p.getX() + 0.5 - player.getX();
            double dz = p.getZ() + 0.5 - player.getZ();
            int dy = p.getY() - player.blockPosition().getY();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            String arrow = Formatting.directionArrow(player.getYRot(), dx, dz);
            String height3 = dy == 0 ? "" : (dy > 0 ? " ▲" + dy : " ▼" + (-dy));
            if (horizontal < 2.5 && Math.abs(dy) < 3) {
                line = Text.translatable("ae2declogger.hud.arrived", pin.label);
            } else {
                line = Text.translatable("ae2declogger.hud.navigate", pin.label, p.getX(), p.getY(), p.getZ(),
                        arrow, Math.round(horizontal), height3);
            }
        }
        int textWidth = mc.font.width(line);
        int x = (width - textWidth) / 2;
        int y = DeclogConfig.CLIENT.hudY.get();
        graphics.fill(x - 3, y - 2, x + textWidth + 3, y + 10, 0x88000000);
        graphics.drawString(mc.font, line, x, y, color, true);
    }
}
