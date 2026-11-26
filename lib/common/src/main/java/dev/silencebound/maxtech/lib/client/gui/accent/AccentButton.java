package dev.silencebound.maxtech.lib.client.gui.accent;

import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The one text button of the Accent look (owner decision 2026-10-18, "one button set"): {@link #HEIGHT} high, or
 * {@link #COMPACT_HEIGHT} for the compact variant, and as wide as {@link #width(int)} gives for its label unless the
 * layout fixes a width. Greyed out and inert while its {@code enabled} check is false. The label is fixed or read every
 * frame ({@link #label(Supplier)}); a label too wide for a fixed width is drawn a little smaller or cut, and then shows
 * whole as the tooltip unless the button has a tooltip of its own. Tooltips are vanilla widget tooltips
 * ({@link #setTooltip}, or {@link #tooltip(IntSupplier, IntFunction)} for one that follows a state). A right click runs
 * {@link #onRightClick} when one is set (step buttons that go back). Client thread only.
 */
public class AccentButton extends AbstractButton {
    /** The normal height. */
    public static final int HEIGHT = 16;
    /** The compact variant's height, for rows of small step buttons. */
    public static final int COMPACT_HEIGHT = 12;
    /** Room left and right of the label. */
    public static final int PAD = 4;
    /** Widths are whole multiples of this, so buttons line up on a grid. */
    public static final int GRID = 4;

    private Consumer<InputWithModifiers> action;
    private @Nullable Runnable back;
    private BooleanSupplier enabled = () -> true;
    private BooleanSupplier lit = () -> false;
    private @Nullable Supplier<Component> label;
    private @Nullable IntSupplier tooltipKey;
    private @Nullable IntFunction<Component> tooltip;
    /** The key the current tooltip was built for; a new tooltip is built only when it changes. */
    private int shownKey = Integer.MIN_VALUE;
    /** Whether it has a tooltip of its own, so a cut label does not add its own. */
    private boolean explained;

    public AccentButton(int x, int y, int width, int height, Component label, Runnable action) {
        super(x, y, width, height, label);
        this.action = input -> action.run();
    }

    /** A button that is greyed out and inert while {@code enabled} is false. */
    public AccentButton(int x, int y, int width, int height, Component label, BooleanSupplier enabled,
            Runnable action) {
        this(x, y, width, height, label, action);
        this.enabled = enabled;
    }

    /** A {@link #HEIGHT} high button as wide as its label needs ({@link #width(Font, Component...)}). */
    public static AccentButton of(Font font, int x, int y, Component label, Runnable action) {
        return new AccentButton(x, y, width(font, label), HEIGHT, label, action);
    }

    /** A {@link #COMPACT_HEIGHT} high button as wide as its label needs. */
    public static AccentButton compact(Font font, int x, int y, Component label, Runnable action) {
        return new AccentButton(x, y, width(font, label), COMPACT_HEIGHT, label, action);
    }

    /**
     * A button whose label is read every frame and whose action sees the modifier keys (shift for larger steps).
     */
    public static AccentButton dynamic(int x, int y, int width, int height, Supplier<Component> label,
            BooleanSupplier enabled, Consumer<InputWithModifiers> action) {
        AccentButton button = new AccentButton(x, y, width, height, label.get(), enabled, () -> {
        });
        button.action = action;
        button.label = label;
        return button;
    }

    /** The width rule: the label plus {@link #PAD} on each side, rounded up to a multiple of {@link #GRID}. */
    public static int width(int labelWidth) {
        int raw = Math.max(0, labelWidth) + 2 * PAD;
        return (raw + GRID - 1) / GRID * GRID;
    }

    /**
     * The width that shows the widest of {@code labels} whole at the current text size; pass every label a changing
     * button can show so it keeps one width.
     */
    public static int width(Font font, Component... labels) {
        int widest = 0;
        for (Component label : labels) {
            widest = Math.max(widest, TextScale.width(font, label));
        }
        return width(widest);
    }

    /** Reads the label every frame from {@code label}. */
    public AccentButton label(Supplier<Component> label) {
        this.label = label;
        setMessage(label.get());
        return this;
    }

    /** Greys the button out and makes it inert while {@code enabled} is false. */
    public AccentButton enabled(BooleanSupplier enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Draws the button lit while {@code lit} is true: the current choice of a row of mode tabs, a toggle that is on. A
     * lit button still acts when pressed; the action decides whether pressing the current choice does anything.
     */
    public AccentButton lit(BooleanSupplier lit) {
        this.lit = lit;
        return this;
    }

    /**
     * A tooltip that follows a state: {@code key} is read every frame (cheap, no allocation), and {@code tooltip}
     * builds the text for a key only when the key changes. An empty text shows no tooltip.
     */
    public AccentButton tooltip(IntSupplier key, IntFunction<Component> tooltip) {
        this.tooltipKey = key;
        this.tooltip = tooltip;
        this.shownKey = Integer.MIN_VALUE;
        return this;
    }

    /** A fixed vanilla tooltip. */
    public AccentButton tooltip(Component tooltip) {
        setTooltip(Tooltip.create(tooltip));
        return this;
    }

    /** What a right click does; without one, right clicks are ignored. */
    public AccentButton onRightClick(Runnable back) {
        this.back = back;
        return this;
    }

    /** Whether the button can act now (read every frame). */
    public boolean enabledNow() {
        return enabled.getAsBoolean();
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == 0 || (buttonInfo.button() == 1 && back != null);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        Runnable backward = back;
        if (event.button() == 1 && backward != null) {
            if (enabled.getAsBoolean()) {
                backward.run();
            }
            return;
        }
        onPress(event);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (enabled.getAsBoolean()) {
            action.accept(input);
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Supplier<Component> current = label;
        if (current != null) {
            setMessage(current.get());
        }
        IntSupplier key = tooltipKey;
        IntFunction<Component> tip = tooltip;
        if (key != null && tip != null) {
            int now = key.getAsInt();
            if (now != shownKey) {
                shownKey = now;
                Component text = tip.apply(now);
                setTooltip(text.getString().isEmpty() ? null : Tooltip.create(text));
            }
        }
        active = enabled.getAsBoolean();
        Font font = Minecraft.getInstance().font;
        if (!active) {
            Accent.disabledButton(graphics, font, getX(), getY(), width, height, getMessage());
        } else if (lit.getAsBoolean()) {
            Accent.litButton(graphics, font, getX(), getY(), width, height, getMessage());
        } else if (explained) {
            // Its own tooltip explains it; a shortened label must not hide that.
            Accent.button(graphics, font, getX(), getY(), width, height, getMessage(), isHovered());
        } else {
            Accent.button(graphics, font, getX(), getY(), width, height, getMessage(), isHovered(), mouseX, mouseY);
        }
    }

    @Override
    public void setTooltip(@Nullable Tooltip tooltip) {
        super.setTooltip(tooltip);
        explained = tooltip != null;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
