package dev.silencebound.maxtech.lib.api.client.gui;

import org.jetbrains.annotations.ApiStatus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * A cheap check that a screen's laid-out pieces stay on the screen and that its widgets do not overlap, plus the
 * placement math screens share. Minecraft never makes the GUI smaller than 320 x 240 scaled pixels (it picks a lower
 * GUI scale instead), so a layout that passes at that size passes everywhere. The check is plain arithmetic for JUnit;
 * Lib also runs it on every MaxTech screen in dev runs and logs what it finds. Client only for {@link #widgets}; the
 * rest is pure.
 */
@ApiStatus.Experimental
public final class LayoutCheck {
    /** The smallest GUI Minecraft lays out: it lowers the GUI scale rather than go below this. */
    public static final int MIN_GUI_WIDTH = 320;
    /** See {@link #MIN_GUI_WIDTH}. */
    public static final int MIN_GUI_HEIGHT = 240;

    private LayoutCheck() {
    }

    /**
     * A named rectangle in screen coordinates.
     *
     * @param name
     *            what the problems call it
     * @param x
     *            the left edge
     * @param y
     *            the top edge
     * @param width
     *            the width
     * @param height
     *            the height
     */
    public record Box(String name, int x, int y, int width, int height) {
        /** The right edge, exclusive. */
        public int right() {
            return x + width;
        }

        /** The bottom edge, exclusive. */
        public int bottom() {
            return y + height;
        }

        /** Whether it covers no pixel; empty boxes are never reported. */
        public boolean empty() {
            return width <= 0 || height <= 0;
        }

        /** Whether the two share at least one pixel; boxes that only touch do not overlap. */
        public boolean overlaps(Box other) {
            return !empty() && !other.empty() && x < other.right() && other.x < right() && y < other.bottom()
                    && other.y < bottom();
        }

        /** Whether it lies wholly on a screen {@code screenWidth} by {@code screenHeight}. */
        public boolean inside(int screenWidth, int screenHeight) {
            return x >= 0 && y >= 0 && right() <= screenWidth && bottom() <= screenHeight;
        }
    }

    /**
     * What is wrong with a layout on a screen {@code screenWidth} by {@code screenHeight}: every box in {@code panels}
     * or {@code widgets} that leaves the screen, and every pair of {@code widgets} that overlap. Panels may hold
     * widgets, so they are only checked against the screen's edges. Empty when all is well.
     */
    public static List<String> problems(int screenWidth, int screenHeight, List<Box> panels, List<Box> widgets) {
        List<String> out = new ArrayList<>();
        for (Box box : panels) {
            offScreen(box, screenWidth, screenHeight, out);
        }
        for (int i = 0; i < widgets.size(); i++) {
            Box box = widgets.get(i);
            offScreen(box, screenWidth, screenHeight, out);
            for (int j = i + 1; j < widgets.size(); j++) {
                Box other = widgets.get(j);
                if (box.overlaps(other)) {
                    out.add(box.name() + " overlaps " + other.name());
                }
            }
        }
        return out;
    }

    private static void offScreen(Box box, int screenWidth, int screenHeight, List<String> out) {
        if (!box.empty() && !box.inside(screenWidth, screenHeight)) {
            out.add(box.name() + " at " + box.x() + "," + box.y() + " size " + box.width() + "x" + box.height()
                    + " leaves the " + screenWidth + "x" + screenHeight + " screen");
        }
    }

    /**
     * Where a panel {@code size} long starts on an axis {@code available} long when a strip {@code header} long sticks
     * out before it (a title tab above a panel): centred while the strip fits too; otherwise the panel stays wholly on
     * the screen and the strip gets what is left; a panel longer than the screen is centred like vanilla's.
     */
    public static int place(int available, int size, int header) {
        int free = available - size;
        if (free >= 2 * header) {
            return free / 2;
        }
        if (free >= header) {
            return header;
        }
        return free >= 0 ? free : free / 2;
    }

    /** How many rows {@code rowHeight} tall fit in {@code room}, kept between {@code min} and {@code max}. */
    public static int rows(int room, int rowHeight, int min, int max) {
        return Math.clamp(rowHeight <= 0 ? max : room / rowHeight, min, max);
    }

    /** The boxes of the visible widgets among {@code children}, named by their class and message. */
    public static List<Box> widgets(List<? extends GuiEventListener> children) {
        List<Box> out = new ArrayList<>(children.size());
        for (GuiEventListener child : children) {
            if (child instanceof AbstractWidget widget && widget.visible) {
                out.add(new Box(widget.getClass().getSimpleName() + " \"" + widget.getMessage().getString() + "\"",
                        widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
            }
        }
        return out;
    }
}
