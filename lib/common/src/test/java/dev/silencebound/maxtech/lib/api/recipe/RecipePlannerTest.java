package dev.silencebound.maxtech.lib.api.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecipePlannerTest {
    /**
     * Item input i needs {@code counts[i]} items of letter {@code 'a' + i}, tank input i {@code amounts[i]} of letter
     * {@code 'x' + i}.
     */
    private static final class Needs implements RecipePlanner.Needs {
        private final int[] counts;
        private final boolean[] consumed;
        private final long[] amounts;

        Needs(int[] counts, boolean[] consumed, long[] amounts) {
            this.counts = counts;
            this.consumed = consumed;
            this.amounts = amounts;
        }

        @Override
        public int itemInputs() {
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

        @Override
        public int tankInputs() {
            return amounts.length;
        }

        @Override
        public long amount(int input) {
            return amounts[input];
        }
    }

    /**
     * Slots and tanks holding letters, as many as the counts say: input slots, then catalysts. With {@code anyItem}
     * every input accepts every slot and tank.
     */
    private static final class Have implements RecipePlanner.Have {
        private final String slots;
        private final int[] counts;
        private final int inputSlots;
        private final String tanks;
        private final long[] amounts;
        private final boolean anyItem;

        Have(String slots, int[] counts, int inputSlots, String tanks, long[] amounts, boolean anyItem) {
            this.slots = slots;
            this.counts = counts;
            this.inputSlots = inputSlots;
            this.tanks = tanks;
            this.amounts = amounts;
            this.anyItem = anyItem;
        }

        @Override
        public int inputSlots() {
            return inputSlots;
        }

        @Override
        public int catalystSlots() {
            return slots.length() - inputSlots;
        }

        @Override
        public int count(int slot) {
            return counts[slot];
        }

        @Override
        public boolean accepts(int input, int slot) {
            return anyItem || slots.charAt(slot) == (char) ('a' + input);
        }

        @Override
        public int tanks() {
            return tanks.length();
        }

        @Override
        public long amount(int tank) {
            return amounts[tank];
        }

        @Override
        public boolean tankAccepts(int input, int tank) {
            return anyItem || tanks.charAt(tank) == (char) ('x' + input);
        }
    }

    private static Needs items(int... counts) {
        boolean[] consumed = new boolean[counts.length];
        for (int i = 0; i < consumed.length; i++) {
            consumed[i] = true;
        }
        return new Needs(counts, consumed, new long[0]);
    }

    private static Have slots(String letters, int... counts) {
        return new Have(letters, counts, letters.length(), "", new long[0], false);
    }

    private static Have tanks(String letters, long... amounts) {
        return new Have("", new int[0], 0, letters, amounts, false);
    }

    @Test
    void exactCountIsEnoughAndOneLessIsNot() {
        MachineRecipePlan plan = new MachineRecipePlan(2, 0);
        assertTrue(RecipePlanner.plan(items(3), slots("ab", 3, 5), plan));
        assertEquals(3, plan.items(0));
        assertEquals(0, plan.items(1));
        assertFalse(RecipePlanner.plan(items(3), slots("ab", 2, 5), plan));
    }

    @Test
    void anInputSpreadsOverSlots() {
        MachineRecipePlan plan = new MachineRecipePlan(3, 0);
        assertTrue(RecipePlanner.plan(items(4), slots("aba", 2, 9, 2), plan));
        assertEquals(2, plan.items(0));
        assertEquals(0, plan.items(1));
        assertEquals(2, plan.items(2));
    }

    @Test
    void twoInputsForTheSameItemNeverShareIt() {
        Needs twice = items(2, 2);
        assertFalse(RecipePlanner.plan(twice, new Have("aa", new int[]{2, 1}, 2, "", new long[0], true),
                new MachineRecipePlan(2, 0)));
        MachineRecipePlan plan = new MachineRecipePlan(2, 0);
        assertTrue(RecipePlanner.plan(twice, new Have("aa", new int[]{2, 2}, 2, "", new long[0], true), plan));
        assertEquals(2, plan.items(0));
        assertEquals(2, plan.items(1));
    }

    @Test
    void catalystsComeOnlyFromCatalystSlotsAndAreReserved() {
        Needs withCatalyst = new Needs(new int[]{1, 1}, new boolean[]{true, false}, new long[0]);
        MachineRecipePlan plan = new MachineRecipePlan(2, 0);
        // Slot 0 is the input slot, slot 1 the catalyst slot.
        assertTrue(RecipePlanner.plan(withCatalyst, new Have("ab", new int[]{1, 1}, 1, "", new long[0], false), plan));
        assertEquals(1, plan.items(1));
        // The catalyst in the input slot does not count.
        assertFalse(RecipePlanner.plan(withCatalyst, new Have("ba", new int[]{1, 1}, 1, "", new long[0], false), plan));
    }

    @Test
    void tankInputsNeedTheirWholeAmount() {
        Needs water = new Needs(new int[0], new boolean[0], new long[]{1000});
        MachineRecipePlan plan = new MachineRecipePlan(0, 2);
        assertTrue(RecipePlanner.plan(water, tanks("yx", 5000, 1000), plan));
        assertEquals(0, plan.drained(0));
        assertEquals(1000, plan.drained(1));
        assertFalse(RecipePlanner.plan(water, tanks("yx", 5000, 999), plan));
        // Two tanks of the same resource add up.
        assertTrue(RecipePlanner.plan(water, tanks("xx", 600, 600), plan));
        assertEquals(600, plan.drained(0));
        assertEquals(400, plan.drained(1));
    }

    @Test
    void twoTankInputsNeverShareTheSameAmount() {
        Needs twice = new Needs(new int[0], new boolean[0], new long[]{1000, 1000});
        assertFalse(RecipePlanner.plan(twice, new Have("", new int[0], 0, "x", new long[]{1500}, true),
                new MachineRecipePlan(0, 1)));
        assertTrue(RecipePlanner.plan(twice, new Have("", new int[0], 0, "x", new long[]{2000}, true),
                new MachineRecipePlan(0, 1)));
    }

    @Test
    void aPlanSmallerThanTheMachineIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> RecipePlanner.plan(items(1), slots("aa", 1, 1), new MachineRecipePlan(1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> RecipePlanner.plan(items(), tanks("x", 1), new MachineRecipePlan(0, 0)));
    }
}
