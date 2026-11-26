package dev.silencebound.maxtech.storage.world;

/**
 * The placer's actions (B09 item 25) by the fixed ids its settings save, in the order the mode tab steps through them:
 * place a block, or drop items in front of it like a dropper, a stack or a single item per operation.
 */
public final class PlacerAction {
    /** Places the filtered block, the placer's original behaviour. */
    public static final String PLACE = "place";
    /** Drops up to one stack of the filtered item per operation. */
    public static final String DROP_STACK = "drop_stack";
    /** Drops one filtered item per operation. */
    public static final String DROP_ITEM = "drop_item";

    private PlacerAction() {
    }
}
