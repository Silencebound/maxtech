package dev.silencebound.maxtech.storage.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The pure parts of port filter entries (B09 items 18 and 22): the match mode and order ids and names that saves, items
 * and menus hold, and the saved form of an entry's settings, including a filter saved before the settings existed.
 */
class PortMatchLogicTest {
    @Test
    void matchModeIdsAndNamesRoundTripAndStayUnique() {
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (MatchMode mode : MatchMode.values()) {
            assertEquals(mode, MatchMode.byId(mode.id()));
            assertEquals(mode, MatchMode.byName(mode.getSerializedName()));
            assertTrue(ids.add(mode.id()), "duplicate id " + mode.id());
            assertTrue(names.add(mode.getSerializedName()), "duplicate name " + mode.getSerializedName());
            // Two bits of the menu's match data slot carry the mode.
            assertTrue(mode.id() >= 0 && mode.id() < 4);
        }
        assertEquals(MatchMode.EXACT, MatchMode.byName("unknown"));
        assertEquals(MatchMode.EXACT, MatchMode.byId(99));
        assertEquals("exact", MatchMode.EXACT.getSerializedName());
        assertEquals("fuzzy", MatchMode.FUZZY.getSerializedName());
        assertEquals("tag", MatchMode.TAG.getSerializedName());
        assertEquals("mod", MatchMode.MOD.getSerializedName());
    }

    @Test
    void orderIdsAndNamesRoundTrip() {
        for (PortOrder order : PortOrder.values()) {
            assertEquals(order, PortOrder.byId(order.id()));
            assertEquals(order, PortOrder.byName(order.getSerializedName()));
            assertEquals(order, order.next().next());
        }
        assertEquals(PortOrder.FILTER, PortOrder.byName(""));
        assertEquals("round_robin", PortOrder.ROUND_ROBIN.getSerializedName());
    }

    @Test
    void entrySettingsSaveByNameAndLeaveDefaultsOut() {
        List<PortEntrySettings> settings = List.of(
                new PortEntrySettings(0, MatchMode.TAG, Optional.of(Identifier.parse("c:ingots")), 0),
                new PortEntrySettings(4, MatchMode.EXACT, Optional.empty(), 64));
        JsonElement json = PortEntrySettings.LIST_CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow();
        assertEquals(JsonParser.parseString(
                "[{\"slot\":0,\"match\":\"tag\",\"tag\":\"c:ingots\"}," + "{\"slot\":4,\"keep\":64}]"), json);
        assertEquals(settings, PortEntrySettings.LIST_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertTrue(new PortEntrySettings(3, MatchMode.EXACT, Optional.empty(), 0).isDefault());
        assertFalse(new PortEntrySettings(3, MatchMode.MOD, Optional.empty(), 0).isDefault());
    }

    @Test
    void anEntryWithOnlyASlotLoadsAsExact() {
        PortEntrySettings loaded = PortEntrySettings.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString("{\"slot\":2}")).getOrThrow();
        assertEquals(new PortEntrySettings(2, MatchMode.EXACT, Optional.empty(), 0), loaded);
        assertTrue(PortEntrySettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"slot\":9}")).isError(),
                "a slot past the filter");
        assertTrue(PortEntrySettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"slot\":1,\"keep\":-5}"))
                .isError(), "a negative keep amount");
    }
}
