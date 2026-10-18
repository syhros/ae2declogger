package com.syhros.ae2declogger.client;

import java.lang.reflect.Field;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.net.Channel;
import com.syhros.ae2declogger.net.Messages;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** 1.12.2 client start-up and events: hotkey, terminal button, pins in the world, HUD line, pin files. */
public final class ClientBoot {

    private static final int BUTTON_ID = 0x0DEC106;
    private static KeyBinding openKey;
    private static String loadedWorld;

    private ClientBoot() {
    }

    public static void init() {
        Messages.setClientSink(new ClientHandler());
        Channel.clientExecutor = r -> Minecraft.getMinecraft().addScheduledTask(r);
        openKey = new KeyBinding("key.ae2declogger.open", Keyboard.KEY_K, "key.categories.ae2declogger");
        ClientRegistry.registerKeyBinding(openKey);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    /** AE2 screens that get the Declogger button: terminals and the crafting CPU / status screens. */
    private static boolean isAe2Screen(Object gui) {
        String name = gui.getClass().getName();
        return name.startsWith("appeng.client.gui.") && (name.contains("GuiMEMonitorable")
                || name.contains("GuiCraftingCPU") || name.contains("GuiCraftingStatus")
                || name.contains("GuiMEPortableCell") || name.contains("GuiWirelessTerm")
                || name.contains("GuiCraftingTerm") || name.contains("GuiPatternTerm"));
    }

    public static final class Events {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            String world = ClientPlat.worldKey();
            if (world == null ? loadedWorld != null : !world.equals(loadedWorld)) {
                PinManager.unload();
                loadedWorld = world;
                if (world != null) {
                    PinManager.load();
                }
            }
            if (mc.player == null) {
                return;
            }
            PinManager.tickTarget();
            while (openKey.isPressed()) {
                if (mc.currentScreen == null) {
                    ClientPlat.send(Messages.openMonitor(false));
                }
            }
        }

        @SubscribeEvent
        public void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
            if (!DeclogConfig.terminalButton() || !(event.getGui() instanceof GuiContainer)
                    || !isAe2Screen(event.getGui())) {
                return;
            }
            GuiContainer gui = (GuiContainer) event.getGui();
            int[] at = place(gui);
            GuiButton b = new GuiButton(BUTTON_ID, at[0], at[1], 16, 16, "D");
            event.getButtonList().add(b);
        }

        @SubscribeEvent
        public void onButton(GuiScreenEvent.ActionPerformedEvent.Pre event) {
            if (event.getButton().id == BUTTON_ID && isAe2Screen(event.getGui())) {
                ClientPlat.send(Messages.openMonitor(true));
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onOverlay(RenderGameOverlayEvent.Text event) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.gameSettings.hideGUI || mc.currentScreen != null && !(mc.currentScreen instanceof HostScreen)) {
                return;
            }
            String line = PinHud.line();
            if (line == null) {
                return;
            }
            ScaledResolution sr = event.getResolution();
            int w = mc.fontRenderer.getStringWidth(line);
            int x = (sr.getScaledWidth() - w) / 2;
            int y = DeclogConfig.hudY();
            net.minecraft.client.gui.Gui.drawRect(x - 3, y - 2, x + w + 3, y + 10, 0x88000000);
            mc.fontRenderer.drawStringWithShadow(line, x, y, PinHud.color());
        }

        @SubscribeEvent
        public void onWorldRender(RenderWorldLastEvent event) {
            if (!DeclogConfig.pinsInWorld() || PinManager.isHidden()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            Entity view = mc.getRenderViewEntity();
            String dim = ClientPlat.playerDim();
            if (view == null || dim == null) {
                return;
            }
            float pt = event.getPartialTicks();
            double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
            double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
            double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
            double max = DeclogConfig.pinRenderDistance();
            GlStateManager.pushMatrix();
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GL11.glLineWidth(2.5f);
            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();
            for (PinManager.Pin pin : PinManager.visiblePins()) {
                if (!pin.dimension.equals(dim) || pin.pos().distSq(cx, cy, cz) > max * max) {
                    continue;
                }
                float r = (pin.color >> 16 & 0xFF) / 255f;
                float g = (pin.color >> 8 & 0xFF) / 255f;
                float b = (pin.color & 0xFF) / 255f;
                double x0 = pin.x - cx - 0.002;
                double y0 = pin.y - cy - 0.002;
                double z0 = pin.z - cz - 0.002;
                double x1 = x0 + 1.004;
                double y1 = y0 + 1.004;
                double z1 = z0 + 1.004;
                buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                double[][] edges = { { x0, y0, z0, x1, y0, z0 }, { x0, y1, z0, x1, y1, z0 }, { x0, y0, z1, x1, y0, z1 },
                        { x0, y1, z1, x1, y1, z1 }, { x0, y0, z0, x0, y1, z0 }, { x1, y0, z0, x1, y1, z0 },
                        { x0, y0, z1, x0, y1, z1 }, { x1, y0, z1, x1, y1, z1 }, { x0, y0, z0, x0, y0, z1 },
                        { x1, y0, z0, x1, y0, z1 }, { x0, y1, z0, x0, y1, z1 }, { x1, y1, z0, x1, y1, z1 } };
                for (double[] e : edges) {
                    buf.pos(e[0], e[1], e[2]).color(r, g, b, 1f).endVertex();
                    buf.pos(e[3], e[4], e[5]).color(r, g, b, 1f).endVertex();
                }
                tess.draw();
            }
            GL11.glLineWidth(1f);
            GlStateManager.enableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }
    }

    private static Field statusField;

    /**
     * Where the button goes: centred above AE2's Crafting Status button on a terminal (the private field
     * {@code craftingStatusBtn}), otherwise at the top right corner of the screen.
     */
    private static int[] place(GuiContainer gui) {
        try {
            for (Class<?> c = gui.getClass(); c != null && statusField == null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("craftingStatusBtn");
                    f.setAccessible(true);
                    statusField = f;
                } catch (NoSuchFieldException e) {
                    // keep looking
                }
            }
            Object status = statusField != null && statusField.getDeclaringClass().isInstance(gui)
                    ? statusField.get(gui) : null;
            if (status instanceof GuiButton && ((GuiButton) status).visible) {
                GuiButton s = (GuiButton) status;
                int y = s.y - 18;
                if (y >= 2) {
                    return new int[] { s.x + (s.width - 16) / 2, y };
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not place the Declogger button", e);
        }
        int x = gui.getGuiLeft() + gui.getXSize() - 18;
        int y = gui.getGuiTop() - 18;
        if (y < 2) {
            x = gui.getGuiLeft() + gui.getXSize() + 2;
            y = gui.getGuiTop() + 2;
        }
        return new int[] { x, y };
    }
}
