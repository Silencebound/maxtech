package dev.silencebound.maxtech.storage.logic;

import java.util.Optional;

/**
 * One thing a compiled graph watches. Nodes that watch the same thing share a watch, so the controller subscribes once.
 * Immutable.
 *
 * @param <K>
 *            what items are
 * @param index
 *            the watch's index in {@link CompiledGraph#watches()}; what {@link LogicEvaluator#changed} takes
 * @param kind
 *            what is watched
 * @param item
 *            the item for item counts and job events; empty for a job event about any job, and for other kinds
 * @param label
 *            the device label; empty for the whole network (counts) or the controller itself (redstone)
 * @param name
 *            the sensor value's name for sensors, the variable's name for shared variables; empty otherwise
 * @param number
 *            the radius in blocks for players nearby, the direction for item moves (0 both, 1 in, 2 out); 0 otherwise
 */
public record Watch<K>(int index, WatchKind kind, Optional<K> item, String label, String name, long number) {
}
