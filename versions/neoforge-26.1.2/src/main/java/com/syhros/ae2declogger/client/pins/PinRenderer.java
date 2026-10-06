package com.syhros.ae2declogger.client.pins;

import java.util.ArrayList;
import java.util.List;

import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.config.DeclogConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws pins in the world on Minecraft 26.1: an outline box and a label, seen through walls. 26.1 has no immediate-mode
 * drawing in the level render event; pins are added as the game's own gizmos once per client tick, marked always on
 * top, and the game draws them every frame until the next tick replaces them.
 */
public final class PinRenderer {

    private PinRenderer() {
    }

    /** Called from the client tick (see ClientEvents), while the game collects gizmos for the tick. */
    public static void addGizmos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || PinManager.isHidden() || PinManager.visiblePins().isEmpty()
                || !DeclogConfig.CLIENT.pinsInWorld.get()) {
            return;
        }
        String dim = Mc.dimensionId(mc.level.dimension());
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double maxDist = DeclogConfig.CLIENT.pinRenderDistance.get();
        PinManager.Pin active = PinManager.active();

        List<PinManager.Pin> visible = new ArrayList<>();
        for (PinManager.Pin pin : PinManager.visiblePins()) {
            if (dim.equals(pin.dimension) && !tooFar(pin.pos(), cam, maxDist)) {
                visible.add(pin);
            }
        }
        for (PinManager.Pin pin : visible) {
            BlockPos p = pin.pos();
            int fillAlpha = pin == active ? 0x48 : 0x24;
            GizmoStyle style = GizmoStyle.strokeAndFill(0xF2000000 | pin.color, 2.5f, (fillAlpha << 24) | pin.color);
            Gizmos.cuboid(new AABB(p).inflate(0.002), style).setAlwaysOnTop();

            Vec3 labelPos = new Vec3(p.getX() + 0.5, p.getY() + 1.4, p.getZ() + 0.5);
            double distance = labelPos.distanceTo(cam);
            float scale = (float) (0.4 * Math.max(1.0, distance / 10.0));
            Gizmos.billboardText(pin.label + " (" + Math.round(distance) + "m)", labelPos,
                    TextGizmo.Style.forColorAndCentered(0xFF000000 | pin.color).withScale(scale)).setAlwaysOnTop();
        }
    }

    private static boolean tooFar(BlockPos pos, Vec3 cam, double max) {
        double dx = pos.getX() + 0.5 - cam.x;
        double dy = pos.getY() + 0.5 - cam.y;
        double dz = pos.getZ() + 0.5 - cam.z;
        return dx * dx + dy * dy + dz * dz > max * max;
    }
}
