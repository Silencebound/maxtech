package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import dev.silencebound.maxtech.lib.neoforge.compat.jade.OverlayLayout.Layout;
import dev.silencebound.maxtech.lib.neoforge.compat.jade.OverlayLayout.Variant;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class OverlayLayoutTest {
    private static final InfoEntry STATUS = InfoEntry.status(BlockInfoSink.Tone.GOOD, Component.literal("Online"),
            Optional.empty());
    private static final InfoEntry BAR = InfoEntry.progress(Component.literal("64%"), 64, 100, 0x3FC3E0);
    private static final InfoEntry STACKS = InfoEntry.stacks(Component.literal("Stone x64"), List.of());
    private static final InfoEntry TEXT = InfoEntry.text(Component.literal("Priority 5"));

    @Test
    void theDefaultIsTheOwnersPickB() {
        assertEquals(Variant.TWO_ROWS, OverlayLayout.DEFAULT);
        assertEquals(new Layout(List.of(STATUS), List.of(List.of(BAR, STACKS))),
                OverlayLayout.arrange(List.of(STATUS, BAR, STACKS), false));
    }

    @Test
    void iconRowPutsTheStatusInTheRow() {
        assertEquals(new Layout(List.of(), List.of(List.of(STATUS, BAR, STACKS))),
                OverlayLayout.arrange(List.of(STATUS, BAR, STACKS), false, Variant.ICON_ROW));
    }

    @Test
    void minimalBarKeepsTheFirstBar() {
        assertEquals(new Layout(List.of(STATUS), List.of(List.of(BAR))),
                OverlayLayout.arrange(List.of(STATUS, TEXT, BAR, STACKS), false, Variant.MINIMAL_BAR));
        assertEquals(new Layout(List.of(STATUS), List.of(List.of(TEXT))),
                OverlayLayout.arrange(List.of(STATUS, TEXT), false, Variant.MINIMAL_BAR));
    }

    @Test
    void detailsGiveEveryEntryItsOwnRow() {
        assertEquals(new Layout(List.of(STATUS), List.of(List.of(BAR), List.of(STACKS), List.of(TEXT))),
                OverlayLayout.arrange(List.of(STATUS, BAR, STACKS, TEXT), true));
    }

    @Test
    void nothingGivesNoRows() {
        assertEquals(new Layout(List.of(), List.of()), OverlayLayout.arrange(List.of(), false));
        assertEquals(new Layout(List.of(STATUS), List.of()), OverlayLayout.arrange(List.of(STATUS), false));
    }
}
