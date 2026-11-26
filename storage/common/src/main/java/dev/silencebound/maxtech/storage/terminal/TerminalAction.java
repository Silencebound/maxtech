package dev.silencebound.maxtech.storage.terminal;

import org.jspecify.annotations.Nullable;

/**
 * What a terminal click asks the server for, packed into the button id of vanilla's container button packet: the low
 * four bits are the {@link Kind}, the rest is the entry's key id plus one (0 for no entry). Vanilla's handler already
 * checks the container id, the spectator flag and {@code stillValid}, so a terminal needs no payload of its own.
 *
 * @param kind
 *            what to do
 * @param keyId
 *            the synced list id the click was on, or -1 for none
 */
public record TerminalAction(Kind kind, int keyId) {
    private static final int KIND_BITS = 4;
    private static final int KIND_MASK = (1 << KIND_BITS) - 1;
    /** Highest key id a button id can carry. */
    static final int MAX_KEY_ID = (Integer.MAX_VALUE >> KIND_BITS) - 1;

    /** The actions; each has a fixed code on the wire. */
    public enum Kind {
        /** Send the full contents again (the screen opened, or wants a resync); rate-limited. */
        REFRESH(0),
        /** A stack of the entry onto the cursor. */
        TAKE_STACK(1),
        /** Half a stack onto the cursor. */
        TAKE_HALF(2),
        /** One item onto the cursor. */
        TAKE_ONE(3),
        /** A stack straight into the player's inventory. */
        TAKE_TO_INVENTORY(4),
        /** Store the whole carried stack. */
        DEPOSIT_ALL(5),
        /** Empty the carried fluid container into storage, or store one carried item if it holds no fluid. */
        DEPOSIT_ONE(6),
        /** Fill the carried fluid container from the entry. */
        FILL_CONTAINER(7),
        /** Store every main-inventory stack whose item is already stored (the deposit key). */
        DEPOSIT_STORED(8),
        /** Top the hotbar's stacks up from storage (the restock key). */
        RESTOCK_HOTBAR(9),
        /** Tell where the entry is stored and outline those blocks (the locate key, M7C). */
        LOCATE(10),
        /** Return the crafting terminal's grid to the network (the clear button). */
        GRID_CLEAR(11),
        /** Order crafting jobs for what the crafting terminal's grid lacks of the last transferred recipe. */
        GRID_REQUEST(12),
        /** A pattern terminal's draft edit; the key id carries the pattern action and slot (PatternPanel). */
        PATTERN(13);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        static @Nullable Kind byCode(int code) {
            for (Kind kind : values()) {
                if (kind.code == code) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** The button id for this action. Throws for a key id the id cannot carry. */
    public int buttonId() {
        if (keyId < -1 || keyId > MAX_KEY_ID) {
            throw new IllegalArgumentException("Key id out of range: " + keyId);
        }
        return (keyId + 1) << KIND_BITS | kind.code;
    }

    /** The action a client sent, or null for a button id no client of ours sends. */
    public static @Nullable TerminalAction decode(int buttonId) {
        if (buttonId < 0) {
            return null;
        }
        Kind kind = Kind.byCode(buttonId & KIND_MASK);
        return kind == null ? null : new TerminalAction(kind, (buttonId >>> KIND_BITS) - 1);
    }
}
