package dev.silencebound.maxtech.storage.crafting.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListCodeTest {
    private static ListCode.Entry item(String id, long amount) {
        return new ListCode.Entry(false, id, amount);
    }

    @Test
    void encodesTheDocumentedFormat() {
        String code = ListCode.encode(List.of(item("minecraft:stick", 64), item("minecraft:torch", 16),
                new ListCode.Entry(true, "minecraft:water", 1000)));
        assertEquals("mtlist1:minecraft:stick*64,minecraft:torch*16,f/minecraft:water*1000", code);
    }

    @Test
    void roundTrip() {
        List<ListCode.Entry> entries = List.of(item("minecraft:stick", 64), item("maxtech_storage:blank_pattern", 3),
                new ListCode.Entry(true, "minecraft:lava", 250));
        ListCode.Parsed parsed = ListCode.decode(ListCode.encode(entries));
        assertEquals(entries, parsed.entries());
        assertTrue(parsed.errors().isEmpty());
    }

    @Test
    void typedListsAreLenient() {
        ListCode.Parsed parsed = ListCode.decode(" stick * 64 ; torch\nMINECRAFT:Coal*2, ");
        assertEquals(List.of(item("minecraft:stick", 64), item("minecraft:torch", 1), item("minecraft:coal", 2)),
                parsed.entries());
        assertTrue(parsed.errors().isEmpty());
    }

    @Test
    void theSameIdAddsUp() {
        ListCode.Parsed parsed = ListCode.decode("mtlist1:stick*10,torch*1,minecraft:stick*5");
        assertEquals(List.of(item("minecraft:stick", 15), item("minecraft:torch", 1)), parsed.entries());
        ListCode.Parsed capped = ListCode.decode("stick*2147483647,stick*5");
        assertEquals(ListCode.MAX_AMOUNT, capped.entries().getFirst().amount());
    }

    @Test
    void brokenPartsAreReportedAndLeftOut() {
        ListCode.Parsed parsed = ListCode.decode("stick*0,torch*-1,coal*x,bad$id,a:b:c,stick*99999999999,ok*3");
        assertEquals(List.of(item("minecraft:ok", 3)), parsed.entries());
        assertEquals(List.of("stick*0", "torch*-1", "coal*x", "bad$id", "a:b:c", "stick*99999999999"), parsed.errors());
    }

    @Test
    void boundedEntriesAndLength() {
        StringBuilder many = new StringBuilder("mtlist1:");
        for (int i = 0; i < ListCode.MAX_ENTRIES + 10; i++) {
            many.append("item_").append(i).append("*1,");
        }
        ListCode.Parsed parsed = ListCode.decode(many.toString());
        assertEquals(ListCode.MAX_ENTRIES, parsed.entries().size());
        assertEquals(10, parsed.errors().size());
        String huge = "stick*1," + "!".repeat(ListCode.MAX_LENGTH * 2);
        assertEquals(1, ListCode.decode(huge).entries().size());
        List<ListCode.Entry> tooMany = new ArrayList<>();
        for (int i = 0; i < ListCode.MAX_ENTRIES + 5; i++) {
            tooMany.add(item("minecraft:item_" + i, 1));
        }
        assertEquals(ListCode.MAX_ENTRIES, ListCode.decode(ListCode.encode(tooMany)).entries().size());
        assertTrue(ListCode.encode(tooMany).length() <= ListCode.MAX_LENGTH);
    }

    @Test
    void emptyText() {
        assertTrue(ListCode.decode("").entries().isEmpty());
        assertTrue(ListCode.decode("mtlist1:").entries().isEmpty());
        assertEquals("mtlist1:", ListCode.encode(List.of()));
    }
}
