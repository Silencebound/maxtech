package dev.silencebound.maxtech.lib.client.gui.accent;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.AccentEnergyBar;
import dev.silencebound.maxtech.lib.api.client.gui.AccentProgress;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTank;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.resource.UnknownResource;
import dev.silencebound.maxtech.lib.api.client.gui.IconButton;
import dev.silencebound.maxtech.lib.api.client.gui.Icon;
import dev.silencebound.maxtech.lib.api.client.gui.AccentInset;
import dev.silencebound.maxtech.lib.api.client.gui.AccentRow;
import dev.silencebound.maxtech.lib.api.client.gui.AccentReadout;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTag;
import dev.silencebound.maxtech.lib.api.client.gui.AccentStatus;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTabRow;
import dev.silencebound.maxtech.lib.api.client.gui.AccentToast;
import dev.silencebound.maxtech.lib.api.client.gui.ScrollBar;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.MaxtechLib;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * A dev-only screen showing every Accent widget with example data, so the look can be checked by eye at each GUI scale:
 * text buttons (normal, compact, greyed out, changing label, right click), icon buttons (plain, toggle, cycle), the
 * scroll bar on a list of rows (selected, hovered, dimmed, with status dots), a tab row with a badge, a readout and
 * tags, the in-screen toast and the vanilla notice, and side tabs with flyouts, a changed dot and both badge levels;
 * right of them a water, a lava and an empty tank, an energy bar and both progress shapes, and below those a tank of
 * the GameTest kind {@code maxtech_lib:test_glow} (empty without the GameTest source set) and one of unknown contents.
 * Opened with the client command {@code /maxtech_lib accent_demo}, which the loader glue registers only outside
 * production. The texts are example data, not player-facing, so they are literals rather than lang keys. Client only.
 */
public final class AccentDemoScreen extends Screen {
    /** The width of the widget column on the left; the bars sit right of it. */
    static final int MAIN = 176;
    /** Tanks and the energy bar are this tall, outline included. */
    static final int BAR_HEIGHT = 60;
    static final int WIDTH = MAIN + 3 * AccentTank.WIDTH + 3 * 4 + AccentEnergyBar.WIDTH + 8;
    static final int HEIGHT = 202;
    private static final long TANK = 8_000;
    private static final Identifier GLOW = Identifier.fromNamespaceAndPath(MaxtechLib.MOD_ID, "test_glow");
    private static final long CELL = 8_000_000_000L;
    private static final long ARROW_TICKS = 200;
    private static final long FLAME_TICKS = 300;
    /** Edge length of the demo's 7x7 icon sprites ({@code maxtech_lib:accent/icon_*}). */
    private static final int ICON_SIZE = 7;
    private static final int LIST_ROWS = 6;
    private static final int ROW = 12;
    private static final int LINES = 24;
    private static final Icon BOLT = Icon.sprite(MaxtechLib.id("accent/icon_bolt"), ICON_SIZE, ICON_SIZE);
    private static final Icon EYE = Icon.sprite(MaxtechLib.id("accent/icon_eye"), ICON_SIZE, ICON_SIZE);
    private static final Icon CHIP = Icon.sprite(MaxtechLib.id("accent/icon_chip"), ICON_SIZE, ICON_SIZE);
    private static final Icon CLOCK = Icon.sprite(MaxtechLib.id("accent/icon_clock"), ICON_SIZE, ICON_SIZE);
    private static final String[] LINE_NAMES = new String[24];

    static {
        for (int i = 0; i < LINE_NAMES.length; i++) {
            LINE_NAMES[i] = i == 3 ? "Line 4 has a name too long for the row" : "Line " + (i + 1);
        }
    }
    private static final List<Icon> MODES = List.of(Icon.glyph("#####", "#...#", "#...#", "#...#", "#####"),
            Icon.glyph("..#..", ".###.", "#####", ".###.", "..#.."),
            Icon.glyph("#...#", ".#.#.", "..#..", ".#.#.", "#...#"));
    private static final List<AccentToast.Kind> TOAST_KINDS = List.of(AccentToast.Kind.INFO, AccentToast.Kind.WARN,
            AccentToast.Kind.ERROR);
    private static final String[] MODE_NAMES = {"Mode: box", "Mode: diamond", "Mode: cross"};

    private final AccentToast toast = new AccentToast();
    private final SideTabs tabs;
    private int clicks;
    private int mode;
    private boolean watching;
    private boolean fuzzy;
    private boolean warn = true;
    private boolean error = true;
    private int listOffset;
    private int left;
    private int top;
    private int listY;
    private int stripY;
    private @Nullable AccentTabRow pages;

    public AccentDemoScreen() {
        super(Component.literal("Accent widgets"));
        tabs = new SideTabs(List.of(
                SideTabs.Tab.of(CLOCK, Component.literal("Info"),
                        new TextFlyout()).order(40),
                SideTabs.Tab.of(BOLT, Component.literal("Settings"), new SettingsFlyout()).order(10)
                        .changed(() -> fuzzy),
                SideTabs.Tab.of(EYE, Component.literal("Filter"), new SettingsFlyout()).order(20)
                        .badge(() -> warn
                                ? new SideTabs.Badge(SideTabs.Level.WARN, Component.literal("The filter is empty"))
                                : null),
                SideTabs.Tab.of(CHIP, Component.literal("Power"), new TextFlyout()).order(30).badge(
                        () -> error ? new SideTabs.Badge(SideTabs.Level.ERROR, Component.literal("No power")) : null)));
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = Accent.top(height, HEIGHT);
        int x = left + 8;
        int y = top + 8;
        AccentButton normal = addRenderableWidget(
                AccentButton.of(font, x, y, Component.literal("Show toast"), this::showToast));
        normal.tooltip(Component.literal("Shows the in-screen toast; each press changes its kind"));
        x += normal.getWidth() + 4;
        addRenderableWidget(AccentButton.of(font, x, y, Component.literal("Notice"),
                () -> AccentToast.notice(Component.literal("Craft finished"), Component.literal("64 x Stone Bricks"))));
        x = left + 8;
        y += AccentButton.HEIGHT + 4;
        Component[] counts = {Component.literal("Clicks: 000")};
        addRenderableWidget(new AccentButton(x, y, AccentButton.width(font, counts), AccentButton.HEIGHT,
                Component.empty(), () -> clicks++).label(() -> Component.literal("Clicks: " + clicks))
                .onRightClick(() -> clicks = Math.max(0, clicks - 1))
                .tooltip(Component.literal("Left click adds one, right click takes one")));
        x += AccentButton.width(font, counts) + 4;
        addRenderableWidget(new AccentButton(x, y, AccentButton.width(font, Component.literal("Greyed")),
                AccentButton.HEIGHT, Component.literal("Greyed"), () -> false, () -> {
                }));
        x = left + 8;
        y += AccentButton.HEIGHT + 4;
        for (String step : new String[]{"-10", "-1", "+1", "+10"}) {
            AccentButton button = addRenderableWidget(
                    AccentButton.compact(font, x, y, Component.literal(step), () -> clicks += Integer.parseInt(step)));
            x += button.getWidth() + 2;
        }
        x = left + 8;
        y += AccentButton.COMPACT_HEIGHT + 4;
        addRenderableWidget(IconButton.of(x, y, BOLT, Component.literal("A plain icon button"), () -> clicks++));
        addRenderableWidget(IconButton.toggle(x + 18, y, EYE, () -> watching,
                () -> Component.literal(watching ? "Watching: on" : "Watching: off"), () -> watching = !watching));
        addRenderableWidget(
                IconButton.cycle(x + 36, y, MODES, () -> mode, state -> Component.literal(MODE_NAMES[state]),
                        forward -> mode = Math.floorMod(mode + (forward ? 1 : -1), MODES.size())));
        listY = y + IconButton.SIZE + 6;
        addRenderableWidget(
                new ScrollBar(left + MAIN - 8 - ScrollBar.WIDTH, listY, LIST_ROWS * ROW, new ScrollBar.Model() {
                    @Override
                    public int total() {
                        return LINES;
                    }

                    @Override
                    public int visible() {
                        return LIST_ROWS;
                    }

                    @Override
                    public int offset() {
                        return listOffset;
                    }

                    @Override
                    public void scrollTo(int offset) {
                        listOffset = Math.clamp(offset, 0, LINES - LIST_ROWS);
                    }
                }));
        stripY = listY + LIST_ROWS * ROW + 2 * AccentInset.BORDER + 6;
        AccentTabRow row = new AccentTabRow(font, left + 8, stripY,
                List.of(AccentTabRow.Tab.text("Rules"), AccentTabRow.Tab.text("Log").withCount(3)));
        AccentTabRow old = pages;
        row.setActive(old == null ? 0 : old.active());
        pages = row;
        for (AbstractWidget bar : bars(left, top)) {
            addRenderableWidget(bar);
        }
        tabs.layout(left, top, WIDTH, HEIGHT, width, height,
                SideTabs.host(this::addRenderableWidget, this::removeWidget));
    }

    /**
     * The bars right of the widget column: water, lava and an empty tank, an energy bar past int range, and both
     * progress shapes running over and over.
     */
    static List<AbstractWidget> bars(int left, int top) {
        AccentTheme theme = AccentTheme.LIB;
        int x = left + MAIN;
        int y = top + 8;
        List<AbstractWidget> bars = new ArrayList<>();
        bars.add(tank(x, y, () -> Optional.of(FluidKey.of(Fluids.WATER)), 1_250));
        bars.add(tank(x + AccentTank.WIDTH + 4, y, () -> Optional.of(FluidKey.of(Fluids.LAVA)), 6_000));
        bars.add(tank(x + 2 * (AccentTank.WIDTH + 4), y, Optional::empty, 0));
        bars.add(new AccentEnergyBar(x + 3 * (AccentTank.WIDTH + 4), y, AccentEnergyBar.WIDTH, BAR_HEIGHT, theme,
                () -> 5_000_000_000L, () -> CELL));
        int progressY = y + BAR_HEIGHT + 8;
        bars.add(new AccentProgress(x, progressY, AccentProgress.Shape.ARROW, theme,
                () -> Util.getMillis() / 50 % (ARROW_TICKS + 1), () -> ARROW_TICKS));
        bars.add(new AccentProgress(x + AccentProgress.Shape.ARROW.width() + 6, progressY, AccentProgress.Shape.FLAME,
                theme, () -> Util.getMillis() / 50 % (FLAME_TICKS + 1), () -> FLAME_TICKS));
        int kindsY = progressY + AccentProgress.Shape.ARROW.height() + 8;
        Optional<KindKey<?>> unknown = Optional.of(unknown());
        bars.add(new AccentTank(x, kindsY, AccentTank.WIDTH, BAR_HEIGHT, theme, AmountUnit.ITEMS, () -> unknown,
                () -> 3_000, () -> TANK));
        bars.add(new AccentTank(x + AccentTank.WIDTH + 4, kindsY, AccentTank.WIDTH, BAR_HEIGHT, theme, AmountUnit.ITEMS,
                () -> Glow.KEY, () -> 2_500, () -> TANK));
        return bars;
    }

    /** Glow 25 of the GameTest kind, looked up when first drawn; empty without the GameTest source set. */
    private static final class Glow {
        static final Optional<KindKey<?>> KEY = ResourceKinds.byId(GLOW).map(AccentDemoScreen::glow);
    }

    /** Glow 25 of the GameTest kind, whose keys are integers. */
    @SuppressWarnings("unchecked") // the GameTest source set registers test_glow with Integer keys
    private static KindKey<?> glow(ResourceKind<?> kind) {
        return new KindKey<>((ResourceKind<Integer>) kind, 25);
    }

    /** Contents of a kind no mod registers, as a tank of a removed mod would hold them. */
    private static KindKey<?> unknown() {
        Identifier gone = Identifier.fromNamespaceAndPath("removed_mod", "essence");
        CompoundTag raw = new CompoundTag();
        raw.putString(KindKey.KIND_FIELD, gone.toString());
        return UnknownResource.key(gone, raw);
    }

    private static AccentTank tank(int x, int y, Supplier<Optional<FluidKey>> fluid, long amount) {
        return new AccentTank(x, y, AccentTank.WIDTH, BAR_HEIGHT, AccentTheme.LIB, fluid, () -> amount, () -> TANK);
    }

    private void showToast() {
        AccentToast.Kind kind = TOAST_KINDS.get(clicks++ % TOAST_KINDS.size());
        toast.show(Component.literal("Pattern encoded"),
                kind == AccentToast.Kind.INFO ? Component.empty() : Component.literal("Kind: " + kind), kind,
                Util.getMillis());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        Accent.panel(graphics, font, left, top, WIDTH, HEIGHT, title, mouseX, mouseY);
        int listWidth = MAIN - 16 - ScrollBar.WIDTH - 2;
        AccentInset.begin(graphics, left + 8, listY, listWidth, LIST_ROWS * ROW + 2 * AccentInset.BORDER);
        AccentStatus[] states = AccentStatus.values();
        for (int i = 0; i < LIST_ROWS; i++) {
            int line = listOffset + i;
            AccentRow.draw(graphics, font, left + 8 + AccentInset.BORDER, listY + AccentInset.BORDER + i * ROW,
                    AccentInset.innerWidth(listWidth), lineName(line), line == 2, line % 5 == 4,
                    states[line % states.length], mouseX, mouseY);
        }
        AccentInset.end(graphics);
        AccentTabRow row = pages;
        if (row != null) {
            row.draw(graphics, font, mouseX, mouseY);
            int readoutWidth = AccentReadout.minWidth(font.width("RULES"), font.width("3 / 8"));
            AccentReadout.draw(graphics, font, left + MAIN - 8 - readoutWidth, stripY, readoutWidth, "RULES", "3 / 8");
        }
        int tagY = stripY + AccentButton.COMPACT_HEIGHT + 4;
        int tagX = left + 8 + AccentTag.draw(graphics, font, left + 8, tagY, "RUNNING", Accent.GREEN) + 4;
        AccentTag.draw(graphics, font, tagX, tagY, "SIMULATE", Accent.CYAN);
        tabs.extractFlyout(graphics, font, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        toast.draw(graphics, font, left + WIDTH - 4, top + HEIGHT - 4, Util.getMillis());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return tabs.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (tabs.mouseClicked(event) || super.mouseClicked(event, doubleClick)) {
            return true;
        }
        AccentTabRow row = pages;
        int clicked = row == null ? -1 : row.tabAt(event.x(), event.y());
        if (row != null && clicked >= 0) {
            row.setActive(clicked);
            return true;
        }
        return false;
    }

    /** A list line's text. */
    private static String lineName(int line) {
        return LINE_NAMES[line];
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (super.mouseScrolled(x, y, scrollX, scrollY)) {
            return true;
        }
        listOffset = Math.clamp(listOffset - (int) Math.signum(scrollY), 0, LINES - LIST_ROWS);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A flyout with a few buttons that change the demo's state (the changed dot and the badges). */
    private final class SettingsFlyout implements SideTabs.Flyout {
        @Override
        public int width() {
            return 96;
        }

        @Override
        public int height() {
            return 2 * AccentButton.HEIGHT + 4 + AccentButton.COMPACT_HEIGHT + 4;
        }

        @Override
        public void init(SideTabs.Rect content, Consumer<AbstractWidget> add) {
            add.accept(new AccentButton(content.x(), content.y(), content.width(), AccentButton.HEIGHT,
                    Component.empty(), () -> fuzzy = !fuzzy)
                    .label(() -> Component.literal(fuzzy ? "Fuzzy (changed)" : "Exact (default)")));
            add.accept(new AccentButton(content.x(), content.y() + AccentButton.HEIGHT + 4, content.width(),
                    AccentButton.HEIGHT, Component.empty(), () -> warn = !warn)
                    .label(() -> Component.literal(warn ? "Clear warning" : "Raise warning")));
            add.accept(new AccentButton(content.x(), content.y() + 2 * (AccentButton.HEIGHT + 4), content.width(),
                    AccentButton.COMPACT_HEIGHT, Component.empty(), () -> error = !error)
                    .label(() -> Component.literal(error ? "Clear error" : "Raise error")));
        }
    }

    /** A flyout with text only. */
    private static final class TextFlyout implements SideTabs.Flyout {
        @Override
        public int width() {
            return 110;
        }

        @Override
        public int height() {
            return 30;
        }

        @Override
        public void init(SideTabs.Rect content, Consumer<AbstractWidget> add) {
        }

        @Override
        public void extract(GuiGraphicsExtractor graphics, Font font, SideTabs.Rect content, int mouseX, int mouseY) {
            TextScale.text(graphics, font, "Flyouts open right of the", content.x(), content.y(), Accent.LABEL, false);
            TextScale.text(graphics, font, "tabs, or left when there", content.x(), content.y() + 10, Accent.LABEL,
                    false);
            TextScale.text(graphics, font, "is no room. Esc closes.", content.x(), content.y() + 20, Accent.LABEL,
                    false);
        }
    }
}
