package dev.silencebound.maxtech.storage.crafting.overview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PatternConflictsTest {
    /** A pattern as "output<-input"; equal strings are the same pattern held twice. */
    private static int[] counts(String... patterns) {
        return PatternConflicts.counts(List.of(patterns), pattern -> pattern.substring(0, pattern.indexOf("<-")));
    }

    @Test
    void differentPatternsForOneItemConflict() {
        assertArrayEquals(new int[]{2, 1, 2}, counts("stick<-oak", "torch<-coal", "stick<-birch"));
    }

    @Test
    void theSamePatternTwiceIsNoConflict() {
        assertArrayEquals(new int[]{1, 1}, counts("stick<-oak", "stick<-oak"));
        assertArrayEquals(new int[]{2, 2, 2}, counts("stick<-oak", "stick<-oak", "stick<-birch"));
    }

    @Test
    void empty() {
        assertArrayEquals(new int[0], counts());
    }
}
