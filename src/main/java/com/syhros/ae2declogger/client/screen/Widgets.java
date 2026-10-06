package com.syhros.ae2declogger.client.screen;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
//#if MC >= 1.19.3
import net.minecraft.client.gui.components.Tooltip;
//#endif

/**
 * Buttons and tooltips the same way on every Minecraft version. 1.19.3 added Button.builder, widget tooltips and
 * getX()/getY(); before that buttons were built with a constructor and tooltips were up to the screen, so here they
 * are remembered and drawn by {@link #renderTooltips}.
 */
public final class Widgets {

    private static final Map<AbstractWidget, Component> TOOLTIPS = new WeakHashMap<>();

    private Widgets() {
    }

    /** Same shape as Button.builder(message, onPress).bounds(x, y, w, h).build(). */
    public static Builder builder(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }

    public static final class Builder {
        private final Component message;
        private final Button.OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        private Builder(Component message, Button.OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Button build() {
            //#if MC >= 1.19.3
            return Button.builder(message, onPress).bounds(x, y, width, height).build();
            //#else
            //$$ return new Button(x, y, width, height, message, onPress);
            //#endif
        }
    }

    public static void tooltip(AbstractWidget widget, Component text) {
        //#if MC >= 1.19.3
        widget.setTooltip(Tooltip.create(text));
        //#else
        //$$ TOOLTIPS.put(widget, text);
        //#endif
    }

    /** Draws the tooltip of the hovered widget where the game does not (before 1.19.3). Call last in render(). */
    public static void renderTooltips(Gfx g, Font font, List<? extends GuiEventListener> children, int mouseX,
            int mouseY) {
        //#if MC < 1.19.3
        //$$ for (GuiEventListener child : children) {
        //$$     if (child instanceof AbstractWidget w && w.visible && w.isMouseOver(mouseX, mouseY)
        //$$             && TOOLTIPS.containsKey(w)) {
        //$$         g.renderComponentTooltip(font, List.of(TOOLTIPS.get(w)), mouseX, mouseY);
        //$$         return;
        //$$     }
        //$$ }
        //#endif
    }

    public static int x(AbstractWidget w) {
        //#if MC >= 1.19.3
        return w.getX();
        //#else
        //$$ return w.x;
        //#endif
    }

    public static int y(AbstractWidget w) {
        //#if MC >= 1.19.3
        return w.getY();
        //#else
        //$$ return w.y;
        //#endif
    }
}
