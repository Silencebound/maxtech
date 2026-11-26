package dev.silencebound.maxtech.storage.research.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.storage.research.ResearchPreset;
import org.junit.jupiter.api.Test;

/** Source points per preset with the shipped multipliers: relaxed 2, expert 0.5 (owner decision 2026-10-03). */
class SourcePointsTest {
    private static final double RELAXED = 2.0;
    private static final double EXPERT = 0.5;

    @Test
    void presetsPickTheirMultiplier() {
        assertEquals(2.0, SourcePoints.scale(ResearchPreset.RELAXED, RELAXED, EXPERT));
        assertEquals(1.0, SourcePoints.scale(ResearchPreset.STANDARD, RELAXED, EXPERT));
        assertEquals(0.5, SourcePoints.scale(ResearchPreset.EXPERT, RELAXED, EXPERT));
        assertEquals(1.0, SourcePoints.scale(ResearchPreset.OFF, RELAXED, EXPERT));
    }

    @Test
    void pointsScaleAndRoundHalfUp() {
        assertEquals(5, SourcePoints.points(5, 1.0));
        assertEquals(10, SourcePoints.points(5, RELAXED));
        assertEquals(3, SourcePoints.points(5, EXPERT));
        assertEquals(8, SourcePoints.points(15, EXPERT));
        assertEquals(1, SourcePoints.points(1, 0.01));
        assertEquals(0, SourcePoints.points(0, RELAXED));
    }

    @Test
    void observerRunsAndCapFollowTheScale() {
        assertEquals(20, SourcePoints.runsPerPoint(20, 1.0));
        assertEquals(10, SourcePoints.runsPerPoint(20, RELAXED));
        assertEquals(40, SourcePoints.runsPerPoint(20, EXPERT));
        assertEquals(1, SourcePoints.runsPerPoint(1, 100.0));
        assertEquals(400, SourcePoints.dailyCap(200, RELAXED));
        assertEquals(100, SourcePoints.dailyCap(200, EXPERT));
    }
}
