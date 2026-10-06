package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.client.pins.PinHud;
import com.syhros.ae2declogger.client.pins.PinManager;
import com.syhros.ae2declogger.client.pins.PinRenderer;
import com.syhros.ae2declogger.client.screen.DecloggerScreen;
import com.syhros.ae2declogger.client.screen.Gfx;
import com.syhros.ae2declogger.client.screen.Widgets;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.network.DeclogNetwork;

import net.minecraft.client.Minecraft;
//#if MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.crafting.CraftingCPUScreen;

/** Client-only event handlers. */
public final class ClientEvents {

    private ClientEvents() {
    }

    @Mod.EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.OPEN_MONITOR);
            event.register(KeyBindings.STOP_NAVIGATION);
            event.register(KeyBindings.TOGGLE_PINS);
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            //#if MC >= 1.20
            event.registerAboveAll("navigation", (gui, g, partialTick, width, height) -> PinHud.render(new Gfx(g),
                    partialTick, width, height));
            //#else
            //$$ event.registerAboveAll("navigation", (gui, pose, partialTick, width, height) -> PinHud.render(
            //$$         new Gfx(pose, Minecraft.getInstance().screen), partialTick, width, height));
            //#endif
        }
    }

    @Mod.EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {
        }

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            PinManager.tickTarget(mc.player);
            while (KeyBindings.OPEN_MONITOR.consumeClick()) {
                if (mc.player != null && mc.screen == null) {
                    DeclogNetwork.sendToServer(new DeclogNetwork.OpenMonitor(false));
                }
            }
            while (KeyBindings.STOP_NAVIGATION.consumeClick()) {
                if (PinManager.active() != null) {
                    PinManager.setActive(null);
                    if (mc.player != null) {
                        Mc.message(mc.player, Text.translatable("ae2declogger.pin.navigation_stopped"),
                                true);
                    }
                }
            }
            while (KeyBindings.TOGGLE_PINS.consumeClick()) {
                PinManager.toggleHidden();
                if (mc.player != null) {
                    Mc.message(mc.player, Text.translatable(PinManager.isHidden()
                            ? "ae2declogger.pin.hidden" : "ae2declogger.pin.shown"), true);
                }
            }
        }

        @SubscribeEvent
        public static void renderLevel(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                PinRenderer.renderWorld(event.getPoseStack(), event.getCamera());
            }
        }

        @SubscribeEvent
        public static void loggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            PinManager.load();
        }

        @SubscribeEvent
        public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            PinManager.unload();
            ClientState.reset(Text.empty());
            ClientState.machine = null;
        }

        @SubscribeEvent
        public static void screenClosing(ScreenEvent.Closing event) {
            CraftingScreenOverlay.onClosing(event.getScreen());
        }

        @SubscribeEvent
        public static void screenRender(ScreenEvent.Render.Post event) {
            //#if MC >= 1.20
            CraftingScreenOverlay.onRender(event.getScreen(), new Gfx(event.getGuiGraphics()), event.getMouseX(),
                    event.getMouseY());
            //#else
            //$$ CraftingScreenOverlay.onRender(event.getScreen(), new Gfx(event.getPoseStack(), event.getScreen()),
            //$$         event.getMouseX(), event.getMouseY());
            //#endif
        }

        @SubscribeEvent
        public static void screenClick(ScreenEvent.MouseButtonPressed.Pre event) {
            if (CraftingScreenOverlay.onClick(event.getScreen(), event.getMouseX(), event.getMouseY(),
                    event.getButton())) {
                event.setCanceled(true);
            }
        }

        /** Adds a small Declogger button above AE2 terminals and crafting status screens. */
        @SubscribeEvent
        public static void screenInit(ScreenEvent.Init.Post event) {
            CraftingScreenOverlay.onInit(event.getScreen());
            if (!DeclogConfig.CLIENT.terminalButton.get()) {
                return;
            }
            if (!(event.getScreen() instanceof MEStorageScreen<?> || event.getScreen() instanceof CraftingCPUScreen<?>)
                    || event.getScreen() instanceof DecloggerScreen) {
                return;
            }
            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) event.getScreen();
            int x = screen.getGuiLeft() + screen.getXSize() - 18;
            int y = screen.getGuiTop() - 18;
            if (y < 2) {
                x = screen.getGuiLeft() + screen.getXSize() + 2;
                y = screen.getGuiTop() + 2;
            }
            IconButton button = new IconButton(x, y, b -> DeclogNetwork.sendToServer(new DeclogNetwork.OpenMonitor(true)));
            Widgets.tooltip(button, Text.translatable("ae2declogger.terminal_button"));
            event.addListener(button);
        }
    }

    /** 16x16 button with the Declogger icon. */
    static final class IconButton extends Button {
        private static final ResourceLocation ICON = Ae2Declogger.id("textures/gui/button.png");

        IconButton(int x, int y, OnPress onPress) {
            //#if MC >= 1.19.3
            super(x, y, 16, 16, Text.translatable("ae2declogger.terminal_button"), onPress, DEFAULT_NARRATION);
            //#else
            //$$ super(x, y, 16, 16, Text.translatable("ae2declogger.terminal_button"), onPress);
            //#endif
        }

        @Override
        //#if MC >= 1.20
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Gfx g = new Gfx(graphics);
        //#else
        //$$ public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        //$$     Gfx g = new Gfx(pose, Minecraft.getInstance().screen);
        //#endif
            int x = Widgets.x(this);
            int y = Widgets.y(this);
            g.fill(x, y, x + width, y + height, isHoveredOrFocused() ? 0xFF5A6070 : 0xFF2C3038);
            g.renderOutline(x, y, width, height, 0xFF8A90A0);
            g.blit(ICON, x, y, 0, 0, 16, 16, 16, 16);
        }
    }
}
