package dev.silencebound.maxtech.storage.logic;

/**
 * One problem the compiler found, shown on the node (and port) it concerns. Immutable.
 *
 * @param node
 *            the id of the node the problem is on, or {@link #GRAPH} for the graph as a whole
 * @param port
 *            the input port or setting the problem is on; empty for the node as a whole
 * @param key
 *            the lang key of the plain-words message ({@link LogicLang})
 * @param arg
 *            the message's argument for keys that take one (a limit); empty otherwise
 */
public record GraphError(int node, String port, String key, String arg) {
    /** {@link #node()} of a problem with the whole graph. */
    public static final int GRAPH = -1;

    static GraphError graph(String key, String arg) {
        return new GraphError(GRAPH, "", key, arg);
    }

    static GraphError node(int node, String key) {
        return new GraphError(node, "", key, "");
    }

    static GraphError port(int node, String port, String key) {
        return new GraphError(node, port, key, "");
    }
}
