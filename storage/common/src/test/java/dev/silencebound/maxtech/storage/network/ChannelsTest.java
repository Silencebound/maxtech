package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.cable.SmartLoad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The channel rules {@link NetworkState} counts with (B09 item 12): with limits a link takes devices while it has room;
 * without limits ({@link ChannelMode#INFINITE}) it always has room and its load stays below saturated; and a mode
 * change raises the stamp networks recount on, once per change.
 */
class ChannelsTest {
    @AfterEach
    void limited() {
        Channels.modeLoaded(ChannelMode.LIMITED);
    }

    @Test
    void limitedLinksTakeDevicesWhileTheyHaveRoom() {
        assertTrue(Channels.hasRoom(0, 8, false));
        assertTrue(Channels.hasRoom(7, 8, false));
        assertFalse(Channels.hasRoom(8, 8, false));
        assertFalse(Channels.hasRoom(9, 8, false));
    }

    @Test
    void infiniteLinksAlwaysHaveRoom() {
        assertTrue(Channels.hasRoom(8, 8, true));
        assertTrue(Channels.hasRoom(1_000, 8, true));
        assertTrue(Channels.hasRoom(Integer.MAX_VALUE, 1, true));
    }

    @Test
    void infiniteLoadShowsButNeverReadsAsFull() {
        assertEquals(50, Channels.loadPercent(4, 8, true));
        assertEquals(Channels.INFINITE_LOAD_MAX, Channels.loadPercent(8, 8, true));
        assertEquals(Channels.INFINITE_LOAD_MAX, Channels.loadPercent(100, 8, true));
        assertEquals(100, Channels.loadPercent(8, 8, false));
        assertTrue(SmartLoad.bucket(Channels.loadPercent(1_000, 8, true)) < SmartLoad.SATURATED);
        assertEquals(0, Channels.loadPercent(0, 8, true));
    }

    @Test
    void aModeChangeRaisesTheStampOnce() {
        Channels.modeLoaded(ChannelMode.LIMITED);
        int before = Channels.modeStamp();
        Channels.modeLoaded(ChannelMode.LIMITED);
        assertEquals(before, Channels.modeStamp(), "a reload with the same mode");
        assertFalse(Channels.infinite());
        Channels.modeLoaded(ChannelMode.INFINITE);
        assertTrue(Channels.infinite());
        assertEquals(before + 1, Channels.modeStamp(), "a switch to infinite");
        Channels.modeLoaded(ChannelMode.INFINITE);
        assertEquals(before + 1, Channels.modeStamp(), "the same mode again");
        Channels.modeLoaded(ChannelMode.LIMITED);
        assertEquals(before + 2, Channels.modeStamp(), "back to limited");
    }
}
