package dev.silencebound.maxtech.storage.logic;

/**
 * One node of a graph as the player placed it. Immutable.
 *
 * @param <K>
 *            what item settings hold
 * @param id
 *            the node's number, unique in its graph; wires and saved memory refer to it, so it survives edits
 * @param type
 *            what the node does
 * @param x
 *            the editor canvas position
 * @param y
 *            the editor canvas position
 * @param settings
 *            the node's settings
 */
public record LogicNode<K>(int id, NodeType type, int x, int y, NodeSettings<K> settings) {
    public LogicNode<K> withSettings(NodeSettings<K> settings) {
        return new LogicNode<>(id, type, x, y, settings);
    }
}
