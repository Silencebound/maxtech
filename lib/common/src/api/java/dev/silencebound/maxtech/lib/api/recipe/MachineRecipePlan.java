package dev.silencebound.maxtech.lib.api.recipe;

import org.jetbrains.annotations.ApiStatus;

/**
 * Where a matched recipe takes its inputs from: per input slot the items a run takes (a catalyst slot's entry only
 * reserves, nothing is taken from it), per input tank the amount drained. {@link MachineRecipe#matches} fills it, so
 * after a match a machine moves exactly what the plan says. One per machine, reused for every match; meaningless after
 * a failed match. Not thread-safe: use it on the machine's thread only.
 */
@ApiStatus.Experimental
public final class MachineRecipePlan {
    final int[] items;
    final long[] tanks;

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
}
