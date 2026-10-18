package com.syhros.ae2declogger.client;

import java.lang.reflect.Field;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.net.Messages;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;

/**
 * 1.16.5 client start-up and events: hotkey, terminal button, crafting screen overlay, pins in the world, HUD line,
 * pin files.
 */
public final class ClientBoot {

    private static KeyBinding openKey;
    private static String loadedWorld;

    private ClientBoot() {
    }

    public static void init() {
        Messages.setClientSink(new ClientHandler());
        openKey = new KeyBinding("key.ae2declogger.open", GLFW.GLFW_KEY_K, "key.categories.ae2declogger");
        ClientRegistry.registerKeyBinding(openKey);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    /** AE2 8 screens that get the Declogger button: terminals and the crafting CPU / status screens. */
    private static boolean isAe2Screen(Object gui) {
        String name = gui.getClass().getName();
        return name.startsWith("appeng.client.gui.me.") && (name.endsWith("TerminalScreen")
                || name.endsWith("TermScreen") || name.endsWith("CraftingCPUScreen")
                || name.endsWith("CraftingStatusScreen"));
    }

    public static final class Events {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            com.syhros.ae2declogger.selftest.ClientSelfTest.tick();
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
            while (openKey.consumeClick()) {
                if (mc.screen == null) {
                    ClientPlat.send(Messages.openMonitor(false));
                }
            }
        }

        @SubscribeEvent
        public void onGuiInit(GuiScreenEvent.InitGuiEvent.Post event) {
            if (!DeclogConfig.terminalButton() || !(event.getGui() instanceof ContainerScreen)
                    || !isAe2Screen(event.getGui())) {
                return;
            }
            int[] at = place((ContainerScreen<?>) event.getGui());
            event.addWidget(new Button(at[0], at[1], 16, 16, new StringTextComponent("D"),
                    b -> ClientPlat.send(Messages.openMonitor(true))));
        }

        @SubscribeEvent
        public void onGuiOpen(GuiOpenEvent event) {
            if (OverlayCanvas.isCraftingScreen(event.getGui()) && DeclogConfig.craftingOverlay()) {
                CraftingOverlay.onOpen(event.getGui());
            } else {
                CraftingOverlay.onOtherScreen(event.getGui());
            }
        }

        @SubscribeEvent
        public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
            Screen gui = event.getGui();
            if (!CraftingOverlay.watching(gui)) {
                return;
            }
            MatrixStack m = event.getMatrixStack();
            m.pushPose();
            m.translate(0, 0, 350);
            RenderSystem.disableDepthTest();
            CraftingOverlay.render(new OverlayCanvas(m), gui, OverlayCanvas.cells(gui), event.getMouseX(),
                    event.getMouseY(), gui.width, gui.height);
            RenderSystem.enableDepthTest();
            m.popPose();
        }

        @SubscribeEvent
        public void onMouseClicked(GuiScreenEvent.MouseClickedEvent.Pre event) {
            Screen gui = event.getGui();
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && CraftingOverlay.watching(gui)
                    && CraftingOverlay.click(gui, OverlayCanvas.cells(gui), event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onOverlay(RenderGameOverlayEvent.Text event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) {
                return;
            }
            String line = PinHud.line();
            if (line == null) {
                return;
            }
            MatrixStack m = event.getMatrixStack();
            int w = mc.font.width(line);
            int x = (event.getWindow().getGuiScaledWidth() - w) / 2;
            int y = DeclogConfig.hudY();
            AbstractGui.fill(m, x - 3, y - 2, x + w + 3, y + 10, 0x88000000);
            mc.font.drawShadow(m, line, x, y, PinHud.color());
        }

        @SubscribeEvent
        public void onWorldRender(RenderWorldLastEvent event) {
            if (!DeclogConfig.pinsInWorld() || PinManager.isHidden()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            String dim = ClientPlat.playerDim();
            if (dim == null) {
                return;
            }
            Vector3d cam = mc.gameRenderer.getMainCamera().getPosition();
            double max = DeclogConfig.pinRenderDistance();
            MatrixStack m = event.getMatrixStack();
            Matrix4f pose = m.last().pose();
            RenderSystem.disableTexture();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.lineWidth(2.5f);
            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuilder();
            for (PinManager.Pin pin : PinManager.visiblePins()) {
                if (!pin.dimension.equals(dim) || pin.pos().distSq(cam.x, cam.y, cam.z) > max * max) {
                    continue;
                }
                float r = (pin.color >> 16 & 0xFF) / 255f;
                float g = (pin.color >> 8 & 0xFF) / 255f;
                float b = (pin.color & 0xFF) / 255f;
                float x0 = (float) (pin.x - cam.x - 0.002);
                float y0 = (float) (pin.y - cam.y - 0.002);
                float z0 = (float) (pin.z - cam.z - 0.002);
                float x1 = x0 + 1.004f;
                float y1 = y0 + 1.004f;
                float z1 = z0 + 1.004f;
                buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                float[][] edges = { { x0, y0, z0, x1, y0, z0 }, { x0, y1, z0, x1, y1, z0 }, { x0, y0, z1, x1, y0, z1 },
                        { x0, y1, z1, x1, y1, z1 }, { x0, y0, z0, x0, y1, z0 }, { x1, y0, z0, x1, y1, z0 },
                        { x0, y0, z1, x0, y1, z1 }, { x1, y0, z1, x1, y1, z1 }, { x0, y0, z0, x0, y0, z1 },
                        { x1, y0, z0, x1, y0, z1 }, { x0, y1, z0, x0, y1, z1 }, { x1, y1, z0, x1, y1, z1 } };
                for (float[] e : edges) {
                    buf.vertex(pose, e[0], e[1], e[2]).color(r, g, b, 1f).endVertex();
                    buf.vertex(pose, e[3], e[4], e[5]).color(r, g, b, 1f).endVertex();
                }
                tess.end();
            }
            RenderSystem.lineWidth(1f);
            RenderSystem.enableDepthTest();
            RenderSystem.enableTexture();
            RenderSystem.disableBlend();
        }
    }

    private static Field statusField;

    /** Centred above AE2's Crafting Status button on a terminal, otherwise the screen's top right corner. */
    private static int[] place(ContainerScreen<?> gui) {
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
            if (status instanceof Widget && ((Widget) status).visible) {
                Widget s = (Widget) status;
                int y = s.y - 18;
                if (y >= 2) {
                    return new int[] { s.x + (s.getWidth() - 16) / 2, y };
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
