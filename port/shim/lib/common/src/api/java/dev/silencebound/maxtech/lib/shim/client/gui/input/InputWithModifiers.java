package dev.silencebound.maxtech.lib.shim.client.gui.input;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 26.1's key or mouse input with modifiers (GLFW modifier bits: 1 shift, 2 control, 4 alt, 8 super). */
@OnlyIn(Dist.CLIENT)
public interface InputWithModifiers {
    int NOT_DIGIT = -1;

    int input();

    @InputWithModifiers.Modifiers
    int modifiers();

    default boolean isSelection() {
        return input() == 257 || input() == 32 || input() == 335;
    }

    default boolean isConfirmation() {
        return input() == 257 || input() == 335;
    }

    default boolean isEscape() {
        return input() == 256;
    }

    default boolean isLeft() {
        return input() == 263;
    }

    default boolean isRight() {
        return input() == 262;
    }

    default boolean isUp() {
        return input() == 265;
    }

    default boolean isDown() {
        return input() == 264;
    }

    default boolean isCycleFocus() {
        return input() == 258;
    }

    default int getDigit() {
        int value = input() - 48;
        return value >= 0 && value <= 9 ? value : NOT_DIGIT;
    }

    default boolean hasAltDown() {
        return (modifiers() & 4) != 0;
    }

    default boolean hasShiftDown() {
        return (modifiers() & 1) != 0;
    }

    default boolean hasControlDown() {
        return (modifiers() & 2) != 0;
    }

    /** Control, or command on macOS (26.1's {@code InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER}). */
    default boolean hasControlDownWithQuirk() {
        return (modifiers() & (Minecraft.ON_OSX ? 8 : 2)) != 0;
    }

    default boolean isSelectAll() {
        return input() == 65 && hasControlDownWithQuirk() && !hasShiftDown() && !hasAltDown();
    }

    default boolean isCopy() {
        return input() == 67 && hasControlDownWithQuirk() && !hasShiftDown() && !hasAltDown();
    }

    default boolean isPaste() {
        return input() == 86 && hasControlDownWithQuirk() && !hasShiftDown() && !hasAltDown();
    }

    default boolean isCut() {
        return input() == 88 && hasControlDownWithQuirk() && !hasShiftDown() && !hasAltDown();
    }

    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.METHOD, ElementType.TYPE_USE})
    @OnlyIn(Dist.CLIENT)
    public @interface Modifiers {
    }
}
