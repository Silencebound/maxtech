package dev.silencebound.maxtech.storage.research.puzzle;

/**
 * SplitMix64, written out so a puzzle generated from a seed is the same on every JVM, client and server; vanilla's
 * random sources are free to change between versions.
 */
final class PuzzleRandom {
    private long state;

    PuzzleRandom(long seed) {
        state = seed;
    }

    long nextLong() {
        long z = state += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in {@code [0, bound)}; {@code bound} must be positive. */
    int nextInt(int bound) {
        return (int) Long.remainderUnsigned(nextLong(), bound);
    }

    /** Uniform in {@code [0, 1)}. */
    double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    boolean nextBoolean() {
        return nextLong() < 0;
    }

    void shuffle(int[] values) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            int swap = values[i];
            values[i] = values[j];
            values[j] = swap;
        }
    }
}
