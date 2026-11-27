package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The one Accent scroll bar: a vertical track with a thumb sized to the visible share of the content. Dragging the
 * thumb scrolls without the thumb jumping under the mouse, a click on the track jumps there, and the wheel over the bar
 * scrolls by {@link Model#step}. What scrolls is the {@link Model}'s business; the bar only reads and sets its offset.
 * The thumb math ({@link #thumbSize}, {@link #thumbPos}, {@link #offsetFor}) is plain arithmetic for JUnit. Client
 * thread only.
 */
@ApiStatus.Experimental
public final class ScrollBar extends AbstractWidget {
    /** The usual width. */
    public static final int WIDTH = 6;
    /** The shortest thumb, so it stays easy to grab. */
    public static final int MIN_THUMB = 12;

    /** What the bar scrolls, in any unit (pixels, rows). */
    public interface Model {
        /** The content's whole length. */
        int total();

        /** How much of it shows at once. */
        int visible();

        /** How far it is scrolled, 0 to {@code total - visible}. */
        int offset();

        /** Scrolls to {@code offset}, kept in range by the model. */
        void scrollTo(int offset);

        /** How far one wheel notch scrolls. */
        default int step() {
            return 1;
        }
    }

    private final Model model;
    private @Nullable Runnable onGrab;
    /** Where on the thumb the drag started, so the thumb does not jump under the mouse. */
    private int grab;
    private boolean dragging;
    private AccentTheme theme = AccentTheme.LIB;

    /** A bar {@link #WIDTH} wide over {@code model}. */
    public ScrollBar(int x, int y, int height, Model model) {
        this(x, y, WIDTH, height, model);
    }

    /** A bar of any width over {@code model}. */
    public ScrollBar(int x, int y, int width, int height, Model model) {
        super(x, y, width, height, Component.empty());
        this.model = model;
    }

    /** Draws the bar in {@code theme}'s colours instead of Lib's cyan. */
    public ScrollBar theme(AccentTheme theme) {
        this.theme = theme;
        return this;
    }

    /** Runs {@code onGrab} whenever the bar is clicked, for screens that must tell a bar click from a click outside. */
    public ScrollBar onGrab(Runnable onGrab) {
        this.onGrab = onGrab;
        return this;
    }

    /** Whether the content is longer than what shows, so there is anything to scroll. */
    public boolean scrolls() {
        return model.total() > model.visible();
    }

    /** Whether the thumb is being dragged. */
    public boolean dragging() {
        return dragging;
    }

    /** The largest offset for content {@code total} long showing {@code visible} at once. */
    public static int maxOffset(int total, int visible) {
        return Math.max(0, total - visible);
    }

    /**
     * The thumb's length on a track {@code track} long: the visible share of the content, at least {@code min} and at
     * most the track. The whole track when everything shows.
     */
    public static int thumbSize(int track, int visible, int total, int min) {
        if (total <= visible || total <= 0) {
            return Math.max(0, track);
        }
        int share = (int) ((long) track * visible / total);
        return Math.min(track, Math.max(min, share));
    }

    /** The thumb's start, relative to the track's, for {@code offset} out of {@code max}. */
    public static int thumbPos(int track, int thumb, int offset, int max) {
        int room = track - thumb;
        return room <= 0 || max <= 0 ? 0 : Math.round((float) room * Math.clamp(offset, 0, max) / max);
    }

    /** The offset that puts the thumb's start at {@code pos} (relative to the track's), kept between 0 and max. */
    public static int offsetFor(int track, int thumb, int pos, int max) {
        int room = track - thumb;
        if (room <= 0 || max <= 0) {
            return 0;
        }
        return Math.clamp(Math.round((float) pos * max / room), 0, max);
    }

    private int thumb() {
        return thumbSize(height, model.visible(), model.total(), MIN_THUMB);
    }

    private int max() {
        return maxOffset(model.total(), model.visible());
    }

    private int thumbTop() {
        return getY() + thumbPos(height, thumb(), model.offset(), max());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        Runnable grabbed = onGrab;
        if (grabbed != null) {
            grabbed.run();
        }
        int top = thumbTop();
        int thumb = thumb();
        int mouse = (int) event.y();
        dragging = true;
        grab = mouse >= top && mouse < top + thumb ? mouse - top : thumb / 2;
        moveThumb(mouse);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        moveThumb((int) event.y());
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        dragging = false;
    }

    private void moveThumb(int mouseY) {
        model.scrollTo(offsetFor(height, thumb(), mouseY - grab - getY(), max()));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY == 0 || !isMouseOver(x, y) || !scrolls()) {
            return false;
        }
        model.scrollTo(model.offset() - (int) Math.round(scrollY * model.step()));
        return true;
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        // A scroll bar is silent.
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        draw(graphics, getX(), getY(), width, height, model.total(), model.visible(), model.offset(),
                isHovered() || dragging, theme);
    }

    /**
     * Draws a scroll bar without a widget, for lists that scroll only by the wheel or draw under their own scale: the
     * track, and the thumb for {@code offset} of content {@code total} long showing {@code visible} at once (none when
     * everything shows). A bar narrower than 3 pixels draws its thumb as one solid strip. {@code lit} brightens the
     * thumb (hovered, dragged). Allocates nothing.
     */
    public static void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int total, int visible,
            int offset, boolean lit) {
        draw(graphics, x, y, width, height, total, visible, offset, lit, AccentTheme.LIB);
    }

    /** {@code draw} in {@code theme}'s colours. */
    public static void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int total, int visible,
            int offset, boolean lit, AccentTheme theme) {
        graphics.fill(x, y, x + width, y + height, Accent.SLOT_EDGE);
        if (width >= 3) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, Accent.SLOT);
        }
        if (total <= visible) {
            return;
        }
        int thumb = thumbSize(height, visible, total, Math.min(MIN_THUMB, height));
        int top = y + thumbPos(height, thumb, offset, maxOffset(total, visible));
        if (width < 3) {
            graphics.fill(x, top, x + width, top + thumb, lit ? theme.barTop() : theme.accent());
            return;
        }
        graphics.fill(x, top, x + width, top + thumb, theme.accent());
        graphics.fill(x + 1, top + 1, x + width - 1, top + thumb - 1, lit ? theme.barTop() : theme.accentDim());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
    }
}
