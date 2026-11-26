package dev.silencebound.maxtech.lib.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.client.gui.LayoutCheck;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentInset;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentPanel;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentReadout;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentRow;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentStatus;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentTabRow;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentTag;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentToast;
import dev.silencebound.maxtech.lib.client.gui.accent.ScrollBar;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs.Rect;
import dev.silencebound.maxtech.lib.text.TextFit;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * The Accent widgets' layout math: the button width rule, side tab order and placement, flyout sides, thumbs, and the
 * list pieces' sizes (tab rows, rows, readouts, tags, wells, panels).
 */
class AccentLayoutTest {
    private static final int SCREEN_WIDTH = LayoutCheck.MIN_GUI_WIDTH;
    private static final int SCREEN_HEIGHT = LayoutCheck.MIN_GUI_HEIGHT;

    @Test
    void buttonWidthIsTheLabelPlusEightRoundedUpToFour() {
        assertEquals(8, AccentButton.width(0), "an empty label keeps the padding");
        assertEquals(36, AccentButton.width(28), "28 + 8 is already a multiple of 4");
        assertEquals(40, AccentButton.width(29), "37 rounds up to 40");
        assertEquals(40, AccentButton.width(32));
        assertEquals(44, AccentButton.width(33));
        assertEquals(8, AccentButton.width(-5), "never narrower than the padding");
        for (int label = 0; label < 200; label++) {
            int width = AccentButton.width(label);
            assertEquals(0, width % AccentButton.GRID, "on the grid for " + label);
            assertTrue(width >= label + 2 * AccentButton.PAD, "room for " + label);
            assertTrue(width < label + 2 * AccentButton.PAD + AccentButton.GRID, "no more than needed for " + label);
        }
        assertEquals(16, AccentButton.HEIGHT);
        assertEquals(12, AccentButton.COMPACT_HEIGHT);
    }

    private record Named(String name, int order) {
    }

    @Test
    void tabsSortByOrderAndKeepTheGivenOrderForTies() {
        List<Named> given = List.of(new Named("info", 40), new Named("settings", 10), new Named("filter", 20),
                new Named("label", 20), new Named("power", 30));
        List<Named> sorted = SideTabs.ordered(given, Named::order);
        assertEquals(List.of("settings", "filter", "label", "power", "info"),
                sorted.stream().map(Named::name).toList());
        assertEquals(List.of(), SideTabs.ordered(List.<Named>of(), Named::order));
    }

    @Test
    void tabsStackDownThePanelsRightEdge() {
        Rect panel = new Rect(72, 37, 176, 166);
        Rect column = SideTabs.column(panel, 4);
        assertEquals(panel.right() + SideTabs.GAP, column.x());
        assertEquals(panel.y() + SideTabs.TOP, column.y());
        assertEquals(SideTabs.TAB, column.width());
        assertEquals(4 * SideTabs.TAB + 3 * SideTabs.SPACING, column.height());
        assertEquals(column.y(), SideTabs.tabY(panel.y(), 0));
        assertEquals(column.y() + 3 * (SideTabs.TAB + SideTabs.SPACING), SideTabs.tabY(panel.y(), 3));
        assertEquals(column.bottom(), SideTabs.tabY(panel.y(), 3) + SideTabs.TAB, "the last tab ends the column");
        assertEquals(0, SideTabs.column(panel, 0).height());
        // A 176 wide panel with its tabs fits the smallest window.
        int left = (SCREEN_WIDTH - panel.width()) / 2;
        assertTrue(SideTabs.column(new Rect(left, 37, 176, 166), 5).right() <= SCREEN_WIDTH);
    }

    @Test
    void aFlyoutOpensRightOfTheTabsWhenThereIsRoom() {
        Rect panel = new Rect(20, 30, 176, 166);
        Rect box = SideTabs.flyout(panel, 100, 60, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertEquals(panel.right() + SideTabs.GAP + SideTabs.TAB + SideTabs.GAP, box.x());
        assertEquals(panel.y(), box.y(), "top lined up with the panel");
        assertTrue(box.right() <= SCREEN_WIDTH);
    }

    @Test
    void aFlyoutOpensLeftOfThePanelWhenTheRightIsFull() {
        // The usual case: a centred 176 wide panel leaves 72 on each side of the smallest window.
        Rect panel = new Rect((SCREEN_WIDTH - 176) / 2, 37, 176, 166);
        Rect box = SideTabs.flyout(panel, 60, 50, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertEquals(panel.x() - SideTabs.GAP - 60, box.x());
        assertTrue(box.x() >= 0);
        assertTrue(box.right() <= panel.x(), "clear of the panel");
    }

    @Test
    void aFlyoutTooWideForEitherSideStaysOnTheWindow() {
        Rect panel = new Rect((SCREEN_WIDTH - 176) / 2, 37, 176, 166);
        Rect box = SideTabs.flyout(panel, 120, 50, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertTrue(box.x() >= 0 && box.right() <= SCREEN_WIDTH, "on the window: " + box);
        Rect leftHeavy = new Rect(130, 37, 176, 166);
        Rect pushed = SideTabs.flyout(leftHeavy, 140, 50, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertEquals(0, pushed.x(), "the left has more room, so it sits at the left edge");
    }

    @Test
    void aTallFlyoutMovesUpToStayOnTheWindow() {
        Rect panel = new Rect(20, 150, 176, 80);
        Rect box = SideTabs.flyout(panel, 80, 120, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertEquals(SCREEN_HEIGHT - 120, box.y());
        Rect taller = SideTabs.flyout(panel, 80, 300, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertEquals(0, taller.y(), "taller than the window: starts at the top");
    }

    @Test
    void clicksInsideTheFlyoutOrTheTabsNeverFallThrough() {
        Rect column = new Rect(250, 41, SideTabs.TAB, 58);
        Rect box = new Rect(272, 37, 40, 60);
        assertTrue(SideTabs.swallows(box, column, 290, 80, false), "empty part of the open flyout");
        assertTrue(SideTabs.swallows(null, column, 255, 98, false), "gap between tabs, nothing open");
        assertFalse(SideTabs.swallows(box, column, 290, 80, true), "a flyout widget gets the click");
        assertFalse(SideTabs.swallows(box, column, 255, 45, true), "a tab gets the click");
        assertFalse(SideTabs.swallows(box, column, 100, 80, false), "outside: goes on to the screen");
        assertFalse(SideTabs.swallows(null, column, 290, 80, false), "a closed flyout's old place is not swallowed");
    }

    @Test
    void flyoutOuterSizeAddsPaddingAndTitle() {
        assertEquals(100 + 2 * SideTabs.PAD, SideTabs.outerWidth(100));
        assertEquals(40 + SideTabs.TITLE + 2 * SideTabs.PAD, SideTabs.outerHeight(40));
        Rect box = new Rect(10, 10, 20, 20);
        assertTrue(box.contains(10, 10));
        assertFalse(box.contains(30, 10), "the right edge is outside");
    }

    @Test
    void scrollBarThumbFollowsTheVisibleShare() {
        assertEquals(100, ScrollBar.thumbSize(100, 10, 10, 12), "everything shows: the whole track");
        assertEquals(50, ScrollBar.thumbSize(100, 10, 20, 12));
        assertEquals(12, ScrollBar.thumbSize(100, 1, 100, 12), "never shorter than the minimum");
        assertEquals(10, ScrollBar.thumbSize(10, 1, 100, 12), "never longer than the track");
        assertEquals(0, ScrollBar.thumbPos(100, 50, 0, 10));
        assertEquals(50, ScrollBar.thumbPos(100, 50, 10, 10));
        assertEquals(25, ScrollBar.thumbPos(100, 50, 5, 10));
        assertEquals(50, ScrollBar.thumbPos(100, 50, 99, 10), "offset kept in range");
        assertEquals(5, ScrollBar.offsetFor(100, 50, 25, 10));
        assertEquals(10, ScrollBar.offsetFor(100, 50, 400, 10));
        assertEquals(0, ScrollBar.offsetFor(100, 50, -20, 10));
        assertEquals(0, ScrollBar.offsetFor(100, 100, 30, 10), "no room to move");
        assertEquals(0, ScrollBar.maxOffset(5, 10));
        assertEquals(15, ScrollBar.maxOffset(25, 10));
    }

    @Test
    void toastShowsForItsTime() {
        assertTrue(AccentToast.visible(1000, 1000, 2500));
        assertFalse(AccentToast.visible(1000, 3500, 2500));
        assertFalse(AccentToast.visible(1000, 999, 2500));
        assertFalse(AccentToast.visible(1000, 1000, 0));
        assertTrue(AccentToast.width(60, 90) > AccentToast.width(60, 0));
        assertTrue(AccentToast.height(8, true) > AccentToast.height(8, false));
    }

    /** Six units per character, like most of the Minecraft font. */
    private static final ToIntFunction<String> MEASURE = AccentLayoutTest::measure;

    private static int measure(String text) {
        return text.length() * 6;
    }

    @Test
    void pageTabsAreAsWideAsTheirTextAndBadge() {
        assertEquals(30 + 2 * AccentTabRow.PAD, AccentTabRow.tabWidth(30, 0), "a text tab: its text and padding");
        assertEquals(30 + 2 * AccentTabRow.PAD + AccentTabRow.BADGE_GAP + 6 + 2 * AccentTabRow.BADGE_PAD,
                AccentTabRow.tabWidth(30, 6), "a badge adds its number, padding and a gap");
        int[] widths = {AccentTabRow.tabWidth(MEASURE.applyAsInt("RULES"), 0),
                AccentTabRow.tabWidth(MEASURE.applyAsInt("LOG"), MEASURE.applyAsInt(AccentTabRow.badgeText(120)))};
        int[] xs = new int[2];
        int end = AccentTabRow.positions(10, widths, xs);
        assertEquals(10, xs[0]);
        assertEquals(10 + widths[0] + AccentTabRow.GAP, xs[1], "tabs follow each other a gap apart");
        assertEquals(xs[1] + widths[1], end);
        assertEquals(0, AccentTabRow.indexAt(xs, widths, 10));
        assertEquals(1, AccentTabRow.indexAt(xs, widths, xs[1] + widths[1] - 1));
        assertEquals(-1, AccentTabRow.indexAt(xs, widths, xs[0] + widths[0]), "the gap belongs to no tab");
        assertEquals(-1, AccentTabRow.indexAt(xs, widths, end));
        assertEquals("", AccentTabRow.badgeText(0));
        assertEquals("", AccentTabRow.badgeText(-3));
        assertEquals("7", AccentTabRow.badgeText(7));
        assertEquals("99", AccentTabRow.badgeText(99));
        assertEquals("99+", AccentTabRow.badgeText(100));
    }

    @Test
    void rowsEndLongTextWithAnEllipsis() {
        int width = 120;
        int room = AccentRow.textRoom(width, true);
        assertEquals(width - AccentRow.TEXT_X - 2 * AccentRow.MARGIN - AccentStatus.SIZE, room,
                "strip on the left, dot on the right");
        assertTrue(AccentRow.textRoom(width, false) > room, "without a dot the text gets the room");
        String name = "Keep a few stacks of every kind of glass";
        assertTrue(TextFit.cut(name, room, MEASURE), "the long name does not fit, so it gets a tooltip");
        String shown = TextFit.fit(name, room, MEASURE);
        assertTrue(shown.endsWith(TextFit.ELLIPSIS));
        assertTrue(MEASURE.applyAsInt(shown) <= room, "shown: " + shown);
        assertEquals(0, AccentRow.textRoom(5, true), "never negative");
        assertEquals(10, AccentStatus.dotY(10, 5), "a dot as tall as the text sits on it");
        assertEquals(11, AccentStatus.dotY(10, 8), "a dot is centred on a taller line");
    }

    @Test
    void readoutsTagsAndWellsGrowWithTheirText() {
        int label = MEASURE.applyAsInt("CHECKS / S");
        int value = MEASURE.applyAsInt("42 / 500");
        int width = AccentReadout.minWidth(label, value);
        assertEquals(label, AccentReadout.labelRoom(width, value), "at the minimum width the label fits exactly");
        assertEquals(0, AccentReadout.labelRoom(10, value), "never negative");
        assertEquals(40 + 2 * AccentTag.PAD + 2, AccentTag.width(40));
        assertEquals(100 - 2 * AccentInset.BORDER, AccentInset.innerWidth(100));
        assertEquals(0, AccentInset.innerWidth(3), "never negative");
        assertEquals(50 - 2 * AccentInset.BORDER, AccentInset.innerHeight(50));
    }

    @Test
    void panelContentStaysInsideItsEdges() {
        AccentPanel panel = AccentPanel.of(20, 30, 200, 150, Component.empty());
        assertEquals(20 + AccentPanel.INSET, panel.contentLeft());
        assertEquals(220 - AccentPanel.INSET, panel.contentRight());
        assertEquals(30 + AccentPanel.CONTENT_TOP, panel.contentTop());
        assertEquals(180 - AccentPanel.INSET, panel.contentBottom());
        assertEquals(200 - 2 * AccentPanel.INSET, panel.contentWidth());
        assertEquals(150 - AccentPanel.CONTENT_TOP - AccentPanel.INSET, panel.contentHeight());
        assertEquals(0, AccentPanel.of(0, 0, 4, 4, Component.empty()).contentWidth(), "never negative");
    }
}
