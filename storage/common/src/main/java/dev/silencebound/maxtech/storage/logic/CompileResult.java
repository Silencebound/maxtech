package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What {@link LogicCompiler#compile} made of a graph: either a runnable {@link CompiledGraph}, or the errors that keep
 * it paused. A paused graph is still saved as the player built it ({@link #source()}); it never runs, not even in part,
 * until a save compiles without errors. Immutable.
 *
 * @param <K>
 *            what items are
 * @param source
 *            the graph as built
 * @param graph
 *            the runnable graph; empty when there are errors
 * @param errors
 *            the problems found, per node; empty when the graph runs
 */
public record CompileResult<K>(LogicGraph<K> source, Optional<CompiledGraph<K>> graph, List<GraphError> errors) {
    public CompileResult {
        errors = List.copyOf(errors);
        if (graph.isPresent() == !errors.isEmpty()) {
            throw new IllegalArgumentException("A compile result has a graph or errors, not both");
        }
    }

    /** Whether the graph has errors and so does not run. */
    public boolean paused() {
        return !errors.isEmpty();
    }

    /** The errors on node {@code id} ({@link GraphError#GRAPH} for the graph's own). */
    public List<GraphError> errorsFor(int id) {
        List<GraphError> found = new ArrayList<>();
        for (GraphError error : errors) {
            if (error.node() == id) {
                found.add(error);
            }
        }
        return found;
    }
}
