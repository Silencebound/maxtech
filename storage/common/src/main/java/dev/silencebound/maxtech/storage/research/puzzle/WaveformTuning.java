package dev.silencebound.maxtech.storage.research.puzzle;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.resources.Identifier;

/**
 * Waveform tuning (signal and wireless nodes): turn the frequency, amplitude and (from medium up) phase dials until the
 * wave matches the target wave. Easy has whole frequencies 1 to 6 and amplitudes 0.2 to 1.0 in steps of 0.2; medium
 * adds the phase in eighths of a turn; hard halves the frequency step, uses amplitude steps of 0.1 and phase steps of a
 * sixteenth of a turn. The target is a random dial setting, so it is reachable by construction.
 *
 * <p>
 * A solution is the three dial steps {@code [frequency, amplitude, phase]} (phase 0 on easy). It is accepted when the
 * root mean square difference of the two waves over one period, sampled at {@link #SAMPLES} points, is below
 * {@link #SOLVED_ERROR}; the start is at least {@link #START_ERROR} away.
 */
public final class WaveformTuning implements PuzzleType<WaveformTuning.Puzzle> {
    public static final Identifier ID = MaxtechStorage.id("waveform_tuning");
    public static final int DIALS = 3;
    public static final int FREQUENCY = 0;
    public static final int AMPLITUDE = 1;
    public static final int PHASE = 2;
    public static final int SAMPLES = 200;
    public static final double SOLVED_ERROR = 0.02;
    static final double START_ERROR = 0.05;
    private static final int[] FREQUENCY_STEPS = {6, 6, 11};
    private static final int[] AMPLITUDE_STEPS = {5, 5, 9};
    private static final int[] PHASE_STEPS = {1, 8, 16};

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public Puzzle generate(long seed, int difficulty) {
        int level = PuzzleType.clampDifficulty(difficulty);
        PuzzleRandom random = new PuzzleRandom(seed);
        Puzzle shape = new Puzzle(level, new int[DIALS], new int[DIALS]);
        int[] target = shape.randomDials(random);
        int[] start;
        do {
            start = shape.randomDials(random);
        } while (shape.error(start, target) < START_ERROR);
        return new Puzzle(level, target, start);
    }

    @Override
    public boolean validate(Puzzle puzzle, int[] solution) {
        return puzzle.inRange(solution) && puzzle.error(solution, puzzle.target) < SOLVED_ERROR;
    }

    @Override
    public int[] knownSolution(Puzzle puzzle) {
        return puzzle.target.clone();
    }

    @Override
    public int[] start(Puzzle puzzle) {
        return puzzle.start.clone();
    }

    /** The dial ranges of one difficulty and the target and start settings. */
    public static final class Puzzle {
        private final int level;
        private final int[] target;
        private final int[] start;

        Puzzle(int level, int[] target, int[] start) {
            this.level = level;
            this.target = target;
            this.start = start;
        }

        /** How many positions dial {@code dial} has; 1 means the dial is fixed (phase on easy). */
        public int steps(int dial) {
            return switch (dial) {
                case FREQUENCY -> FREQUENCY_STEPS[level];
                case AMPLITUDE -> AMPLITUDE_STEPS[level];
                default -> PHASE_STEPS[level];
            };
        }

        public double frequency(int step) {
            return 1.0 + step * (level == PuzzleType.HARD ? 0.5 : 1.0);
        }

        public double amplitude(int step) {
            return 0.2 + step * (level == PuzzleType.HARD ? 0.1 : 0.2);
        }

        /** The phase in radians. */
        public double phase(int step) {
            return step * 2 * Math.PI / Math.max(PHASE_STEPS[level], 8);
        }

        /** The wave of {@code dials} at {@code x} radians. */
        public double sample(int[] dials, double x) {
            return amplitude(dials[AMPLITUDE]) * Math.sin(frequency(dials[FREQUENCY]) * x + phase(dials[PHASE]));
        }

        public int[] target() {
            return target.clone();
        }

        /** Root mean square difference of the two waves over one period; both settings must be in range. */
        public double error(int[] dials, int[] other) {
            double sum = 0;
            for (int i = 0; i < SAMPLES; i++) {
                double x = i * 2 * Math.PI / SAMPLES;
                double difference = sample(dials, x) - sample(other, x);
                sum += difference * difference;
            }
            return Math.sqrt(sum / SAMPLES);
        }

        public boolean inRange(int[] dials) {
            if (dials.length != DIALS) {
                return false;
            }
            for (int dial = 0; dial < DIALS; dial++) {
                if (dials[dial] < 0 || dials[dial] >= steps(dial)) {
                    return false;
                }
            }
            return true;
        }

        int[] randomDials(PuzzleRandom random) {
            int[] dials = new int[DIALS];
            for (int dial = 0; dial < DIALS; dial++) {
                dials[dial] = random.nextInt(steps(dial));
            }
            return dials;
        }
    }
}
