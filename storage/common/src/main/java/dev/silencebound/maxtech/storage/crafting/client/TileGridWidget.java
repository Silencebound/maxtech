package dev.silencebound.maxtech.storage.crafting.client;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.text.TextFit;
import dev.silencebound.maxtech.storage.crafting.request.PlanView;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The AE2-style tile grid of the crafting screens: one tile per key with its item and up to three count lines. A plan
 * shows "Stored" (taken from storage), "Crafting" and "Missing" (red tile); a running job "Waiting" with "not in
 * storage" (amber tile), "Crafting" (in flight), "Scheduled" (still to send) and "Stored" (held in the CPU). The
 * tooltip has every line; the wheel scrolls. Client only.
 */
public final class TileGridWidget extends AbstractWidget {
    static final int TILE_HEIGHT = 30;
    private static final int LINES = 3;

    /** What the counts of the shown tiles mean. */
    public enum Kind {
        /** A plan: taken from storage, to craft, missing. */
        PLAN,
        /** A running job: held in the CPU, in flight, to send, waiting for. */
        STATUS
    }

    /** One text line of a tile. */
    record Line(Component text, int colour) {
    }

    private final Font font;
    private final int columns;
    private Kind kind = Kind.PLAN;
    private List<PlanView.Tile> tiles = List.of();
    private int scroll;
    private @Nullable Component emptyText;

    public TileGridWidget(Font font, int x, int y, int width, int height, int columns) {
        super(x, y, width, height, Component.translatable(RequestContent.VIEW_TILES_KEY));
        this.font = font;
        this.columns = columns;
    }

    /** Shows {@code next}; keeps the scroll position when the number of rows stays. */
    public void setTiles(List<PlanView.Tile> next, Kind nextKind) {
        if (rowsOf(next.size()) != rowsOf(tiles.size()) || nextKind != kind) {
            scroll = 0;
        }
        tiles = next;
        kind = nextKind;
    }

    /** What the grid says while it has no tiles; null says nothing. */
    public void setEmptyText(@Nullable Component text) {
        emptyText = text;
    }

    private int rowsOf(int count) {
        return (count + columns - 1) / columns;
    }

    private int visibleRows() {
        return height / TILE_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, rowsOf(tiles.size()) - visibleRows());
    }

    private int tileWidth() {
        return (width - 4) / columns;
    }

    /** The lines of {@code tile}, every one; the tile draws the first three. */
    static List<Line> lines(PlanView.Tile tile, Kind kind) {
        StackKey key = tile.key();
        List<Line> out = new ArrayList<>(5);
        if (kind == Kind.PLAN) {
            add(out, RequestContent.TILE_STORED_KEY, key, tile.stored(), AccentFrame.TEXT);
            add(out, RequestContent.TILE_CRAFTING_KEY, key, tile.crafting(), AccentFrame.TEXT);
            add(out, RequestContent.TILE_MISSING_KEY, key, tile.missing(), StatusPalette.bad(), StatusPalette.Tone.BAD);
        } else {
            if (tile.missing() > 0) {
                add(out, RequestContent.TILE_WAITING_KEY, key, tile.missing(), StatusPalette.warn(),
                        StatusPalette.Tone.WARN);
                out.add(new Line(Component.literal(StatusPalette.mark(StatusPalette.Tone.WARN))
                        .append(Component.translatable(RequestContent.TILE_NOT_STORED_KEY)), StatusPalette.warn()));
            }
            add(out, RequestContent.TILE_CRAFTING_KEY, key, tile.crafting(), AccentFrame.TEXT);
            add(out, RequestContent.TILE_SCHEDULED_KEY, key, tile.scheduled(), AccentFrame.LABEL);
            add(out, RequestContent.TILE_STORED_KEY, key, tile.stored(), AccentFrame.TEXT);
        }
        return out;
    }

    private static void add(List<Line> out, String translation, StackKey key, long amount, int colour) {
        if (amount > 0) {
            out.add(new Line(Component.translatable(translation, KeyDisplay.amount(key, amount)), colour));
        }
    }

    /** A status line: its colour from the palette and its glyph before the text. */
    private static void add(List<Line> out, String translation, StackKey key, long amount, int colour,
            StatusPalette.Tone tone) {
        if (amount > 0) {
            out.add(new Line(Component.literal(StatusPalette.mark(tone))
                    .append(Component.translatable(translation, KeyDisplay.amount(key, amount))), colour));
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, AccentFrame.SLOT);
        if (tiles.isEmpty()) {
            Component empty = emptyText;
            if (empty != null) {
                TextScale.text(graphics, font, empty, getX() + (width - font.width(empty)) / 2, getY() + height / 2 - 4,
                        AccentFrame.LABEL, false);
            }
            return;
        }
        scroll = Math.clamp(scroll, 0, maxScroll());
        int tileWidth = tileWidth();
        int first = scroll * columns;
        int last = Math.min(tiles.size(), first + visibleRows() * columns);
        for (int i = first; i < last; i++) {
            PlanView.Tile tile = tiles.get(i);
            int x = getX() + 1 + ((i - first) % columns) * tileWidth;
            int y = getY() + 1 + ((i - first) / columns) * TILE_HEIGHT;
            int background = tile.missing() > 0
                    ? StatusPalette.tile(kind == Kind.PLAN ? StatusPalette.Tone.BAD : StatusPalette.Tone.WARN)
                    : AccentFrame.GRAPHITE;
            graphics.fill(x, y, x + tileWidth - 1, y + TILE_HEIGHT - 1, background);
            graphics.item(KeyDisplay.icon(tile.key()), x + 3, y + (TILE_HEIGHT - 17) / 2);
            List<Line> lines = lines(tile, kind);
            int shown = Math.min(LINES, lines.size());
            int textY = y + (TILE_HEIGHT - 1 - shown * 9) / 2 + 1;
            for (int l = 0; l < shown; l++) {
                Line line = lines.get(l);
                // The tile's tooltip has every line whole.
                String text = TextFit.fit(line.text().getString(), tileWidth - 26, font::width);
                TextScale.text(graphics, font, text, x + 22, textY + l * 9, line.colour(), false);
            }
        }
        if (maxScroll() > 0) {
            int track = height;
            int thumb = Math.max(8, track * visibleRows() / rowsOf(tiles.size()));
            int thumbY = getY() + (track - thumb) * scroll / maxScroll();
            graphics.fill(getX() + width - 3, getY(), getX() + width, getY() + height, AccentFrame.SLOT_EDGE);
            graphics.fill(getX() + width - 3, thumbY, getX() + width, thumbY + thumb, AccentFrame.ACCENT);
        }
    }

    /** The tile under the mouse, or -1. */
    private int tileAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return -1;
        }
        int column = (int) ((mouseX - getX() - 1) / tileWidth());
        int row = (int) ((mouseY - getY() - 1) / TILE_HEIGHT);
        if (column < 0 || column >= columns || row < 0 || row >= visibleRows()) {
            return -1;
        }
        int index = (scroll + row) * columns + column;
        return index < tiles.size() ? index : -1;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!isMouseOver(x, y)) {
            return false;
        }
        scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
        return true;
    }

    /** Draws the hovered tile's tooltip: its name, every line and what "Stored" means here. */
    public void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int index = tileAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        PlanView.Tile tile = tiles.get(index);
        List<Component> text = new ArrayList<>();
        text.add(KeyDisplay.name(tile.key()));
        for (Line line : lines(tile, kind)) {
            text.add(line.text().copy().withColor(line.colour()));
        }
        if (tile.stored() > 0) {
            text.add(Component
                    .translatable(
                            kind == Kind.PLAN ? RequestContent.TILE_FROM_STORAGE_KEY : RequestContent.TILE_IN_CPU_KEY)
                    .withColor(AccentFrame.LABEL));
        }
        ScreenComponents.tooltip(graphics, font, text, mouseX, mouseY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
