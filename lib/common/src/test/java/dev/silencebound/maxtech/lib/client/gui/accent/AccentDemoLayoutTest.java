package dev.silencebound.maxtech.lib.client.gui.accent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck;
import dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The demo screen's bar column fits the smallest GUI next to the widget column and the side tabs. */
class AccentDemoLayoutTest {
    @Test
    void barsFitBesideTheWidgetsOnTheSmallestGui() {
        int screenWidth = LayoutCheck.MIN_GUI_WIDTH;
        int screenHeight = LayoutCheck.MIN_GUI_HEIGHT;
        int left = (screenWidth - AccentDemoScreen.WIDTH) / 2;
        int top = Accent.top(screenHeight, AccentDemoScreen.HEIGHT);
        Box panel = new Box("panel", left, top - Accent.TAB_HEIGHT, AccentDemoScreen.WIDTH,
                AccentDemoScreen.HEIGHT + Accent.TAB_HEIGHT);
        Box tabs = new Box("side tabs", left + AccentDemoScreen.WIDTH + SideTabs.GAP, top + SideTabs.TOP, SideTabs.TAB,
                4 * (SideTabs.TAB + SideTabs.SPACING));
        List<Box> widgets = new ArrayList<>(LayoutCheck.widgets(AccentDemoScreen.bars(left, top)));
        assertEquals(6, widgets.size());
        widgets.add(
                new Box("widget column", left + 8, top + 8, AccentDemoScreen.MAIN - 16, AccentDemoScreen.HEIGHT - 16));
        widgets.add(tabs);
        assertEquals(List.of(), LayoutCheck.problems(screenWidth, screenHeight, List.of(panel), widgets));
        for (Box bar : widgets.subList(0, 6)) {
            assertTrue(
                    bar.right() <= left + AccentDemoScreen.WIDTH - 8
                            && bar.bottom() <= top + AccentDemoScreen.HEIGHT - 8,
                    bar.name() + " stays inside the panel's margin");
        }
    }
}
