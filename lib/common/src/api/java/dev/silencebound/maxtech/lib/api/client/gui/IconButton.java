package dev.silencebound.maxtech.lib.api.client.gui;

import org.jetbrains.annotations.ApiStatus;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A square Accent button showing an {@link Icon}, {@link #SIZE} wide by default, with its meaning in a vanilla widget
 * tooltip. Three kinds: a plain button ({@link #of}), a toggle that is lit while on ({@link #toggle}) and a cycle
 * button that shows one icon per state and steps forward on a left click and back on a right click ({@link #cycle}).
 * The tooltip text is built only when the shown state (the state index, and on or off for a toggle) changes, or after
 * {@link #refreshTooltip} when it depends on something else. Client thread only.
 */
@ApiStatus.Experimental
public final class IconButton extends AbstractButton {
    /** The default edge length. */
    public static final int SIZE = 16;

    /** What a click on a cycle button does: {@code forward} is true for a left click, false for a right click. */
    @FunctionalInterface
    public interface Stepper {
        /** Moves to the next state, or the previous one when {@code forward} is false. */
        void step(boolean forward);
    }

    private final List<Icon> icons;
    private final IntSupplier state;
    private final IntFunction<Component> tooltip;
    private final @Nullable BooleanSupplier on;
    private final Stepper stepper;
    private final boolean cycles;
    private BooleanSupplier enabled = () -> true;
    /** The state the current tooltip was built for; -1 until the first frame or after {@link #refreshTooltip}. */
    private int shownKey = -1;

    private IconButton(int x, int y, int size, List<Icon> icons, IntSupplier state, IntFunction<Component> tooltip,
            @Nullable BooleanSupplier on, Stepper stepper, boolean cycles) {
        super(x, y, size, size, tooltip.apply(Math.floorMod(state.getAsInt(), count(icons))));
        this.icons = List.copyOf(icons);
        this.state = state;
        this.tooltip = tooltip;
        this.on = on;
        this.stepper = stepper;
        this.cycles = cycles;
    }

    private static int count(List<Icon> icons) {
        if (icons.isEmpty()) {
            throw new IllegalArgumentException("an icon button needs an icon");
        }
        return icons.size();
    }

    /** A plain button. */
    public static IconButton of(int x, int y, Icon icon, Component tooltip, Runnable action) {
        return new IconButton(x, y, SIZE, List.of(icon), () -> 0, state -> tooltip, null, forward -> action.run(),
                false);
    }

    /**
     * A plain button whose tooltip is built by {@code tooltip} on the first frame and again after each
     * {@link #refreshTooltip} (an icon that changes what it stands for).
     */
    public static IconButton of(int x, int y, Icon icon, Supplier<Component> tooltip, Runnable action) {
        return new IconButton(x, y, SIZE, List.of(icon), () -> 0, state -> tooltip.get(), null, forward -> action.run(),
                false);
    }

    /** A toggle: lit while {@code on} is true; the tooltip usually names the current state. */
    public static IconButton toggle(int x, int y, Icon icon, BooleanSupplier on, Supplier<Component> tooltip,
            Runnable action) {
        return new IconButton(x, y, SIZE, List.of(icon), () -> 0, state -> tooltip.get(), on, forward -> action.run(),
                false);
    }

    /**
     * A cycle button: shows {@code icons.get(state)} (taken modulo the icon count) with {@code tooltip.apply(state)};
     * left clicks step forward, right clicks back.
     */
    public static IconButton cycle(int x, int y, List<Icon> icons, IntSupplier state, IntFunction<Component> tooltip,
            Stepper stepper) {
        return new IconButton(x, y, SIZE, icons, state, tooltip, null, stepper, true);
    }

    /** Resizes the square button, for side columns that use 18. */
    public IconButton size(int size) {
        setSize(size, size);
        return this;
    }

    /** Greys the button out and makes it inert while {@code enabled} is false. */
    public IconButton enabled(BooleanSupplier enabled) {
        this.enabled = enabled;
        return this;
    }

    /** The state shown now, 0 for plain and toggle buttons. */
    public int current() {
        return Math.floorMod(state.getAsInt(), icons.size());
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == 0 || (cycles && buttonInfo.button() == 1);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (enabled.getAsBoolean()) {
            stepper.step(event.button() != 1);
        }
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (enabled.getAsBoolean()) {
            stepper.step(!input.hasShiftDown());
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int index = current();
        BooleanSupplier lit = on;
        boolean isOn = lit != null && lit.getAsBoolean();
        int key = index * 2 + (isOn ? 1 : 0);
        if (key != shownKey) {
            shownKey = key;
            Component text = tooltip.apply(index);
            setMessage(text);
            setTooltip(text.getString().isEmpty() ? null : Tooltip.create(text));
        }
        active = enabled.getAsBoolean();
        boolean hovered = active && isHovered();
        if (isOn) {
            Accent.litFace(graphics, getX(), getY(), width, height);
        } else {
            Accent.face(graphics, getX(), getY(), width, height, hovered);
        }
        int colour = !active ? Accent.LABEL : hovered || isOn ? Accent.BAR_TOP : Accent.TEXT;
        icons.get(index).drawCentred(graphics, getX(), getY(), width, height, colour);
    }

    /** Builds the tooltip again on the next frame, for a tooltip that depends on more than the shown state. */
    public void refreshTooltip() {
        shownKey = -1;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
