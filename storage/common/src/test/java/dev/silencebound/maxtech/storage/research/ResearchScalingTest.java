package dev.silencebound.maxtech.storage.research;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Preset scaling and pack-size kind targets with the shipped defaults: relaxed 0.5, expert 2. */
class ResearchScalingTest {
    private static final double RELAXED = 0.5;
    private static final double EXPERT = 2.0;

    @Test
    void presetsPickTheirMultiplier() {
        assertEquals(0.5, ResearchScaling.scale(ResearchPreset.RELAXED, RELAXED, EXPERT));
        assertEquals(1.0, ResearchScaling.scale(ResearchPreset.STANDARD, RELAXED, EXPERT));
        assertEquals(2.0, ResearchScaling.scale(ResearchPreset.EXPERT, RELAXED, EXPERT));
        assertEquals(1.0, ResearchScaling.scale(ResearchPreset.OFF, RELAXED, EXPERT));
    }

    @Test
    void countsRoundUpAndNeverReachZero() {
        assertEquals(4096, ResearchScaling.count(4096, 1.0));
        assertEquals(2048, ResearchScaling.count(4096, RELAXED));
        assertEquals(8192, ResearchScaling.count(4096, EXPERT));
        assertEquals(2, ResearchScaling.count(3, RELAXED));
        assertEquals(1, ResearchScaling.count(1, 0.01));
    }

    @Test
    void kindsAreAFractionOfThePackUpToTheCap() {
        assertEquals(15, ResearchScaling.kinds(0.01, 100, 1450));
        assertEquals(100, ResearchScaling.kinds(0.01, 100, 50_000));
        assertEquals(500, ResearchScaling.kinds(0.01, 0, 50_000));
        assertEquals(1, ResearchScaling.kinds(0.0001, 100, 10));
    }

    @Test
    void percentsStayWithinAHundred() {
        assertEquals(75, ResearchScaling.percent(75, 1.0));
        assertEquals(100, ResearchScaling.percent(75, EXPERT));
        assertEquals(38, ResearchScaling.percent(75, RELAXED));
    }

    @Test
    void costsScaleAndZeroStaysFree() {
        assertEquals(200, ResearchScaling.cost(100, EXPERT));
        assertEquals(50, ResearchScaling.cost(100, RELAXED));
        assertEquals(0, ResearchScaling.cost(0, EXPERT));
    }
}
