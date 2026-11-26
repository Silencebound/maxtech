package dev.silencebound.maxtech.storage.cable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.silencebound.maxtech.storage.network.Channels;
import org.junit.jupiter.api.Test;

class SmartLoadTest {
    @Test
    void bucketsSplitAtFiftyAndNinety() {
        assertEquals(SmartLoad.IDLE, SmartLoad.bucket(0));
        assertEquals(SmartLoad.IDLE, SmartLoad.bucket(-5));
        assertEquals(SmartLoad.LOW, SmartLoad.bucket(1));
        assertEquals(SmartLoad.LOW, SmartLoad.bucket(49));
        assertEquals(SmartLoad.BUSY, SmartLoad.bucket(50));
        assertEquals(SmartLoad.BUSY, SmartLoad.bucket(89));
        assertEquals(SmartLoad.SATURATED, SmartLoad.bucket(90));
        assertEquals(SmartLoad.SATURATED, SmartLoad.bucket(100));
    }

    @Test
    void idleKeepsTheCablesColour() {
        assertEquals(0x123456, SmartLoad.colour(SmartLoad.IDLE, 0x123456));
        assertEquals(0x123456, SmartLoad.colour(7, 0x123456));
        assertNotEquals(SmartLoad.colour(SmartLoad.LOW, 0), SmartLoad.colour(SmartLoad.SATURATED, 0));
        assertNotEquals(0x123456, SmartLoad.colour(SmartLoad.BUSY, 0x123456));
    }

    /** The lane is dim while idle and brighter per bucket, brightest saturated; an unknown bucket reads idle. */
    @Test
    void brightnessFollowsTheBucket() {
        assertEquals(1, SmartLoad.brightness(SmartLoad.IDLE));
        assertEquals(2, SmartLoad.brightness(SmartLoad.LOW));
        assertEquals(3, SmartLoad.brightness(SmartLoad.BUSY));
        assertEquals(4, SmartLoad.brightness(SmartLoad.SATURATED));
        assertEquals(1, SmartLoad.brightness(7));
    }

    /** A tier-3 segment (32 channels) with 16 channels used reads busy, with 30 saturated and with none idle. */
    @Test
    void bucketFollowsTheChannelLoad() {
        assertEquals(SmartLoad.IDLE, SmartLoad.bucket(Channels.loadPercent(0, 32)));
        assertEquals(SmartLoad.LOW, SmartLoad.bucket(Channels.loadPercent(1, 32)));
        assertEquals(SmartLoad.BUSY, SmartLoad.bucket(Channels.loadPercent(16, 32)));
        assertEquals(SmartLoad.SATURATED, SmartLoad.bucket(Channels.loadPercent(30, 32)));
        assertEquals(SmartLoad.SATURATED, SmartLoad.bucket(Channels.loadPercent(32, 32)));
    }
}
