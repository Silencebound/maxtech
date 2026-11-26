package dev.silencebound.maxtech.storage.controller.module;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerBookTest {
    private static final int DAYS = 7;
    private static final int ROWS = 100;
    private static final UUID A = LedgerBook.AUTOMATION;
    private static final UUID ALICE = new UUID(1L, 1L);
    private static final UUID BOB = new UUID(2L, 2L);

    @Test
    void recordsInAndOutPerDay() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(A, "cobble", 64, 3, DAYS, ROWS);
        book.record(A, "cobble", -10, 3, DAYS, ROWS);
        book.record(A, "cobble", 5, 4, DAYS, ROWS);
        book.record(A, "dirt", 0, 4, DAYS, ROWS);
        assertArrayEquals(new long[]{64, 10}, book.dayTotals(A, "cobble", 3));
        assertArrayEquals(new long[]{5, 0}, book.dayTotals(A, "cobble", 4));
        assertEquals(2, book.rows(), "a zero change makes no row");
        List<LedgerBook.Row<String>> totals = book.totals(10, null);
        assertEquals(List.of(new LedgerBook.Row<>(LedgerBook.AUTOMATION, "cobble", 69, 10)), totals);
    }

    @Test
    void dropsDaysOutsideTheWindow() {
        LedgerBook<String> book = new LedgerBook<>();
        for (int day = 0; day < 10; day++) {
            book.record(A, "cobble", 1, day, DAYS, ROWS);
        }
        assertEquals(DAYS, book.days());
        assertArrayEquals(new long[2], book.dayTotals(A, "cobble", 2), "day 2 is gone");
        assertArrayEquals(new long[]{1, 0}, book.dayTotals(A, "cobble", 3), "day 3 is the oldest kept");
        book.expire(20, DAYS);
        assertEquals(0, book.rows(), "a quiet week empties the book");
    }

    @Test
    void timeGoingBackCountsOnTheNewestDay() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(A, "cobble", 4, 5, DAYS, ROWS);
        book.record(A, "cobble", 6, 2, DAYS, ROWS);
        assertArrayEquals(new long[]{10, 0}, book.dayTotals(A, "cobble", 5));
        assertEquals(1, book.days());
    }

    @Test
    void capDropsTheOldestDayThenRefusesNewKeys() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(A, "a", 1, 0, DAYS, 3);
        book.record(A, "b", 1, 0, DAYS, 3);
        book.record(A, "c", 1, 1, DAYS, 3);
        book.record(A, "d", 1, 1, DAYS, 3);
        assertEquals(2, book.rows(), "day 0 went to make room");
        assertEquals(1, book.days());
        book.record(A, "e", 1, 1, DAYS, 3);
        book.record(A, "f", 1, 1, DAYS, 3);
        assertEquals(3, book.rows(), "never past the cap");
        assertEquals(1, book.unrecorded(), "the key with no room is counted");
        book.record(A, "c", 5, 1, DAYS, 3);
        assertArrayEquals(new long[]{6, 0}, book.dayTotals(A, "c", 1), "known keys still add up at the cap");
        book.record(A, "g", 1, 1, DAYS, 0);
        assertEquals(2, book.unrecorded(), "a cap of 0 records nothing new");
    }

    @Test
    void roundTripsThroughItsCodec() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(A, "cobble", 64, 3, DAYS, ROWS);
        book.record(A, "cobble", -10, 3, DAYS, ROWS);
        book.record(A, "dirt", -7, 4, DAYS, ROWS);
        book.record(A, "x", 1, 4, DAYS, 0);
        Codec<LedgerBook<String>> codec = LedgerBook.codec(Codec.STRING);
        JsonElement json = codec.encodeStart(JsonOps.INSTANCE, book).getOrThrow();
        LedgerBook<String> loaded = codec.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(book.rows(), loaded.rows());
        assertEquals(book.days(), loaded.days());
        assertEquals(book.unrecorded(), loaded.unrecorded());
        assertArrayEquals(new long[]{64, 10}, loaded.dayTotals(A, "cobble", 3));
        assertArrayEquals(new long[]{0, 7}, loaded.dayTotals(A, "dirt", 4));
        assertEquals(book.totals(10, null), loaded.totals(10, null));
    }

    @Test
    void keepsPlayersApart() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(ALICE, "cobble", 64, 3, DAYS, ROWS);
        book.record(BOB, "cobble", -10, 3, DAYS, ROWS);
        book.record(A, "cobble", 5, 3, DAYS, ROWS);
        book.record(BOB, "dirt", -100, 3, DAYS, ROWS);
        assertEquals(4, book.rows(), "one row per player, key and day");
        assertArrayEquals(new long[]{64, 0}, book.dayTotals(ALICE, "cobble", 3));
        assertArrayEquals(new long[]{0, 10}, book.dayTotals(BOB, "cobble", 3));
        assertEquals(List.of(new LedgerBook.Row<>(ALICE, "cobble", 64, 0)), book.totals(10, ALICE));
        assertEquals(List.of(new LedgerBook.Row<>(BOB, "dirt", 0, 100), new LedgerBook.Row<>(BOB, "cobble", 0, 10)),
                book.totals(10, BOB));
        assertEquals(List.of(new LedgerBook.Row<>(A, "dirt", 0, 100), new LedgerBook.Row<>(A, "cobble", 69, 10)),
                book.totals(10, null), "everyone summed");
        assertEquals(List.of(BOB, ALICE, A), book.players(), "busiest first");
    }

    @Test
    void capCountsPlayerRows() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(ALICE, "cobble", 1, 0, DAYS, 2);
        book.record(BOB, "cobble", 1, 0, DAYS, 2);
        book.record(A, "cobble", 1, 0, DAYS, 2);
        assertEquals(2, book.rows());
        assertEquals(1, book.unrecorded(), "a third player's row finds no room");
    }

    @Test
    void roundTripsPlayers() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(ALICE, "cobble", 64, 3, DAYS, ROWS);
        book.record(BOB, "cobble", -10, 3, DAYS, ROWS);
        book.record(A, "dirt", 7, 4, DAYS, ROWS);
        Codec<LedgerBook<String>> codec = LedgerBook.codec(Codec.STRING);
        LedgerBook<String> loaded = codec
                .parse(JsonOps.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, book).getOrThrow()).getOrThrow();
        assertEquals(3, loaded.rows());
        assertArrayEquals(new long[]{64, 0}, loaded.dayTotals(ALICE, "cobble", 3));
        assertArrayEquals(new long[]{0, 10}, loaded.dayTotals(BOB, "cobble", 3));
        assertArrayEquals(new long[]{7, 0}, loaded.dayTotals(A, "dirt", 4));
    }

    @Test
    void loadsASaveFromBeforePlayersAsAutomation() {
        JsonElement old = JsonParser
                .parseString("{\"days\":[{\"day\":3,\"keys\":[\"cobble\",\"dirt\"],\"in\":[64,0],\"out\":[10,7]}]}");
        LedgerBook<String> loaded = LedgerBook.codec(Codec.STRING).parse(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(2, loaded.rows());
        assertArrayEquals(new long[]{64, 10}, loaded.dayTotals(A, "cobble", 3));
        assertArrayEquals(new long[]{0, 7}, loaded.dayTotals(A, "dirt", 3));
        assertEquals(List.of(A), loaded.players());
    }

    @Test
    void mergeAddsRowsOnTheirOwnDays() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(A, "cobble", 10, 5, DAYS, ROWS);
        book.record(ALICE, "dirt", -2, 6, DAYS, ROWS);
        LedgerBook<String> other = new LedgerBook<>();
        other.record(A, "cobble", 5, 4, DAYS, ROWS);
        other.record(A, "cobble", 7, 5, DAYS, ROWS);
        other.record(BOB, "sand", -3, 6, DAYS, ROWS);
        book.merge(other, 6, DAYS, ROWS);
        assertArrayEquals(new long[]{5, 0}, book.dayTotals(A, "cobble", 4), "an older day goes in its place");
        assertArrayEquals(new long[]{17, 0}, book.dayTotals(A, "cobble", 5), "the same row adds up");
        assertArrayEquals(new long[]{0, 2}, book.dayTotals(ALICE, "dirt", 6), "own rows stay");
        assertArrayEquals(new long[]{0, 3}, book.dayTotals(BOB, "sand", 6), "the other player's row");
        assertEquals(4, book.rows());
        assertEquals(3, book.days());
        assertEquals(3, other.rows(), "the other book is left as it was");
    }

    @Test
    void mergeKeepsTheWindowAndTheCap() {
        LedgerBook<String> book = new LedgerBook<>();
        book.record(A, "a", 1, 10, DAYS, 2);
        LedgerBook<String> other = new LedgerBook<>();
        other.record(A, "old", 1, 1, DAYS, ROWS);
        other.record(A, "b", 1, 10, DAYS, ROWS);
        other.record(A, "c", 1, 10, DAYS, ROWS);
        book.merge(other, 10, DAYS, 2);
        assertArrayEquals(new long[2], book.dayTotals(A, "old", 1), "a day outside the window is left out");
        assertEquals(2, book.rows(), "the cap holds");
        assertEquals(1, book.unrecorded(), "the row past the cap is counted");
        assertEquals(1, book.days());
    }
}
