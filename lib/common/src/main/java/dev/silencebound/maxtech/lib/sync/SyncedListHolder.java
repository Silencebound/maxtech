package dev.silencebound.maxtech.lib.sync;

import org.jspecify.annotations.Nullable;

/**
 * A container menu that receives synced lists. On the client, an update for the player's open menu is applied to the
 * list this returns for its type; updates for a closed or different menu are dropped.
 */
public interface SyncedListHolder {
    /** The client-side copy of the list of {@code type} this menu shows, or null if it shows none. */
    <E> @Nullable ClientSyncedList<E> syncedList(SyncedListType<E> type);
}
