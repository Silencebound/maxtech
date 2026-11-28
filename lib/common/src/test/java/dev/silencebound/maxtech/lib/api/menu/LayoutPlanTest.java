package dev.silencebound.maxtech.lib.api.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentEnergyBar;
import dev.silencebound.maxtech.lib.api.client.gui.AccentProgress;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Measure and arrange passes of {@link Layout}, and the checks of {@link LayoutPlan}. */
class LayoutPlanTest {
    private static final MenuValues.Builder<Object> VALUES = MenuValues.builder();
    private static final TankValue TANK = VALUES.tank(host -> java.util.Optional.empty(), host -> 0, host -> 0);
    private static final EnergyValue ENERGY = VALUES.energy(host -> 0, host -> 0);
    private static final ProgressValue BURN = VALUES.progress(host -> 0, host -> 0);
    private static final IntValue SAMPLES = VALUES.intValue(host -> 0);
    private static final IntValue TOTAL = VALUES.intValue(host -> 0);
    private static final MenuItems<Object> ITEMS = new MenuItems<>(0, host -> {
        throw new UnsupportedOperationException();
    }, ItemSlots.builder(12));
    private static final MenuSlots FUEL = new MenuSlots(0, ITEMS, 0, 1);
    private static final MenuSlots GRID = new MenuSlots(1, ITEMS, 1, 10);

    @AfterEach
    void normalText() {
        TextScale.setOptionSource(() -> TextScale.NORMAL);
    }

    /** {@code (x, y, width, height)} of every box in tree order. */
    private static List<List<Integer>> boxes(LayoutPlan plan) {
        return plan.boxes().stream().map(box -> List.of(box.x(), box.y(), box.width(), box.height())).toList();
    }

    @Test
    void leafSizes() {
        record Case(Layout node, int width, int height) {
        }
        for (Case c : List.of(new Case(Layout.slots(GRID, 3, 3), 54, 54), new Case(Layout.tank(TANK, 18, 52), 18, 52),
                new Case(Layout.energy(ENERGY, 40), 10, 40),
                new Case(Layout.progress(BURN, Layout.ProgressShape.ARROW), 22, 15),
                new Case(Layout.progress(BURN, Layout.ProgressShape.FLAME), 13, 14),
                new Case(Layout.label("a.key", 60), 60, 10), new Case(Layout.value(SAMPLES, "a.count", 40), 40, 10),
                new Case(Layout.spacer(5, 0), 5, 0), new Case(Layout.playerInventory(), 162, 76))) {
            LayoutPlan plan = LayoutPlan.of(c.node());
            assertEquals(List.of(List.of(7, 7, c.width(), c.height())), boxes(plan), c.toString());
            assertEquals(c.width() + 14, plan.panelWidth(), c.toString());
            assertEquals(c.height() + 14, plan.panelHeight(), c.toString());
        }
    }

    @Test
    void hiddenValueKeepsItsSpace() {
        Layout shown = Layout.row(Layout.value(SAMPLES, "a.count", 40), Layout.label("b", 30));
        Layout conditional = Layout.row(Layout.value(SAMPLES, "a.count", 40, TOTAL), Layout.label("b", 30));
        assertEquals(boxes(LayoutPlan.of(shown)), boxes(LayoutPlan.of(conditional)));
        assertEquals(LayoutPlan.of(shown).panelWidth(), LayoutPlan.of(conditional).panelWidth());
    }

    @Test
    void valueShownOnlyWhileConditionIsNonZero() {
        MenuValueView zero = view(0);
        MenuValueView one = view(1);
        Layout.Value always = (Layout.Value) Layout.value(SAMPLES, "a.count", 40);
        Layout.Value conditional = (Layout.Value) Layout.value(SAMPLES, "a.count", 40, TOTAL);
        assertTrue(always.shownIn(zero));
        assertFalse(conditional.shownIn(zero));
        assertTrue(conditional.shownIn(one));
    }

    /** A view answering only int reads: {@code condition} for TOTAL, 5 for the rest. */
    private static MenuValueView view(int condition) {
        return (MenuValueView) Proxy.newProxyInstance(MenuValueView.class.getClassLoader(),
                new Class<?>[]{MenuValueView.class}, (proxy, method, args) -> args[0] == TOTAL ? condition : 5);
    }

    @Test
    void sizesMatchTheWidgets() {
        assertEquals(AccentProgress.Shape.ARROW.width(), Layout.ProgressShape.ARROW.width());
        assertEquals(AccentProgress.Shape.ARROW.height(), Layout.ProgressShape.ARROW.height());
        assertEquals(AccentProgress.Shape.FLAME.width(), Layout.ProgressShape.FLAME.width());
        assertEquals(AccentProgress.Shape.FLAME.height(), Layout.ProgressShape.FLAME.height());
        assertEquals(AccentEnergyBar.WIDTH, Layout.ENERGY_WIDTH);
        assertEquals(Accent.TAB_HEIGHT - 1, Layout.TITLE_TAB);
        assertEquals(LayoutCheckSizes.WIDTH, LayoutPlan.FIT_WIDTH);
        assertEquals(LayoutCheckSizes.HEIGHT, LayoutPlan.FIT_HEIGHT);
    }

    /** The screen-side check's minimum GUI, which a toolkit panel must fit. */
    private static final class LayoutCheckSizes {
        static final int WIDTH = dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck.MIN_GUI_WIDTH;
        static final int HEIGHT = dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck.MIN_GUI_HEIGHT;
    }

    @Test
    void rowsCentreVerticallyWithLeftoverBelow() {
        // Heights 52, 18 and 15 in a row 52 tall: (52 - 18) / 2 = 17, (52 - 15) / 2 = 18 (one pixel left below).
        LayoutPlan plan = LayoutPlan.of(Layout.row(Layout.tank(TANK, 18, 52), Layout.slots(FUEL, 1, 1),
                Layout.progress(BURN, Layout.ProgressShape.ARROW)));
        assertEquals(List.of(List.of(7, 7, 18, 52), List.of(29, 24, 18, 18), List.of(51, 25, 22, 15)), boxes(plan));
        assertEquals(7 + 18 + 4 + 18 + 4 + 22 + 7, plan.panelWidth());
        assertEquals(52 + 14, plan.panelHeight());
    }

    @Test
    void columnsCentreHorizontallyWithCustomGap() {
        LayoutPlan plan = LayoutPlan.of(Layout.column(Layout.SMALL_GAP, Layout.slots(FUEL, 1, 1),
                Layout.progress(BURN, Layout.ProgressShape.FLAME), Layout.label("k", 25)));
        // Width 25: the slot at (25 - 18) / 2 = 3, the flame at (25 - 13) / 2 = 6.
        assertEquals(List.of(List.of(10, 7, 18, 18), List.of(13, 27, 13, 14), List.of(7, 43, 25, 10)), boxes(plan));
        assertEquals(18 + 2 + 14 + 2 + 10 + 14, plan.panelHeight());
    }

    @Test
    void vanillaSizedInventoryPanel() {
        LayoutPlan plan = LayoutPlan.of(Layout.column(Layout.slots(GRID, 9, 1), Layout.playerInventory()));
        assertEquals(176, plan.panelWidth());
        assertEquals(List.of(List.of(7, 7, 162, 18), List.of(7, 29, 162, 76)), boxes(plan));
    }

    /** A lab-hub-like screen: tank, fuel with its flame, output, a status column, energy, and the inventory. */
    static Layout labHub(MenuSlots fuel, MenuSlots output, TankValue tank, EnergyValue energy, ProgressValue burn) {
        return Layout.column(Layout.row(Layout.tank(tank, 18, 52),
                Layout.column(Layout.SMALL_GAP, Layout.slots(fuel, 1, 1),
                        Layout.progress(burn, Layout.ProgressShape.FLAME)),
                Layout.column(Layout.label("a.title", 70), Layout.label("a.line", 70)), Layout.slots(output, 2, 2),
                Layout.energy(energy, 52)), Layout.playerInventory());
    }

    @Test
    void labHubFitsWithTitleTab() {
        LayoutPlan plan = LayoutPlan.of(labHub(FUEL, GRID, TANK, ENERGY, BURN));
        assertEquals(List.of(), plan.problems());
        assertTrue(plan.panelWidth() <= LayoutPlan.FIT_WIDTH);
        assertTrue(plan.panelHeight() + Layout.TITLE_TAB <= LayoutPlan.FIT_HEIGHT);
    }

    @Test
    void tooTallIsReportedWithTheTab() {
        // 7 + 147 + 4 + 76 + 7 = 241 without the tab: too tall.
        Layout tall = Layout.column(Layout.spacer(10, 147), Layout.playerInventory());
        assertEquals(1, LayoutPlan.of(tall).problems().size());
        // 7 + 136 + 4 + 76 + 7 = 230, plus the tab 241: still too tall.
        Layout tab = Layout.column(Layout.spacer(10, 136), Layout.playerInventory());
        assertEquals(1, LayoutPlan.of(tab).problems().size(), "the title tab counts");
        // 229 + 11 = 240 fits.
        Layout fits = Layout.column(Layout.spacer(10, 135), Layout.playerInventory());
        assertEquals(List.of(), LayoutPlan.of(fits).problems());
        Layout wide = Layout.row(Layout.playerInventory(), Layout.spacer(144, 1));
        assertTrue(LayoutPlan.of(wide).problems().getFirst().contains("does not fit"), "307 + 14 > 320");
    }

    @Test
    void overlapIsReported() {
        Layout.Leaf slots = (Layout.Leaf) Layout.slots(FUEL, 1, 1);
        Layout.Leaf tank = (Layout.Leaf) Layout.tank(TANK, 18, 18);
        List<String> problems = LayoutPlan.check(50, 50,
                List.of(new LayoutPlan.Box(slots, 7, 7, 18, 18), new LayoutPlan.Box(tank, 24, 7, 18, 18)));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("slots 0") && problems.getFirst().contains("overlaps tank"),
                problems.getFirst());
        // Touching edges are no overlap, and spacers never count.
        assertEquals(List.of(),
                LayoutPlan.of(Layout.row(0, Layout.slots(FUEL, 1, 1), Layout.tank(TANK, 18, 18))).problems());
        assertEquals(List.of(), LayoutPlan.check(50, 50, List.of(new LayoutPlan.Box(tank, 7, 7, 18, 18),
                new LayoutPlan.Box((Layout.Leaf) Layout.spacer(10, 10), 10, 10, 10, 10))));
    }

    @Test
    void textSizeNeverMovesBoxes() {
        Layout layout = labHub(FUEL, GRID, TANK, ENERGY, BURN);
        LayoutPlan normal = LayoutPlan.of(layout);
        for (String option : List.of(TextScale.LARGE, TextScale.LARGER)) {
            TextScale.setOptionSource(() -> option);
            assertTrue(TextScale.factor() > 1, option);
            LayoutPlan scaled = LayoutPlan.of(layout);
            assertEquals(boxes(normal), boxes(scaled), option);
            assertEquals(normal.panelWidth(), scaled.panelWidth(), option);
            assertEquals(normal.panelHeight(), scaled.panelHeight(), option);
        }
    }

    @Test
    void badSizesThrow() {
        assertThrows(IllegalArgumentException.class, () -> Layout.slots(FUEL, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Layout.tank(TANK, 18, 0));
        assertThrows(IllegalArgumentException.class, () -> Layout.label("k", 0));
        assertThrows(IllegalArgumentException.class, () -> Layout.spacer(-1, 0));
        assertThrows(IllegalArgumentException.class, Layout::row);
        assertThrows(IllegalArgumentException.class, () -> Layout.row(-1, Layout.spacer(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> Layout.column(-1, Layout.spacer(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> Layout.value(SAMPLES, "k", 0));
    }
}
