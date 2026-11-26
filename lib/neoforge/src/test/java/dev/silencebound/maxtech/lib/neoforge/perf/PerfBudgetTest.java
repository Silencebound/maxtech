package dev.silencebound.maxtech.lib.neoforge.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget.CpuTimes;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget.Outcome;
import org.junit.jupiter.api.Test;

class PerfBudgetTest {
    private static final String STAT_START = "cpu  1000 50 400 8000 100 10 20 5 30 0\n";
    private static final String STAT_END = "cpu  1600 50 700 8400 100 10 20 5 30 0\n";
    /** A command name with spaces and a parenthesis, as {@code comm} allows; utime 300, stime 100. */
    private static final String SELF_START = "4242 (java (main) x) S 1 4242 4242 0 -1 4194560 100 0 0 0 300 100 0 0 20"
            + " 0 80 0 12345 1000000 5000";
    private static final String SELF_END = "4242 (java (main) x) S 1 4242 4242 0 -1 4194560 100 0 0 0 500 200 0 0 20"
            + " 0 80 0 12345 1000000 5000";

    @Test
    void loadAverageRuleIsPerCpu() {
        assertTrue(PerfBudget.busyByLoadAverage(25.0, 16));
        assertFalse(PerfBudget.busyByLoadAverage(24.0, 16));
        assertFalse(PerfBudget.busyByLoadAverage(8.0, 16));
        assertFalse(PerfBudget.busyByLoadAverage(-1.0, 16));
    }

    @Test
    void cpuLoadIgnoresOwnProcess() {
        assertTrue(PerfBudget.busyByCpuLoad(0.9, 0.1));
        assertFalse(PerfBudget.busyByCpuLoad(0.9, 0.5));
        assertFalse(PerfBudget.busyByCpuLoad(0.3, 0.0));
    }

    @Test
    void unmeasuredCpuLoadIsNeverBusy() {
        assertFalse(PerfBudget.busyByCpuLoad(-1.0, 0.1));
        assertFalse(PerfBudget.busyByCpuLoad(0.9, -1.0));
    }

    @Test
    void parsesProcStatAndSelfStat() {
        CpuTimes times = PerfBudget.CpuTimes.parse(STAT_START, SELF_START);
        assertNotNull(times);
        // busy: user + nice + system + irq + softirq + steal; guest (30) is already in user.
        assertEquals(1000 + 50 + 400 + 10 + 20 + 5, times.busy());
        assertEquals(1485 + 8000 + 100, times.total());
        assertEquals(400, times.own());
    }

    @Test
    void rejectsMalformedProcFiles() {
        assertNull(PerfBudget.CpuTimes.parse("intr 1 2 3", SELF_START));
        assertNull(PerfBudget.CpuTimes.parse("cpu 1 2", SELF_START));
        assertNull(PerfBudget.CpuTimes.parse(STAT_START, "4242 java S 1"));
        assertNull(PerfBudget.CpuTimes.parse(STAT_START, "4242 (java) S 1 2 3"));
        assertNull(PerfBudget.CpuTimes.parse("cpu  a b c d e f g", SELF_START));
    }

    @Test
    void otherShareSubtractsOwnTime() {
        CpuTimes start = PerfBudget.CpuTimes.parse(STAT_START, SELF_START);
        CpuTimes end = PerfBudget.CpuTimes.parse(STAT_END, SELF_END);
        assertNotNull(start);
        assertNotNull(end);
        // Busy +900, own +300, total +1300: others used 600 of 1300.
        assertEquals(600 / 1300.0, end.otherShareSince(start), 1e-9);
        assertFalse(PerfBudget.busyByCpuLoad(end.otherShareSince(start), 0));
        CpuTimes crowded = new CpuTimes(start.busy() + 900, start.total() + 1000, start.own() + 100);
        assertEquals(0.8, crowded.otherShareSince(start), 1e-9);
        assertTrue(PerfBudget.busyByCpuLoad(crowded.otherShareSince(start), 0));
    }

    @Test
    void noElapsedTimeIsUnmeasured() {
        CpuTimes start = new CpuTimes(100, 1000, 10);
        assertEquals(-1, start.otherShareSince(start));
    }

    @Test
    void firstAttemptUnderBudgetPasses() {
        assertEquals(Outcome.PASS, PerfBudget.decide(false, 99, 100, true));
    }

    @Test
    void firstAttemptOverBudgetRetriesEvenWhenBusy() {
        assertEquals(Outcome.RETRY, PerfBudget.decide(false, 100, 100, false));
        assertEquals(Outcome.RETRY, PerfBudget.decide(false, 500, 100, true));
    }

    @Test
    void retryUnderBudgetPasses() {
        assertEquals(Outcome.PASS_ON_RETRY, PerfBudget.decide(true, 99, 100, false));
        assertEquals(Outcome.PASS_ON_RETRY, PerfBudget.decide(true, 99, 100, true));
    }

    @Test
    void retryOverBudgetFailsUnlessBusy() {
        assertEquals(Outcome.FAIL, PerfBudget.decide(true, 100, 100, false));
        assertEquals(Outcome.SKIP_BUSY, PerfBudget.decide(true, 150, 100, true));
    }
}
