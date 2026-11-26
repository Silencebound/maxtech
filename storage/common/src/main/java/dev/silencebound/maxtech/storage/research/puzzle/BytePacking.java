package dev.silencebound.maxtech.storage.research.puzzle;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Arrays;
import net.minecraft.resources.Identifier;

/**
 * Byte packing (memory nodes): store every item kind, each a block of bytes, in memory banks of fixed size without
 * overfilling any bank. Generation cuts each bank into two to four pieces at random points, so the pieces fill every
 * bank exactly, then shuffles them. A solution is one bank index per item (in the shuffled order); since the pieces add
 * up to all banks' capacity, any assignment that overfills no bank fills them all exactly.
 */
public final class BytePacking implements PuzzleType<BytePacking.Puzzle> {
    public static final Identifier ID = MaxtechStorage.id("byte_packing");
    /** The start configuration: nothing is stored yet. */
    public static final int UNPLACED = -1;
    private static final int[] BANKS = {3, 4, 5};
    private static final int[] CAPACITY = {8, 12, 16};

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public Puzzle generate(long seed, int difficulty) {
        int level = PuzzleType.clampDifficulty(difficulty);
        PuzzleRandom random = new PuzzleRandom(seed);
        int banks = BANKS[level];
        int capacity = CAPACITY[level];
        int[] sizes = new int[banks * 4];
        int[] owners = new int[banks * 4];
        int count = 0;
        for (int bank = 0; bank < banks; bank++) {
            int parts = 2 + random.nextInt(level == PuzzleType.EASY ? 2 : 3);
            boolean[] cut = new boolean[capacity];
            int cuts = 0;
            while (cuts < parts - 1) {
                int at = 1 + random.nextInt(capacity - 1);
                if (!cut[at]) {
                    cut[at] = true;
                    cuts++;
                }
            }
            int from = 0;
            for (int at = 1; at <= capacity; at++) {
                if (at == capacity || cut[at]) {
                    sizes[count] = at - from;
                    owners[count] = bank;
                    count++;
                    from = at;
                }
            }
        }
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        random.shuffle(order);
        int[] shuffledSizes = new int[count];
        int[] solution = new int[count];
        for (int i = 0; i < count; i++) {
            shuffledSizes[i] = sizes[order[i]];
            solution[i] = owners[order[i]];
        }
        return new Puzzle(banks, capacity, shuffledSizes, solution);
    }

    @Override
    public boolean validate(Puzzle puzzle, int[] solution) {
        if (solution.length != puzzle.sizes.length) {
            return false;
        }
        int[] used = new int[puzzle.banks];
        for (int i = 0; i < solution.length; i++) {
            int bank = solution[i];
            if (bank < 0 || bank >= puzzle.banks) {
                return false;
            }
            used[bank] += puzzle.sizes[i];
            if (used[bank] > puzzle.capacity) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int[] knownSolution(Puzzle puzzle) {
        return puzzle.solution.clone();
    }

    @Override
    public int[] start(Puzzle puzzle) {
        int[] start = new int[puzzle.sizes.length];
        Arrays.fill(start, UNPLACED);
        return start;
    }

    /** Memory banks of one capacity and the item kinds to store, in the order the player sees them. */
    public static final class Puzzle {
        private final int banks;
        private final int capacity;
        private final int[] sizes;
        private final int[] solution;

        Puzzle(int banks, int capacity, int[] sizes, int[] solution) {
            this.banks = banks;
            this.capacity = capacity;
            this.sizes = sizes;
            this.solution = solution;
        }

        public int banks() {
            return banks;
        }

        /** Bytes per bank. */
        public int capacity() {
            return capacity;
        }

        public int items() {
            return sizes.length;
        }

        /** Bytes item {@code index} takes. */
        public int size(int index) {
            return sizes[index];
        }

        /** Bytes the items assigned to {@code bank} take, for an assignment with values in range or unplaced. */
        public int used(int[] assignment, int bank) {
            int total = 0;
            for (int i = 0; i < assignment.length && i < sizes.length; i++) {
                if (assignment[i] == bank) {
                    total += sizes[i];
                }
            }
            return total;
        }
    }
}
