package dev.silencebound.maxtech.lib.client.gui;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/**
 * A scrolling grid of entries named by {@code int} id (a {@code SyncedListIndex} view): {@code columns} by {@code rows}
 * cells of {@code cellSize} pixels with a scrollbar on the right. Only the visible rows are drawn, so the cost per
 * frame does not grow with the list. Cells are drawn by a {@link CellRenderer}; clicks with any mouse button on an
 * entry go to a {@link ClickHandler}. The mouse wheel scrolls a row per notch; the thumb can be dragged.
 */
public class VirtualGrid extends AbstractWidget {
    public static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_GAP = 2;
    private static final int MIN_THUMB = 8;
    private static final int TRACK_COLOUR = 0xFF373737;
    private static final int THUMB_COLOUR = 0xFFC6C6C6;
    private static final int THUMB_HOVER_COLOUR = 0xFFFFFFFF;
    private static final int HOVER_COLOUR = 0x80FFFFFF;

    private final int columns;
    private final int rows;
    private final int cellSize;
    private final CellRenderer renderer;
    private final ClickHandler clicks;
    private IntList entries = IntLists.emptyList();
    private int scroll;
    private boolean draggingThumb;

    /** Draws one cell; ({@code x}, {@code y}) is the cell's top left corner. */
    @FunctionalInterface
    public interface CellRenderer {
        void render(GuiGraphicsExtractor graphics, int id, int x, int y, boolean hovered);
    }

    /** A click on the entry {@code id}; {@code event} tells the button and modifiers. */
    @FunctionalInterface
    public interface ClickHandler {
        void clicked(int id, MouseButtonEvent event);
    }

    public VirtualGrid(int x, int y, int columns, int rows, int cellSize, Component narration, CellRenderer renderer,
            ClickHandler clicks) {
        super(x, y, columns * cellSize + SCROLLBAR_GAP + SCROLLBAR_WIDTH, rows * cellSize, narration);
        this.columns = columns;
        this.rows = rows;
        this.cellSize = cellSize;
        this.renderer = renderer;
        this.clicks = clicks;
    }

    /** Shows {@code ids} in this order; keeps the scroll position where the new list allows it. */
    public void setEntries(IntList ids) {
        entries = ids;
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    /** The first visible row. */
    public int scroll() {
        return scroll;
    }

    public void setScroll(int row) {
        scroll = Math.clamp(row, 0, maxScroll());
    }

    /** The entry id under the mouse, or -1 for an empty cell, the scrollbar or outside the grid. */
    public int entryAt(double mouseX, double mouseY) {
        int column = (int) Math.floor((mouseX - getX()) / cellSize);
        int row = (int) Math.floor((mouseY - getY()) / cellSize);
        if (mouseX < getX() || mouseY < getY() || column >= columns || row >= rows) {
            return -1;
        }
        int index = (scroll + row) * columns + column;
        return index < entries.size() ? entries.getInt(index) : -1;
    }

    private int maxScroll() {
        return GridScroll.maxScroll(entries.size(), columns, rows);
    }

    private int trackX() {
        return getX() + columns * cellSize + SCROLLBAR_GAP;
    }

    private int thumbHeight() {
        return GridScroll.thumbHeight(getHeight(), entries.size(), columns, rows, MIN_THUMB);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int hovered = isHovered() ? entryAt(mouseX, mouseY) : -1;
        int first = scroll * columns;
        int end = Math.min(entries.size(), first + rows * columns);
        for (int index = first; index < end; index++) {
            int cell = index - first;
            int cellX = getX() + (cell % columns) * cellSize;
            int cellY = getY() + (cell / columns) * cellSize;
            int id = entries.getInt(index);
            renderer.render(graphics, id, cellX, cellY, id == hovered);
            if (id == hovered) {
                graphics.fill(cellX, cellY, cellX + cellSize, cellY + cellSize, HOVER_COLOUR);
            }
        }
        int trackX = trackX();
        graphics.fill(trackX, getY(), trackX + SCROLLBAR_WIDTH, getBottom(), TRACK_COLOUR);
        int thumb = thumbHeight();
        int thumbY = getY() + GridScroll.thumbOffset(scroll, maxScroll(), getHeight(), thumb);
        boolean overThumb = mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH && mouseY >= thumbY
                && mouseY < thumbY + thumb;
        graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumb,
                overThumb || draggingThumb ? THUMB_HOVER_COLOUR : THUMB_COLOUR);
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
        return buttonInfo.button() >= 0 && buttonInfo.button() <= 2;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() >= trackX()) {
            draggingThumb = event.buttonInfo().button() == 0;
            dragThumbTo(event.y());
            return;
        }
        int id = entryAt(event.x(), event.y());
        if (id >= 0) {
            clicks.clicked(id, event);
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (draggingThumb) {
            dragThumbTo(event.y());
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        draggingThumb = false;
    }

    private void dragThumbTo(double mouseY) {
        int thumb = thumbHeight();
        scroll = GridScroll.scrollForThumb(mouseY - getY() - thumb / 2.0, maxScroll(), getHeight(), thumb);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY == 0 || !isMouseOver(x, y)) {
            return false;
        }
        setScroll(scroll - (int) Math.signum(scrollY));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
