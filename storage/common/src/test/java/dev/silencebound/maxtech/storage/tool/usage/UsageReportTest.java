package dev.silencebound.maxtech.storage.tool.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class UsageReportTest {
    @Test
    void tallyCountsDevicesPerOwnerMostFirstThenByName() {
        List<UsageReport.OwnerCount> owners = UsageReport.tally(List.of("bea", "al", "bea", "-", "cy", "al", "bea"));
        assertEquals(List.of(new UsageReport.OwnerCount("bea", 3), new UsageReport.OwnerCount("al", 2),
                new UsageReport.OwnerCount("-", 1), new UsageReport.OwnerCount("cy", 1)), owners);
    }

    @Test
    void tallyOfNothingIsEmpty() {
        assertEquals(List.of(), UsageReport.tally(List.of()));
    }

    @Test
    void chunkCutsIntoRunsOfAtMostSize() {
        assertEquals(List.of(List.of(1, 2), List.of(3, 4), List.of(5)), UsageBook.chunk(List.of(1, 2, 3, 4, 5), 2));
        assertEquals(List.of(List.of(1, 2)), UsageBook.chunk(List.of(1, 2), 2));
        assertEquals(List.of(), UsageBook.chunk(List.of(), 12));
    }
}
