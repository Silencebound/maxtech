package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.energy.FeRate;
import java.util.Arrays;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** The bars' maths and text: fill heights, tick marks, shapes, tooltip numbers, and Lib's theme. */
class AccentBarsTest {
    @Test
    void fillRoundsDownButNeverHidesAnAmount() {
        assertEquals(0, AccentBar.fill(0, 8000, 50));
        assertEquals(1, AccentBar.fill(1, 8000, 50), "a drop still shows");
        assertEquals(7, AccentBar.fill(1250, 8000, 50), "7.8 rounds down");
        assertEquals(49, AccentBar.fill(7999, 8000, 50), "only a full tank looks full");
        assertEquals(50, AccentBar.fill(8000, 8000, 50));
        assertEquals(50, AccentBar.fill(9000, 8000, 50), "never more than the bar");
        assertEquals(0, AccentBar.fill(5, 0, 50), "no capacity shows nothing");
        assertEquals(0, AccentBar.fill(-5, 8000, 50));
        assertEquals(31, AccentBar.fill(5_000_000_000L, 8_000_000_000L, 50), "amounts past int range");
        assertEquals(49, AccentBar.fill(Long.MAX_VALUE - 1, Long.MAX_VALUE, 50));
        for (int size = 1; size <= 64; size++) {
            int last = 0;
            for (long amount = 0; amount <= 1000; amount += 7) {
                int pixels = AccentBar.fill(amount, 1000, size);
                assertTrue(pixels >= last && pixels <= size, "monotonic and in range for size " + size);
                last = pixels;
            }
        }
    }

    @Test
    void ticksMarkEachQuarterFromTheTop() {
        assertArrayEquals(new int[]{12, 24, 36}, AccentTank.tickRows(48));
        assertArrayEquals(new int[]{13, 25, 38}, AccentTank.tickRows(50));
        int height = 50;
        int[] rows = AccentTank.tickRows(height);
        assertEquals(rows[1], height - AccentBar.fill(4000, 8000, height), "the half mark is where half a tank ends");
    }

    @Test
    void tankTooltipGivesNameAndGroupedAmounts() {
        List<Component> lines = AccentTank.tooltip(Component.literal("Water"), 1250, 8000);
        assertEquals("Water", lines.get(0).getString());
        assertTranslation(lines.get(1), AccentTank.AMOUNT_KEY, "1,250", "8,000");
        List<Component> empty = AccentTank.tooltip(null, 0, 8000);
        assertTranslation(empty.get(0), AccentTank.EMPTY_KEY);
        assertTranslation(empty.get(1), AccentTank.AMOUNT_KEY, "0", "8,000");
        assertTranslation(AccentTank.tooltip(Component.literal("Lava"), 0, 8000).get(0), AccentTank.EMPTY_KEY);
    }

    @Test
    void energyTooltipGivesMuThenFe() {
        List<Component> lines = AccentEnergyBar.tooltip(5_000_000_000L, 8_000_000_000L, FeRate.ONE_TO_ONE);
        assertTranslation(lines.get(0), AccentEnergyBar.MU_KEY, "5,000,000,000", "8,000,000,000");
        assertTranslation(lines.get(1), AccentEnergyBar.FE_KEY, "5,000,000,000", "8,000,000,000");
        assertTranslation(AccentEnergyBar.tooltip(10, 100, new FeRate(1, 4)).get(1), AccentEnergyBar.FE_KEY, "2", "25");
        List<Component> huge = AccentEnergyBar.tooltip(Long.MAX_VALUE / 2, Long.MAX_VALUE, new FeRate(4, 1));
        assertTranslation(huge.get(1), AccentEnergyBar.FE_KEY, "9,223,372,036,854,775,807",
                "9,223,372,036,854,775,807");
        assertTranslation(AccentEnergyBar.tooltip(-3, 100, FeRate.ONE_TO_ONE).get(0), AccentEnergyBar.MU_KEY, "0",
                "100");
    }

    @Test
    void progressTooltipGivesPercentAndSecondsLeft() {
        assertEquals(0, AccentProgress.percent(0, 200));
        assertEquals(99, AccentProgress.percent(199, 200), "only finished work shows 100");
        assertEquals(100, AccentProgress.percent(200, 200));
        assertEquals(0, AccentProgress.percent(5, 0));
        assertEquals(99, AccentProgress.percent(Long.MAX_VALUE - 1, Long.MAX_VALUE));
        List<Component> lines = AccentProgress.tooltip(50, 200);
        assertTranslation(lines.get(0), AccentProgress.PERCENT_KEY, 25);
        assertTranslation(lines.get(1), AccentProgress.LEFT_KEY, "7.5");
        assertEquals(1, AccentProgress.tooltip(200, 200).size(), "no time left once done");
        assertEquals(1, AccentProgress.tooltip(0, 0).size(), "no time for idle work");
    }

    @Test
    void shapesDifferAndHaveFullRows() {
        AccentProgress.Shape arrow = AccentProgress.Shape.ARROW;
        AccentProgress.Shape flame = AccentProgress.Shape.FLAME;
        assertEquals(22, arrow.width());
        assertEquals(15, arrow.height());
        assertEquals(13, flame.width());
        assertEquals(14, flame.height());
        assertTrue(arrow.covers(0, 7) && arrow.covers(21, 7), "the arrow runs the full width on its middle row");
        assertFalse(arrow.covers(0, 0) || arrow.covers(21, 6), "shaft and tip are narrow");
        assertTrue(arrow.covers(0, 5) && arrow.covers(0, 9) && !arrow.covers(0, 4) && !arrow.covers(0, 10),
                "the shaft is five pixels thick");
        assertTrue(arrow.covers(14, 0) && arrow.covers(14, 14), "the head's base is full height");
        assertTrue(flame.covers(6, 0) && !flame.covers(0, 0), "the flame has a tip");
        assertTrue(AccentEnergyBar.cellGap(2) && AccentEnergyBar.cellGap(5) && !AccentEnergyBar.cellGap(3),
                "energy cells are cut every third row");
    }

    @Test
    void libThemeIsTheCyanPalette() {
        AccentTheme lib = AccentTheme.LIB;
        assertEquals(0xFF2A8794, lib.accent());
        assertEquals(0xFF1C5660, lib.accentDim());
        assertEquals(0xFF46B2BD, lib.barTop());
        assertEquals(0xFF294C86, lib.barBottom());
        assertEquals(0xFF9FD3D9, lib.text());
        assertEquals(0xFF46B2BD, lib.heading());
        assertEquals(new AccentTheme(Accent.ACCENT, Accent.ACCENT_DIM, Accent.BAR_TOP, Accent.BAR_BOTTOM, Accent.TEXT,
                Accent.CYAN), lib, "the theme and the old constants agree");
    }

    private static final AccentTheme MINT = new AccentTheme(0xFF11AA66, 0xFF0B6640, 0xFF55EEAA, 0xFF206050, 0xFFBBEEDD,
            0xFF66FFBB);

    @Test
    void facesAndLabelsUseTheThemeAndTheOldFormsUseLib() {
        assertEquals(MINT.accent(), Accent.faceEdge(true, MINT));
        assertEquals(MINT.accentDim(), Accent.faceFill(true, MINT));
        assertEquals(Accent.SLOT_EDGE, Accent.faceEdge(false, MINT), "the idle frame is the same for every mod");
        assertEquals(Accent.SLOT, Accent.faceFill(false, MINT));
        assertEquals(Accent.ACCENT, Accent.faceEdge(true, AccentTheme.LIB), "Lib's theme is the old cyan");
        assertEquals(Accent.ACCENT_DIM, Accent.faceFill(true, AccentTheme.LIB));
        assertEquals(MINT.heading(), IconButton.iconColour(true, true, MINT));
        assertEquals(MINT.text(), IconButton.iconColour(true, false, MINT));
        assertEquals(Accent.LABEL, IconButton.iconColour(false, true, MINT), "inactive stays grey");
        assertEquals(Accent.BAR_TOP, IconButton.iconColour(true, true, AccentTheme.LIB));
        assertEquals(Accent.TEXT, IconButton.iconColour(true, false, AccentTheme.LIB));
        assertEquals(MINT.text(), AccentTabRow.labelColour(true, false, MINT));
        assertEquals(MINT.text(), AccentTabRow.labelColour(false, true, MINT));
        assertEquals(Accent.LABEL, AccentTabRow.labelColour(false, false, MINT));
        assertEquals(Accent.TEXT, AccentTabRow.labelColour(true, false, AccentTheme.LIB));
    }

    private static void assertTranslation(Component line, String key, Object... args) {
        TranslatableContents contents = (TranslatableContents) line.getContents();
        assertEquals(key, contents.getKey());
        assertEquals(Arrays.asList(args), Arrays.asList(contents.getArgs()));
    }
}
