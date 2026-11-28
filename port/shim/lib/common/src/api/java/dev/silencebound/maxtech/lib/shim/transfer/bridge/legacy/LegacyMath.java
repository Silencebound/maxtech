package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

/** Amount arithmetic for the legacy wrappers. */
final class LegacyMath {
    private LegacyMath() {
    }

    /** {@return a + b for non-negative amounts, saturated at {@code Integer.MAX_VALUE}} */
    static int add(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
    }
}
