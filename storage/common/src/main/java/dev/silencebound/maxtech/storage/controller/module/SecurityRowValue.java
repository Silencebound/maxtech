package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.storage.network.AccessLevel;
import org.jspecify.annotations.Nullable;

/**
 * How one security screen row travels in a data slot (a short): the low three bits are the rule's level plus one (0 for
 * a row without a rule of its own), bit 3 says the owner trusts the row's player. Pure; any thread.
 */
public final class SecurityRowValue {
    private static final int LEVEL_MASK = 7;
    private static final int TRUSTED_BIT = 8;

    private SecurityRowValue() {
    }

    /** The value for a row with {@code level} (null: no rule) whose player is or is not trusted. */
    public static int encode(@Nullable AccessLevel level, boolean trusted) {
        return (level == null ? 0 : level.id() + 1) | (trusted ? TRUSTED_BIT : 0);
    }

    /** The row's rule, or null when it has none. */
    public static @Nullable AccessLevel level(int value) {
        int level = value & LEVEL_MASK;
        return level == 0 ? null : AccessLevel.byId(level - 1);
    }

    /** Whether the row's player is trusted. */
    public static boolean trusted(int value) {
        return (value & TRUSTED_BIT) != 0;
    }
}
