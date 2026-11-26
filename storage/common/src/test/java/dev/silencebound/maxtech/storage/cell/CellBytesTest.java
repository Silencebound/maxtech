package dev.silencebound.maxtech.storage.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The byte math with M6a's starting numbers: 8 items or 8,000 mB a byte, kinds 8 bytes, with components 64. */
class CellBytesTest {
    private static final long KB = 1024;
    private static final long ITEMS = 8;
    private static final long MB = 8000;
    private static final long KIND = 8;
    private static final long COMPONENT_KIND = 64;

    @Test
    void bytesRoundUpPerKindAndAddTheOverhead() {
        assertEquals(0, CellBytes.bytes(0, KIND, ITEMS));
        assertEquals(KIND + 1, CellBytes.bytes(1, KIND, ITEMS));
        assertEquals(KIND + 1, CellBytes.bytes(8, KIND, ITEMS));
        assertEquals(KIND + 2, CellBytes.bytes(9, KIND, ITEMS));
        assertEquals(COMPONENT_KIND + 1, CellBytes.bytes(1, COMPONENT_KIND, ITEMS));
        assertEquals(KIND + 1, CellBytes.bytes(1, KIND, MB));
        assertEquals(KIND + 2, CellBytes.bytes(8001, KIND, MB));
    }

    @Test
    void starterCellHoldsOneKindMinusItsOverhead() {
        // 1 KB holds 8,192 plain items of one kind, minus the 8-byte overhead: 1,016 bytes of 8.
        assertEquals(8128, CellBytes.room(0, KIND, 0, KB, ITEMS));
        assertEquals((KB - COMPONENT_KIND) * ITEMS, CellBytes.room(0, COMPONENT_KIND, 0, KB, ITEMS));
        assertEquals((KB - KIND) * MB, CellBytes.room(0, KIND, 0, KB, MB));
    }

    @Test
    void heldKindsFillTheirLastByteFirst() {
        // 3 items use 9 bytes of which 5 slots are free; 1,015 free bytes add 8,120 more.
        long used = CellBytes.bytes(3, KIND, ITEMS);
        assertEquals(5 + (KB - used) * ITEMS, CellBytes.room(3, KIND, used, KB, ITEMS));
        // A full last byte and no free bytes: nothing more fits.
        assertEquals(0, CellBytes.room(8, KIND, KB, KB, ITEMS));
        // Its last byte still has room even when the cell has no free byte.
        assertEquals(7, CellBytes.room(1, KIND, KB, KB, ITEMS));
    }

    @Test
    void newKindsNeedTheirOverheadAndOneByte() {
        assertEquals(0, CellBytes.room(0, KIND, KB - KIND, KB, ITEMS));
        assertEquals(ITEMS, CellBytes.room(0, KIND, KB - KIND - 1, KB, ITEMS));
        assertEquals(0, CellBytes.room(0, COMPONENT_KIND, KB - 63, KB, ITEMS));
    }

    @Test
    void overfullCellsTakeNothingAndNeverGoNegative() {
        assertEquals(0, CellBytes.room(0, KIND, KB + 100, KB, ITEMS));
        assertEquals(0, CellBytes.room(500, KIND, KB + 100, KB, ITEMS));
    }

    @Test
    void hugeCapacitiesSaturate() {
        assertEquals(Long.MAX_VALUE, CellBytes.room(0, KIND, 0, Long.MAX_VALUE / 2, MB));
        assertEquals(Long.MAX_VALUE, CellBytes.bytes(Long.MAX_VALUE, Long.MAX_VALUE, 1));
    }

    @Test
    void bandsSplitAtThreeQuartersAndFull() {
        assertEquals(CellBytes.BAND_LOW, CellBytes.band(0, KB));
        assertEquals(CellBytes.BAND_LOW, CellBytes.band(767, KB));
        assertEquals(CellBytes.BAND_HIGH, CellBytes.band(768, KB));
        assertEquals(CellBytes.BAND_HIGH, CellBytes.band(KB - 1, KB));
        assertEquals(CellBytes.BAND_FULL, CellBytes.band(KB, KB));
        assertEquals(CellBytes.BAND_FULL, CellBytes.band(0, 0));
    }
}
