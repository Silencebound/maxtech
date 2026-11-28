package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

/** Amount arithmetic for the legacy wrappers. */
final class LegacyMath {
    private LegacyMath() {
    }

    /** {@return a + b for non-negative amounts, saturated at {@code Integer.MAX_VALUE}} */
    static int add(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
    }

    /** {@return {@code reported} clamped to {@code 0..amount}: a handler never counts as moving more than asked} */
    static int clamp(int reported, int amount) {
        return Math.max(0, Math.min(amount, reported));
    }
}
