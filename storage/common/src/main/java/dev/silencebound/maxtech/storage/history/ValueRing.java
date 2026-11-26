package dev.silencebound.maxtech.storage.history;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.stream.LongStream;

/**
 * One recorded value over time: a fine ring of {@link #CAPACITY} samples taken every {@link #interval()} ticks and a
 * coarse ring of as many points, each the mean of {@link #COARSE_FACTOR} fine samples, so the default one-minute
 * interval keeps four hours in full and 24 hours in six-minute steps. Memory is fixed at construction: two
 * {@code long[CAPACITY]} (3,840 bytes) and a few fields.
 *
 * <p>
 * {@link #record} takes a sample only when one is due, so callers may ask early. Intervals in which nothing was
 * recorded (the chunk was unloaded, the network was down) are stored as {@link #NO_VALUE} gaps, never made up; a chart
 * leaves them blank. Server thread only; saved with its owner through {@link #CODEC}.
 */
public final class ValueRing {
    /** A sample that was not taken, or a value that could not be read. */
    public static final long NO_VALUE = Long.MIN_VALUE;
    /** Samples each ring holds. */
    public static final int CAPACITY = 240;
    /** Fine samples per coarse point. */
    public static final int COARSE_FACTOR = 6;

    public static final Codec<ValueRing> CODEC = RecordCodecBuilder
            .create(instance -> instance
                    .group(Codec.INT.fieldOf("interval").forGetter(ValueRing::interval),
                            Codec.LONG.optionalFieldOf("last", Long.MIN_VALUE).forGetter(ValueRing::lastTime),
                            Codec.INT.optionalFieldOf("phase", 0).forGetter(ring -> ring.phase),
                            Codec.DOUBLE.optionalFieldOf("sum", 0D).forGetter(ring -> ring.sum),
                            Codec.INT.optionalFieldOf("count", 0).forGetter(ring -> ring.count),
                            Codec.LONG_STREAM.optionalFieldOf("fine", LongStream.empty())
                                    .forGetter(ring -> LongStream.of(ring.fineArray())),
                            Codec.LONG_STREAM.optionalFieldOf("coarse", LongStream.empty())
                                    .forGetter(ring -> LongStream.of(ring.coarseArray())))
                    .apply(instance, ValueRing::loaded));

    private final int interval;
    private final long[] fine = new long[CAPACITY];
    private int fineHead;
    private int fineSize;
    private final long[] coarse = new long[CAPACITY];
    private int coarseHead;
    private int coarseSize;
    private long lastTime = Long.MIN_VALUE;
    /** Fine samples pushed into the coarse point being built. */
    private int phase;
    /** Sum and count of the read values among them (gaps left out). */
    private double sum;
    private int count;

    /** An empty ring sampling every {@code intervalTicks} ticks (at least 1). */
    public ValueRing(int intervalTicks) {
        this.interval = Math.max(1, intervalTicks);
    }

    private static ValueRing loaded(int interval, long last, int phase, double sum, int count, LongStream fine,
            LongStream coarse) {
        ValueRing ring = new ValueRing(interval);
        ring.lastTime = last;
        ring.phase = Math.clamp(phase, 0, COARSE_FACTOR - 1);
        ring.sum = Double.isFinite(sum) ? sum : 0;
        ring.count = Math.clamp(count, 0, ring.phase);
        long[] values = fine.toArray();
        for (int i = Math.max(0, values.length - CAPACITY); i < values.length; i++) {
            ring.fine[ring.fineHead] = values[i];
            ring.fineHead = (ring.fineHead + 1) % CAPACITY;
            ring.fineSize = Math.min(CAPACITY, ring.fineSize + 1);
        }
        long[] points = coarse.toArray();
        for (int i = Math.max(0, points.length - CAPACITY); i < points.length; i++) {
            ring.pushCoarse(points[i]);
        }
        return ring;
    }

    /** Ticks between two samples. */
    public int interval() {
        return interval;
    }

    /** Game time of the newest sample; {@link Long#MIN_VALUE} before the first. */
    public long lastTime() {
        return lastTime;
    }

    /** Whether a sample is due at {@code gameTime}. */
    public boolean due(long gameTime) {
        return lastTime == Long.MIN_VALUE || gameTime >= lastTime + interval || gameTime < lastTime;
    }

    /** Ticks from {@code gameTime} until the next sample is due; at least 1. */
    public int ticksUntilDue(long gameTime) {
        if (due(gameTime)) {
            return 1;
        }
        return (int) Math.max(1, lastTime + interval - gameTime);
    }

    /**
     * Records {@code value} ({@link #NO_VALUE} when it could not be read) at {@code gameTime} if a sample is due;
     * returns whether it did. Whole intervals missed since the last sample become gaps first, at most as many as both
     * rings hold. Allocates nothing.
     */
    public boolean record(long gameTime, long value) {
        if (!due(gameTime)) {
            return false;
        }
        if (lastTime != Long.MIN_VALUE && gameTime > lastTime) {
            long missed = (gameTime - lastTime) / interval - 1;
            long gaps = Math.min(missed, (long) CAPACITY * COARSE_FACTOR + COARSE_FACTOR);
            for (long i = 0; i < gaps; i++) {
                push(NO_VALUE);
            }
        }
        push(value);
        lastTime = gameTime;
        return true;
    }

    private void push(long value) {
        fine[fineHead] = value;
        fineHead = (fineHead + 1) % CAPACITY;
        fineSize = Math.min(CAPACITY, fineSize + 1);
        if (value != NO_VALUE) {
            sum += value;
            count++;
        }
        phase++;
        if (phase == COARSE_FACTOR) {
            pushCoarse(count == 0 ? NO_VALUE : Math.round(sum / count));
            phase = 0;
            sum = 0;
            count = 0;
        }
    }

    private void pushCoarse(long value) {
        coarse[coarseHead] = value;
        coarseHead = (coarseHead + 1) % CAPACITY;
        coarseSize = Math.min(CAPACITY, coarseSize + 1);
    }

    /** Fine samples held, at most {@link #CAPACITY}. */
    public int size() {
        return fineSize;
    }

    /** Coarse points held, at most {@link #CAPACITY}. */
    public int coarseSize() {
        return coarseSize;
    }

    /** Fine sample {@code index}, 0 the oldest held. */
    public long fineAt(int index) {
        return fine[Math.floorMod(fineHead - fineSize + index, CAPACITY)];
    }

    /** Coarse point {@code index}, 0 the oldest held. */
    public long coarseAt(int index) {
        return coarse[Math.floorMod(coarseHead - coarseSize + index, CAPACITY)];
    }

    /** The newest sample, or {@link #NO_VALUE} before the first or when it could not be read. */
    public long latest() {
        return fineSize == 0 ? NO_VALUE : fineAt(fineSize - 1);
    }

    /** Ticks the fine ring covers when full. */
    public long fineSpan() {
        return (long) CAPACITY * interval;
    }

    /**
     * Writes the last {@code windowTicks} as at most {@code maxPoints} chart points into {@code out}, oldest first,
     * ending at the newest sample; returns how many it wrote (the points are {@code windowTicks / n} ticks apart,
     * rounded up). Reads the fine ring when it spans the window, else the coarse one; a point is the mean of the
     * samples in its stretch, or {@link #NO_VALUE} when none was read there (a gap, or older than the history). Never
     * more points than samples fit in the window, so a long interval does not make every other point a gap. Allocates
     * nothing.
     */
    public int chart(long windowTicks, int maxPoints, long[] out) {
        long window = Math.max(1, windowTicks);
        boolean useFine = window <= fineSpan();
        long step = useFine ? interval : (long) interval * COARSE_FACTOR;
        int held = useFine ? fineSize : coarseSize;
        int points = (int) Math.clamp(window / step, 1, Math.min(maxPoints, out.length));
        long perPoint = (window + points - 1) / points;
        Arrays.fill(out, 0, points, NO_VALUE);
        // Means without a scratch array: walk the samples newest first, closing a point when the walk leaves it.
        int current = -1;
        double total = 0;
        int read = 0;
        for (int age = 0; age < held; age++) {
            long ago = age * step;
            if (ago >= window) {
                break;
            }
            int point = points - 1 - (int) (ago / perPoint);
            if (point != current) {
                if (current >= 0 && read > 0) {
                    out[current] = Math.round(total / read);
                }
                current = point;
                total = 0;
                read = 0;
            }
            long value = useFine ? fineAt(held - 1 - age) : coarseAt(held - 1 - age);
            if (value != NO_VALUE) {
                total += value;
                read++;
            }
        }
        if (current >= 0 && read > 0) {
            out[current] = Math.round(total / read);
        }
        return points;
    }

    private long[] fineArray() {
        long[] out = new long[fineSize];
        for (int i = 0; i < fineSize; i++) {
            out[i] = fineAt(i);
        }
        return out;
    }

    private long[] coarseArray() {
        long[] out = new long[coarseSize];
        for (int i = 0; i < coarseSize; i++) {
            out[i] = coarseAt(i);
        }
        return out;
    }
}
