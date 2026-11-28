package dev.silencebound.maxtech.lib.shim.client.gui.input;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 26.1's key press: the three ints 1.21.1 passes to {@code keyPressed(int, int, int)}. */
@OnlyIn(Dist.CLIENT)
public record KeyEvent(int key, int scancode, @InputWithModifiers.Modifiers int modifiers) implements InputWithModifiers {
    @Override
    public int input() {
        return key;
    }

    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.METHOD, ElementType.TYPE_USE})
    @OnlyIn(Dist.CLIENT)
    public @interface Action {
    }
}
