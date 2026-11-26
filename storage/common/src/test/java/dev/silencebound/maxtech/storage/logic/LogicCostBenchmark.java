package dev.silencebound.maxtech.storage.logic;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Times the evaluator per node type (M7L2 chunk 5), to replace the flat check costs with measured weights. Not a test:
 * it asserts nothing and runs only with {@code MAXTECH_BENCH=1} in the environment, so normal builds and CI skip it.
 * Rerun with {@code MAXTECH_BENCH=1 ./gradlew :storage:common:test --tests '*LogicCostBenchmark' --rerun}; results go
 * to {@code storage/common/build/test-work/logic-bench/}.
 *
 * <p>
 * For each type it builds two graphs over the same sources (graph start for events, a device state for yes/no, an item
 * count for numbers): a baseline with only the sources, and one with {@link #COPIES} copies of the type wired to them.
 * Each run restarts the evaluator (so every node runs and every watch is read again), flips the values the context
 * reads (so crossings, switches and redstone change every run) and moves the clock on (so timers and delays come due).
 * Baseline and test samples alternate; a sample's cost per node is the difference divided by the copies. Reads come
 * from an array-backed context, so read nodes show the evaluator's side of a read, not the controller's lookups (the
 * GameTest {@code bench_logic_actions} measures those).
 */
@EnabledIfEnvironmentVariable(named = "MAXTECH_BENCH", matches = "1")
class LogicCostBenchmark {
    private static final int COPIES = 16;
    private static final int RUNS_PER_SAMPLE = 200;
    private static final int WARMUP_SAMPLES = 300;
    private static final int SAMPLES = 1000;
    /** Defeats dead-code elimination of the runs; printed at the end. */
    private static long sink;

    /** Reads cost nothing beyond an array index; actions only count. Values flip with the phase. */
    private static final class BenchContext implements LogicContext<String> {
        long phase;
        long actions;

        @Override
        public long read(Watch<String> watch) {
            return (phase & 1) == 0 ? 0 : 40 + watch.index();
        }

        @Override
        public boolean craftRunning(String item) {
            return false;
        }

        @Override
        public void switchDevice(String label, boolean on) {
            actions++;
        }

        @Override
        public void setPriority(String label, long priority) {
            actions++;
        }

        @Override
        public void redstone(String label, int strength) {
            actions++;
        }

        @Override
        public void requestCraft(String item, long amount) {
            actions++;
        }

        @Override
        public void moveItems(String item, String from, String to, long amount) {
            actions++;
        }

        @Override
        public void notify(String text, int channels) {
            actions++;
        }

        @Override
        public void pulse(String label, int strength, boolean on) {
            actions++;
        }

        @Override
        public void cancelCraft(String item, boolean ownOnly) {
            actions++;
        }

        @Override
        public void writeDisplay(String label, String text, long a, long b) {
            actions++;
        }

        @Override
        public void setFilter(String label, @Nullable String item, int slot, int portMode) {
            actions++;
        }

        @Override
        public void setVariable(String name, long value, boolean yesNo) {
            actions++;
        }

        @Override
        public void runRule(String rule) {
            actions++;
        }

        @Override
        public void switchStockRule(String label, @org.jspecify.annotations.Nullable String item, boolean on) {
            actions++;
        }

        @Override
        public void setStockAmount(String label, String item, int field, long amount) {
            actions++;
        }

        @Override
        public long sendToPool(String item, String label, long amount, int strategy, String idleValue, long idle,
                long cursor) {
            actions++;
            return cursor;
        }
    }

    /** One graph under the clock. */
    private static final class Runner {
        final LogicEvaluator<String> evaluator;
        final int[] signals;
        final BenchContext context = new BenchContext();
        long time = 1000;

        Runner(CompiledGraph<String> graph) {
            evaluator = new LogicEvaluator<>(graph);
            List<Integer> found = new ArrayList<>();
            for (Watch<String> watch : graph.watches()) {
                if (watch.kind().signal()) {
                    found.add(watch.index());
                }
            }
            signals = found.stream().mapToInt(Integer::intValue).toArray();
        }

        /** Nanoseconds for {@code runs} runs. */
        long sample(int runs) {
            long cost = 0;
            long start = System.nanoTime();
            for (int r = 0; r < runs; r++) {
                runOnce();
                cost += evaluator.lastCost();
            }
            long elapsed = System.nanoTime() - start;
            sink += cost + context.actions;
            return elapsed;
        }

        void runOnce() {
            evaluator.restart();
            for (int w : signals) {
                evaluator.signal(w);
            }
            context.phase++;
            evaluator.run(context, time);
            time += 40;
        }
    }

    private record Result(NodeType type, int reads, int checks, double median, double p95, double allocBytes) {
    }

    private static LogicGraph<String> graph(NodeType type, int copies) {
        List<LogicNode<String>> nodes = new ArrayList<>();
        List<Wire> wires = new ArrayList<>();
        nodes.add(new LogicNode<>(1, NodeTypes.GRAPH_START, 0, 0, NodeSettings.empty()));
        nodes.add(new LogicNode<>(2, NodeTypes.DEVICE_STATE, 0, 0,
                NodeSettings.<String>empty().withText("label", "src")));
        nodes.add(new LogicNode<>(3, NodeTypes.ITEM_COUNT, 0, 0, NodeSettings.<String>empty().withItem("item", "src")));
        for (int i = 0; i < copies; i++) {
            int id = 100 + i;
            nodes.add(new LogicNode<>(id, type, 0, 0, settings(type, i)));
            for (Port port : type.inputs()) {
                if (!port.required()) {
                    continue;
                }
                switch (port.type()) {
                    case EVENT -> wires.add(new Wire(1, "started", id, port.name()));
                    case YES_NO -> wires.add(new Wire(2, "on", id, port.name()));
                    case NUMBER -> wires.add(new Wire(3, "count", id, port.name()));
                    default -> throw new AssertionError(type.id() + ": no source for required " + port);
                }
            }
        }
        return new LogicGraph<>(nodes, wires);
    }

    /** Distinct items and labels per copy, so each copy of a read node has a watch of its own. */
    private static NodeSettings<String> settings(NodeType type, int copy) {
        NodeSettings<String> settings = NodeSettings.empty();
        for (SettingSpec spec : type.settings()) {
            settings = switch (spec.kind()) {
                case ITEM -> settings.withItem(spec.name(), "item" + copy);
                case FLUID -> settings.withItem(spec.name(), "fluid" + copy);
                case TAG -> settings.withItem(spec.name(), "#tag" + copy);
                // A move goes from the network ("from" empty) to a label.
                case LABEL -> spec.name().equals("from") ? settings : settings.withText(spec.name(), "L" + copy);
                case TEXT -> settings.withText(spec.name(), "text" + copy);
                default -> settings;
            };
        }
        return settings;
    }

    private static CompiledGraph<String> compile(LogicGraph<String> graph, String what) {
        CompileResult<String> result = LogicCompiler.compile(graph, 1000);
        if (result.paused()) {
            throw new AssertionError(what + " has errors: " + result.errors());
        }
        return result.graph().orElseThrow();
    }

    private static long allocated(ThreadMXBean threads) {
        return threads instanceof com.sun.management.ThreadMXBean sun ? sun.getCurrentThreadAllocatedBytes() : -1;
    }

    private static Result measure(NodeType type, CompiledGraph<String> base, ThreadMXBean threads) {
        CompiledGraph<String> test = compile(graph(type, COPIES), type.id());
        Runner baseline = new Runner(base);
        Runner runner = new Runner(test);
        for (int s = 0; s < WARMUP_SAMPLES; s++) {
            baseline.sample(RUNS_PER_SAMPLE);
            runner.sample(RUNS_PER_SAMPLE);
        }
        double[] perNode = new double[SAMPLES];
        for (int s = 0; s < SAMPLES; s++) {
            long b = baseline.sample(RUNS_PER_SAMPLE);
            long t = runner.sample(RUNS_PER_SAMPLE);
            perNode[s] = (double) (t - b) / RUNS_PER_SAMPLE / COPIES;
        }
        Arrays.sort(perNode);
        long a0 = allocated(threads);
        baseline.sample(RUNS_PER_SAMPLE);
        long a1 = allocated(threads);
        runner.sample(RUNS_PER_SAMPLE);
        long a2 = allocated(threads);
        double alloc = a0 < 0 ? Double.NaN : (double) ((a2 - a1) - (a1 - a0)) / RUNS_PER_SAMPLE / COPIES;
        int reads = (test.watches().size() - base.watches().size()) / COPIES;
        int checks = type.cost() + reads * LogicEvaluator.READ_COST
                + (type == NodeTypes.REQUEST_CRAFT ? LogicEvaluator.READ_COST : 0);
        return new Result(type, reads, checks, perNode[SAMPLES / 2], perNode[SAMPLES * 95 / 100], alloc);
    }

    @Test
    void benchmark() throws IOException {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (threads instanceof com.sun.management.ThreadMXBean sun && !sun.isThreadAllocatedMemoryEnabled()) {
            sun.setThreadAllocatedMemoryEnabled(true);
        }
        List<NodeType> types = NodeTypes.all();
        CompiledGraph<String> base = compile(graph(NodeTypes.CONSTANT, 0), "baseline");
        // Warm the evaluator on every type first, so no type is timed with a JIT profile that saw only the earlier
        // ones.
        for (NodeType type : types) {
            Runner runner = new Runner(compile(graph(type, COPIES), type.id()));
            for (int s = 0; s < WARMUP_SAMPLES / 4; s++) {
                runner.sample(RUNS_PER_SAMPLE);
            }
        }
        Runner baseline = new Runner(base);
        for (int s = 0; s < WARMUP_SAMPLES; s++) {
            baseline.sample(RUNS_PER_SAMPLE);
        }
        double[] baseRuns = new double[SAMPLES];
        for (int s = 0; s < SAMPLES; s++) {
            baseRuns[s] = (double) baseline.sample(RUNS_PER_SAMPLE) / RUNS_PER_SAMPLE;
        }
        Arrays.sort(baseRuns);
        List<Result> results = new ArrayList<>();
        for (NodeType type : types) {
            results.add(measure(type, base, threads));
        }
        StringBuilder out = new StringBuilder();
        out.append("# os=").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch"))
                .append(" cpus=").append(Runtime.getRuntime().availableProcessors()).append(" jvm=")
                .append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.vm.version"))
                .append(" maxHeapMb=").append(Runtime.getRuntime().maxMemory() >> 20).append('\n');
        out.append(String.format(Locale.ROOT, "# baseline run (3 source nodes, restart, 2 reads): median %.1f ns%n",
                baseRuns[SAMPLES / 2]));
        out.append("id,category,typeCost,reads,checks,medianNs,p95Ns,allocBytes\n");
        for (Result r : results) {
            out.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%.2f,%.2f,%.1f%n", r.type().id(), r.type().category(),
                    r.type().cost(), r.reads(), r.checks(), r.median(), r.p95(), r.allocBytes()));
        }
        Path dir = Path.of("logic-bench");
        Files.createDirectories(dir);
        Path file = dir.resolve("junit-" + System.currentTimeMillis() + ".csv");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        System.out.println("Logic cost benchmark written to " + file.toAbsolutePath());
        System.out.print(out);
        System.out.println("# sink " + sink);
    }
}
