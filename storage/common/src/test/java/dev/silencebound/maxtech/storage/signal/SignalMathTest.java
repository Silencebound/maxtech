package dev.silencebound.maxtech.storage.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SignalMathTest {
    @Test
    void linkRangeIsTheLowerTiers() {
        assertEquals(256, SignalMath.linkRange(1, 1, 256, 1024));
        assertEquals(256, SignalMath.linkRange(3, 1, 256, 1024));
        assertEquals(1024, SignalMath.linkRange(2, 3, 256, 1024));
        assertEquals(SignalMath.UNLIMITED, SignalMath.linkRange(3, 3, 256, 1024));
    }

    @Test
    void inRangeIncludesTheEdge() {
        assertTrue(SignalMath.inRange(256.0 * 256, 256));
        assertFalse(SignalMath.inRange(256.0 * 256 + 1, 256));
        assertTrue(SignalMath.inRange(1e15, SignalMath.UNLIMITED), "tier 3 reaches the whole dimension");
    }

    @Test
    void linkWidthIsTheLowerTiers() {
        assertEquals(128, SignalMath.linkWidth(1, 3, 128, 512, 2048));
        assertEquals(512, SignalMath.linkWidth(2, 2, 128, 512, 2048));
        assertEquals(2048, SignalMath.linkWidth(3, 3, 128, 512, 2048));
    }

    @Test
    void wirelessRangeGrowsPerMountUpToTheCap() {
        assertEquals(0, SignalMath.wirelessRange(0, 64, 32, 256), "no mount, no range");
        assertEquals(64, SignalMath.wirelessRange(1, 64, 32, 256));
        assertEquals(96, SignalMath.wirelessRange(2, 64, 32, 256));
        assertEquals(224, SignalMath.wirelessRange(6, 64, 32, 256));
        assertEquals(256, SignalMath.wirelessRange(7, 64, 32, 256));
        assertEquals(256, SignalMath.wirelessRange(30, 64, 32, 256), "capped");
        assertEquals(256, SignalMath.wirelessRange(Integer.MAX_VALUE, 64, Integer.MAX_VALUE, 256), "no overflow");
    }
}
