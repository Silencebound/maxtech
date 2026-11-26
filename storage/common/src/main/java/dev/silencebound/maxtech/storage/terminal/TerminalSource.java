package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.filter.CarriedViewFilters;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterSlots;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
// PLATFORM-LEAK: player transfers run in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link TerminalMenu} shows and changes on the server: a storage network behind a terminal, or a storage
 * chest's own slots. Contents are totals by key id; the source tells each open menu about changes through
 * {@link TerminalMenu#changed} and, when its contents are replaced (the terminal joined another network), through
 * {@link TerminalMenu#reset}. Server thread.
 */
public interface TerminalSource {
    /** Whether {@code player} may keep the menu open: the block is still there and within reach. */
    boolean stillValid(Player player);

    /** Whether {@code player} may see the contents (owner security, or a network's security module). */
    boolean canAccess(Player player);

    /** Whether {@code player} may put items and fluids in; {@link #canAccess} unless a security module says less. */
    default boolean canDeposit(Player player) {
        return canAccess(player);
    }

    /** Whether {@code player} may take items and fluids out; {@link #canAccess} unless a security module says less. */
    default boolean canTake(Player player) {
        return canAccess(player);
    }

    /** The totals by key id now, every total above 0; read-only, and not kept by the caller. */
    Int2LongMap contents();

    /** The key with {@code id}, or null for an id not in use. */
    @Nullable
    StackKey key(int id);

    /** The total of the key with {@code id}; 0 for an id not in use. */
    long amount(int id);

    /** Stores up to {@code amount} of {@code key} for a player inside {@code transaction}; returns what went in. */
    long insert(StackKey key, long amount, TransactionContext transaction);

    /** Takes up to {@code amount} of {@code key} out for a player inside {@code transaction}; returns what came out. */
    long extract(StackKey key, long amount, TransactionContext transaction);

    /** Starts telling {@code menu} about changes, until {@link #unwatch}. */
    void watch(TerminalMenu menu);

    void unwatch(TerminalMenu menu);

    /**
     * {@code menu} closed, whether or not it ever watched this source (a menu whose screen never asked for its list
     * never watched). A source that holds something open for its menus (a mounted portable cell) lets go here.
     */
    default void closed(TerminalMenu menu) {
    }

    /**
     * The storage network behind this source, for autocrafting requests; null for a source that is no network (a
     * storage chest) or a terminal without one.
     */
    default @Nullable NetworkState network() {
        return null;
    }

    /**
     * Pays for one remote action that moves no items through this source, inside {@code transaction}: a crafting
     * request, locate, a pattern priority change, opening a provider or the crafter manager from the pattern overview
     * (B09 item 23). A wireless terminal pays one operation from its charge, plus the dimension bridge's surcharge from
     * another dimension, as for its inserts and extracts. False when it cannot pay; free (true) by default. Use
     * {@link RemoteCost} rather than calling this directly.
     */
    default boolean payAction(TransactionContext transaction) {
        return true;
    }

    /**
     * The view filter slots the terminal menu shows (B09 item 24): a terminal block's or part's and a storage chest's
     * own ({@link ViewFilterSlots}, saved with it), or a wireless terminal's or portable cell's
     * ({@link CarriedViewFilters}, kept in the item, #322); the same object on every call. Null for sources without
     * them (the tablet's jobs view).
     */
    default @Nullable Container viewFilters() {
        return null;
    }

    /** The value of one of the menu's status fields ({@link TerminalMenu#STATUS} ...), for its data slots. */
    int status(int field);
}
