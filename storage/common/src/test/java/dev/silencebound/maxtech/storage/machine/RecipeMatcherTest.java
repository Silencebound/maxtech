package dev.silencebound.maxtech.storage.machine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecipeMatcherTest {
    /** Slots holding "items" named by a letter, as many as {@code counts} says; empty slots are blank. */
    private static final class Slots implements RecipeMatcher.Slots {
        private final String items;
        private final int[] counts;
        private final int inputSlots;

        Slots(String items, int[] counts, int inputSlots) {
            this.items = items;
            this.counts = counts;
            this.inputSlots = inputSlots;
        }

        @Override
        public int inputSlots() {
            return inputSlots;
        }

        @Override
        public int catalystSlots() {
            return items.length() - inputSlots;
        }

        @Override
        public int count(int slot) {
            return counts[slot];
        }

        @Override
        public boolean accepts(int input, int slot) {
            return items.charAt(slot) == (char) ('a' + input);
        }
    }

    /**
     * Input {@code i} wants item {@code 'a' + i}, {@code counts[i]} of it, used up unless {@code consumed[i]} is false.
     */
    private static final class Recipe implements RecipeMatcher.Requirements {
        private final int[] counts;
        private final boolean[] consumed;

        Recipe(int[] counts, boolean[] consumed) {
            this.counts = counts;
            this.consumed = consumed;
        }

        @Override
        public int inputCount() {
            return counts.length;
        }

        @Override
        public int count(int input) {
            return counts[input];
        }

        @Override
        public boolean consumed(int input) {
            return consumed[input];
        }
    }

    @Test
    void takesFromSeveralSlots() {
        int[] take = new int[3];
        assertTrue(RecipeMatcher.plan(new Recipe(new int[]{5}, new boolean[]{true}),
                new Slots("aba", new int[]{3, 9, 4}, 3), take));
        assertArrayEquals(new int[]{3, 0, 2}, take);
    }

    @Test
    void failsWhenShort() {
        assertFalse(RecipeMatcher.plan(new Recipe(new int[]{2, 1}, new boolean[]{true, true}),
                new Slots("ab", new int[]{1, 1}, 2), new int[2]));
    }

    @Test
    void neverCountsAnItemTwice() {
        // Two inputs that both take item 'a' would need three, but there are only two.
        Recipe twoOfA = new Recipe(new int[]{2, 1}, new boolean[]{true, true});
        RecipeMatcher.Slots slots = new RecipeMatcher.Slots() {
            @Override
            public int inputSlots() {
                return 1;
            }

            @Override
            public int catalystSlots() {
                return 0;
            }

            @Override
            public int count(int slot) {
                return 2;
            }

            @Override
            public boolean accepts(int input, int slot) {
                return true;
            }
        };
        assertFalse(RecipeMatcher.plan(twoOfA, slots, new int[1]));
    }

    @Test
    void catalystOnlyFromCatalystSlotAndNeverTaken() {
        Recipe withMask = new Recipe(new int[]{1, 1}, new boolean[]{true, false});
        int[] take = new int[3];
        // The mask ('b') sits in an input slot: it does not count.
        assertFalse(RecipeMatcher.plan(withMask, new Slots("ab ", new int[]{1, 1, 0}, 2), take));
        assertTrue(RecipeMatcher.plan(withMask, new Slots("a b", new int[]{1, 0, 1}, 2), take));
        assertArrayEquals(new int[]{1, 0, 1}, take, "the catalyst slot is only reserved");
    }

    @Test
    void reusesTheTakeArray() {
        int[] take = {7, 7, 7};
        assertTrue(RecipeMatcher.plan(new Recipe(new int[]{1}, new boolean[]{true}),
                new Slots("xa", new int[]{4, 4}, 2), take));
        assertArrayEquals(new int[]{0, 1, 7}, take, "only the slots of the machine are reset");
    }
}
