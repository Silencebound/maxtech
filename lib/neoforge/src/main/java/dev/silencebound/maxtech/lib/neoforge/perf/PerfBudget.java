package dev.silencebound.maxtech.lib.neoforge.perf;

import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The budget check every perf GameTest of every MaxTech mod goes through. The test hands its measurement to
 * {@link #measureUnder} (measured within one call) or {@link #measureTicks} (measured over ticks), so PerfBudget can
 * repeat it:
 * <ul>
 * <li>Under budget on the first attempt: passes.</li>
 * <li>Over budget: measures once more, fresh, with the same windows and budget. The retry alone decides; both results
 * are logged and a "Perf budget retried" warning annotation shows on the run's checks. One slow attempt is often
 * another process on the same machine; a real regression is over budget twice.</li>
 * <li>The retry over budget too: fails, unless the machine was busy during the retry. Then the result says more about
 * the other processes than about our code, so it is logged as "skipped budget: machine busy" with a "Perf budget
 * skipped" annotation. Only the budget is skipped; every other assertion of a perf test still runs.</li>
 * </ul>
 * Busy means other processes used more than {@link #BUSY_OTHER_CPU_LOAD} of all CPUs during the retry. On Linux that
 * share comes from {@code /proc/stat} and {@code /proc/self/stat}, read at the start and end of each attempt (outside
 * the measured code). Where {@code /proc} cannot be read, the 1-minute load average decides (macOS), and where there is
 * none, the CPU load sampled after the measurement (Windows). Budgets and window counts never change. With
 * {@link #WARN_ENV} set to {@code true} (every CI run), a failing budget only logs and annotates "Perf budget over" and
 * the test goes on; local runs still fail on it. Everything here runs on the server thread, from the test itself.
 */
public final class PerfBudget {
    /**
     * Fallback without {@code /proc}: load per CPU above which the machine counts as busy. Locally, three game-test
     * servers on the same Mac pushed a 1.0 ms budget to 1.31 ms while the same test passed alone. 1.5 runnable
     * processes per core means each one gets two thirds of a core at best, so timings above that say little about our
     * code.
     */
    public static final double BUSY_LOAD_PER_CPU = 1.5;

    /**
     * Share of all cores used by processes other than this JVM above which the machine counts as busy. Half the machine
     * taken by others leaves the test at best the other half, comparable to the load average rule's two thirds of a
     * core per runnable process.
     */
    public static final double BUSY_OTHER_CPU_LOAD = 0.5;

    private static final long CPU_SAMPLE_MILLIS = 250;
    private static final Path PROC_STAT = Path.of("/proc/stat");
    private static final Path PROC_SELF_STAT = Path.of("/proc/self/stat");

    /**
     * Environment variable that turns budget failures into warnings. CI sets it on every run: the budgets are tuned on
     * a quiet machine, and GitHub-hosted runners vary too much to block on (owner 2026-11-25). A real check runs
     * locally, where budgets fail.
     */
    public static final String WARN_ENV = "MAXTECH_PERF_BUDGETS_WARN";

    private static final boolean WARN_ONLY = "true".equals(System.getenv(WARN_ENV));

    private static final Logger LOGGER = LogUtils.getLogger();

    private PerfBudget() {
    }

    /**
     * Runs {@code measurement} and fails {@code helper} with {@code failure.apply(measured)} unless it returns less
     * than {@code budgetNanos}, retrying once and skipping on a busy machine as the class describes. The measurement
     * runs within this call, at most twice, so it must leave the test able to measure again (reset what it filled).
     * Assertions inside it fail the test as usual.
     */
    public static void measureUnder(GameTestHelper helper, String test, long budgetNanos, LongSupplier measurement,
            LongFunction<String> failure) {
        long first = -1;
        for (int attempt = 1;; attempt++) {
            CpuTimes start = CpuTimes.read();
            long measured = measurement.getAsLong();
            CpuTimes end = CpuTimes.read();
            boolean retried = attempt > 1;
            Busy busy = retried && measured >= budgetNanos ? busy(start, end) : null;
            Outcome outcome = decide(retried, measured, budgetNanos, busy != null && busy.busy());
            if (outcome == Outcome.RETRY) {
                first = measured;
                logRetry(test, measured, budgetNanos);
                continue;
            }
            String message = report(test, outcome, first, measured, budgetNanos, busy, failure);
            if (message != null) {
                helper.fail(message);
            }
            return;
        }
    }

    /**
     * A measurement over ticks for {@link #measureTicks}. Each attempt calls {@link #start()}, then {@link #tick()}
     * once on that tick and each of the following ticks for the measured number of ticks, then {@link #finish()} on the
     * next tick. Start must reset everything an attempt adds up, since a retry runs all three again. Assertions in any
     * of them fail the test as they would in a sequence step.
     */
    public interface TickMeasurement {
        /** Resets the sums and records starting values for one attempt. */
        void start();

        /** One measured tick; keep it free of allocation, it runs in the measured window. */
        void tick();

        /** Ends the attempt: checks and logs what it likes, and returns the value compared to the budget. */
        long finish();

        /** A measurement from three lambdas, for tests that keep their state in local arrays. */
        static TickMeasurement of(Runnable start, Runnable tick, LongSupplier finish) {
            return new TickMeasurement() {
                @Override
                public void start() {
                    start.run();
                }

                @Override
                public void tick() {
                    tick.run();
                }

                @Override
                public long finish() {
                    return finish.getAsLong();
                }
            };
        }
    }

    /**
     * Returns a sequence step for {@code GameTestSequence.thenWaitUntil} that measures {@code ticks} ticks with
     * {@code measurement} and checks the result against {@code budgetNanos} like {@link #measureUnder}. A retry takes
     * another {@code ticks + 1} ticks, which the test's {@code maxTicks} must allow for. The step completes when the
     * budget passed, was skipped or failed the test; until then it throws to keep the sequence waiting.
     */
    public static Runnable measureTicks(GameTestHelper helper, String test, int ticks, long budgetNanos,
            TickMeasurement measurement, LongFunction<String> failure) {
        return new TickedRun(helper, test, ticks, budgetNanos, measurement, failure, false);
    }

    /**
     * {@link #measureTicks} with one attempt and no retry, for a measurement that changes the world in a way a second
     * attempt cannot repeat (a retry would measure a different situation). Over budget it fails, is skipped as busy, or
     * warns under {@link #WARN_ENV}, like a retry would.
     */
    public static Runnable measureTicksOnce(GameTestHelper helper, String test, int ticks, long budgetNanos,
            TickMeasurement measurement, LongFunction<String> failure) {
        return new TickedRun(helper, test, ticks, budgetNanos, measurement, failure, true);
    }

    /** What happens after one attempt. */
    enum Outcome {
        /** Under budget on the first attempt. */
        PASS,
        /** Over budget on the first attempt: measure again. */
        RETRY,
        /** Over budget once, under budget on the retry. */
        PASS_ON_RETRY,
        /** Over budget on the retry while the machine was busy. */
        SKIP_BUSY,
        /** Over budget on the retry on a quiet machine. */
        FAIL
    }

    /**
     * The retry decision. {@code busy} counts only for a retry over budget; callers find it out only then, since that
     * can take a CPU sample.
     */
    static Outcome decide(boolean retried, long measuredNanos, long budgetNanos, boolean busy) {
        if (measuredNanos < budgetNanos) {
            return retried ? Outcome.PASS_ON_RETRY : Outcome.PASS;
        }
        if (!retried) {
            return Outcome.RETRY;
        }
        return busy ? Outcome.SKIP_BUSY : Outcome.FAIL;
    }

    /** Busy decision on the 1-minute load average; false when the platform reports none (negative). */
    static boolean busyByLoadAverage(double loadAverage, int cpus) {
        return loadAverage >= 0 && loadAverage / cpus > BUSY_LOAD_PER_CPU;
    }

    /**
     * Busy decision on CPU load fractions (0..1) of all cores. Busy when other processes use more than
     * {@link #BUSY_OTHER_CPU_LOAD} of all cores; a negative input means "not measured" and is never busy.
     */
    static boolean busyByCpuLoad(double systemCpuLoad, double processCpuLoad) {
        if (systemCpuLoad < 0 || processCpuLoad < 0) {
            return false;
        }
        return systemCpuLoad - processCpuLoad > BUSY_OTHER_CPU_LOAD;
    }

    /**
     * CPU time from {@code /proc}, in clock ticks: {@code busy} and {@code total} summed over all CPUs, {@code own}
     * this process's user and system time.
     */
    record CpuTimes(long busy, long total, long own) {
        /** Reads both files; empty (null) where they don't exist or don't parse, as on Windows and macOS. */
        static @Nullable CpuTimes read() {
            try {
                String system;
                try (BufferedReader reader = Files.newBufferedReader(PROC_STAT, StandardCharsets.US_ASCII)) {
                    system = reader.readLine();
                }
                if (system == null) {
                    return null;
                }
                return parse(system, Files.readString(PROC_SELF_STAT, StandardCharsets.US_ASCII));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /**
         * Parses the first line of {@code /proc/stat} ({@code cpu user nice system idle iowait irq softirq steal ...})
         * and {@code /proc/self/stat} (utime and stime are fields 14 and 15, counted after the command name in
         * parentheses, which may hold spaces). Guest time is already part of user time and is not added again.
         */
        static @Nullable CpuTimes parse(String procStatLine, String selfStat) {
            String[] cpu = procStatLine.trim().split("\\s+", -1);
            if (cpu.length < 8 || !cpu[0].equals("cpu")) {
                return null;
            }
            int close = selfStat.lastIndexOf(')');
            if (close < 0) {
                return null;
            }
            // Field 3 (state) is the first after the parenthesis, so field n is at index n - 3.
            String[] self = selfStat.substring(close + 1).trim().split("\\s+", -1);
            if (self.length < 13) {
                return null;
            }
            try {
                long user = Long.parseLong(cpu[1]);
                long nice = Long.parseLong(cpu[2]);
                long sys = Long.parseLong(cpu[3]);
                long idle = Long.parseLong(cpu[4]);
                long iowait = Long.parseLong(cpu[5]);
                long irq = Long.parseLong(cpu[6]);
                long softirq = Long.parseLong(cpu[7]);
                long steal = cpu.length > 8 ? Long.parseLong(cpu[8]) : 0;
                long busy = user + nice + sys + irq + softirq + steal;
                long own = Long.parseLong(self[11]) + Long.parseLong(self[12]);
                return new CpuTimes(busy, busy + idle + iowait, own);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /**
         * Share of all CPUs (0..1) other processes used between {@code start} and this, or -1 when no time passed on
         * the clock-tick scale.
         */
        double otherShareSince(CpuTimes start) {
            long total = this.total - start.total;
            if (total <= 0) {
                return -1;
            }
            long others = (busy - start.busy) - (own - start.own);
            return Math.max(0, others) / (double) total;
        }
    }

    private record Busy(boolean busy, String detail) {
    }

    /** Busy over the attempt between {@code start} and {@code end}; falls back when {@code /proc} gave nothing. */
    private static Busy busy(@Nullable CpuTimes start, @Nullable CpuTimes end) {
        if (start != null && end != null) {
            double share = end.otherShareSince(start);
            if (share >= 0) {
                return new Busy(busyByCpuLoad(share, 0),
                        String.format(Locale.ROOT,
                                "other processes used %.0f%% of all CPUs during the measurement (busy over %.0f%%)",
                                share * 100, BUSY_OTHER_CPU_LOAD * 100));
            }
        }
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemLoadAverage();
        if (load >= 0) {
            int cpus = os.getAvailableProcessors();
            return new Busy(busyByLoadAverage(load, cpus), String.format(Locale.ROOT,
                    "load average %.2f per CPU (busy over %s)", load / cpus, BUSY_LOAD_PER_CPU));
        }
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            // getCpuLoad() covers the time since the previous call and is negative on the first one, so the first call
            // only starts a window. This runs after the measurement, on the over-budget path only, so sleeping is fine.
            sun.getCpuLoad();
            sun.getProcessCpuLoad();
            double system = -1;
            double process = -1;
            for (int i = 0; i < 3 && (system < 0 || process < 0); i++) {
                try {
                    Thread.sleep(CPU_SAMPLE_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                system = sun.getCpuLoad();
                process = sun.getProcessCpuLoad();
            }
            if (system >= 0 && process >= 0) {
                return new Busy(busyByCpuLoad(system, process),
                        String.format(Locale.ROOT,
                                "CPU load %.0f%% total, %.0f%% this JVM (busy when others use over %.0f%%)",
                                system * 100, process * 100, BUSY_OTHER_CPU_LOAD * 100));
            }
        }
        return new Busy(false, "no CPU times, load average or CPU load available");
    }

    private static void logRetry(String test, long measuredNanos, long budgetNanos) {
        LOGGER.warn("{}: measured {} ms against a {} ms budget, measuring again", test, millis(measuredNanos),
                millis(budgetNanos));
    }

    /**
     * Logs and annotates a final outcome; returns the failure message for {@link Outcome#FAIL}, else null.
     * {@code first} is the first attempt's value, or negative when there was no retry.
     */
    private static @Nullable String report(String test, Outcome outcome, long first, long measured, long budget,
            @Nullable Busy busy, LongFunction<String> failure) {
        if (outcome != Outcome.RETRY) {
            // One fixed-format line per finished budget, read by scripts/perf-run.sh to track results across runs.
            LOGGER.info("perf-result {} {} {} {}", test, measured, budget, outcome);
        }
        return switch (outcome) {
            case PASS, RETRY -> null;
            case PASS_ON_RETRY -> {
                LOGGER.warn("{}: retry measured {} ms, within the {} ms budget (first attempt {} ms)", test,
                        millis(measured), millis(budget), millis(first));
                annotate("Perf budget retried", test + " first " + millis(first) + " ms, retry " + millis(measured)
                        + " ms, budget " + millis(budget) + " ms: passed on retry");
                yield null;
            }
            case SKIP_BUSY -> {
                String detail = busy != null ? busy.detail() : "busy";
                LOGGER.warn("{}: retry measured {} ms against a {} ms budget (first attempt {} ms), {}", test,
                        millis(measured), millis(budget), millis(first), detail);
                LOGGER.warn("{}: skipped budget: machine busy", test);
                annotate("Perf budget skipped", test + " median " + millis(measured) + " ms over " + millis(budget)
                        + " ms (first attempt " + millis(first) + " ms), " + detail);
                yield null;
            }
            case FAIL -> {
                if (WARN_ONLY) {
                    LOGGER.warn("{}: retry measured {} ms against a {} ms budget (first attempt {} ms), warning only",
                            test, millis(measured), millis(budget), millis(first));
                    annotate("Perf budget over", test + " first " + millis(first) + " ms, retry " + millis(measured)
                            + " ms, budget " + millis(budget) + " ms: not failing in CI");
                    yield null;
                }
                LOGGER.warn("{}: retry measured {} ms against a {} ms budget (first attempt {} ms), failing", test,
                        millis(measured), millis(budget), millis(first));
                yield failure.apply(measured) + " (first attempt " + millis(first) + " ms)";
            }
        };
    }

    /**
     * Prints a GitHub Actions warning annotation. Actions reads a workflow command only at the start of a stdout line.
     * The runGameTestServer task forwards the server's stdout to the step log, but System.out is wrapped into the
     * logger by then and every log line starts with a timestamp, so this writes to the real stdout instead.
     */
    private static void annotate(String title, String text) {
        Bootstrap.realStdoutPrintln("::warning title=" + title + "::" + text);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }

    /** The state of one {@link #measureTicks} step across ticks. */
    private static final class TickedRun implements Runnable {
        private final GameTestHelper helper;
        private final String test;
        private final int ticks;
        private final long budget;
        private final TickMeasurement measurement;
        private final LongFunction<String> failure;
        private final GameTestAssertException pending;
        private final boolean once;
        private boolean retried;
        private boolean done;
        /** Ticks measured in this attempt; -1 before its start. */
        private int ticked = -1;
        private long first = -1;
        private @Nullable CpuTimes start;

        TickedRun(GameTestHelper helper, String test, int ticks, long budget, TickMeasurement measurement,
                LongFunction<String> failure, boolean once) {
            this.helper = helper;
            this.once = once;
            this.test = test;
            this.ticks = ticks;
            this.budget = budget;
            this.measurement = measurement;
            this.failure = failure;
            // One instance, thrown on every waiting tick; its tick number is only read if the test times out.
            this.pending = new GameTestAssertException(Component.literal(test + ": perf measurement not finished"), 0);
        }

        @Override
        public void run() {
            if (done) {
                return;
            }
            boolean waiting;
            try {
                waiting = step();
            } catch (GameTestException e) {
                // An assertion of the test's own: fail like a sequence step would, and end this step.
                helper.testInfo.fail(e);
                done = true;
                return;
            }
            if (waiting) {
                throw pending;
            }
            done = true;
        }

        /** Advances one tick; returns whether the step must keep waiting. */
        private boolean step() {
            if (ticked < 0) {
                start = CpuTimes.read();
                measurement.start();
                ticked = 0;
            }
            if (ticked < ticks) {
                measurement.tick();
                ticked++;
                return true;
            }
            long measured = measurement.finish();
            CpuTimes end = CpuTimes.read();
            boolean last = retried || once;
            Busy busy = last && measured >= budget ? busy(start, end) : null;
            Outcome outcome = decide(last, measured, budget, busy != null && busy.busy());
            if (outcome == Outcome.PASS_ON_RETRY && !retried) {
                outcome = Outcome.PASS;
            }
            if (outcome == Outcome.RETRY) {
                logRetry(test, measured, budget);
                first = measured;
                retried = true;
                ticked = -1;
                return true;
            }
            String message = report(test, outcome, first, measured, budget, busy, failure);
            if (message != null) {
                helper.testInfo.fail(helper.assertionException(Component.literal(message)));
            }
            return false;
        }
    }
}
