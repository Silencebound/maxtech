package dev.silencebound.maxtech.storage.research.puzzle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PuzzleGeneratorsTest {
    private static final int SEEDS = 300;
    private static final List<PuzzleType<?>> TYPES = List.of(new SignalRouting(), new MaskAlignment(),
            new BytePacking(), new WaveformTuning());

    @Test
    void everyPuzzleIsSolvableAndStartsUnsolved() {
        for (PuzzleType<?> type : TYPES) {
            for (int difficulty = PuzzleType.EASY; difficulty <= PuzzleType.HARD; difficulty++) {
                for (long seed = 0; seed < SEEDS; seed++) {
                    solvableAndScrambled(type, seed * 0x9E3779B97F4A7C15L, difficulty);
                }
            }
        }
    }

    private static <S> void solvableAndScrambled(PuzzleType<S> type, long seed, int difficulty) {
        String where = type.id() + " seed " + seed + " difficulty " + difficulty;
        S puzzle = type.generate(seed, difficulty);
        int[] solution = type.knownSolution(puzzle);
        int[] start = type.start(puzzle);
        assertTrue(type.validate(puzzle, solution), where);
        assertTrue(type.check(seed, difficulty, solution), where + " regenerated");
        assertFalse(type.validate(puzzle, start), where + " start");
        assertFalse(Arrays.equals(solution, start), where + " start equals the solution");
        S again = type.generate(seed, difficulty);
        assertArrayEquals(solution, type.knownSolution(again), where + " not deterministic");
        assertArrayEquals(start, type.start(again), where + " not deterministic");
    }

    @Test
    void malformedSolutionsAreRejected() {
        for (PuzzleType<?> type : TYPES) {
            for (int difficulty = PuzzleType.EASY; difficulty <= PuzzleType.HARD; difficulty++) {
                malformed(type, 17L, difficulty);
            }
        }
    }

    private static <S> void malformed(PuzzleType<S> type, long seed, int difficulty) {
        S puzzle = type.generate(seed, difficulty);
        int[] solution = type.knownSolution(puzzle);
        assertFalse(type.validate(puzzle, new int[0]), type.id() + " empty");
        assertFalse(type.validate(puzzle, Arrays.copyOf(solution, solution.length + 1)), type.id() + " too long");
        assertFalse(type.validate(puzzle, Arrays.copyOf(solution, solution.length - 1)), type.id() + " too short");
        for (int bad : new int[]{-1, 1000, Integer.MIN_VALUE}) {
            int[] broken = solution.clone();
            broken[0] = bad;
            assertFalse(type.validate(puzzle, broken), type.id() + " value " + bad);
        }
    }

    @Test
    void difficultyIsClamped() {
        for (PuzzleType<?> type : TYPES) {
            sameAs(type, 5L, 9, PuzzleType.HARD);
            sameAs(type, 5L, -3, PuzzleType.EASY);
        }
    }

    private static <S> void sameAs(PuzzleType<S> type, long seed, int asked, int expected) {
        assertArrayEquals(type.knownSolution(type.generate(seed, expected)),
                type.knownSolution(type.generate(seed, asked)), type.id().toString());
    }

    @Test
    void signalRoutingSizesAndTurnedOutputs() {
        SignalRouting type = new SignalRouting();
        int[] sizes = {5, 6, 7};
        int[] outputs = {2, 3, 4};
        for (int difficulty = 0; difficulty < 3; difficulty++) {
            for (long seed = 0; seed < SEEDS; seed++) {
                SignalRouting.Puzzle puzzle = type.generate(seed, difficulty);
                assertEquals(sizes[difficulty], puzzle.size());
                assertEquals(outputs[difficulty], puzzle.outputCount());
                assertFalse(puzzle.isOutput(puzzle.source()));
                int[] solution = type.knownSolution(puzzle);
                assertEquals(puzzle.outputCount(), puzzle.litOutputs(solution));
                // An output is a leaf of the tree: turned away from its only link, it goes dark.
                for (int cell = 0; cell < solution.length; cell++) {
                    if (puzzle.isOutput(cell)) {
                        int[] turned = solution.clone();
                        turned[cell] = (turned[cell] + 1) % 4;
                        assertFalse(type.validate(puzzle, turned), "seed " + seed + " cell " + cell);
                    }
                }
            }
        }
    }

    @Test
    void signalRoutingRotatesClockwise() {
        assertEquals(2, SignalRouting.rotate(1, 1));
        assertEquals(1, SignalRouting.rotate(8, 1));
        assertEquals(5, SignalRouting.rotate(5, 2));
        assertEquals(3, SignalRouting.rotate(9, 1));
    }

    @Test
    void maskAlignmentHasFewSolutionsAndFlipsOnlyFromMedium() {
        MaskAlignment type = new MaskAlignment();
        int[] masks = {2, 2, 3};
        for (int difficulty = 0; difficulty < 3; difficulty++) {
            for (long seed = 0; seed < SEEDS; seed++) {
                MaskAlignment.Puzzle puzzle = type.generate(seed, difficulty);
                assertEquals(masks[difficulty], puzzle.maskCount());
                assertEquals(difficulty > 0, puzzle.flipAllowed());
                int open = Long.bitCount(puzzle.target());
                assertTrue(open >= 8 && open <= 22, "open cells " + open);
                int accepted = countAccepted(type, puzzle, new int[puzzle.maskCount()], 0);
                assertTrue(accepted >= 1 && accepted <= MaskAlignment.MAX_SOLUTIONS, "solutions " + accepted);
            }
        }
    }

    private static int countAccepted(MaskAlignment type, MaskAlignment.Puzzle puzzle, int[] orientations, int index) {
        if (index == orientations.length) {
            return type.validate(puzzle, orientations) ? 1 : 0;
        }
        int total = 0;
        for (int o = 0; o < puzzle.orientations(); o++) {
            orientations[index] = o;
            total += countAccepted(type, puzzle, orientations, index + 1);
        }
        return total;
    }

    @Test
    void maskTransformTurnsClockwiseAndMirrors() {
        long topLeft = 1L;
        long topRight = 1L << 5;
        long bottomRight = 1L << 35;
        assertEquals(topRight, MaskAlignment.transform(topLeft, 1));
        assertEquals(bottomRight, MaskAlignment.transform(topLeft, 2));
        assertEquals(topRight, MaskAlignment.transform(topLeft, 4));
        assertEquals(bottomRight, MaskAlignment.transform(topLeft, 5));
    }

    @Test
    void bytePackingFillsEveryBankExactly() {
        BytePacking type = new BytePacking();
        int[] banks = {3, 4, 5};
        int[] capacity = {8, 12, 16};
        for (int difficulty = 0; difficulty < 3; difficulty++) {
            for (long seed = 0; seed < SEEDS; seed++) {
                BytePacking.Puzzle puzzle = type.generate(seed, difficulty);
                assertEquals(banks[difficulty], puzzle.banks());
                assertEquals(capacity[difficulty], puzzle.capacity());
                int[] solution = type.knownSolution(puzzle);
                for (int bank = 0; bank < puzzle.banks(); bank++) {
                    assertEquals(puzzle.capacity(), puzzle.used(solution, bank));
                }
                for (int item = 0; item < puzzle.items(); item++) {
                    assertTrue(puzzle.size(item) >= 1);
                    int[] moved = solution.clone();
                    moved[item] = (moved[item] + 1) % puzzle.banks();
                    assertFalse(type.validate(puzzle, moved), "seed " + seed + " item " + item);
                }
            }
        }
    }

    @Test
    void waveformAcceptsOnlyTheTarget() {
        WaveformTuning type = new WaveformTuning();
        for (int difficulty = 0; difficulty < 3; difficulty++) {
            for (long seed = 0; seed < 20; seed++) {
                WaveformTuning.Puzzle puzzle = type.generate(seed, difficulty);
                int[] target = puzzle.target();
                int accepted = 0;
                int[] dials = new int[WaveformTuning.DIALS];
                for (int f = 0; f < puzzle.steps(WaveformTuning.FREQUENCY); f++) {
                    for (int a = 0; a < puzzle.steps(WaveformTuning.AMPLITUDE); a++) {
                        for (int p = 0; p < puzzle.steps(WaveformTuning.PHASE); p++) {
                            dials[0] = f;
                            dials[1] = a;
                            dials[2] = p;
                            if (type.validate(puzzle, dials)) {
                                accepted++;
                                assertArrayEquals(target, dials);
                            }
                        }
                    }
                }
                assertEquals(1, accepted);
                assertEquals(difficulty == 0 ? 1 : 8 * difficulty, puzzle.steps(WaveformTuning.PHASE));
                assertNotEquals(0.0, puzzle.error(type.start(puzzle), target));
            }
        }
    }
}
