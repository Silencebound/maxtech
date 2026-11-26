package dev.silencebound.maxtech.lib.client.gui.accent;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * A device screen's side tabs (owner decision 2026-10-18): a column of {@link #TAB} square icon tabs on the panel's
 * right edge, in a fixed order ({@link Tab#order}), each opening a {@link Flyout} panel beside the main panel. The
 * flyout goes right of the tabs, or left of the panel when the window has no room on the right; only one is open at a
 * time; Escape, a click outside it or a second click on its tab closes it. A tab shows a small accent dot while its
 * settings differ from the defaults ({@link Tab#changed}), and a red or amber {@link Badge} with the reason in its
 * tooltip when something needs the player ({@link Tab#badge}). Tooltips are vanilla widget tooltips.
 *
 * <p>
 * A screen keeps one instance across resizes and wires it up in four places:
 * <ol>
 * <li>{@code init()}: {@link #layout} with the panel's box, the window's size and a {@link Host} made with
 * {@link #host}{@code (this::addRenderableWidget, this::removeWidget)}; it adds the tab buttons and reopens the open
 * flyout at its new place.</li>
 * <li>{@code extractBackground}, after the panel: {@link #extractFlyout}, so the flyout's widgets draw over its
 * box.</li>
 * <li>{@code keyPressed} and {@code mouseClicked}, before {@code super}: {@link #keyPressed} and {@link #mouseClicked};
 * when either returns true, the screen returns true without calling {@code super}.</li>
 * <li>Recipe viewers' exclusion areas: {@link #areas}.</li>
 * </ol>
 * The placement math ({@link #column}, {@link #tabY}, {@link #flyout}, {@link #ordered}) is plain arithmetic for JUnit.
 * Client thread only.
 */
public final class SideTabs {
    /** A tab's edge length. */
    public static final int TAB = 18;
    /** Room between the panel's right edge and the tabs, and between the tabs and a flyout on the right. */
    public static final int GAP = 2;
    /** Room between two tabs. */
    public static final int SPACING = 2;
    /** How far below the panel's top the first tab sits. */
    public static final int TOP = 4;
    /** A flyout's padding around its content. */
    public static final int PAD = 6;
    /** A flyout's title strip above its content: the title line and the accent line under it. */
    public static final int TITLE = 14;
    private static final int DOT = 3;
    /** The badge dot's size, drawn in a tab's top right corner. */
    public static final int BADGE = 4;

    /** How urgent a badge is. */
    public enum Level {
        WARN(Accent.AMBER), ERROR(Accent.RED);

        private final int colour;

        Level(int colour) {
            this.colour = colour;
        }

        public int colour() {
            return colour;
        }
    }

    /** A badge on a tab: its level and the reason shown in the tab's tooltip. */
    public record Badge(Level level, Component reason) {
    }

    /** A rectangle in screen coordinates. */
    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < right() && py < bottom();
        }
    }

    /** Where a screen's widgets go; {@link #host} builds one from a screen's protected methods. */
    public interface Host {
        void add(AbstractWidget widget);

        void remove(AbstractWidget widget);
    }

    /** What a tab opens: a panel of a fixed size with widgets and drawing of its own. */
    public interface Flyout {
        /** The content's width, padding excluded. */
        int width();

        /** The content's height, padding and title excluded. */
        int height();

        /**
         * Adds the content's widgets inside {@code content} through {@code add}; called each time the flyout opens or
         * moves. The widgets are removed again when it closes.
         */
        void init(Rect content, Consumer<AbstractWidget> add);

        /** Draws what is not a widget (labels, values) inside {@code content}; called before the widgets draw. */
        default void extract(GuiGraphicsExtractor graphics, Font font, Rect content, int mouseX, int mouseY) {
        }

        /** Called when the flyout closes. */
        default void closed() {
        }
    }

    /** One tab. Build with {@link #of} and the {@code with} methods. */
    public static final class Tab {
        private final Icon icon;
        private final Component title;
        private final Flyout flyout;
        private int order;
        private BooleanSupplier changed = () -> false;
        private Supplier<@Nullable Badge> badge = () -> null;

        private Tab(Icon icon, Component title, Flyout flyout) {
            this.icon = icon;
            this.title = title;
            this.flyout = flyout;
        }

        public static Tab of(Icon icon, Component title, Flyout flyout) {
            return new Tab(icon, title, flyout);
        }

        /** The tab's place in the column: lower first; tabs with the same order keep the order they were given. */
        public Tab order(int order) {
            this.order = order;
            return this;
        }

        /** Shows an accent dot while {@code changed} is true (settings differ from the defaults). */
        public Tab changed(BooleanSupplier changed) {
            this.changed = changed;
            return this;
        }

        /** Shows the badge {@code badge} returns, none for null. Read every frame. */
        public Tab badge(Supplier<@Nullable Badge> badge) {
            this.badge = badge;
            return this;
        }

        public int order() {
            return order;
        }

        public Component title() {
            return title;
        }
    }

    private final List<Tab> tabs;
    private final List<TabButton> buttons = new ArrayList<>();
    private final List<AbstractWidget> flyoutWidgets = new ArrayList<>();
    private @Nullable Host host;
    private Rect panel = new Rect(0, 0, 0, 0);
    private int screenWidth;
    private int screenHeight;
    private int open = -1;
    private @Nullable Rect flyoutBox;

    /** Tabs in the order given, sorted by {@link Tab#order}. */
    public SideTabs(List<Tab> tabs) {
        this.tabs = ordered(tabs, Tab::order);
    }

    /** A host from a screen's {@code addRenderableWidget} and {@code removeWidget}. */
    public static Host host(Consumer<AbstractWidget> add, Consumer<AbstractWidget> remove) {
        return new Host() {
            @Override
            public void add(AbstractWidget widget) {
                add.accept(widget);
            }

            @Override
            public void remove(AbstractWidget widget) {
                remove.accept(widget);
            }
        };
    }

    /** {@code items} sorted by {@code key}, lowest first; equal keys keep their order. */
    public static <T> List<T> ordered(List<T> items, ToIntFunction<T> key) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Integer.compare(key.applyAsInt(a), key.applyAsInt(b)));
        return List.copyOf(sorted);
    }

    /** The column of {@code count} tabs right of {@code panel}. */
    public static Rect column(Rect panel, int count) {
        int height = count <= 0 ? 0 : count * TAB + (count - 1) * SPACING;
        return new Rect(panel.right() + GAP, panel.y() + TOP, TAB, height);
    }

    /** The top of tab {@code index} beside a panel whose top is {@code panelY}. */
    public static int tabY(int panelY, int index) {
        return panelY + TOP + index * (TAB + SPACING);
    }

    /**
     * Where a flyout {@code width} by {@code height} (padding and title included) goes beside {@code panel} on a window
     * {@code screenWidth} by {@code screenHeight}: right of the tab column when it fits there, else left of the panel
     * when it fits there, else on the side with more room, pushed onto the window. Its top lines up with the panel's,
     * moved up as far as needed to stay on the window.
     */
    public static Rect flyout(Rect panel, int width, int height, int screenWidth, int screenHeight) {
        int right = panel.right() + GAP + TAB + GAP;
        int left = panel.x() - GAP - width;
        int x;
        if (right + width <= screenWidth) {
            x = right;
        } else if (left >= 0) {
            x = left;
        } else if (screenWidth - right >= panel.x()) {
            x = Math.max(0, screenWidth - width);
        } else {
            x = 0;
        }
        int y = Math.max(0, Math.min(panel.y(), screenHeight - height));
        return new Rect(x, y, width, height);
    }

    /** A flyout's whole width and height for content {@code width} by {@code height}. */
    public static int outerWidth(int width) {
        return width + 2 * PAD;
    }

    public static int outerHeight(int height) {
        return height + TITLE + 2 * PAD;
    }

    /**
     * Lays the tabs out beside the panel at ({@code panelX}, {@code panelY}), {@code panelWidth} by
     * {@code panelHeight}, on a window {@code screenWidth} by {@code screenHeight}, adding them through {@code host}.
     * An open flyout is reopened at its new place.
     */
    public void layout(int panelX, int panelY, int panelWidth, int panelHeight, int screenWidth, int screenHeight,
            Host host) {
        Host old = this.host;
        if (old != null) {
            for (TabButton button : buttons) {
                old.remove(button);
            }
            removeFlyoutWidgets(old);
        }
        buttons.clear();
        this.host = host;
        this.panel = new Rect(panelX, panelY, panelWidth, panelHeight);
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        Rect column = column(panel, tabs.size());
        for (int i = 0; i < tabs.size(); i++) {
            TabButton button = new TabButton(column.x(), tabY(panelY, i), i);
            buttons.add(button);
            host.add(button);
        }
        if (open >= 0) {
            placeFlyout(host);
        }
    }

    /** The tabs in column order. */
    public List<Tab> tabs() {
        return tabs;
    }

    /** The open tab's index, -1 for none. */
    public int openIndex() {
        return open;
    }

    /** The open flyout's box, or null when none is open. */
    public @Nullable Rect flyoutBox() {
        return flyoutBox;
    }

    /** Opens tab {@code index}, or closes it when it is open already. */
    public void toggle(int index) {
        if (open == index) {
            close();
        } else {
            open(index);
        }
    }

    /** Opens tab {@code index}'s flyout, closing any other. */
    public void open(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }
        close();
        open = index;
        Host current = host;
        if (current != null) {
            placeFlyout(current);
        }
    }

    /** Closes the open flyout, if any. */
    public void close() {
        if (open < 0) {
            return;
        }
        Tab tab = tabs.get(open);
        Host current = host;
        if (current != null) {
            removeFlyoutWidgets(current);
        }
        open = -1;
        flyoutBox = null;
        tab.flyout.closed();
    }

    private void placeFlyout(Host current) {
        Flyout flyout = tabs.get(open).flyout;
        Rect box = flyout(panel, outerWidth(flyout.width()), outerHeight(flyout.height()), screenWidth, screenHeight);
        flyoutBox = box;
        flyout.init(content(box), widget -> {
            flyoutWidgets.add(widget);
            current.add(widget);
        });
    }

    private void removeFlyoutWidgets(Host current) {
        for (AbstractWidget widget : flyoutWidgets) {
            current.remove(widget);
        }
        flyoutWidgets.clear();
    }

    private static Rect content(Rect box) {
        return new Rect(box.x() + PAD, box.y() + PAD + TITLE, box.width() - 2 * PAD, box.height() - 2 * PAD - TITLE);
    }

    /** Draws the open flyout's box, title and non-widget content; call before the widgets draw. */
    public void extractFlyout(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        Rect box = flyoutBox;
        if (open < 0 || box == null) {
            return;
        }
        Tab tab = tabs.get(open);
        Accent.box(graphics, box.x(), box.y(), box.width(), box.height());
        Accent.text(graphics, font, tab.title, box.x() + PAD, box.y() + PAD, box.width() - 2 * PAD, Accent.TEXT, mouseX,
                mouseY);
        int lineY = box.y() + PAD + TextScale.lineHeight(font) + 1;
        graphics.fill(box.x() + 3, lineY, box.right() - 3, lineY + 1, Accent.ACCENT);
        tab.flyout.extract(graphics, font, content(box), mouseX, mouseY);
    }

    /** Escape closes an open flyout; returns whether it did, so the screen stays open. */
    public boolean keyPressed(KeyEvent event) {
        if (open >= 0 && event.isEscape()) {
            close();
            return true;
        }
        return false;
    }

    /**
     * A click outside the open flyout and the tab column closes the flyout and goes on to whatever it landed on. A
     * click inside the flyout or the column that misses their widgets is swallowed, so it never reaches a slot or
     * widget behind them. Returns whether the screen must stop there; a click on a tab or a flyout widget returns false
     * so the screen hands it to that widget as usual.
     */
    public boolean mouseClicked(MouseButtonEvent event) {
        double x = event.x();
        double y = event.y();
        Rect column = column(panel, tabs.size());
        Rect box = flyoutBox;
        if (open >= 0 && box != null && !box.contains(x, y) && !column.contains(x, y)) {
            close();
            return false;
        }
        return swallows(open >= 0 ? box : null, column, x, y, widgetAt(x, y));
    }

    /**
     * Whether a click at ({@code x}, {@code y}) stops at the side tabs: it lands in the open flyout's {@code box} (null
     * when none is open) or the tab {@code column}, but on none of their widgets ({@code onWidget}).
     */
    public static boolean swallows(@Nullable Rect box, Rect column, double x, double y, boolean onWidget) {
        boolean inside = column.contains(x, y) || (box != null && box.contains(x, y));
        return inside && !onWidget;
    }

    private boolean widgetAt(double x, double y) {
        for (TabButton button : buttons) {
            if (button.isMouseOver(x, y)) {
                return true;
            }
        }
        for (AbstractWidget widget : flyoutWidgets) {
            if (widget.isMouseOver(x, y)) {
                return true;
            }
        }
        return false;
    }

    /** The tab column and the open flyout, for recipe viewers to keep clear of. */
    public List<Rect> areas() {
        List<Rect> out = new ArrayList<>(2);
        out.add(column(panel, tabs.size()));
        Rect box = flyoutBox;
        if (box != null) {
            out.add(box);
        }
        return out;
    }

    /** The tooltip for a tab: its title, then the badge's reason in the badge's colour. */
    static Component tooltip(Component title, @Nullable Badge badge) {
        if (badge == null) {
            return title;
        }
        MutableComponent text = Component.empty().append(title).append(CommonComponents.NEW_LINE);
        return text.append(badge.reason().copy().withColor(badge.level().colour()));
    }

    /** A tab in the column. */
    private final class TabButton extends AbstractButton {
        private final int index;
        /** The badge the tooltip was built for; the tooltip is built again only when it changes. */
        private @Nullable Badge shownBadge;
        private boolean built;

        TabButton(int x, int y, int index) {
            super(x, y, TAB, TAB, tabs.get(index).title);
            this.index = index;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            toggle(index);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            Tab tab = tabs.get(index);
            Badge badge = tab.badge.get();
            if (!built || !Objects.equals(badge, shownBadge)) {
                built = true;
                shownBadge = badge;
                setTooltip(Tooltip.create(tooltip(tab.title, badge)));
            }
            boolean lit = open == index;
            if (lit) {
                Accent.litFace(graphics, getX(), getY(), width, height);
            } else {
                Accent.face(graphics, getX(), getY(), width, height, isHovered());
            }
            tab.icon.drawCentred(graphics, getX(), getY(), width, height,
                    lit || isHovered() ? Accent.BAR_TOP : Accent.TEXT);
            if (tab.changed.getAsBoolean()) {
                Accent.dot(graphics, getX() + 2, getBottom() - 2 - DOT, DOT, Accent.BAR_TOP);
            }
            if (badge != null) {
                Accent.dot(graphics, getRight() - 1 - BADGE, getY() + 1, BADGE, badge.level().colour());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
