package dev.silencebound.maxtech.storage.research.puzzle;

import net.minecraft.resources.Identifier;

/**
 * One kind of research puzzle (signal routing, mask alignment, byte packing, waveform tuning). A puzzle is generated
 * from a seed and a difficulty, the same on client and server, so only the seed travels to the client and only the
 * player's final configuration travels back; the server generates the puzzle again and validates that configuration.
 *
 * <p>
 * A solution is a flat {@code int[]} whose meaning each type defines: tile rotations, mask orientations, a bank per
 * item, dial steps. Generation builds a solution first and scrambles from it, so every generated puzzle is solvable.
 * Implementations are stateless and thread-safe; {@code generate} and {@code validate} never throw for any input.
 *
 * @param <S>
 *            the generated puzzle, immutable
 */
public interface PuzzleType<S> {
    /** Difficulty for the lowest research tier. */
    int EASY = 0;
    /** Difficulty for the middle research tier. */
    int MEDIUM = 1;
    /** Difficulty for the highest research tier. */
    int HARD = 2;

    /** The registry id, e.g. {@code maxtech_storage:signal_routing}. */
    Identifier id();

    /**
     * Builds the puzzle for {@code seed} and {@code difficulty}. The same arguments give an equal puzzle on every side
     * and every run. A difficulty outside {@link #EASY} to {@link #HARD} is clamped into that range.
     */
    S generate(long seed, int difficulty);

    /**
     * Whether {@code solution} solves {@code puzzle}. Rejects a solution of the wrong length or with values out of
     * range instead of throwing, since it comes from the client.
     */
    boolean validate(S puzzle, int[] solution);

    /** The solution generation started from; a valid answer, for tests. Never empty for a generated puzzle. */
    int[] knownSolution(S puzzle);

    /** The scrambled configuration the player starts from; never a solution itself. */
    int[] start(S puzzle);

    /** Generates the puzzle for {@code seed} and {@code difficulty} again and validates {@code solution} against it. */
    default boolean check(long seed, int difficulty, int[] solution) {
        return validate(generate(seed, difficulty), solution);
    }

    /** Clamps {@code difficulty} into {@link #EASY} to {@link #HARD}. */
    static int clampDifficulty(int difficulty) {
        return Math.clamp(difficulty, EASY, HARD);
    }
}
