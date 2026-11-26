package dev.silencebound.maxtech.storage.cell;

/**
 * A cell's byte accounting (PRD 7.2, capacity in bytes; M6 starting numbers): a kind held in any amount costs its
 * overhead (more for keys with data components) plus one byte per started {@code unitsPerByte} items or mB, rounded up
 * per kind. Pure math on longs; amounts that would overflow are capped at {@link Long#MAX_VALUE}.
 */
public final class CellBytes {
    /** Fill band of a cell below 75 %. */
    public static final int BAND_LOW = 1;
    /** Fill band of a cell at 75 % or more. */
    public static final int BAND_HIGH = 2;
    /** Fill band of a cell with no free byte. */
    public static final int BAND_FULL = 3;

    private CellBytes() {
    }

    /** Bytes {@code count} of one kind costs; 0 for none. */
    public static long bytes(long count, long overhead, long unitsPerByte) {
        if (count <= 0) {
            return 0;
        }
        return saturatedAdd(overhead, ceilDiv(count, unitsPerByte));
    }

    /**
     * How much more of a kind fits: the kind holds {@code count} now (0 for a new kind), the cell uses {@code used} of
     * its {@code total} bytes. A new kind needs its overhead and at least one byte; a kind already held may also fill
     * the slack of its last started byte. 0 when nothing fits, also when a cell is over its total (after a config
     * change), so nothing is ever lost and extracting still works.
     */
    public static long room(long count, long overhead, long used, long total, long unitsPerByte) {
        long budget = total - used + bytes(count, overhead, unitsPerByte);
        long dataBytes = budget - overhead;
        if (dataBytes <= 0) {
            return 0;
        }
        long max = saturatedMultiply(dataBytes, unitsPerByte);
        return Math.max(0, max - count);
    }

    /**
     * The fill band a drive shows for a cell: {@link #BAND_LOW} below 75 % of its bytes, {@link #BAND_HIGH} from 75 %,
     * {@link #BAND_FULL} once no byte is free.
     */
    public static int band(long used, long total) {
        if (used >= total) {
            return BAND_FULL;
        }
        // Three quarters without multiplying, so no total can overflow.
        return used >= total - total / 4 ? BAND_HIGH : BAND_LOW;
    }

    private static long ceilDiv(long count, long unitsPerByte) {
        return Math.ceilDiv(count, Math.max(1, unitsPerByte));
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    private static long saturatedMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return high != 0 || low < 0 ? Long.MAX_VALUE : low;
    }
}
