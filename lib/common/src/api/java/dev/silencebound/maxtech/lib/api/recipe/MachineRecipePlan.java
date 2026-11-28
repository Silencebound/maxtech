package dev.silencebound.maxtech.lib.api.recipe;

import org.jetbrains.annotations.ApiStatus;

/**
 * Where a matched recipe takes its inputs from: per input slot the items a run takes (a catalyst slot's entry only
 * reserves, nothing is taken from it), per input tank the amount drained. {@link MachineRecipe#matches} fills it, so
 * after a match a machine moves exactly what the plan says. One per machine, reused for every match; meaningless after
 * a failed match. It also holds the matcher's scratch, which grows only when a recipe has more inputs than any before,
 * so matching allocates nothing. Not thread-safe: use it on the machine's thread only.
 */
@ApiStatus.Experimental
public final class MachineRecipePlan {
    final int[] items;
    final long[] tanks;
    /** Binds a recipe and an input as the planner's views, so a match allocates no view objects. */
    final MachineRecipe.Views views = new MachineRecipe.Views();
    // Planner scratch, see RecipePlanner: per input, per column, and an inputs x columns matrix of width stride.
    int[] members = new int[0];
    long[] need = new long[0];
    long[] spare = new long[0];
    long[] flow = new long[0];
    boolean[] edge = new boolean[0];
    int[] parent = new int[0];
    int[] queue = new int[0];
    int stride;

    /** A plan for a machine with {@code slots} input and catalyst slots together and {@code tanks} input tanks. */
    public MachineRecipePlan(int slots, int tanks) {
        this.items = new int[slots];
        this.tanks = new long[tanks];
    }

    /** Input and catalyst slots this plan covers. */
    public int slots() {
        return items.length;
    }

    /** Input tanks this plan covers. */
    public int tanks() {
        return tanks.length;
    }

    /** Items the run takes from {@code slot} (input slots first, then catalyst slots, which are only reserved). */
    public int items(int slot) {
        return items[slot];
    }

    /** Amount the run drains from input tank {@code tank}. */
    public long drained(int tank) {
        return tanks[tank];
    }

    /** Makes the scratch room for {@code inputs} inputs over {@code columns} slots or tanks. */
    void ensure(int inputs, int columns) {
        if (inputs <= members.length && columns <= stride) {
            return;
        }
        int rows = Math.max(inputs, members.length);
        int width = Math.max(columns, stride);
        members = new int[rows];
        need = new long[rows];
        spare = new long[width];
        flow = new long[rows * width];
        edge = new boolean[rows * width];
        parent = new int[rows + width];
        queue = new int[rows + width];
        stride = width;
    }
}
