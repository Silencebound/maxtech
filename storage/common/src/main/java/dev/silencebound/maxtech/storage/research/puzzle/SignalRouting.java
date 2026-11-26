package dev.silencebound.maxtech.storage.research.puzzle;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Arrays;
import net.minecraft.resources.Identifier;

/**
 * Signal routing (network nodes): rotate wire tiles on a square grid until the source reaches every output. Generation
 * grows a random spanning tree from the source, picks outputs among its far leaves, then turns every tile a random
 * number of quarter turns. A solution is one clockwise turn count (0 to 3) per cell, row by row, applied to the
 * scrambled tiles; any rotation set that lights every output is accepted, not only the original tree.
 *
 * <p>
 * Tiles are four-bit masks of their open sides: north 1, east 2, south 4, west 8.
 */
public final class SignalRouting implements PuzzleType<SignalRouting.Puzzle> {
    public static final Identifier ID = MaxtechStorage.id("signal_routing");
    private static final int[] SIZES = {5, 6, 7};
    private static final int[] OUTPUTS = {2, 3, 4};
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DY = {-1, 0, 1, 0};
    private static final int TREE_ATTEMPTS = 64;

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public Puzzle generate(long seed, int difficulty) {
        int level = PuzzleType.clampDifficulty(difficulty);
        PuzzleRandom random = new PuzzleRandom(seed);
        int n = SIZES[level];
        int outputCount = OUTPUTS[level];
        int source = n / 2 * n;
        int[] tree = new int[0];
        int[] candidates = new int[0];
        for (int attempt = 0; attempt < TREE_ATTEMPTS && candidates.length < outputCount; attempt++) {
            tree = spanningTree(random, n, source);
            candidates = farCells(tree, n, source, outputCount, true);
        }
        if (candidates.length < outputCount) {
            // No tree in TREE_ATTEMPTS had enough leaves (never seen in tests); any cell of the tree still works.
            candidates = farCells(tree, n, source, outputCount, false);
        }
        random.shuffle(candidates);
        int[] outputs = Arrays.copyOf(candidates, outputCount);
        Arrays.sort(outputs);

        int[] turns = new int[n * n];
        int[] tiles = new int[n * n];
        // A random scramble lights every output with negligible chance; draw again until it doesn't.
        do {
            for (int cell = 0; cell < tiles.length; cell++) {
                turns[cell] = random.nextInt(4);
                tiles[cell] = rotate(tree[cell], turns[cell]);
            }
        } while (allOutputsLit(n, source, tiles, outputs));
        int[] solution = new int[n * n];
        for (int cell = 0; cell < solution.length; cell++) {
            solution[cell] = (4 - turns[cell]) % 4;
        }
        return new Puzzle(n, source, tiles, outputs, solution);
    }

    @Override
    public boolean validate(Puzzle puzzle, int[] solution) {
        int[] tiles = puzzle.rotated(solution);
        return tiles.length > 0 && allOutputsLit(puzzle.size(), puzzle.source(), tiles, puzzle.outputs);
    }

    @Override
    public int[] knownSolution(Puzzle puzzle) {
        return puzzle.solution.clone();
    }

    @Override
    public int[] start(Puzzle puzzle) {
        return new int[puzzle.tiles.length];
    }

    /** {@code mask} turned clockwise {@code turns} quarter turns. */
    public static int rotate(int mask, int turns) {
        int result = mask & 15;
        for (int i = 0; i < (turns & 3); i++) {
            result = (result << 1 | result >> 3) & 15;
        }
        return result;
    }

    /** Which cells the source reaches through mutually open sides, for the given tiles. */
    public static boolean[] lit(int n, int source, int[] tiles) {
        boolean[] on = new boolean[n * n];
        int[] queue = new int[n * n];
        int head = 0;
        int tail = 0;
        on[source] = true;
        queue[tail++] = source;
        while (head < tail) {
            int cell = queue[head++];
            int x = cell % n;
            int y = cell / n;
            for (int d = 0; d < 4; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                if ((tiles[cell] & 1 << d) == 0 || nx < 0 || ny < 0 || nx >= n || ny >= n) {
                    continue;
                }
                int next = ny * n + nx;
                if (!on[next] && (tiles[next] & 1 << ((d + 2) % 4)) != 0) {
                    on[next] = true;
                    queue[tail++] = next;
                }
            }
        }
        return on;
    }

    static boolean allOutputsLit(int n, int source, int[] tiles, int[] outputs) {
        boolean[] on = lit(n, source, tiles);
        for (int output : outputs) {
            if (!on[output]) {
                return false;
            }
        }
        return true;
    }

    /** A depth-first random spanning tree over the whole grid, as side masks per cell. */
    private static int[] spanningTree(PuzzleRandom random, int n, int source) {
        int[] tree = new int[n * n];
        boolean[] seen = new boolean[n * n];
        int[] stack = new int[n * n];
        int[] options = new int[4];
        int top = 0;
        stack[top++] = source;
        seen[source] = true;
        while (top > 0) {
            int cell = stack[top - 1];
            int x = cell % n;
            int y = cell / n;
            int count = 0;
            for (int d = 0; d < 4; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                if (nx >= 0 && ny >= 0 && nx < n && ny < n && !seen[ny * n + nx]) {
                    options[count++] = d;
                }
            }
            if (count == 0) {
                top--;
                continue;
            }
            int d = options[random.nextInt(count)];
            int next = (y + DY[d]) * n + x + DX[d];
            tree[cell] |= 1 << d;
            tree[next] |= 1 << ((d + 2) % 4);
            seen[next] = true;
            stack[top++] = next;
        }
        return tree;
    }

    /**
     * Leaves other than the source, farthest right (then lowest) first, at most twice the output count: the source sits
     * on the left edge, so outputs end up across the board. Too few leaves gives a shorter array. With
     * {@code leavesOnly} false every cell but the source counts.
     */
    private static int[] farCells(int[] tree, int n, int source, int outputCount, boolean leavesOnly) {
        int[] leaves = new int[tree.length];
        int count = 0;
        for (int cell = 0; cell < tree.length; cell++) {
            if (cell != source && (!leavesOnly || Integer.bitCount(tree[cell]) == 1)) {
                leaves[count++] = cell;
            }
        }
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            order[i] = leaves[i];
        }
        Arrays.sort(order, (a, b) -> Integer.compare(farKey(b, n), farKey(a, n)));
        int[] picked = new int[Math.min(count, outputCount * 2)];
        for (int i = 0; i < picked.length; i++) {
            picked[i] = order[i];
        }
        return picked;
    }

    private static int farKey(int cell, int n) {
        return 10 * (cell % n) + 3 * (cell / n);
    }

    /** One generated board: scrambled tiles, the source cell and the output cells (ascending). */
    public static final class Puzzle {
        private final int size;
        private final int source;
        private final int[] tiles;
        private final int[] outputs;
        private final int[] solution;

        Puzzle(int size, int source, int[] tiles, int[] outputs, int[] solution) {
            this.size = size;
            this.source = source;
            this.tiles = tiles;
            this.outputs = outputs;
            this.solution = solution;
        }

        public int size() {
            return size;
        }

        public int source() {
            return source;
        }

        /** The scrambled tile at {@code cell}, before the player turns it. */
        public int tile(int cell) {
            return tiles[cell];
        }

        public boolean isOutput(int cell) {
            return Arrays.binarySearch(outputs, cell) >= 0;
        }

        public int outputCount() {
            return outputs.length;
        }

        /** The tiles after applying {@code rotations}; empty when they are malformed. */
        public int[] rotated(int[] rotations) {
            if (rotations.length != tiles.length) {
                return new int[0];
            }
            int[] result = new int[tiles.length];
            for (int cell = 0; cell < tiles.length; cell++) {
                if (rotations[cell] < 0 || rotations[cell] > 3) {
                    return new int[0];
                }
                result[cell] = rotate(tiles[cell], rotations[cell]);
            }
            return result;
        }

        /** How many outputs the source reaches with {@code rotations}. */
        public int litOutputs(int[] rotations) {
            int[] turned = rotated(rotations);
            if (turned.length == 0) {
                return 0;
            }
            boolean[] on = lit(size, source, turned);
            int count = 0;
            for (int output : outputs) {
                if (on[output]) {
                    count++;
                }
            }
            return count;
        }
    }
}
