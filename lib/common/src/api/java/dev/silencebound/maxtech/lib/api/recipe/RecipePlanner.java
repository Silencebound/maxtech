package dev.silencebound.maxtech.lib.api.recipe;

/**
 * Plans which slots and tanks a recipe run takes from, without touching any item: pure logic over two small views, so
 * it is unit-tested without a game (Storage's {@code RecipeMatcher}, extended by tanks). Each consumed item input takes
 * from the input slots in order, first come first served, never counting an item twice; each catalyst must be present
 * in the catalyst slots and stays there; each tank input drains the input tanks the same way. Greedy: two inputs that
 * accept the same resource can fail to plan in an order a search would find, which recipes avoid by not overlapping.
 * Allocates nothing.
 */
final class RecipePlanner {
    private RecipePlanner() {
    }

    /** A recipe's inputs as the planner sees them. */
    interface Needs {
        int itemInputs();

        /** Items item input {@code input} needs per run, at least 1. */
        int count(int input);

        /** Whether a run uses item input {@code input} up; false for a catalyst. */
        boolean consumed(int input);

        int tankInputs();

        /** Amount tank input {@code input} needs per run, at least 1. */
        long amount(int input);
    }

    /** A machine's contents as the planner sees them: input slots, then catalyst slots, then input tanks. */
    interface Have {
        int inputSlots();

        int catalystSlots();

        /** Items in {@code slot}; 0 when empty. */
        int count(int slot);

        /**
         * Whether the items in {@code slot} are ones item input {@code input} accepts; never asked for an empty slot.
         */
        boolean accepts(int input, int slot);

        int tanks();

        /** Amount in {@code tank}; 0 when empty. */
        long amount(int tank);

        /** Whether the resource in {@code tank} is one tank input {@code input} accepts; never asked when empty. */
        boolean tankAccepts(int input, int tank);
    }

    /**
     * Fills {@code plan} with what each slot and tank gives up. Returns false, leaving {@code plan} meaningless, when
     * an input cannot be covered.
     *
     * @throws IllegalArgumentException
     *             if {@code plan} covers fewer slots or tanks than {@code have}
     */
    static boolean plan(Needs needs, Have have, MachineRecipePlan plan) {
        int inputSlots = have.inputSlots();
        int slots = inputSlots + have.catalystSlots();
        int tanks = have.tanks();
        if (plan.items.length < slots || plan.tanks.length < tanks) {
            throw new IllegalArgumentException("Plan for " + plan.items.length + " slots and " + plan.tanks.length
                    + " tanks, machine has " + slots + " and " + tanks);
        }
        int[] take = plan.items;
        for (int slot = 0; slot < slots; slot++) {
            take[slot] = 0;
        }
        for (int input = 0; input < needs.itemInputs(); input++) {
            boolean consumed = needs.consumed(input);
            int need = needs.count(input);
            int first = consumed ? 0 : inputSlots;
            int end = consumed ? inputSlots : slots;
            for (int slot = first; slot < end && need > 0; slot++) {
                int left = have.count(slot) - take[slot];
                if (left > 0 && have.accepts(input, slot)) {
                    int taken = Math.min(left, need);
                    take[slot] += taken;
                    need -= taken;
                }
            }
            if (need > 0) {
                return false;
            }
        }
        long[] drain = plan.tanks;
        for (int tank = 0; tank < tanks; tank++) {
            drain[tank] = 0;
        }
        for (int input = 0; input < needs.tankInputs(); input++) {
            long need = needs.amount(input);
            for (int tank = 0; tank < tanks && need > 0; tank++) {
                long left = have.amount(tank) - drain[tank];
                if (left > 0 && have.tankAccepts(input, tank)) {
                    long taken = Math.min(left, need);
                    drain[tank] += taken;
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
