package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.storage.logic.CompiledGraph;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where the pinned values of one graph come from (M7L2 chunk 8), worked out once when its evaluator is made: an
 * output's value slot, the value a crossing watches, or the tick a delay, timer or pulse is due. Reading a value then
 * allocates nothing, so the open controller screen can compare them on its update timer and nothing runs while no
 * screen is open. Server thread.
 */
final class PinnedValues {
    static final PinnedValues NONE = new PinnedValues(0);
    /** A pinned value that is not known (the graph is paused, the watch not looked at yet). */
    static final long UNKNOWN = Long.MIN_VALUE;

    private static final int MISSING = 0;
    private static final int SLOT = 1;
    private static final int WATCH = 2;
    private static final int DUE = 3;

    private final int[] sources;
    private final int[] indexes;
    private final String[] nodeKeys;
    private final String[] lineKeys;
    private final LiveLine.Format[] formats;
    private final long[] limits;
    private final boolean[] below;

    private PinnedValues(int size) {
        sources = new int[size];
        indexes = new int[size];
        nodeKeys = new String[size];
        lineKeys = new String[size];
        formats = new LiveLine.Format[size];
        limits = new long[size];
        below = new boolean[size];
    }

    /** The pins of {@code slot} resolved against {@code evaluator} (null while the graph is paused). Allocates. */
    static PinnedValues of(GraphSlot slot, @Nullable LogicEvaluator<LogicKey> evaluator) {
        List<Integer> pins = slot.pins();
        if (pins.isEmpty()) {
            return NONE;
        }
        PinnedValues out = new PinnedValues(pins.size());
        CompiledGraph<LogicKey> compiled = evaluator == null ? null : evaluator.graph();
        for (int i = 0; i < pins.size(); i++) {
            int id = pins.get(i);
            LogicNode<LogicKey> node = slot.graph().node(id);
            Optional<LiveLine.Spec> found = node == null
                    ? Optional.empty()
                    : LiveLine.pin(node.type(), node.settings());
            out.nodeKeys[i] = node == null ? "" : node.type().nameKey();
            out.lineKeys[i] = found.map(LiveLine.Spec::key).orElse(LogicLang.LIVE_VALUE);
            out.formats[i] = found.map(LiveLine.Spec::format).orElse(LiveLine.Format.NUMBER);
            out.sources[i] = MISSING;
            if (node == null || found.isEmpty() || compiled == null) {
                continue;
            }
            String entry = found.get().entry();
            if (out.formats[i] == LiveLine.Format.CROSSING) {
                out.limits[i] = LiveLine.threshold(node.type(), node.settings());
                out.below[i] = LiveLine.below(node.type(), node.settings());
            }
            int position = compiled.position(id);
            if (entry.equals(LiveLine.WATCHED)) {
                int watch = position < 0 ? -1 : compiled.watchAt(position);
                if (watch >= 0) {
                    out.sources[i] = WATCH;
                    out.indexes[i] = watch;
                }
            } else if (entry.equals(LiveLine.DUE)) {
                if (position >= 0) {
                    out.sources[i] = DUE;
                    out.indexes[i] = position;
                }
            } else {
                int valueSlot = compiled.slot(id, entry);
                if (valueSlot >= 0) {
                    out.sources[i] = SLOT;
                    out.indexes[i] = valueSlot;
                }
            }
        }
        return out;
    }

    int size() {
        return sources.length;
    }

    /** Pin {@code i}'s value now, or {@link #UNKNOWN}. Allocates nothing. */
    long value(int i, @Nullable LogicEvaluator<LogicKey> evaluator, LogicWorker worker, int graph) {
        if (evaluator == null) {
            return UNKNOWN;
        }
        return switch (sources[i]) {
            case SLOT -> evaluator.value(indexes[i]);
            case WATCH -> worker.watched(graph, indexes[i]);
            case DUE -> evaluator.due(indexes[i]);
            default -> UNKNOWN;
        };
    }

    /** Pin {@code i} with {@code value}, as the controller screen gets it. */
    ControllerLog.Pin pin(int i, long value) {
        return new ControllerLog.Pin(nodeKeys[i], lineKeys[i], formats[i].id(), value, limits[i], below[i]);
    }
}
