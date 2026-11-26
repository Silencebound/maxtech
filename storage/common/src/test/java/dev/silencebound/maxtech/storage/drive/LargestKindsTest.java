package dev.silencebound.maxtech.storage.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LargestKindsTest {
    @Test
    void keepsTheLargestInOrder() {
        List<String> keys = new ArrayList<>();
        LongArrayList amounts = new LongArrayList();
        long[] offered = {5, 50, 1, 30, 30, 70, 2, 40};
        for (int i = 0; i < offered.length; i++) {
            LargestKinds.offer(keys, amounts, 3, "k" + i, offered[i]);
        }
        assertEquals(List.of("k5", "k1", "k7"), keys);
        assertEquals(LongArrayList.of(70, 50, 40), amounts);
    }

    @Test
    void tiesKeepOfferOrderAndSmallListsStaySmall() {
        List<String> keys = new ArrayList<>();
        LongArrayList amounts = new LongArrayList();
        LargestKinds.offer(keys, amounts, 5, "a", 10);
        LargestKinds.offer(keys, amounts, 5, "b", 10);
        assertEquals(List.of("a", "b"), keys);
        assertEquals(LongArrayList.of(10, 10), amounts);
    }
}
