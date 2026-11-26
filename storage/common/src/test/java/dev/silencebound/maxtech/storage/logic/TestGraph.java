package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** Builds test graphs with items as plain strings. */
final class TestGraph {
    private final List<LogicNode<String>> nodes = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();

    TestGraph node(int id, NodeType type) {
        return node(id, type, settings -> settings);
    }

    TestGraph node(int id, NodeType type, UnaryOperator<NodeSettings<String>> settings) {
        nodes.add(new LogicNode<>(id, type, id * 10, 0, settings.apply(NodeSettings.empty())));
        return this;
    }

    TestGraph wire(int from, String output, int to, String input) {
        wires.add(new Wire(from, output, to, input));
        return this;
    }

    LogicGraph<String> build() {
        return new LogicGraph<>(nodes, wires);
    }

    CompileResult<String> compile() {
        return LogicCompiler.compile(build(), 64);
    }

    CompiledGraph<String> compiled() {
        CompileResult<String> result = compile();
        if (result.paused()) {
            throw new AssertionError("Graph has errors: " + result.errors());
        }
        return result.graph().orElseThrow();
    }
}
