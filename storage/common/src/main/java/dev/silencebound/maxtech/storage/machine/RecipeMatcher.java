package dev.silencebound.maxtech.storage.machine;

/**
 * Plans which input slots a recipe run takes its items from, without touching any item: pure logic over two small
 * views, so it is unit-tested without a game. Each consumed input takes from the input slots in order, first come first
 * served, never counting an item twice; each unconsumed input (a mask) must be present in the catalyst slots and stays
 * there. Greedy: two inputs that accept the same item can fail to plan in an order a smarter search would find, which
 * recipes avoid by not overlapping. Allocates nothing.
 */
public final class RecipeMatcher {
    private RecipeMatcher() {
    }

    /** A recipe's inputs as the matcher sees them. */
    public interface Requirements {
        int inputCount();

        /** Items input {@code input} needs per run, at least 1. */
        int count(int input);

        /** Whether a run uses input {@code input} up; false for a catalyst. */
        boolean consumed(int input);
    }

    /** A machine's slots as the matcher sees them: input slots first, then catalyst slots. */
    public interface Slots {
        int inputSlots();

        int catalystSlots();

        /** Items in {@code slot}; 0 when empty. */
        int count(int slot);

        /** Whether the items in {@code slot} are ones input {@code input} accepts; never asked for an empty slot. */
        boolean accepts(int input, int slot);
    }

    /**
     * Fills {@code take} (at least as long as both slot groups together) with the items each slot gives up; catalyst
     * slots' entries only reserve and are never taken. Returns false, leaving {@code take} meaningless, when an input
     * cannot be covered.
     */
    public static boolean plan(Requirements recipe, Slots slots, int[] take) {
        int inputSlots = slots.inputSlots();
        int all = inputSlots + slots.catalystSlots();
        for (int slot = 0; slot < all; slot++) {
            take[slot] = 0;
        }
        for (int input = 0; input < recipe.inputCount(); input++) {
            boolean consumed = recipe.consumed(input);
            int need = recipe.count(input);
            int first = consumed ? 0 : inputSlots;
            int end = consumed ? inputSlots : all;
            for (int slot = first; slot < end && need > 0; slot++) {
                int left = slots.count(slot) - take[slot];
                if (left > 0 && slots.accepts(input, slot)) {
                    int taken = Math.min(left, need);
                    take[slot] += taken;
                    need -= taken;
                }
            }
            if (need > 0) {
                return false;
            }
        }
        return true;
    }
}
