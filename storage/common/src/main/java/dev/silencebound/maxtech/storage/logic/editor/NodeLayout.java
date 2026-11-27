package dev.silencebound.maxtech.storage.logic.editor;

import dev.silencebound.maxtech.lib.api.client.gui.TextFit;
import java.util.function.ToIntFunction;

/**
 * Where things are on a node in the editor (M7L area F), in editor units relative to the node's top left corner. A node
 * is at least {@link #WIDTH} wide and grows with its title and text lines up to {@link #MAX_WIDTH} ({@link #width});
 * its height grows with its port rows, and nodes with a live line ({@link LiveLine}) have room for a second text row.
 * Inputs sit on the left edge and outputs on the right edge, one per row below the title. Pure; the screen and the
 * JUnit tests share it.
 */
public final class NodeLayout {
    /** The narrowest node. */
    public static final int WIDTH = 76;
    /** The widest node; text past it ends with an ellipsis and shows whole in a tooltip. */
    public static final int MAX_WIDTH = 150;
    /** Room around the title in the title bar: the left inset and the note badge on the right. */
    public static final int TITLE_PADDING = 14;
    /** Room around a text line in the body: the insets that keep it clear of the ports. */
    public static final int LINE_PADDING = 8;
    /** The title bar's height. */
    public static final int HEADER = 11;
    /** Distance between port rows. */
    public static final int PORT_PITCH = 10;
    /** The first port row's centre below the node's top. */
    public static final int PORT_TOP = HEADER + 6;
    /** A port's drawn size. */
    public static final int PORT_SIZE = 5;
    /** How far from a port's centre a click still hits it. */
    public static final int PORT_HIT = 4;
    /** Body height of a node with at most one port row, room for one line of summary. */
    public static final int MIN_BODY = 14;
    /** Positions snap to this many units when a node is dropped. */
    public static final int SNAP = 4;
    /** The y of the summary line below the node's top. */
    public static final int SUMMARY_TOP = HEADER + 2;
    /** The y of the live line ({@link LiveLine}) below the node's top: one text row under the summary. */
    public static final int LIVE_TOP = SUMMARY_TOP + PORT_PITCH;

    private NodeLayout() {
    }

    /**
     * A node's width for a title {@code titleWidth} wide and body lines at most {@code lineWidth} wide: wide enough for
     * both, between {@link #WIDTH} and {@link #MAX_WIDTH}.
     */
    public static int width(int titleWidth, int lineWidth) {
        return TextFit.grow(Math.max(titleWidth + TITLE_PADDING, lineWidth + LINE_PADDING), WIDTH, MAX_WIDTH);
    }

    /** The room for the title in a node {@code width} wide. */
    public static int titleRoom(int width) {
        return width - TITLE_PADDING;
    }

    /** The room for a body line in a node {@code width} wide. */
    public static int lineRoom(int width) {
        return width - LINE_PADDING;
    }

    /**
     * {@code title} as shown in the title bar of a node {@code width} wide: whole when it fits, otherwise ending with
     * {@link TextFit#ELLIPSIS} (the screen then shows the whole title as a tooltip). The editor and the controller's
     * NODES diagram both use it.
     */
    public static String fitTitle(String title, int width, ToIntFunction<String> measure) {
        return TextFit.fit(title, titleRoom(width), measure);
    }

    /** {@code line} as shown in the body of a node {@code width} wide, like {@link #fitTitle}. */
    public static String fitLine(String line, int width, ToIntFunction<String> measure) {
        return TextFit.fit(line, lineRoom(width), measure);
    }

    /** The height of a node with {@code inputs} input and {@code outputs} output ports. */
    public static int height(int inputs, int outputs) {
        return height(inputs, outputs, false);
    }

    /**
     * The height of a node with {@code inputs} input and {@code outputs} output ports; with {@code live} its body has
     * room for a second text row, the live line.
     */
    public static int height(int inputs, int outputs, boolean live) {
        int body = live ? MIN_BODY + PORT_PITCH : MIN_BODY;
        return HEADER + Math.max(body, Math.max(inputs, outputs) * PORT_PITCH + 2);
    }

    /** The centre y of port row {@code index}, relative to the node's top. */
    public static int portY(int index) {
        return PORT_TOP + index * PORT_PITCH;
    }

    /** The centre x of an input port (the left edge) or an output port (the right edge) of a {@link #WIDTH} node. */
    public static int portX(boolean output) {
        return portX(output, WIDTH);
    }

    /**
     * The centre x of an input port (the left edge) or an output port (the right edge) of a node {@code width} wide.
     */
    public static int portX(boolean output, int width) {
        return output ? width : 0;
    }

    /** {@code value} snapped to the {@link #SNAP} grid. */
    public static int snap(int value) {
        return Math.floorDiv(value + SNAP / 2, SNAP) * SNAP;
    }

    /** A port hit: {@link #hitIndex} and {@link #hitOutput} read it; {@link #MISS} for none. */
    public static final int MISS = -1;

    /** {@link #portAt(int, int, int, double, double)} for a {@link #WIDTH} node. */
    public static int portAt(int inputs, int outputs, double x, double y) {
        return portAt(inputs, outputs, WIDTH, x, y);
    }

    /**
     * The port of a node {@code width} wide with {@code inputs} and {@code outputs} ports at ({@code x}, {@code y})
     * relative to its top left corner, or {@link #MISS}. Outputs win where both sides are in reach (never on a node
     * this wide).
     */
    public static int portAt(int inputs, int outputs, int width, double x, double y) {
        int hit = side(outputs, true, width, x, y);
        return hit != MISS ? hit : side(inputs, false, width, x, y);
    }

    private static int side(int count, boolean output, int width, double x, double y) {
        if (Math.abs(x - portX(output, width)) > PORT_HIT) {
            return MISS;
        }
        for (int i = 0; i < count; i++) {
            if (Math.abs(y - portY(i)) <= PORT_HIT) {
                return i << 1 | (output ? 1 : 0);
            }
        }
        return MISS;
    }

    /** The port row of a {@link #portAt} hit. */
    public static int hitIndex(int hit) {
        return hit >> 1;
    }

    /** Whether a {@link #portAt} hit is an output. */
    public static boolean hitOutput(int hit) {
        return (hit & 1) == 1;
    }

    /** Whether ({@code x}, {@code y}) relative to the node's corner is on a {@link #WIDTH} node's body or title. */
    public static boolean inside(int inputs, int outputs, double x, double y) {
        return inside(inputs, outputs, false, WIDTH, x, y);
    }

    /**
     * Whether ({@code x}, {@code y}) relative to the node's corner is on the body or title of a node {@code width} wide
     * that may have a live line.
     */
    public static boolean inside(int inputs, int outputs, boolean live, int width, double x, double y) {
        return x >= 0 && y >= 0 && x < width && y < height(inputs, outputs, live);
    }

    /** Whether ({@code x}, {@code y}) relative to the node's corner is on a {@link #WIDTH} node's title bar. */
    public static boolean onHeader(double x, double y) {
        return onHeader(WIDTH, x, y);
    }

    /** Whether ({@code x}, {@code y}) relative to the node's corner is on its title bar, which drags it. */
    public static boolean onHeader(int width, double x, double y) {
        return x >= 0 && y >= 0 && x < width && y < HEADER;
    }
}
