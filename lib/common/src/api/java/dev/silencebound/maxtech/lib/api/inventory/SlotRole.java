package dev.silencebound.maxtech.lib.api.inventory;

/**
 * What players and automation may do with one slot of an {@link ItemSlots}. The machine itself (the methods of
 * {@link ItemSlots}) may change any slot whatever its role. Immutable; any thread.
 */
public enum SlotRole {
    /**
     * Takes what the slot's filter accepts from players and automation (pipes, hoppers, transfer ports); players can
     * take it back out, automation cannot (a hopper under a machine does not pull out its inputs).
     */
    INPUT,
    /** Only taken from, by players and automation; nothing goes in except through the machine itself. */
    OUTPUT,
    /** Shown only: neither players nor automation put anything in or take anything out. */
    DISPLAY
}
