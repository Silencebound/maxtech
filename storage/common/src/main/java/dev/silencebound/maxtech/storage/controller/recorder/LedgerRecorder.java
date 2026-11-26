package dev.silencebound.maxtech.storage.controller.recorder;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.module.LedgerBook;
import dev.silencebound.maxtech.storage.network.MoveWatcher;
import dev.silencebound.maxtech.storage.network.NetworkActor;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A controller's ledger (PRD 7.2, Multiplayer; M6e area G, M6f F9; the ledger module's recorder before M7R item 3): it
 * records how much of each key each player put into and took out of its network per in-game day, in a
 * {@link LedgerBook} saved with the controller's primary block.
 *
 * <p>
 * It hears the network's operations themselves ({@link MoveWatcher}) once their transaction committed, with the player
 * a terminal, the network chest or a tool set ({@link NetworkActor}); device operations count as automation. Devices
 * joining or leaving move nothing, so they never show up. It watches a network only while its host records
 * ({@link Host#records()}: a working primary with {@code controller.recorders.ledger} on), so a network without a
 * recording ledger keeps no move journal at all. Server thread.
 */
public final class LedgerRecorder implements MoveWatcher {
    /** What the ledger needs from the block it is kept on. Server thread. */
    public interface Host {
        /** Whether the ledger records now. */
        boolean records();

        /** The book changed: the host saves it. */
        void ledgerChanged();

        /** The game time now, for dropping old days; 0 without a level. */
        long gameTime();
    }

    private final Host host;
    private LedgerBook<StackKey> book = new LedgerBook<>();
    private @Nullable NetworkState bound;
    /** Counts every change to the book, so views know when to send it again. Not saved. */
    private int version;

    public LedgerRecorder(Host host) {
        this.host = host;
    }

    /**
     * Follows the host's network: stops hearing the old one's moves and, while the host records, starts on the new
     * one's. Called on network changes and again on the load display's cadence, which picks up a config change.
     */
    public void bind(@Nullable NetworkState network) {
        NetworkState wanted = host.records() ? network : null;
        if (wanted == bound) {
            return;
        }
        NetworkState previous = bound;
        if (previous != null) {
            previous.unwatchMoves(this);
        }
        bound = wanted;
        if (wanted != null) {
            wanted.watchMoves(this);
        }
    }

    /** The network whose moves the ledger hears, or null. */
    public @Nullable NetworkState bound() {
        return bound;
    }

    @Override
    public void moved(StackKey key, long amount, @Nullable UUID player, long gameTime) {
        if (!host.records()) {
            return;
        }
        book.record(player == null ? LedgerBook.AUTOMATION : player, key, amount,
                Math.floorDiv(gameTime, LedgerBook.TICKS_PER_DAY), StorageServerConfig.LEDGER_DAYS.getAsInt(),
                StorageServerConfig.LEDGER_MAX_ROWS.getAsInt());
        version++;
        host.ledgerChanged();
    }

    /** The book with old days dropped; for the views and info lines. */
    public LedgerBook<StackKey> book() {
        book.expire(Math.floorDiv(host.gameTime(), LedgerBook.TICKS_PER_DAY),
                StorageServerConfig.LEDGER_DAYS.getAsInt());
        return book;
    }

    /** A number that changes whenever the book does. */
    public int version() {
        return version;
    }

    /**
     * The busiest keys of the kept days for {@code player}, or summed over everyone with null; at most {@code limit}.
     */
    public List<LedgerBook.Row<StackKey>> totals(int limit, @Nullable UUID player) {
        return book().totals(limit, player);
    }

    /** The book to save. */
    public LedgerBook<StackKey> saved() {
        return book;
    }

    /** Replaces the book with a loaded or handed-over one. */
    public void load(LedgerBook<StackKey> loaded) {
        book = loaded;
        version++;
    }

    /**
     * Adds every row of {@code other} (a merged controller's, or an old ledger module's) to this book, within the
     * config's days and rows; see {@link LedgerBook#merge}.
     */
    public void merge(LedgerBook<StackKey> other) {
        if (other.rows() == 0 && other.unrecorded() == 0) {
            return;
        }
        book.merge(other, Math.floorDiv(host.gameTime(), LedgerBook.TICKS_PER_DAY),
                StorageServerConfig.LEDGER_DAYS.getAsInt(), StorageServerConfig.LEDGER_MAX_ROWS.getAsInt());
        version++;
        host.ledgerChanged();
    }

    /** Takes the book out (a handover or merge): this ledger is empty afterwards and hears nothing. */
    public LedgerBook<StackKey> takeAll() {
        bind(null);
        LedgerBook<StackKey> out = book;
        book = new LedgerBook<>();
        version++;
        return out;
    }
}
