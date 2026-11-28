package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import java.util.Arrays;

/**
 * A plain slot inventory standing in for a 1.21.1 handler: strings as resources, 64 per slot, no transactions. Each
 * {@link #view()} is a separate {@link LegacyTarget} over the same slots, like two capability lookups of one block.
 */
final class FakeInventory {
    static final int LIMIT = 64;

    final String[] resource;
    final int[] count;
    final boolean[] refusesInsert;
    boolean extractsImmediately = true;
    int realCalls;
    int overflow;

    FakeInventory(int slots) {
        resource = new String[slots];
        count = new int[slots];
        refusesInsert = new boolean[slots];
    }

    FakeInventory with(int slot, String what, int amount) {
        resource[slot] = what;
        count[slot] = amount;
        return this;
    }

    /** {@return every unit of {@code what} in the slots plus the overflow} */
    int total(String what) {
        int sum = overflow;
        for (int i = 0; i < count.length; i++) {
            if (what.equals(resource[i])) {
                sum += count[i];
            }
        }
        return sum;
    }

    @Override
    public String toString() {
        return Arrays.toString(resource) + Arrays.toString(count) + " overflow " + overflow;
    }

    View view() {
        return new View(LIMIT);
    }

    /** A view whose side lets at most {@code sideLimit} into a slot, like a restricted face of a machine. */
    View view(int sideLimit) {
        return new View(sideLimit);
    }

    final class View implements LegacyTarget<String> {
        private final int sideLimit;

        View(int sideLimit) {
            this.sideLimit = sideLimit;
        }

        @Override
        public boolean extractsImmediately() {
            return extractsImmediately;
        }

        @Override
        public int simulateInsert(int slot, String what, int amount) {
            if (refusesInsert[slot] || (count[slot] > 0 && !what.equals(resource[slot]))) {
                return 0;
            }
            return Math.max(0, Math.min(amount, Math.min(LIMIT, sideLimit) - count[slot]));
        }

        @Override
        public int simulateExtract(int slot, String what, int amount) {
            return what.equals(resource[slot]) ? Math.min(amount, count[slot]) : 0;
        }

        @Override
        public int insert(int slot, String what, int amount) {
            realCalls++;
            int accepted = simulateInsert(slot, what, amount);
            if (accepted > 0) {
                resource[slot] = what;
                count[slot] += accepted;
            }
            return accepted;
        }

        @Override
        public int insertAnywhere(String what, int amount) {
            int inserted = 0;
            for (int slot = 0; slot < count.length && inserted < amount; slot++) {
                inserted += insert(slot, what, amount - inserted);
            }
            return inserted;
        }

        @Override
        public int extract(int slot, String what, int amount) {
            realCalls++;
            int taken = simulateExtract(slot, what, amount);
            count[slot] -= taken;
            if (count[slot] == 0) {
                resource[slot] = null;
            }
            return taken;
        }

        @Override
        public int restore(int slot, String what, int amount) {
            realCalls++;
            if (count[slot] == 0 || what.equals(resource[slot])) {
                resource[slot] = what;
                count[slot] += amount;
                return amount;
            }
            return 0;
        }

        @Override
        public void overflow(String what, int amount) {
            overflow += amount;
        }

        @Override
        public Object handler() {
            return FakeInventory.this;
        }
    }
}
