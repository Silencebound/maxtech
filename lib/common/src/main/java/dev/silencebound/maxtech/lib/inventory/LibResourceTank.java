package dev.silencebound.maxtech.lib.inventory;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: the tank's own steps are root transactions on its journal (ADR-0002).
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Lib's {@link ResourceTank}: a {@link TankJournal} with its kind and save format, {@code resource} (the key in the
 * kind's codec) and {@code amount}, both only when the tank holds something. Saved contents that no longer decode are
 * kept as saved, block the tank and are saved back unchanged.
 *
 * @param <K>
 *            the kind's key type
 */
public class LibResourceTank<K> implements ResourceTank<K> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RESOURCE = "resource";
    private static final String AMOUNT = "amount";

    private final ResourceKind<K> kind;
    private final TankJournal<K> journal;
    private @Nullable Dynamic<?> unloaded;
    private long unloadedAmount;

    LibResourceTank(ResourceKind<K> kind, long capacity, Predicate<? super K> filter, Runnable onChange) {
        this.kind = kind;
        this.journal = new TankJournal<>(capacity, filter, onChange);
    }

    @Override
    public final ResourceKind<K> kind() {
        return kind;
    }

    @Override
    public final Optional<K> resource() {
        return journal.resource();
    }

    /** The key held, null when empty. */
    public final @Nullable K key() {
        return journal.key();
    }

    @Override
    public final long amount() {
        return journal.amount();
    }

    @Override
    public final long capacity() {
        return journal.capacity();
    }

    @Override
    public final boolean accepts(K key) {
        return journal.accepts(key);
    }

    /** Whether the tank holds saved contents that did not load, and so takes nothing. */
    public final boolean blocked() {
        return journal.blocked();
    }

    /** Inserts as part of {@code transaction}. */
    public final long insert(K key, long amount, TransactionContext transaction) {
        return journal.insert(key, amount, transaction);
    }

    /** Extracts as part of {@code transaction}. */
    public final long extract(K key, long amount, TransactionContext transaction) {
        return journal.extract(key, amount, transaction);
    }

    @Override
    public final long insert(K key, long amount, boolean simulate) {
        try (Transaction transaction = Transaction.openRoot()) {
            long inserted = journal.insert(key, amount, transaction);
            if (!simulate) {
                transaction.commit();
            }
            return inserted;
        }
    }

    @Override
    public final long extract(K key, long amount, boolean simulate) {
        try (Transaction transaction = Transaction.openRoot()) {
            long extracted = journal.extract(key, amount, transaction);
            if (!simulate) {
                transaction.commit();
            }
            return extracted;
        }
    }

    @Override
    public final void set(K key, long amount) {
        unblock();
        journal.set(key, amount);
    }

    @Override
    public final void clear() {
        unblock();
        journal.set(null, 0);
    }

    private void unblock() {
        unloaded = null;
        unloadedAmount = 0;
        journal.block(false);
    }

    @Override
    public final void save(ValueOutput output) {
        Dynamic<?> raw = unloaded;
        if (raw != null) {
            output.store(RESOURCE, Codec.PASSTHROUGH, raw);
            output.putLong(AMOUNT, unloadedAmount);
            return;
        }
        K key = journal.key();
        if (key != null) {
            output.store(RESOURCE, kind.codec(), key);
            output.putLong(AMOUNT, journal.amount());
        }
    }

    @Override
    public final void load(ValueInput input) {
        unblock();
        long amount = input.getLongOr(AMOUNT, 0);
        Optional<K> key = amount > 0 ? input.read(RESOURCE, kind.codec()) : Optional.empty();
        if (key.isPresent()) {
            journal.load(key.get(), Math.min(amount, journal.capacity()));
            if (amount > journal.capacity()) {
                LOGGER.warn("Tank of {} loaded {} of {}, over its capacity {}; kept {}", kind.id(), amount, key.get(),
                        journal.capacity(), journal.capacity());
            }
            return;
        }
        journal.load(null, 0);
        Optional<Dynamic<?>> raw = amount > 0 ? input.read(RESOURCE, Codec.PASSTHROUGH) : Optional.empty();
        if (raw.isPresent()) {
            unloaded = raw.get();
            unloadedAmount = amount;
            journal.block(true);
            LOGGER.warn("Tank of {} kept {} of a resource that does not load: {}", kind.id(), amount, raw.get());
        }
    }

    @Override
    public String toString() {
        return "ResourceTank[" + kind.id() + ", " + journal.amount() + " " + journal.key() + "]";
    }
}
