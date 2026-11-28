package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A short notice inside a screen in the Accent look ("Copied", "Pattern encoded", "Not enough power"): a graphite box
 * with an edge and a coloured left strip in its {@link Kind}'s colour, a title and an optional second line. It shows
 * for a number of milliseconds of client time ({@code Util.getMillis()}) and then disappears; showing another replaces
 * it. The screen draws it last, anchored by its bottom right corner, so it sits above the rest. No animation.
 *
 * <p>
 * For a notice while no MaxTech screen is open (a craft finished, a device went offline), {@link #notice} posts a
 * vanilla system toast in the top right corner instead.
 *
 * <p>
 * Mutable, one per screen; client thread only. {@link #visible(long, long, int)}, {@link #width(int, int)} and
 * {@link #height(int, boolean)} are plain arithmetic.
 */
@ApiStatus.Experimental
public final class AccentToast {
    /** How long a toast shows by default. */
    public static final int DEFAULT_MILLIS = 2500;
    /** The coloured left strip. */
    public static final int STRIP = 2;
    /** Room between the edge and the text. */
    public static final int PAD = 4;
    /** Where the text starts, from the toast's left edge: the edge, the strip and the pad. */
    public static final int TEXT_X = 1 + STRIP + PAD;
    /** The id every notice outside screens shares, so a new one replaces the last instead of stacking. */
    private static final SystemToast.SystemToastId NOTICE = new SystemToast.SystemToastId();

    /** What the toast reports; sets its colour. */
    public enum Kind {
        /** News: cyan, or the toast's theme's bright gauge colour ({@link AccentToast#theme}). */
        INFO(Accent.BAR_TOP),
        /** Something needs a look: amber. */
        WARN(Accent.AMBER),
        /** Something failed: red. */
        ERROR(Accent.RED);

        private final int colour;

        Kind(int colour) {
            this.colour = colour;
        }

        /** The colour of the strip on the toast's left edge (ARGB) in Lib's theme. */
        public int colour() {
            return colour;
        }
    }

    private AccentTheme theme = AccentTheme.LIB;
    private Component title = Component.empty();
    private Component detail = Component.empty();
    private Kind kind = Kind.INFO;
    private long shownAt;
    private int millis;

    /**
     * Draws in {@code theme}'s colours instead of Lib's cyan: the title in its text colour and an {@link Kind#INFO}
     * strip in its bright gauge colour. Warnings and errors keep their amber and red, as on every MaxTech screen.
     */
    public AccentToast theme(AccentTheme value) {
        theme = value;
        return this;
    }

    /** Shows {@code title} and {@code detail} (empty for one line) from {@code now} for {@code millis}. */
    public void show(Component title, Component detail, Kind kind, long now, int millis) {
        this.title = title;
        this.detail = detail;
        this.kind = kind;
        this.shownAt = now;
        this.millis = millis;
    }

    /** Shows a toast for {@link #DEFAULT_MILLIS}. */
    public void show(Component title, Component detail, Kind kind, long now) {
        show(title, detail, kind, now, DEFAULT_MILLIS);
    }

    /** Hides the toast now. */
    public void hide() {
        millis = 0;
    }

    /** Whether the toast shows at {@code now}. */
    public boolean visible(long now) {
        return visible(shownAt, now, millis);
    }

    /** Whether a toast shown at {@code shownAt} for {@code millis} still shows at {@code now}. */
    public static boolean visible(long shownAt, long now, int millis) {
        return millis > 0 && now >= shownAt && now - shownAt < millis;
    }

    /** A toast's width for a title {@code titleWidth} wide and a second line {@code detailWidth} wide. */
    public static int width(int titleWidth, int detailWidth) {
        return TEXT_X + Math.max(titleWidth, detailWidth) + PAD + 1;
    }

    /** A toast's height for text lines {@code lineHeight} tall, one or two lines. */
    public static int height(int lineHeight, boolean detail) {
        return 2 * PAD + lineHeight + (detail ? 2 + lineHeight : 0);
    }

    /**
     * Whether ({@code mouseX}, {@code mouseY}) is over the toast drawn with its bottom right corner at ({@code right},
     * {@code bottom}) while it shows at {@code now}, so a screen can act on a click on it.
     */
    public boolean contains(Font font, int right, int bottom, long now, double mouseX, double mouseY) {
        if (!visible(now)) {
            return false;
        }
        boolean two = !detail.getString().isEmpty();
        int width = width(TextScale.width(font, title), two ? TextScale.width(font, detail) : 0);
        int height = height(TextScale.lineHeight(font) - 1, two);
        return mouseX >= right - width && mouseX < right && mouseY >= bottom - height && mouseY < bottom;
    }

    /** Draws the toast with its bottom right corner at ({@code right}, {@code bottom}) when it shows at {@code now}. */
    public void draw(GuiGraphicsExtractor graphics, Font font, int right, int bottom, long now) {
        if (!visible(now)) {
            return;
        }
        int lineHeight = TextScale.lineHeight(font) - 1;
        boolean two = !detail.getString().isEmpty();
        int width = width(TextScale.width(font, title), two ? TextScale.width(font, detail) : 0);
        int height = height(lineHeight, two);
        int x = right - width;
        int y = bottom - height;
        Accent.box(graphics, x, y, width, height);
        graphics.fill(x + 1, y + 1, x + 1 + STRIP, bottom - 1, stripColour(kind, theme));
        int textY = y + PAD;
        TextScale.text(graphics, font, title, x + TEXT_X, textY, theme.text(), false);
        if (two) {
            TextScale.text(graphics, font, detail, x + TEXT_X, textY + lineHeight + 2, Accent.LABEL, false);
        }
    }

    /** The strip colour of {@code kind} in {@code theme}: the theme's bright gauge colour for news. */
    public static int stripColour(Kind kind, AccentTheme theme) {
        return kind == Kind.INFO ? theme.barTop() : kind.colour();
    }

    /**
     * Posts a notice outside screens as a vanilla system toast (top right, about five seconds); a newer notice replaces
     * one still showing. {@code message} may be null for a title alone.
     */
    public static void notice(Component title, @Nullable Component message) {
        SystemToast.addOrUpdate(Minecraft.getInstance().getToastManager(), NOTICE, title, message);
    }
}
