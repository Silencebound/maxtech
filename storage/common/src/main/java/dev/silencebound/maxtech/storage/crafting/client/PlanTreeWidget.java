package dev.silencebound.maxtech.storage.crafting.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.api.client.gui.TextFit;
import dev.silencebound.maxtech.storage.crafting.request.PlanView;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A plan or job tree drawn as an indented list with guide lines ({@link PlanLayout}): per node its item, name and, on
 * the right, needed / from storage / to craft, or for a running job the crafts left. Nodes with a shortfall are red
 * with the missing count, finished job nodes green. A click on a node with children folds it; the wheel scrolls; the
 * tooltip has every number, the provider and the estimate. Client only.
 */
public final class PlanTreeWidget extends AbstractWidget {
    static final int ROW = 18;
    private static final int INDENT = 10;
    private static final int GUIDE = 0xFF3B4247;

    /** A running job's progress per node; -1 for a plan, which has none. */
    @FunctionalInterface
    public interface Progress {
        long remaining(int node);
    }

    private final Font font;
    private @Nullable PlanView view;
    private @Nullable PlanLayout layout;
    private final IntOpenHashSet collapsed = new IntOpenHashSet();
    private Progress progress = node -> -1;
    private int scroll;

    public PlanTreeWidget(Font font, int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable(RequestContent.PLAN_KEY));
        this.font = font;
    }

    /** Shows {@code next}; keeps the folds and scroll when it is the same tree again (a job's state changed). */
    @SuppressWarnings("ReferenceEquality") // a new view object means new data
    public void setView(@Nullable PlanView next) {
        if (next == view) {
            return;
        }
        boolean sameTree = view != null && next != null && view.nodes().size() == next.nodes().size()
                && view.key().equals(next.key());
        view = next;
        if (!sameTree) {
            collapsed.clear();
            scroll = 0;
        }
        if (next == null || next.nodes().isEmpty()) {
            layout = null;
            return;
        }
        int[] parents = new int[next.nodes().size()];
        for (int i = 0; i < parents.length; i++) {
            parents[i] = next.nodes().get(i).parent();
        }
        PlanLayout built = new PlanLayout(parents);
        built.layout(collapsed);
        layout = built;
    }

    public void setProgress(Progress next) {
        progress = next;
    }

    private int visibleRows() {
        return height / ROW;
    }

    private int maxScroll(PlanLayout current) {
        return Math.max(0, current.rowCount() - visibleRows());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, AccentFrame.SLOT);
        PlanView shown = view;
        PlanLayout current = layout;
        if (shown == null || current == null) {
            return;
        }
        scroll = Math.clamp(scroll, 0, maxScroll(current));
        int rows = Math.min(visibleRows(), current.rowCount() - scroll);
        int textRight = getX() + width - 8;
        for (int r = 0; r < rows; r++) {
            int row = scroll + r;
            int node = current.node(row);
            PlanView.Node data = shown.nodes().get(node);
            int depth = current.depth(node);
            int y = getY() + r * ROW;
            int mid = y + ROW / 2;
            for (int k = 1; k < depth; k++) {
                if (current.guide(row, k)) {
                    int gx = column(k - 1) + 4;
                    graphics.fill(gx, y, gx + 1, y + ROW, GUIDE);
                }
            }
            if (depth > 0) {
                int cx = column(depth - 1) + 4;
                graphics.fill(cx, y, cx + 1, current.isLast(node) ? mid + 1 : y + ROW, GUIDE);
                graphics.fill(cx, mid, column(depth), mid + 1, GUIDE);
            }
            int iconX = column(depth);
            StackKey key = shown.nodeKey(node);
            graphics.item(KeyDisplay.icon(key), iconX, y + 1);
            if (current.hasChildren(node) && collapsed.contains(node)) {
                graphics.fill(iconX - 1, y + 12, iconX + 5, y + 13, AccentFrame.BAR_TOP);
                graphics.fill(iconX + 1, y + 10, iconX + 2, y + 15, AccentFrame.BAR_TOP);
            }
            long left = progress.remaining(node);
            String right;
            int colour;
            if (data.missing() > 0) {
                right = StatusPalette.mark(StatusPalette.Tone.BAD) + "-" + KeyDisplay.amount(key, data.missing());
                colour = StatusPalette.bad();
            } else if (left == 0) {
                right = StatusPalette.mark(StatusPalette.Tone.GOOD)
                        + Component.translatable(RequestContent.NODE_DONE_KEY).getString();
                colour = StatusPalette.good();
            } else if (left > 0) {
                right = left + "x";
                colour = AccentFrame.TEXT;
            } else {
                right = KeyDisplay.amount(key, data.stored()) + " | +" + KeyDisplay.amount(key, data.toCraft());
                colour = AccentFrame.TEXT;
            }
            int rightX = textRight - font.width(right);
            TextScale.text(graphics, font, right, rightX, y + 5, colour, false);
            int nameX = iconX + 18;
            String name = TextFit.fit(KeyDisplay.name(key).getString(), Math.max(0, rightX - 4 - nameX), font::width);
            TextScale.text(graphics, font, name, nameX, y + 5,
                    data.missing() > 0 ? StatusPalette.bad() : AccentFrame.TEXT, false);
        }
        if (current.rowCount() > visibleRows()) {
            int track = height;
            int thumb = Math.max(8, track * visibleRows() / current.rowCount());
            int thumbY = getY() + (track - thumb) * scroll / Math.max(1, maxScroll(current));
            graphics.fill(getX() + width - 4, getY(), getX() + width - 1, getY() + height, AccentFrame.SLOT_EDGE);
            graphics.fill(getX() + width - 4, thumbY, getX() + width - 1, thumbY + thumb, AccentFrame.ACCENT);
        }
    }

    private int column(int depth) {
        return getX() + 2 + depth * INDENT;
    }

    /** The node under the mouse, or -1. */
    public int nodeAt(double mouseX, double mouseY) {
        PlanLayout current = layout;
        if (current == null || !isMouseOver(mouseX, mouseY)) {
            return -1;
        }
        int row = scroll + (int) ((mouseY - getY()) / ROW);
        return row >= 0 && row < current.rowCount() ? current.node(row) : -1;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        PlanLayout current = layout;
        int node = nodeAt(event.x(), event.y());
        if (current != null && node >= 0 && current.hasChildren(node)) {
            if (!collapsed.remove(node)) {
                collapsed.add(node);
            }
            current.layout(collapsed);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        PlanLayout current = layout;
        if (current == null || !isMouseOver(x, y)) {
            return false;
        }
        scroll = Math.clamp(scroll - (int) Math.signum(scrollY) * 3, 0, maxScroll(current));
        return true;
    }

    /** The tooltip for the node under the mouse; empty when none. */
    public List<Component> tooltip(int mouseX, int mouseY) {
        PlanView shown = view;
        int node = nodeAt(mouseX, mouseY);
        if (shown == null || node < 0) {
            return List.of();
        }
        PlanView.Node data = shown.nodes().get(node);
        StackKey key = shown.nodeKey(node);
        List<Component> lines = new ArrayList<>();
        lines.add(KeyDisplay.name(key));
        lines.add(Component.translatable(RequestContent.NODE_NEEDED_KEY, KeyDisplay.amount(key, data.needed())));
        lines.add(Component.translatable(RequestContent.NODE_STORED_KEY, KeyDisplay.amount(key, data.stored())));
        if (data.crafts() > 0) {
            lines.add(Component.translatable(RequestContent.NODE_CRAFT_KEY, KeyDisplay.amount(key, data.toCraft()),
                    data.crafts()));
        }
        if (data.missing() > 0) {
            lines.add(Component
                    .literal(StatusPalette.mark(StatusPalette.Tone.BAD)).append(Component
                            .translatable(RequestContent.NODE_MISSING_KEY, KeyDisplay.amount(key, data.missing())))
                    .withColor(StatusPalette.bad()));
        }
        if (data.provider() >= 0 && data.provider() < shown.providers().size()) {
            lines.add(Component.translatable(RequestContent.NODE_PROVIDER_KEY, shown.providers().get(data.provider())));
        }
        if (data.ticks() >= 0) {
            lines.add(Component.translatable(RequestContent.NODE_TIME_KEY, PlanLayout.duration(data.ticks())));
        }
        long left = progress.remaining(node);
        if (left > 0) {
            lines.add(Component.translatable(RequestContent.NODE_LEFT_KEY, left));
        } else if (left == 0) {
            lines.add(Component.literal(StatusPalette.mark(StatusPalette.Tone.GOOD))
                    .append(Component.translatable(RequestContent.NODE_DONE_KEY)).withColor(StatusPalette.good()));
        }
        return lines;
    }

    /** Draws {@link #tooltip} at the mouse, if any. */
    public void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<Component> lines = tooltip(mouseX, mouseY);
        if (!lines.isEmpty()) {
            ScreenComponents.tooltip(graphics, font, lines, mouseX, mouseY);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
