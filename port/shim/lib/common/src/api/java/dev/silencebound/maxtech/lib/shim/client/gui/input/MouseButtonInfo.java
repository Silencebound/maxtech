package dev.silencebound.maxtech.lib.shim.client.gui.input;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 26.1's mouse button with the modifier keys held at the time. */
@OnlyIn(Dist.CLIENT)
public record MouseButtonInfo(@MouseButtonInfo.MouseButton int button, @InputWithModifiers.Modifiers int modifiers)
        implements InputWithModifiers {
    @MouseButtonInfo.MouseButton
    @Override
    public int input() {
        return button;
    }

    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.METHOD, ElementType.TYPE_USE})
    @OnlyIn(Dist.CLIENT)
    public @interface Action {
    }

    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.METHOD, ElementType.TYPE_USE})
    @OnlyIn(Dist.CLIENT)
    public @interface MouseButton {
    }
}
