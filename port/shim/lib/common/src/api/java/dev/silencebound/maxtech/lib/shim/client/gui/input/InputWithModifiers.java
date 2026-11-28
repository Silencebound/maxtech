package dev.silencebound.maxtech.lib.shim.client.gui.input;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
@OnlyIn(Dist.CLIENT)
public interface InputWithModifiers {
    int NOT_DIGIT = 0;
    int input();
    @InputWithModifiers.Modifiers
    int modifiers();
    default boolean isSelection() { throw new UnsupportedOperationException("port stub"); }
    default boolean isConfirmation() { throw new UnsupportedOperationException("port stub"); }
    default boolean isEscape() { throw new UnsupportedOperationException("port stub"); }
    default boolean isLeft() { throw new UnsupportedOperationException("port stub"); }
    default boolean isRight() { throw new UnsupportedOperationException("port stub"); }
    default boolean isUp() { throw new UnsupportedOperationException("port stub"); }
    default boolean isDown() { throw new UnsupportedOperationException("port stub"); }
    default boolean isCycleFocus() { throw new UnsupportedOperationException("port stub"); }
    default int getDigit() { throw new UnsupportedOperationException("port stub"); }
    default boolean hasAltDown() { throw new UnsupportedOperationException("port stub"); }
    default boolean hasShiftDown() { throw new UnsupportedOperationException("port stub"); }
    default boolean hasControlDown() { throw new UnsupportedOperationException("port stub"); }
    default boolean hasControlDownWithQuirk() { throw new UnsupportedOperationException("port stub"); }
    default boolean isSelectAll() { throw new UnsupportedOperationException("port stub"); }
    default boolean isCopy() { throw new UnsupportedOperationException("port stub"); }
    default boolean isPaste() { throw new UnsupportedOperationException("port stub"); }
    default boolean isCut() { throw new UnsupportedOperationException("port stub"); }
    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.METHOD, ElementType.TYPE_USE})
    @OnlyIn(Dist.CLIENT)
    public @interface Modifiers {
    }
}
