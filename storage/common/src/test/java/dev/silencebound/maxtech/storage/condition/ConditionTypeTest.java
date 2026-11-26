package dev.silencebound.maxtech.storage.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConditionTypeTest {
    @Test
    void idsAndNamesAreUniqueAndRoundTrip() {
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (ConditionType type : ConditionType.values()) {
            assertTrue(ids.add(type.id()), "id " + type.id() + " used twice");
            assertTrue(names.add(type.getSerializedName()), type.getSerializedName() + " used twice");
            assertEquals(type, ConditionType.byId(type.id()));
        }
    }

    @Test
    void savedIdsStayFixed() {
        // Saved in worlds and items by name and synced to the GUI by id: these must never change.
        assertEquals(8, ConditionType.WHILE_CRAFTING.id());
        assertEquals("while_crafting", ConditionType.WHILE_CRAFTING.getSerializedName());
        assertEquals(9, ConditionType.CRAFT_REQUEST.id());
        assertEquals("craft_request", ConditionType.CRAFT_REQUEST.getSerializedName());
    }

    @Test
    void theButtonCyclesThroughEveryType() {
        Set<ConditionType> seen = EnumSet.noneOf(ConditionType.class);
        ConditionType type = ConditionType.ITEM_COUNT;
        for (int i = 0; i < ConditionType.values().length; i++) {
            seen.add(type);
            type = type.next();
        }
        assertEquals(ConditionType.ITEM_COUNT, type, "back at the start after one round");
        assertEquals(EnumSet.allOf(ConditionType.class), seen);
    }
}
