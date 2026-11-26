package dev.silencebound.maxtech.storage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Moved config keys (M7R item 8): a set old key wins and warns, an unset one leaves the new key alone. */
class MovedKeysTest {
    @Test
    void setOldKeyWins() {
        assertEquals(30, MovedKeys.pick(30, 20));
        assertEquals(0, MovedKeys.pick(0, 20), "0 is a value, not unset");
        assertEquals(500L, MovedKeys.pick(500L, 200L));
    }

    @Test
    void unsetOldKeyLeavesTheNewOne() {
        assertEquals(20, MovedKeys.pick(MovedKeys.UNSET, 20));
        assertEquals(200L, MovedKeys.pick((long) MovedKeys.UNSET, 200L));
    }

    @Test
    void warningsNameTheNewKeyOfEachSetOldKey() {
        List<String> warnings = MovedKeys.warnings(List.of(new MovedKeys.Moved("a.old", "a.new", () -> 7),
                new MovedKeys.Moved("b.old", "b.new", () -> MovedKeys.UNSET)));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("'a.old'") && warnings.get(0).contains("'a.new'")
                && warnings.get(0).contains(" 7 "), warnings.get(0));
    }
}
