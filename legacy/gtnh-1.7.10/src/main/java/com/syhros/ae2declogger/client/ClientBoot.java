package com.syhros.ae2declogger.client;

import java.lang.reflect.Field;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.net.Channel;
import com.syhros.ae2declogger.net.Messages;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

/** 1.7.10 client start-up and events: hotkey, terminal button, pins in the world, HUD line, pin files. */
public final class ClientBoot {

    private static final int BUTTON_ID = 0x0DEC106;
    private static KeyBinding openKey;
    private static String loadedWorld;

    private ClientBoot() {
    }

    public static void init() {
        Messages.setClientSink(new ClientHandler());
        openKey = new KeyBinding("key.ae2declogger.open", Keyboard.KEY_K, "key.categories.ae2declogger");
        ClientRegistry.registerKeyBinding(openKey);
        Events events = new Events();
        FMLCommonHandler.instance().bus().register(events);
        MinecraftForge.EVENT_BUS.register(events);
    }

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
            Channel.drainClient();
            Minecraft mc = Minecraft.getMinecraft();
            com.syhros.ae2declogger.selftest.ClientSelfTest.tick();
            String world = ClientPlat.worldKey();
            if (world == null ? loadedWorld != null : !world.equals(loadedWorld)) {
                PinManager.unload();
                loadedWorld = world;
                if (world != null) {
                    PinManager.load();
                }
            }
            if (mc.thePlayer == null) {
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
            if (!DeclogConfig.terminalButton() || !(event.gui instanceof GuiContainer) || !isAe2Screen(event.gui)) {
                return;
            }
            int[] at = place((GuiContainer) event.gui);
            event.buttonList.add(new GuiButton(BUTTON_ID, at[0], at[1], 16, 16, "D"));
        }

        @SubscribeEvent
        public void onButton(GuiScreenEvent.ActionPerformedEvent.Pre event) {
            if (event.button.id == BUTTON_ID && isAe2Screen(event.gui)) {
                ClientPlat.send(Messages.openMonitor(true));
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onOverlay(RenderGameOverlayEvent.Text event) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.gameSettings.hideGUI) {
                return;
            }
            String line = PinHud.line();
            if (line == null) {
                return;
            }
            int w = mc.fontRenderer.getStringWidth(line);
            int x = (event.resolution.getScaledWidth() - w) / 2;
            int y = DeclogConfig.hudY();
            Gui.drawRect(x - 3, y - 2, x + w + 3, y + 10, 0x88000000);
            mc.fontRenderer.drawStringWithShadow(line, x, y, PinHud.color());
        }

        @SubscribeEvent
        public void onWorldRender(RenderWorldLastEvent event) {
            if (!DeclogConfig.pinsInWorld() || PinManager.isHidden()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            EntityLivingBase view = mc.renderViewEntity;
            String dim = ClientPlat.playerDim();
            if (view == null || dim == null) {
                return;
            }
            float pt = event.partialTicks;
            double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
            double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
            double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
            double max = DeclogConfig.pinRenderDistance();
            GL11.glPushMatrix();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glLineWidth(2.5f);
            Tessellator t = Tessellator.instance;
            for (PinManager.Pin pin : PinManager.visiblePins()) {
                if (!pin.dimension.equals(dim) || pin.pos().distSq(cx, cy, cz) > max * max) {
                    continue;
                }
                double x0 = pin.x - cx - 0.002;
                double y0 = pin.y - cy - 0.002;
                double z0 = pin.z - cz - 0.002;
                double x1 = x0 + 1.004;
                double y1 = y0 + 1.004;
                double z1 = z0 + 1.004;
                t.startDrawing(GL11.GL_LINES);
                t.setColorOpaque_I(pin.color);
                double[][] edges = { { x0, y0, z0, x1, y0, z0 }, { x0, y1, z0, x1, y1, z0 }, { x0, y0, z1, x1, y0, z1 },
                        { x0, y1, z1, x1, y1, z1 }, { x0, y0, z0, x0, y1, z0 }, { x1, y0, z0, x1, y1, z0 },
                        { x0, y0, z1, x0, y1, z1 }, { x1, y0, z1, x1, y1, z1 }, { x0, y0, z0, x0, y0, z1 },
                        { x1, y0, z0, x1, y0, z1 }, { x0, y1, z0, x0, y1, z1 }, { x1, y1, z0, x1, y1, z1 } };
                for (double[] e : edges) {
                    t.addVertex(e[0], e[1], e[2]);
                    t.addVertex(e[3], e[4], e[5]);
                }
                t.draw();
            }
            GL11.glLineWidth(1f);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glPopMatrix();
        }
    }

    private static Field statusField;
    private static final String[][] GUI_FIELDS = { { "guiLeft", "field_147003_i" }, { "guiTop", "field_147009_r" },
            { "xSize", "field_146999_f" } };

    private static int guiInt(GuiContainer gui, int which) {
        for (String name : GUI_FIELDS[which]) {
            try {
                Field f = GuiContainer.class.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(gui);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // try the other name
            }
        }
        return -1;
    }

    /** Centred above AE2's Crafting Status button on a terminal, otherwise the screen's top right corner. */
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
                int y = s.yPosition - 18;
                if (y >= 2) {
                    return new int[] { s.xPosition + 3, y };
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ae2Declogger.LOG.debug("Could not place the Declogger button", e);
        }
        int left = guiInt(gui, 0);
        int top = guiInt(gui, 1);
        int xSize = guiInt(gui, 2);
        if (left < 0 || top < 0 || xSize < 0) {
            return new int[] { gui.width - 20, 2 };
        }
        int x = left + xSize - 18;
        int y = top - 18;
        if (y < 2) {
            x = left + xSize + 2;
            y = top + 2;
        }
        return new int[] { x, y };
    }
}
