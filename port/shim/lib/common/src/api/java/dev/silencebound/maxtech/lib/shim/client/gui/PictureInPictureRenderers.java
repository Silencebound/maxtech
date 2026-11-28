package dev.silencebound.maxtech.lib.shim.client.gui;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

/**
 * Stands in for 26.1's picture-in-picture renderers. 26.1 widgets submit a render state
 * ({@link GuiGraphicsExtractor#submitPictureInPictureRenderState}) and a renderer registered for the state's class
 * draws it into the GUI later; 1.21.1 has one render pass, so the drawer registered here draws the state right away
 * into the GuiGraphics, inside the widget's pose and scissor. The rendering side registers one drawer per state class,
 * where 26.1 registers its renderer ({@code RegisterPictureInPictureRenderersEvent}).
 */
@OnlyIn(Dist.CLIENT)
public final class PictureInPictureRenderers {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Class<?>, Drawer<?>> DRAWERS = new ConcurrentHashMap<>();
    private static final Set<Class<?>> WARNED = new HashSet<>();

    private PictureInPictureRenderers() {
    }

    /** Draws one picture-in-picture state into the current GUI pass. */
    @FunctionalInterface
    public interface Drawer<S> {
        void draw(GuiGraphics graphics, S state);
    }

    public static <S> void register(Class<S> stateClass, Drawer<S> drawer) {
        DRAWERS.put(stateClass, drawer);
    }

    @SuppressWarnings("unchecked")
    static void draw(GuiGraphics graphics, Object state) {
        Drawer<Object> drawer = (Drawer<Object>) DRAWERS.get(state.getClass());
        if (drawer != null) {
            drawer.draw(graphics, state);
        } else if (WARNED.add(state.getClass())) {
            LOGGER.warn("No picture-in-picture drawer registered for {}; it is not drawn", state.getClass().getName());
        }
    }
}
