package dev.silencebound.maxtech.storage.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

/**
 * What a graph remembers between runs, saved beside the graph: crossing and compare states, latches, counters, held
 * values, pending delays (as game times) and the last state an action set. Keyed by node id and type, so after an edit
 * unchanged nodes keep their memory and changed ones start fresh. Immutable.
 *
 * @param nodes
 *            each remembering node's memory
 */
public record LogicMemory(List<Entry> nodes) {
    public static final LogicMemory EMPTY = new LogicMemory(List.of());
    /**
     * Most longs one node's memory saves. Was 16 before the statistics node; raising it only accepts more, so older
     * saves read as before.
     */
    public static final int MAX_VALUES = 128;

    public static final Codec<LogicMemory> CODEC = Entry.CODEC.listOf(0, LogicGraph.HARD_MAX_NODES)
            .xmap(LogicMemory::new, LogicMemory::nodes);

    public LogicMemory {
        nodes = List.copyOf(nodes);
    }

    /**
     * One node's memory.
     *
     * @param node
     *            the node's id
     * @param type
     *            the node's type id; memory is dropped when the type changed
     * @param values
     *            the node's memory longs
     */
    public record Entry(int node, String type, List<Long> values) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance
                .group(Codec.INT.fieldOf("node").forGetter(Entry::node),
                        Codec.string(1, 64).fieldOf("type").forGetter(Entry::type),
                        Codec.LONG.listOf(0, MAX_VALUES).fieldOf("values").forGetter(Entry::values))
                .apply(instance, Entry::new));

        public Entry {
            values = List.copyOf(values);
        }
    }
}
