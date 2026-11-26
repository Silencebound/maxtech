package dev.silencebound.maxtech.storage.research.puzzle;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.resources.Identifier;

/**
 * Mask alignment (processor nodes): stacked lithography masks expose only the cells open in all of them; turn (and on
 * medium and hard, flip) each mask until the exposed pattern matches the target. Generation draws random masks and a
 * random orientation per mask, takes their exposure as the target, and keeps the puzzle only when the target is neither
 * nearly empty nor nearly full and at most {@link #MAX_SOLUTIONS} orientation sets produce it.
 *
 * <p>
 * Masks are 6x6 bit sets (bit {@code y * 6 + x} set = open). A solution is one orientation per mask: quarter turns
 * clockwise (0 to 3) plus 4 when the mask is mirrored left to right first; mirroring is only allowed from medium up.
 */
public final class MaskAlignment implements PuzzleType<MaskAlignment.Puzzle> {
    public static final Identifier ID = MaxtechStorage.id("mask_alignment");
    public static final int SIZE = 6;
    static final int MAX_SOLUTIONS = 3;
    private static final long ALL = (1L << (SIZE * SIZE)) - 1;
    private static final int[] MASKS = {2, 2, 3};
    private static final int MIN_OPEN = 8;
    private static final int MAX_OPEN = 22;
    private static final int STRICT_TRIES = 1000;

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public Puzzle generate(long seed, int difficulty) {
        int level = PuzzleType.clampDifficulty(difficulty);
        PuzzleRandom random = new PuzzleRandom(seed);
        int count = MASKS[level];
        boolean flip = level > PuzzleType.EASY;
        double density = count == 3 ? 0.72 : 0.62;
        int orientations = flip ? 8 : 4;
        // The strict rules practically always hold within a few tries; past STRICT_TRIES only "the start is not
        // already solved" is kept, so generation always ends with a solvable puzzle.
        for (int tries = 0;; tries++) {
            boolean strict = tries < STRICT_TRIES;
            long[] masks = new long[count];
            for (int i = 0; i < count; i++) {
                for (int bit = 0; bit < SIZE * SIZE; bit++) {
                    if (random.nextDouble() < density) {
                        masks[i] |= 1L << bit;
                    }
                }
            }
            int[] solution = randomOrientations(random, count, orientations);
            long target = expose(masks, solution);
            int open = Long.bitCount(target);
            if (strict && (open < MIN_OPEN || open > MAX_OPEN)) {
                continue;
            }
            int[] start = randomOrientations(random, count, orientations);
            if (expose(masks, start) == target) {
                continue;
            }
            if (strict && countSolutions(masks, target, orientations) > MAX_SOLUTIONS) {
                continue;
            }
            return new Puzzle(masks, flip, target, solution, start);
        }
    }

    @Override
    public boolean validate(Puzzle puzzle, int[] solution) {
        if (solution.length != puzzle.masks.length) {
            return false;
        }
        for (int orientation : solution) {
            if (orientation < 0 || orientation >= puzzle.orientations()) {
                return false;
            }
        }
        return expose(puzzle.masks, solution) == puzzle.target;
    }

    @Override
    public int[] knownSolution(Puzzle puzzle) {
        return puzzle.solution.clone();
    }

    @Override
    public int[] start(Puzzle puzzle) {
        return puzzle.start.clone();
    }

    /**
     * {@code mask} mirrored left to right when {@code orientation >= 4}, then turned clockwise {@code orientation & 3}.
     */
    public static long transform(long mask, int orientation) {
        long grid = mask;
        if (orientation >= 4) {
            long mirrored = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    if ((grid >>> (y * SIZE + x) & 1) != 0) {
                        mirrored |= 1L << (y * SIZE + SIZE - 1 - x);
                    }
                }
            }
            grid = mirrored;
        }
        for (int turn = 0; turn < (orientation & 3); turn++) {
            long turned = 0;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    // Clockwise: the new cell (x, y) shows the old cell (y, SIZE - 1 - x).
                    if ((grid >>> ((SIZE - 1 - x) * SIZE + y) & 1) != 0) {
                        turned |= 1L << (y * SIZE + x);
                    }
                }
            }
            grid = turned;
        }
        return grid;
    }

    /** The cells open in every mask after orienting each; orientations must be in range. */
    public static long expose(long[] masks, int[] orientations) {
        long open = ALL;
        for (int i = 0; i < masks.length; i++) {
            open &= transform(masks[i], orientations[i]);
        }
        return open;
    }

    static int countSolutions(long[] masks, long target, int orientations) {
        long[][] oriented = new long[masks.length][orientations];
        for (int i = 0; i < masks.length; i++) {
            for (int o = 0; o < orientations; o++) {
                oriented[i][o] = transform(masks[i], o);
            }
        }
        return count(oriented, 0, ALL, target);
    }

    private static int count(long[][] oriented, int index, long open, long target) {
        if (index == oriented.length) {
            return open == target ? 1 : 0;
        }
        int total = 0;
        for (long option : oriented[index]) {
            long next = open & option;
            // Exposure only shrinks, so a branch that already closed a target cell cannot solve.
            if ((next & target) == target) {
                total += count(oriented, index + 1, next, target);
                if (total > MAX_SOLUTIONS) {
                    return total;
                }
            }
        }
        return total;
    }

    private static int[] randomOrientations(PuzzleRandom random, int count, int orientations) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = random.nextInt(orientations);
        }
        return result;
    }

    /** One generated set of masks with its target exposure. */
    public static final class Puzzle {
        private final long[] masks;
        private final boolean flip;
        private final long target;
        private final int[] solution;
        private final int[] start;

        Puzzle(long[] masks, boolean flip, long target, int[] solution, int[] start) {
            this.masks = masks;
            this.flip = flip;
            this.target = target;
            this.solution = solution;
            this.start = start;
        }

        public int maskCount() {
            return masks.length;
        }

        public long mask(int index) {
            return masks[index];
        }

        public boolean flipAllowed() {
            return flip;
        }

        /** 8 with mirroring, else 4. */
        public int orientations() {
            return flip ? 8 : 4;
        }

        public long target() {
            return target;
        }

        public long expose(int[] orientations) {
            return MaskAlignment.expose(masks, orientations);
        }
    }
}
