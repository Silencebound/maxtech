package dev.silencebound.maxtech.lib.shim.client.gui.input;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 26.1's mouse button event: the position and button 1.21.1 passes as {@code (double, double, int)}. */
@OnlyIn(Dist.CLIENT)
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) implements InputWithModifiers {
    @Override
    public int input() {
        return button();
    }

    @MouseButtonInfo.MouseButton
    public int button() {
        return buttonInfo.button();
    }

    @InputWithModifiers.Modifiers
    @Override
    public int modifiers() {
        return buttonInfo.modifiers();
    }
}
