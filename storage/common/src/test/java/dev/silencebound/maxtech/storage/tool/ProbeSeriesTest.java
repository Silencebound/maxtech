package dev.silencebound.maxtech.storage.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.silencebound.maxtech.storage.tool.cartridge.CartridgeCredit;
import dev.silencebound.maxtech.storage.tool.probe.ProbeSeries;
import org.junit.jupiter.api.Test;

class ProbeSeriesTest {
    @Test
    void windowKeepsTheNewestSamples() {
        ProbeSeries series = new ProbeSeries(3);
        for (int i = 1; i <= 5; i++) {
            series.add(i * 10);
        }
        assertEquals(3, series.size());
        assertEquals(30, series.get(0), "oldest kept");
        assertEquals(50, series.last());
        assertEquals(50, series.peak());
        assertThrows(IndexOutOfBoundsException.class, () -> series.get(3));
    }

    @Test
    void loadsAreClampedAndGapsKept() {
        ProbeSeries series = new ProbeSeries(4);
        assertEquals(ProbeSeries.NO_SIGNAL, series.last(), "empty");
        series.add(250);
        series.add(-5);
        assertEquals(100, series.get(0));
        assertEquals(ProbeSeries.NO_SIGNAL, series.get(1));
        assertEquals(100, series.peak(), "a gap never raises the peak");
    }

    @Test
    void capacityFollowsWindowAndCadence() {
        assertEquals(60, ProbeSeries.capacity(60, 20), "a minute at one sample a second");
        assertEquals(120, ProbeSeries.capacity(60, 10));
        assertEquals(1, ProbeSeries.capacity(0, 20), "never empty");
    }

    @Test
    void cartridgeGivesItsShareOfATokenAndNeverNothingAtAPositiveShare() {
        assertEquals(125, CartridgeCredit.points(250, 50));
        assertEquals(1, CartridgeCredit.points(1, 10), "rounded up to one");
        assertEquals(0, CartridgeCredit.points(250, 0));
        assertEquals(0, CartridgeCredit.points(0, 50));
    }
}
