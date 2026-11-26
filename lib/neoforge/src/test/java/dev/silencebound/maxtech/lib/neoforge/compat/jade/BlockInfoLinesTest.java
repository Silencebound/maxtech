package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

class BlockInfoLinesTest {
    @Test
    void detailsKeyPicksFull() {
        assertEquals(BlockInfoRequest.Detail.BRIEF, BlockInfoLines.detail(false));
        assertEquals(BlockInfoRequest.Detail.FULL, BlockInfoLines.detail(true));
    }

    @Test
    void clientOptionsPickTheDetailLevel() {
        // Defaults: compact, details while sneaking.
        assertFalse(BlockInfoLines.wantsDetails(false, false, true));
        assertTrue(BlockInfoLines.wantsDetails(true, false, true));
        // Sneaking for details turned off: never.
        assertFalse(BlockInfoLines.wantsDetails(true, false, false));
        // Full detail level: always.
        assertTrue(BlockInfoLines.wantsDetails(false, true, false));
        assertTrue(BlockInfoLines.wantsDetails(false, true, true));
    }

    @Test
    void keepsEntriesThatFit() {
        List<InfoEntry> entries = entries(BlockInfoLines.MAX_LINES);
        assertSame(entries, BlockInfoLines.cap(entries));
        assertEquals(List.of(), BlockInfoLines.cap(List.of()));
    }

    @Test
    void replacesTheOverflowWithACount() {
        List<InfoEntry> capped = BlockInfoLines.cap(entries(BlockInfoLines.MAX_LINES + 5));
        assertEquals(BlockInfoLines.MAX_LINES, capped.size());
        assertEquals("line 0", capped.getFirst().text().getString());
        assertEquals("line " + (BlockInfoLines.MAX_LINES - 2),
                capped.get(BlockInfoLines.MAX_LINES - 2).text().getString());
        TranslatableContents more = (TranslatableContents) capped.getLast().text().getContents();
        assertEquals(BlockInfoLines.MORE_KEY, more.getKey());
        assertEquals(6, more.getArgs()[0]);
    }

    @Test
    void configKeyFollowsJadesScheme() {
        assertEquals("config.jade.plugin_maxtech_lib.block_info", BlockInfoLines.CONFIG_KEY);
    }

    private static List<InfoEntry> entries(int count) {
        List<InfoEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(InfoEntry.text(Component.literal("line " + i)));
        }
        return entries;
    }
}
