package dev.silencebound.maxtech.storage.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalOrderTest {
    private static IntArrayList arrange(IntList view, List<Integer> pins, List<Integer> recent) {
        IntArrayList out = new IntArrayList();
        TerminalOrder.arrange(view, pins::contains, recent::indexOf, TerminalPrefs.RECENT, out);
        return out;
    }

    @Test
    void pinnedThenRecentThenTheRestInSortOrder() {
        IntList view = IntList.of(10, 11, 12, 13, 14, 15);
        assertEquals(IntList.of(11, 14, 15, 12, 10, 13), arrange(view, List.of(14, 11), List.of(15, 12)));
    }

    @Test
    void entriesTheSearchHidesStayHidden() {
        IntList view = IntList.of(1, 2, 3);
        assertEquals(IntList.of(2, 1, 3), arrange(view, List.of(2, 9), List.of(8, 1)));
    }

    @Test
    void aPinnedRecentEntryShowsOnce() {
        IntList view = IntList.of(1, 2, 3);
        assertEquals(IntList.of(3, 1, 2), arrange(view, List.of(3), List.of(3)));
    }

    @Test
    void withNothingPinnedOrRecentTheViewIsUnchanged() {
        IntList view = IntList.of(5, 4, 3);
        assertEquals(view, arrange(view, List.of(), List.of()));
    }
}
