package dev.silencebound.maxtech.storage.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The allow and deny rules of a cell filter (B09 item 10) and the saved deny flag. Matching real keys (exact, fuzzy,
 * tag) needs bootstrapped registries, so that half runs as the {@code cell_deny_matcher} GameTest; here the decision a
 * match feeds into, which the matcher calls for every key.
 */
class CellFilterTest {
    @Test
    void allowListTakesOnlyMatches() {
        assertTrue(CellFilter.accepts(false, false, true));
        assertFalse(CellFilter.accepts(false, false, false));
    }

    @Test
    void denyListTakesEverythingButMatches() {
        assertFalse(CellFilter.accepts(false, true, true));
        assertTrue(CellFilter.accepts(false, true, false));
    }

    @Test
    void blankFilterTakesEverythingInBothModes() {
        assertTrue(CellFilter.accepts(true, false, false));
        assertTrue(CellFilter.accepts(true, true, false));
    }

    @Test
    void voidOverflowNeverDestroysADeniedKey() {
        assertTrue(CellFilter.voids(false, false, true));
        assertFalse(CellFilter.voids(false, false, false));
        assertFalse(CellFilter.voids(false, true, true));
        assertTrue(CellFilter.voids(false, true, false));
    }

    @Test
    void blankFilterVoidsNothingInBothModes() {
        assertFalse(CellFilter.voids(true, false, false));
        assertFalse(CellFilter.voids(true, true, false));
    }

    @Test
    void editsKeepTheMode() {
        CellFilter deny = CellFilter.EMPTY.withDeny(true);
        assertTrue(deny.deny());
        assertTrue(deny.withFuzzy(true).deny());
        assertTrue(deny.with(0, null).deny());
        assertFalse(deny.withDeny(false).deny());
        assertFalse(new CellFilter(List.of(), true).deny());
    }

    @Test
    void denyFlagIsSavedAndOldFiltersLoadAsAllowLists() {
        JsonElement saved = CellFilter.CODEC.encodeStart(JsonOps.INSTANCE, new CellFilter(List.of(), true, true))
                .getOrThrow();
        CellFilter loaded = CellFilter.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(new CellFilter(List.of(), true, true), loaded);
        CellFilter old = CellFilter.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"fuzzy\": true}"))
                .getOrThrow();
        assertFalse(old.deny());
        assertTrue(old.fuzzy());
    }
}
