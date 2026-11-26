package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Which graphs of one controller run which others ("Run another rule", M7L2 batch 4), and the loop check over them:
 * graphs that would end up running themselves, directly or through others, are found before anything runs, so the
 * controller pauses them with {@link LogicLang#RULE_LOOP}. Pure; safe on any thread.
 */
public final class RuleCalls {
    private RuleCalls() {
    }

    /**
     * The graph {@code rule} names among {@code count} graphs called {@code name.apply(i)}: a text of digits only is a
     * row number (1 is the first graph), anything else the first graph with that name, ignoring case and surrounding
     * spaces. -1 when none matches.
     */
    public static int target(String rule, int count, IntFunction<String> name) {
        String wanted = rule.strip();
        if (wanted.isEmpty()) {
            return -1;
        }
        if (digits(wanted)) {
            // At most 64 graphs: a longer number names none of them.
            if (wanted.length() > 3) {
                return -1;
            }
            int row = Integer.parseInt(wanted);
            return row >= 1 && row <= count ? row - 1 : -1;
        }
        String lower = wanted.toLowerCase(Locale.ROOT);
        for (int i = 0; i < count; i++) {
            if (name.apply(i).strip().toLowerCase(Locale.ROOT).equals(lower)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean digits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The loop check for one controller's graphs: per graph (same order as {@code graphs}), the errors it gets, one on
     * each of its run-rule nodes that names a graph on the same loop; empty for a graph on no loop. Since M7L2 row 19
     * also one on each run-rule node that fills a parameter the rule it runs does not have, or has of another kind
     * ({@link LogicLang#PARAM_UNKNOWN}, {@link LogicLang#PARAM_KIND}, naming the parameter). {@code names} are the
     * graphs' names; graphs that do not compile ({@code compiles} false) run nothing and so call nothing. Allocates;
     * run it when a graph is saved, renamed or removed, not per run.
     */
    public static <K> List<List<GraphError>> check(List<LogicGraph<K>> graphs, List<String> names, boolean[] compiles) {
        int n = graphs.size();
        int[][] calls = new int[n][];
        for (int g = 0; g < n; g++) {
            List<Integer> targets = new ArrayList<>();
            if (compiles[g]) {
                for (LogicNode<K> node : graphs.get(g).nodes()) {
                    if (node.type().op() == Op.RUN_RULE) {
                        targets.add(target(node.settings().text(NodeTypes.RULE).orElse(""), n, names::get));
                    }
                }
            }
            calls[g] = targets.stream().mapToInt(Integer::intValue).toArray();
        }
        int[] loop = loops(calls);
        List<List<GraphError>> errors = new ArrayList<>(n);
        for (int g = 0; g < n; g++) {
            List<GraphError> found = new ArrayList<>();
            if (loop[g] >= 0) {
                for (LogicNode<K> node : graphs.get(g).nodes()) {
                    if (node.type().op() != Op.RUN_RULE) {
                        continue;
                    }
                    int to = target(node.settings().text(NodeTypes.RULE).orElse(""), n, names::get);
                    if (to >= 0 && loop[to] == loop[g]) {
                        found.add(GraphError.port(node.id(), NodeTypes.RULE, LogicLang.RULE_LOOP));
                    }
                }
            }
            if (compiles[g]) {
                for (LogicNode<K> node : graphs.get(g).nodes()) {
                    if (node.type().op() != Op.RUN_RULE) {
                        continue;
                    }
                    int to = target(node.settings().text(NodeTypes.RULE).orElse(""), n, names::get);
                    if (to >= 0) {
                        argument(found, node, NodeTypes.ITEM_PARAM, Parameters.ITEM, graphs.get(to));
                        argument(found, node, NodeTypes.AMOUNT_PARAM, Parameters.NUMBER, graphs.get(to));
                    }
                }
            }
            errors.add(List.copyOf(found));
        }
        return errors;
    }

    /**
     * Adds an error when run-rule node {@code node}'s setting {@code setting} names a parameter that {@code target}
     * does not have, or has of another kind than {@code kind}.
     */
    private static <K> void argument(List<GraphError> found, LogicNode<K> node, String setting, int kind,
            LogicGraph<K> target) {
        String name = node.settings().text(setting).orElse("").strip();
        if (name.isEmpty()) {
            return;
        }
        String key = Parameters.key(name);
        java.util.Optional<Parameters.Param<K>> param = Parameters.find(target, key);
        if (param.isEmpty()) {
            found.add(new GraphError(node.id(), setting, LogicLang.PARAM_UNKNOWN, key));
        } else if (param.get().kind() != kind) {
            found.add(new GraphError(node.id(), setting, LogicLang.PARAM_KIND, key));
        }
    }

    /**
     * Loops in the calls between graphs: {@code calls[g]} lists the graphs graph {@code g} runs (out-of-range entries
     * are ignored). Returns, per graph, the number of the loop it is on, or -1 when it is on none. A graph that runs
     * itself is a loop of one; graphs on the same loop share its number.
     */
    public static int[] loops(int[][] calls) {
        int n = calls.length;
        Scc scc = new Scc(calls);
        for (int g = 0; g < n; g++) {
            if (scc.order[g] < 0) {
                scc.visit(g);
            }
        }
        int[] size = new int[n];
        for (int g = 0; g < n; g++) {
            size[scc.component[g]]++;
        }
        int[] loop = new int[n];
        Arrays.fill(loop, -1);
        for (int g = 0; g < n; g++) {
            boolean self = false;
            for (int to : calls[g]) {
                self |= to == g;
            }
            if (size[scc.component[g]] > 1 || self) {
                loop[g] = scc.component[g];
            }
        }
        return loop;
    }

    /** Tarjan's strongly connected components over at most a few dozen graphs; recursion depth is the graph count. */
    private static final class Scc {
        final int[][] calls;
        final int[] order;
        final int[] low;
        final int[] component;
        final boolean[] onStack;
        final int[] stack;
        int stackSize;
        int counter;
        int components;

        Scc(int[][] calls) {
            int n = calls.length;
            this.calls = calls;
            order = new int[n];
            Arrays.fill(order, -1);
            low = new int[n];
            component = new int[n];
            onStack = new boolean[n];
            stack = new int[n];
        }

        void visit(int v) {
            order[v] = counter;
            low[v] = counter;
            counter++;
            stack[stackSize++] = v;
            onStack[v] = true;
            for (int w : calls[v]) {
                if (w < 0 || w >= calls.length) {
                    continue;
                }
                if (order[w] < 0) {
                    visit(w);
                    low[v] = Math.min(low[v], low[w]);
                } else if (onStack[w]) {
                    low[v] = Math.min(low[v], order[w]);
                }
            }
            if (low[v] == order[v]) {
                int w;
                do {
                    w = stack[--stackSize];
                    onStack[w] = false;
                    component[w] = components;
                } while (w != v);
                components++;
            }
        }
    }
}
