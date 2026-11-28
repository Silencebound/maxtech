package dev.silencebound.maxtech.lib.api.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Layout} measured and arranged: the panel's size and where each leaf sits, in GUI pixels from the panel's top
 * left corner (the layout starts {@link Layout#PADDING} in), plus what is wrong with it. Pure arithmetic over ints, so
 * the server and every client get the same plan. Immutable; any thread.
 *
 * @param panelWidth
 *            the panel's width: the layout's and the padding on both sides
 * @param panelHeight
 *            the panel's height, without the title tab above it
 * @param boxes
 *            every leaf in tree order (depth first, children in order)
 * @param problems
 *            what {@link #check} found; empty when the panel fits and nothing overlaps
 */
public record LayoutPlan(int panelWidth, int panelHeight, List<Box> boxes, List<String> problems) {
    /** The narrowest GUI a panel must fit, with its title tab: the GUI at scale 4 of a 1280x960 window. */
    public static final int FIT_WIDTH = 320;
    /** See {@link #FIT_WIDTH}. */
    public static final int FIT_HEIGHT = 240;

    /** Copies the lists. */
    public LayoutPlan {
        boxes = List.copyOf(boxes);
        problems = List.copyOf(problems);
    }

    /**
     * One placed leaf.
     *
     * @param node
     *            the leaf
     * @param x
     *            its left edge from the panel's left edge
     * @param y
     *            its top edge from the panel's top edge
     * @param width
     *            its width
     * @param height
     *            its height
     */
    public record Box(Layout.Leaf node, int x, int y, int width, int height) {
        /** Whether this box and {@code other} share any pixel. */
        public boolean overlaps(Box other) {
            return width > 0 && height > 0 && other.width > 0 && other.height > 0 && x < other.x + other.width
                    && other.x < x + width && y < other.y + other.height && other.y < y + height;
        }
    }

    /** Measures and arranges {@code root} and checks the result ({@link #check}). */
    public static LayoutPlan of(Layout root) {
        List<Box> boxes = new ArrayList<>();
        int[] size = measure(root);
        arrange(root, Layout.PADDING, Layout.PADDING, boxes);
        int width = size[0] + 2 * Layout.PADDING;
        int height = size[1] + 2 * Layout.PADDING;
        return new LayoutPlan(width, height, boxes, check(width, height, boxes));
    }

    /**
     * The problems of a panel {@code width} by {@code height} holding {@code boxes}: the panel and its title tab
     * ({@link Layout#TITLE_TAB}) must fit {@link #FIT_WIDTH} by {@link #FIT_HEIGHT}, and no two boxes may overlap
     * (spacers are empty room and never count).
     */
    public static List<String> check(int width, int height, List<Box> boxes) {
        List<String> problems = new ArrayList<>();
        if (width > FIT_WIDTH || height + Layout.TITLE_TAB > FIT_HEIGHT) {
            problems.add("Panel " + width + "x" + height + " with its title tab does not fit " + FIT_WIDTH + "x"
                    + FIT_HEIGHT);
        }
        for (int i = 0; i < boxes.size(); i++) {
            Box a = boxes.get(i);
            if (a.node() instanceof Layout.Spacer) {
                continue;
            }
            for (int j = i + 1; j < boxes.size(); j++) {
                Box b = boxes.get(j);
                if (!(b.node() instanceof Layout.Spacer) && a.overlaps(b)) {
                    problems.add(name(a, i) + " overlaps " + name(b, j));
                }
            }
        }
        return problems;
    }

    private static String name(Box box, int index) {
        String what = switch (box.node()) {
            case Layout.Slots slots -> "slots " + slots.group().index();
            case Layout.Tank tank -> "tank " + tank.value().index();
            case Layout.Energy energy -> "energy " + energy.value().index();
            case Layout.Progress progress -> "progress " + progress.value().index();
            case Layout.Label label -> "label " + label.key();
            case Layout.Value value -> "value " + value.value().index();
            case Layout.Status status -> "status " + status.value().index();
            case Layout.Spacer spacer -> "spacer";
            case Layout.PlayerInventory inventory -> "player inventory";
        };
        return what + " (box " + index + " at " + box.x() + "," + box.y() + ")";
    }

    /** Width and height of {@code node}. */
    private static int[] measure(Layout node) {
        return switch (node) {
            case Layout.Leaf leaf -> new int[]{leaf.width(), leaf.height()};
            case Layout.Row row -> line(row.children(), row.gap(), true);
            case Layout.Column column -> line(column.children(), column.gap(), false);
        };
    }

    private static int[] line(List<Layout> children, int gap, boolean across) {
        int along = 0;
        int cross = 0;
        for (int i = 0; i < children.size(); i++) {
            int[] size = measure(children.get(i));
            along += size[across ? 0 : 1] + (i > 0 ? gap : 0);
            cross = Math.max(cross, size[across ? 1 : 0]);
        }
        return across ? new int[]{along, cross} : new int[]{cross, along};
    }

    private static void arrange(Layout node, int x, int y, List<Box> out) {
        switch (node) {
            case Layout.Leaf leaf -> out.add(new Box(leaf, x, y, leaf.width(), leaf.height()));
            case Layout.Row row -> {
                int height = measure(row)[1];
                int cursor = x;
                for (Layout child : row.children()) {
                    int[] size = measure(child);
                    arrange(child, cursor, y + (height - size[1]) / 2, out);
                    cursor += size[0] + row.gap();
                }
            }
            case Layout.Column column -> {
                int width = measure(column)[0];
                int cursor = y;
                for (Layout child : column.children()) {
                    int[] size = measure(child);
                    arrange(child, x + (width - size[0]) / 2, cursor, out);
                    cursor += size[1] + column.gap();
                }
            }
        }
    }
}
