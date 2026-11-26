package dev.silencebound.maxtech.storage.cable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CableTierMarksTest {
    private static final int[] BASE = {CableContent.TIER_1_COLOUR, CableContent.TIER_2_COLOUR,
            CableContent.TIER_3_COLOUR, CableContent.TIER_4_COLOUR};
    private static final int MOCKUP_CYAN = 0xFF1D5F70;

    private static double luma(int rgb) {
        return 0.2126 * (rgb >> 16 & 0xFF) + 0.7152 * (rgb >> 8 & 0xFF) + 0.0722 * (rgb & 0xFF);
    }

    /** Each tier's undyed lane reads brighter than the tier below, although its base colour is darker. */
    @Test
    void undyedLanesGetBrighterPerTier() {
        double previous = -1;
        for (int tier = 1; tier <= BASE.length; tier++) {
            double luma = luma(CableTierMarks.lineRgb(tier, 0xFF000000 | BASE[tier - 1]));
            assertTrue(luma > previous, "tier " + tier + " is not brighter than the tier below");
            previous = luma;
        }
    }

    /** The owner's mockup cyan: tier 1 keeps it, the others come close to the mockup's #2687a0, #33b2d0, #6fe6f8. */
    @Test
    void mockupCyanMatches() {
        assertEquals(MOCKUP_CYAN, CableTierMarks.lineRgb(1, MOCKUP_CYAN));
        int[] mockup = {0x2687A0, 0x33B2D0, 0x6FE6F8};
        for (int tier = 2; tier <= 4; tier++) {
            int line = CableTierMarks.lineRgb(tier, MOCKUP_CYAN);
            for (int shift = 0; shift <= 16; shift += 8) {
                int got = line >> shift & 0xFF;
                int want = mockup[tier - 2] >> shift & 0xFF;
                assertTrue(Math.abs(got - want) <= 12, "tier " + tier + ": " + Integer.toHexString(line));
            }
        }
    }

    @Test
    void alphaKeptAndBlackReadsAsGrey() {
        assertEquals(0x80, CableTierMarks.lineRgb(4, 0x80FFFFFF) >>> 24, "alpha kept");
        int black = CableTierMarks.lineRgb(1, 0xFF000000);
        assertEquals(black >> 16 & 0xFF, black & 0xFF, "grey");
        assertTrue((black & 0xFF) > 0, "not black");
        assertEquals(CableTierMarks.lineRgb(4, MOCKUP_CYAN), CableTierMarks.lineRgb(9, MOCKUP_CYAN), "clamped");
    }

    @Test
    void glowFollowsTheTier() {
        assertEquals(0, CableTierMarks.lineEmission(1, true), "tier 1 is not lit");
        assertEquals(15, CableTierMarks.lineEmission(4, true), "tier 4 glows");
        for (int tier = 2; tier <= BASE.length; tier++) {
            assertTrue(CableTierMarks.lineEmission(tier, true) > CableTierMarks.lineEmission(tier - 1, true));
        }
    }

    /** With the client's glow switch off no tier's lane is lit. */
    @Test
    void glowSwitchOffLightsNoTier() {
        for (int tier = 1; tier <= BASE.length; tier++) {
            assertEquals(0, CableTierMarks.lineEmission(tier, false), "tier " + tier + " lit with the glow off");
        }
    }
}
