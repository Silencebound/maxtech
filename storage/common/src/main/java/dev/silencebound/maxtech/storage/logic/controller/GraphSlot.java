package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicMemory;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One saved graph of a Logic Controller: its name, whether the player switched it on, the graph as built (saved even
 * with errors, which keep it paused) and what it remembers; since M7L2 chunk 8 also whether it only simulates its
 * actions, the last working version before the current one (for Revert) and the nodes whose values are pinned to the
 * controller screen, and since chunk 9 its change history. Saved on the block entity and in the dropped item
 * ({@code maxtech_storage:logic_graphs}); the new fields are optional, so older saves load unchanged. Immutable.
 *
 * @param name
 *            the player's name for the graph, at most {@link #MAX_NAME} characters
 * @param enabled
 *            whether the graph may run
 * @param graph
 *            the graph as built
 * @param memory
 *            its memory as of the last save; while it simulates, the memory it had when simulating began
 * @param simulate
 *            whether its actions are only recorded ("would turn intake on"), never carried out
 * @param previous
 *            the last version that compiled and ran before the current one was saved; empty when there is none
 * @param pins
 *            ids of the nodes whose values the controller screen shows, at most {@link #MAX_PINS}, each a node of
 *            {@code graph}
 * @param history
 *            its change history (M7L2 chunk 9), oldest first, at most {@link HistoryEntry#HARD_MAX}; the worker keeps
 *            {@code logic.historyEntries}
 * @param waiting
 *            whether it was started as a blank card (M7L2 chunk 11) and waits, switched off, for a trigger and an
 *            action; card edits switch it on once both are there, and switching it by hand ends the wait
 * @param instance
 *            for a copy of another rule with its own parameter values (M7L2 row 19), the link and the values; then
 *            {@code graph} is the template's graph with the values in, which the worker makes, and is saved as an empty
 *            graph, and {@code previous} is empty. Empty for an ordinary rule
 * @param id
 *            the rule's id on its controller (M7L2 row 19), random and kept through edits, reverts and saves, so a copy
 *            finds its template even when rows move and never takes another rule that lands on the template's row; 0
 *            until the worker gives it one (older saves)
 */
public record GraphSlot(String name, boolean enabled, LogicGraph<LogicKey> graph, LogicMemory memory, boolean simulate,
        Optional<LogicGraph<LogicKey>> previous, List<Integer> pins, List<HistoryEntry> history, boolean waiting,
        Optional<RuleInstance> instance, long id) {
    /** Longest graph name; a GUI and packet bound. */
    public static final int MAX_NAME = 32;
    /** Most graphs the codec reads, whatever the config allows. */
    public static final int HARD_MAX = 64;
    /** Most pinned nodes the codec reads; the configured limit is {@code logic.maxPins}. */
    public static final int MAX_PINS = 16;

    public static final Codec<GraphSlot> CODEC = RecordCodecBuilder
            .create(instance -> instance
                    .group(Codec.string(0, MAX_NAME).optionalFieldOf("name", "").forGetter(GraphSlot::name),
                            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(GraphSlot::enabled),
                            // A copy saves only its link and values; its graph is made from the template's when loaded.
                            LogicKey.GRAPH_CODEC.fieldOf("graph")
                                    .forGetter(slot -> slot.instance().isPresent() ? LogicGraph.empty() : slot.graph()),
                            LogicMemory.CODEC.optionalFieldOf("memory", LogicMemory.EMPTY).forGetter(GraphSlot::memory),
                            Codec.BOOL.optionalFieldOf("simulate", false).forGetter(GraphSlot::simulate),
                            LogicKey.GRAPH_CODEC.optionalFieldOf("previous").forGetter(GraphSlot::previous),
                            Codec.INT.listOf(0, MAX_PINS).optionalFieldOf("pins", List.of()).forGetter(GraphSlot::pins),
                            HistoryEntry.LIST_CODEC.optionalFieldOf("history", List.of()).forGetter(GraphSlot::history),
                            Codec.BOOL.optionalFieldOf("waiting", false).forGetter(GraphSlot::waiting),
                            RuleInstance.CODEC.optionalFieldOf("instance").forGetter(GraphSlot::instance),
                            Codec.LONG.optionalFieldOf("id", 0L).forGetter(GraphSlot::id))
                    .apply(instance, GraphSlot::new));
    public static final Codec<List<GraphSlot>> LIST_CODEC = CODEC.listOf(0, HARD_MAX);

    public GraphSlot {
        name = name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
        // A copy's graph is made by the worker after loading (empty until then), which cleans its pins.
        pins = instance.isPresent()
                ? List.copyOf(pins.subList(0, Math.min(pins.size(), MAX_PINS)))
                : cleanPins(pins, graph, MAX_PINS);
        previous = instance.isPresent() ? Optional.empty() : previous;
        history = HistoryEntry.trim(history, HistoryEntry.HARD_MAX);
    }

    /** A slot without an id yet; the worker gives it one. */
    public GraphSlot(String name, boolean enabled, LogicGraph<LogicKey> graph, LogicMemory memory, boolean simulate,
            Optional<LogicGraph<LogicKey>> previous, List<Integer> pins, List<HistoryEntry> history, boolean waiting,
            Optional<RuleInstance> instance) {
        this(name, enabled, graph, memory, simulate, previous, pins, history, waiting, instance, 0L);
    }

    /** An ordinary rule, not a copy of another. */
    public GraphSlot(String name, boolean enabled, LogicGraph<LogicKey> graph, LogicMemory memory, boolean simulate,
            Optional<LogicGraph<LogicKey>> previous, List<Integer> pins, List<HistoryEntry> history, boolean waiting) {
        this(name, enabled, graph, memory, simulate, previous, pins, history, waiting, Optional.empty());
    }

    /** A slot that does not wait to be filled. */
    public GraphSlot(String name, boolean enabled, LogicGraph<LogicKey> graph, LogicMemory memory, boolean simulate,
            Optional<LogicGraph<LogicKey>> previous, List<Integer> pins, List<HistoryEntry> history) {
        this(name, enabled, graph, memory, simulate, previous, pins, history, false);
    }

    /** A plain slot: not simulating, no earlier version, nothing pinned. */
    public GraphSlot(String name, boolean enabled, LogicGraph<LogicKey> graph, LogicMemory memory) {
        this(name, enabled, graph, memory, false, Optional.empty(), List.of(), List.of());
    }

    GraphSlot withWaiting(boolean next) {
        return new GraphSlot(name, enabled, graph, memory, simulate, previous, pins, history, next, instance, id);
    }

    GraphSlot withMemory(LogicMemory next) {
        return new GraphSlot(name, enabled, graph, next, simulate, previous, pins, history, waiting, instance, id);
    }

    GraphSlot withEnabled(boolean next) {
        return new GraphSlot(name, next, graph, memory, simulate, previous, pins, history, waiting, instance, id);
    }

    GraphSlot withSimulate(boolean next) {
        return new GraphSlot(name, enabled, graph, memory, next, previous, pins, history, waiting, instance, id);
    }

    GraphSlot withHistory(List<HistoryEntry> next) {
        return new GraphSlot(name, enabled, graph, memory, simulate, previous, pins, next, waiting, instance, id);
    }

    GraphSlot withPins(List<Integer> next) {
        return new GraphSlot(name, enabled, graph, memory, simulate, previous, next, history, waiting, instance, id);
    }

    GraphSlot withName(String next) {
        return new GraphSlot(next, enabled, graph, memory, simulate, previous, pins, history, waiting, instance, id);
    }

    /** A copy holding {@code next} as its graph (a copy's graph made from its template's). */
    GraphSlot withGraph(LogicGraph<LogicKey> next) {
        return new GraphSlot(name, enabled, next, memory, simulate, previous, pins, history, waiting, instance, id);
    }

    GraphSlot withInstance(Optional<RuleInstance> next) {
        return new GraphSlot(name, enabled, graph, memory, simulate, previous, pins, history, waiting, next, id);
    }

    GraphSlot withId(long next) {
        return new GraphSlot(name, enabled, graph, memory, simulate, previous, pins, history, waiting, instance, next);
    }

    /**
     * {@code pins} without repeats and without ids that name no node of {@code graph} with a value to pin
     * ({@link LiveLine#pin}), at most {@code most} of them, in the order given. Allocates.
     */
    static List<Integer> cleanPins(List<Integer> pins, LogicGraph<LogicKey> graph, int most) {
        if (pins.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>(Math.min(pins.size(), most));
        for (Integer id : pins) {
            if (out.size() >= most) {
                break;
            }
            LogicNode<LogicKey> node = graph.node(id);
            if (!out.contains(id) && node != null && LiveLine.pin(node.type(), node.settings()).isPresent()) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
