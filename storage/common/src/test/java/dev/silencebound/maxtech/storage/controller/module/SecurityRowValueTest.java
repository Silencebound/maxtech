package dev.silencebound.maxtech.storage.controller.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.network.AccessLevel;
import org.junit.jupiter.api.Test;

class SecurityRowValueTest {
    @Test
    void everyLevelAndMarkRoundTrips() {
        for (AccessLevel level : AccessLevel.values()) {
            for (boolean trusted : new boolean[]{false, true}) {
                int value = SecurityRowValue.encode(level, trusted);
                assertEquals(level, SecurityRowValue.level(value));
                assertEquals(trusted, SecurityRowValue.trusted(value));
                assertTrue(value >= 0 && value <= Short.MAX_VALUE, "fits a data slot");
            }
        }
    }

    @Test
    void noRuleIsNotNone() {
        int value = SecurityRowValue.encode(null, true);
        assertNull(SecurityRowValue.level(value));
        assertTrue(SecurityRowValue.trusted(value));
        assertEquals(AccessLevel.NONE, SecurityRowValue.level(SecurityRowValue.encode(AccessLevel.NONE, false)));
        assertFalse(SecurityRowValue.trusted(SecurityRowValue.encode(null, false)));
    }
}
