package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.logic.Formatting;

/** The navigation line on the HUD for the active pin: "Pin: Label  x y z  ↑ 42m ▲3". */
public final class PinHud {

    private PinHud() {
    }

    /** The line to show, or null for none. Each version draws it centred at {@code hudY}. */
    public static String line() {
        if (!DeclogConfig.pinsHud()) {
            return null;
        }
        PinManager.Pin pin = PinManager.active();
        String dim = ClientPlat.playerDim();
        double[] p = ClientPlat.playerPos();
        if (pin == null || dim == null || p == null) {
            return null;
        }
        if (!dim.equals(pin.dimension)) {
            return Lang.t("ae2declogger.hud.other_dimension", pin.label, pin.dimension);
        }
        double dx = pin.x + 0.5 - p[0];
        double dz = pin.z + 0.5 - p[2];
        int dy = pin.y - (int) Math.floor(p[1]);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 2.5 && Math.abs(dy) < 3) {
            return Lang.t("ae2declogger.hud.arrived", pin.label);
        }
        String arrow = Formatting.directionArrow(ClientPlat.playerYaw(), dx, dz);
        String height = dy == 0 ? "" : dy > 0 ? " ▲" + dy : " ▼" + -dy;
        return Lang.t("ae2declogger.hud.navigate", pin.label, pin.x, pin.y, pin.z, arrow, Math.round(horizontal),
                height);
    }

    public static int color() {
        PinManager.Pin pin = PinManager.active();
        return 0xFF000000 | (pin != null ? pin.color : PinManager.PIN_COLOR);
    }
}
