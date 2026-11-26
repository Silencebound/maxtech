package dev.silencebound.maxtech.storage.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The reference forms that need no registry; item and fluid counts are covered by the GameTests. */
class ChartValueTest {
    private static String read(String text) {
        return ChartValue.parse(text).map(ChartValue::describe).orElse("none");
    }

    @Test
    void networkTotals() {
        assertEquals("energy|||0", read("energy"));
        assertEquals("energy|||0", read(" Energy "));
        assertEquals("bytes|||0", read("bytes"));
        assertEquals("items|||0", read("items"));
        assertEquals("fluids|||0", read("fluid"));
    }

    @Test
    void variablesAndPins() {
        assertEquals("variable|main|batch|0", read("variable:Main: Batch"));
        assertEquals("pin|main|Smelting: ores|1", read("pin:main:Smelting: ores:2"));
        assertEquals("pin|main|3|0", read("pin:main:3:1"));
    }

    @Test
    void refused() {
        for (String bad : new String[]{"", "power", "variable:main", "variable::x", "pin:main:rule", "pin:main:rule:0",
                "pin:main:rule:x", "pin::rule:1", "sensor:nolabel", "x".repeat(ChartValue.MAX_LENGTH + 1)}) {
            assertTrue(ChartValue.parse(bad).isEmpty(), bad);
        }
    }
}
