package dev.silencebound.maxtech.lib.api.recipe;

/**
 * Plans which slots and tanks a recipe run takes from, without touching any item: pure logic over two small views, so
 * it is unit-tested without a game. Three independent assignments: consumed item inputs over the input slots, catalysts
 * over the catalyst slots (reserved, never taken) and tank inputs over the input tanks. Each is a small max-flow
 * problem (inputs need their counts, slots and tanks hold theirs, an input may draw from what it accepts), solved with
 * shortest augmenting paths, so a plan is found whenever one exists, also when two inputs accept the same resource and
 * a first-come choice would block the other. Work is bounded by the inputs, slots and tanks of the machine; the scratch
 * lives in the {@link MachineRecipePlan} and grows only when a recipe has more inputs than any before, so a match
 * allocates nothing.
 */
final class RecipePlanner {
    private static final int CONSUMED = 0;
    private static final int CATALYSTS = 1;
    private static final int TANKS = 2;

    private RecipePlanner() {
    }

    /** A recipe's inputs as the planner sees them. */
    interface Needs {
        int itemInputs();

        /** Items item input {@code input} needs per run, at least 1. */
        int itemCount(int input);

        /** Whether a run uses item input {@code input} up; false for a catalyst. */
        boolean consumed(int input);

        int tankInputs();

        /** Amount tank input {@code input} needs per run, at least 1. */
        long tankAmount(int input);
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
     * the inputs cannot all be covered at once.
     *
     * @throws IllegalArgumentException
     *             if {@code plan} covers fewer slots or tanks than {@code have}
     */
    static boolean plan(Needs needs, Have have, MachineRecipePlan plan) {
        int slots = have.inputSlots() + have.catalystSlots();
        int tanks = have.tanks();
        if (plan.items.length < slots || plan.tanks.length < tanks) {
            throw new IllegalArgumentException("Plan for " + plan.items.length + " slots and " + plan.tanks.length
                    + " tanks, machine has " + slots + " and " + tanks);
        }
        plan.ensure(Math.max(needs.itemInputs(), needs.tankInputs()), Math.max(slots, tanks));
        return assign(CONSUMED, needs, have, plan) && assign(CATALYSTS, needs, have, plan)
                && assign(TANKS, needs, have, plan);
    }

    /** Solves one of the three assignments and writes its columns' totals into the plan. */
    private static boolean assign(int group, Needs needs, Have have, MachineRecipePlan plan) {
        int[] members = plan.members;
        long[] need = plan.need;
        int n = 0;
        if (group == TANKS) {
            for (int input = 0; input < needs.tankInputs(); input++) {
                members[n] = input;
                need[n++] = needs.tankAmount(input);
            }
        } else {
            for (int input = 0; input < needs.itemInputs(); input++) {
                if (needs.consumed(input) == (group == CONSUMED)) {
                    members[n] = input;
                    need[n++] = needs.itemCount(input);
                }
            }
        }
        int first = group == CATALYSTS ? have.inputSlots() : 0;
        int m = group == TANKS ? have.tanks() : group == CONSUMED ? have.inputSlots() : have.catalystSlots();
        long[] spare = plan.spare;
        long[] flow = plan.flow;
        boolean[] edge = plan.edge;
        int stride = plan.stride;
        for (int c = 0; c < m; c++) {
            spare[c] = group == TANKS ? have.amount(c) : have.count(first + c);
        }
        for (int k = 0; k < n; k++) {
            for (int c = 0; c < m; c++) {
                int at = k * stride + c;
                flow[at] = 0;
                edge[at] = spare[c] > 0
                        && (group == TANKS ? have.tankAccepts(members[k], c) : have.accepts(members[k], first + c));
            }
        }
        boolean covered = true;
        for (int k = 0; k < n && covered; k++) {
            while (need[k] > 0 && covered) {
                covered = augment(k, n, m, plan);
            }
        }
        for (int c = 0; c < m; c++) {
            long used = 0;
            for (int k = 0; k < n; k++) {
                used += flow[k * stride + c];
            }
            if (group == TANKS) {
                plan.tanks[c] = used;
            } else {
                plan.items[first + c] = (int) used;
            }
        }
        return covered;
    }

    /**
     * One shortest augmenting path from input {@code root} to a column with spare room, through inputs whose flow can
     * be moved to another column they accept; pushes the path's bottleneck. False when no path exists.
     */
    private static boolean augment(int root, int n, int m, MachineRecipePlan plan) {
        int[] parent = plan.parent;
        int[] queue = plan.queue;
        long[] flow = plan.flow;
        boolean[] edge = plan.edge;
        int stride = plan.stride;
        // Nodes: inputs 0..n-1, columns n..n+m-1; parent -2 is unvisited, -1 the root.
        for (int i = 0; i < n + m; i++) {
            parent[i] = -2;
        }
        parent[root] = -1;
        int head = 0;
        int tail = 0;
        queue[tail++] = root;
        int end = -1;
        while (head < tail && end < 0) {
            int node = queue[head++];
            if (node < n) {
                for (int c = 0; c < m; c++) {
                    if (edge[node * stride + c] && parent[n + c] == -2) {
                        parent[n + c] = node;
                        if (plan.spare[c] > 0) {
                            end = c;
                            break;
                        }
                        queue[tail++] = n + c;
                    }
                }
            } else {
                int c = node - n;
                for (int k = 0; k < n; k++) {
                    if (parent[k] == -2 && flow[k * stride + c] > 0) {
                        parent[k] = node;
                        queue[tail++] = k;
                    }
                }
            }
        }
        if (end < 0) {
            return false;
        }
        long push = Math.min(plan.need[root], plan.spare[end]);
        for (int column = end;;) {
            int input = parent[n + column];
            int from = parent[input];
            if (from < 0) {
                break;
            }
            push = Math.min(push, flow[input * stride + (from - n)]);
            column = from - n;
        }
        for (int column = end;;) {
            int input = parent[n + column];
            flow[input * stride + column] += push;
            int from = parent[input];
            if (from < 0) {
                break;
            }
            flow[input * stride + (from - n)] -= push;
            column = from - n;
        }
        plan.spare[end] -= push;
        plan.need[root] -= push;
        return true;
    }
}
