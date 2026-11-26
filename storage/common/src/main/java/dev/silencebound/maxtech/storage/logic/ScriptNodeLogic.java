package dev.silencebound.maxtech.storage.logic;

/**
 * What a pack's script node type does when its node runs ({@link ScriptNodes}): reads its inputs and sets its outputs
 * through the context. Called on the server thread during a graph run, at most once per node per run, and only when an
 * input changed or the graph rechecks everything. It must return quickly and must not keep the context. Throwing pauses
 * the graph with the message on the node; nothing the run did is carried out.
 */
@FunctionalInterface
public interface ScriptNodeLogic {
    void evaluate(ScriptNodeContext context);
}
