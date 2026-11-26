package dev.silencebound.maxtech.storage.research;

/**
 * A click in the research screen, packed into vanilla's container button id (as the terminal's clicks are), so vanilla
 * checks the container id, spectators and {@code stillValid} before the menu sees it: two bits of kind, four of option
 * index, the rest the node's index in the tree's order. Pure, any thread.
 *
 * @param node
 *            the node's index in {@link ResearchTree#entries()}, the same on both sides since both load the same synced
 *            registry
 */
public record ResearchAction(Kind kind, int node, int option) {
    private static final int KIND_BITS = 2;
    private static final int OPTION_BITS = 4;
    /** Most options a node may have and still be addressed; {@link ResearchNode#MAX_ENTRIES}. */
    static final int MAX_OPTIONS = 1 << OPTION_BITS;

    /** What the click asks for. */
    public enum Kind {
        /** Send the view now (the screen asks once it exists, so the first view is not dropped). */
        REFRESH,
        /** Complete the node with its points option. */
        SPEND_POINTS,
        /** Complete the node with a skip token. */
        USE_TOKEN,
        /** Open option {@code option}'s puzzle. */
        PUZZLE;

        private static final Kind[] VALUES = values();
    }

    public ResearchAction {
        if (node < 0 || option < 0 || option >= MAX_OPTIONS) {
            throw new IllegalArgumentException("Node or option out of range: " + node + ", " + option);
        }
    }

    /** The packed button id; never negative. */
    public int encode() {
        int kindIndex = switch (kind) {
            case REFRESH -> 0;
            case SPEND_POINTS -> 1;
            case USE_TOKEN -> 2;
            case PUZZLE -> 3;
        };
        return kindIndex | option << KIND_BITS | node << (KIND_BITS + OPTION_BITS);
    }

    /** The action {@code id} names; any non-negative id decodes, the menu checks the node and option. */
    public static ResearchAction decode(int id) {
        return new ResearchAction(Kind.VALUES[id & ((1 << KIND_BITS) - 1)], id >>> (KIND_BITS + OPTION_BITS),
                (id >>> KIND_BITS) & (MAX_OPTIONS - 1));
    }
}
