package dev.silencebound.maxtech.lib.shim.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.silencebound.maxtech.lib.shim.client.gui.input.MouseButtonEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.MouseButtonInfo;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

/**
 * Builds 26.1's input events from 1.21.1's mouse callbacks, which pass neither the modifier keys nor whether a click
 * is a double click.
 */
@OnlyIn(Dist.CLIENT)
final class Inputs {
    /** 26.1's {@code MouseHandler.DOUBLE_CLICK_THRESHOLD_MS}. */
    private static final long DOUBLE_CLICK_MS = 250L;
    private static @Nullable Screen lastScreen;
    private static long lastTime;
    private static int lastButton = -1;
    /** Whether the click a bridged screen is dispatching right now is a double click; false outside one. */
    private static boolean dispatchingDouble;

    private Inputs() {
    }

    /** The modifier bits held now (GLFW: 1 shift, 2 control, 4 alt, 8 super). */
    static int modifiers() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        int bits = 0;
        if (Screen.hasShiftDown()) {
            bits |= 1;
        }
        if (InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL)) {
            bits |= 2;
        }
        if (Screen.hasAltDown()) {
            bits |= 4;
        }
        if (InputConstants.isKeyDown(window, InputConstants.KEY_LWIN)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RWIN)) {
            bits |= 8;
        }
        return bits;
    }

    static MouseButtonEvent mouse(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, modifiers()));
    }

    static MouseButtonInfo button(int button) {
        return new MouseButtonInfo(button, modifiers());
    }

    /**
     * Records a press on {@code screen} and tells whether it is a double click, by 26.1's rule: the same button on the
     * same screen within 250 ms.
     */
    static boolean pressOn(Screen screen, int button) {
        long now = Util.getMillis();
        boolean result = lastScreen == screen && now - lastTime < DOUBLE_CLICK_MS && lastButton == button;
        lastScreen = screen;
        lastTime = now;
        lastButton = button;
        return result;
    }

    static boolean beginDispatch(boolean doubleClick) {
        boolean outer = dispatchingDouble;
        dispatchingDouble = doubleClick;
        return outer;
    }

    static void endDispatch(boolean outer) {
        dispatchingDouble = outer;
    }

    /** The double click flag for a widget: the one of the screen dispatching the click, false outside one. */
    static boolean doubleClick() {
        return dispatchingDouble;
    }
}
