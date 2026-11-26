package dev.silencebound.maxtech.storage.terminal.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.crafting.order.ListCode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryExportTest {
    private static final InventoryExport.Row STICK = new InventoryExport.Row("Stick", "minecraft:stick", false, 64,
            true);
    private static final InventoryExport.Row WATER = new InventoryExport.Row("Water", "minecraft:water", true, 1500,
            false);
    private static final InventoryExport.Row TORCH_ONLY = new InventoryExport.Row("Torch", "minecraft:torch", false, 0,
            true);

    @Test
    void csvHasTheHeaderAndOneLinePerRow() {
        assertEquals("name,id,count,craftable\r\nStick,minecraft:stick,64,true\r\nWater,minecraft:water,1500,false\r\n",
                InventoryExport.csv(List.of(STICK, WATER)));
    }

    @Test
    void csvQuotesCommasQuotesAndLineBreaks() {
        InventoryExport.Row odd = new InventoryExport.Row("Box, \"large\"\nnew", "mod:box", false, 2, false);
        assertEquals("name,id,count,craftable\r\n\"Box, \"\"large\"\"\nnew\",mod:box,2,false\r\n",
                InventoryExport.csv(List.of(odd)));
        assertEquals("plain", InventoryExport.field("plain"));
        assertEquals("\"a,b\"", InventoryExport.field("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", InventoryExport.field("say \"hi\""));
    }

    @Test
    void csvKeepsFormulaNamesAsText() {
        InventoryExport.Row formula = new InventoryExport.Row("=SUM(A1)", "minecraft:paper", false, 1, false);
        assertEquals("name,id,count,craftable\r\n'=SUM(A1),minecraft:paper,1,false\r\n",
                InventoryExport.csv(List.of(formula)));
        assertEquals("'-1", InventoryExport.safe("-1"));
        assertEquals("x", InventoryExport.safe("x"));
        assertEquals("'+1", InventoryExport.safe("+1"));
        assertEquals("", InventoryExport.safe(""));
    }

    @Test
    void plainTextGivesCountsUnitsAndTheCraftableMark() {
        assertEquals("64 × Stick (craftable)\n1,500 mB Water\n0 × Torch (craftable)\n",
                InventoryExport.plain(List.of(STICK, WATER, TORCH_ONLY), "(craftable)"));
        assertEquals("64 × Stick\n", InventoryExport.plain(List.of(STICK), ""));
    }

    @Test
    void listCodeRoundTripsThroughListCode() {
        String code = InventoryExport.listCode(List.of(STICK, WATER, TORCH_ONLY));
        assertEquals("mtlist1:minecraft:stick*64,f/minecraft:water*1500", code);
        ListCode.Parsed parsed = ListCode.decode(code);
        assertEquals(List.of(new ListCode.Entry(false, "minecraft:stick", 64),
                new ListCode.Entry(true, "minecraft:water", 1500)), parsed.entries());
        assertTrue(parsed.errors().isEmpty());
    }

    @Test
    void listCodeCapsHugeCounts() {
        InventoryExport.Row huge = new InventoryExport.Row("Cobblestone", "minecraft:cobblestone", false,
                10_000_000_000L, false);
        assertEquals("mtlist1:minecraft:cobblestone*" + ListCode.MAX_AMOUNT, InventoryExport.listCode(List.of(huge)));
    }

    @Test
    void fileNameIsTimeStamped() {
        assertEquals("inventory-2026-10-10_14-03-09.csv",
                InventoryExport.fileName(LocalDateTime.of(2026, 10, 10, 14, 3, 9)));
    }
}
