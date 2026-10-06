package com.syhros.ae2declogger.client;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.client.pins.PinManager;
import com.syhros.ae2declogger.client.pins.PinRenderer;
import com.syhros.ae2declogger.client.screen.DecloggerScreen;
import com.syhros.ae2declogger.config.DeclogConfig;
import com.syhros.ae2declogger.network.DeclogNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.me.crafting.CraftingCPUScreen;

/** Client-only event handlers (NeoForge; the Forge version is in the shared sources). */
public final class ClientEvents {

    private ClientEvents() {
    }

    @EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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
        public static void registerOverlays(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Ae2Declogger.id("navigation"), (g, delta) -> PinRenderer.renderHud(g,
                    delta.getGameTimeDeltaPartialTick(false), g.guiWidth(), g.guiHeight()));
        }
    }

    @EventBusSubscriber(modid = Ae2Declogger.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {
        }

        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
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
                        mc.player.displayClientMessage(Component.translatable("ae2declogger.pin.navigation_stopped"),
                                true);
                    }
                }
            }
            while (KeyBindings.TOGGLE_PINS.consumeClick()) {
                PinManager.toggleHidden();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable(PinManager.isHidden()
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
            ClientState.reset(Component.empty());
            ClientState.machine = null;
        }

        @SubscribeEvent
        public static void screenClosing(ScreenEvent.Closing event) {
            CraftingScreenOverlay.onClosing(event.getScreen());
        }

        @SubscribeEvent
        public static void screenRender(ScreenEvent.Render.Post event) {
            CraftingScreenOverlay.onRender(event.getScreen(), event.getGuiGraphics(), event.getMouseX(),
                    event.getMouseY());
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
            button.setTooltip(Tooltip.create(Component.translatable("ae2declogger.terminal_button")));
            event.addListener(button);
        }
    }

    /** 16x16 button with the Declogger icon. */
    static final class IconButton extends Button {
        private static final ResourceLocation ICON = Ae2Declogger.id("textures/gui/button.png");

        IconButton(int x, int y, OnPress onPress) {
            super(x, y, 16, 16, Component.translatable("ae2declogger.terminal_button"), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(getX(), getY(), getX() + width, getY() + height, isHoveredOrFocused() ? 0xFF5A6070 : 0xFF2C3038);
            g.renderOutline(getX(), getY(), width, height, 0xFF8A90A0);
            g.blit(ICON, getX(), getY(), 0, 0, 16, 16, 16, 16);
        }
    }
}
