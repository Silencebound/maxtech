package dev.silencebound.maxtech.storage.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BulkStoreTest {
    @Test
    void roomIsWhatFitsUnderTheCap() {
        assertEquals(64, BulkStore.room(0, 1000, 64));
        assertEquals(10, BulkStore.room(990, 1000, 64));
        assertEquals(0, BulkStore.room(1000, 1000, 64));
    }

    @Test
    void aLoweredCapNeverGivesNegativeRoom() {
        assertEquals(0, BulkStore.room(2000, 1000, 64));
    }

    @Test
    void theLargestCapDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE - 1, BulkStore.room(1, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(0, BulkStore.room(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
