package dev.silencebound.maxtech.storage.port;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PortSpeedTest {
    @Test
    void eachCardMultipliesTheRate() {
        assertEquals(256, PortSpeed.rate(256, 0, 2.0), "no cards");
        assertEquals(512, PortSpeed.rate(256, 1, 2.0), "one card");
        assertEquals(1024, PortSpeed.rate(256, 2, 2.0), "two cards: x4");
        assertEquals(24, PortSpeed.rate(16, 1, 1.5), "another multiplier");
        assertEquals(36, PortSpeed.rate(16, 2, 1.5), "two cards of 1.5");
        assertEquals(17, PortSpeed.rate(11, 1, 1.6), "rounded down from 17.6");
    }

    @Test
    void rateStaysWithinBounds() {
        assertEquals(1, PortSpeed.rate(0, 0, 2.0), "at least one");
        assertEquals(Integer.MAX_VALUE, PortSpeed.rate(1_000_000_000, 4, 16.0), "saturates");
    }

    @Test
    void operationsAreOnePerBatch() {
        assertEquals(1, PortSpeed.operations(16, 64), "tier 1: one operation a second");
        assertEquals(1, PortSpeed.operations(64, 64), "tier 2: one batch");
        assertEquals(4, PortSpeed.operations(256, 64), "tier 3: four batches");
        assertEquals(16, PortSpeed.operations(1024, 64), "tier 3 with two cards");
        assertEquals(64, PortSpeed.operations(64_000, 1000), "tier 3 fluid with two cards");
        assertEquals(2, PortSpeed.operations(65, 64), "a partial batch is an operation");
    }
}
