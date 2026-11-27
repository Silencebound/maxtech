package dev.silencebound.maxtech.lib.api.inventory;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.ApiStatus;

/**
 * A tank holding one resource of one {@link ResourceKind} at a time, with an amount in the kind's unit up to a fixed
 * capacity, made by {@link #create} (or {@link FluidTank#create} for fluids) and implemented by Lib only. Empty means
 * no resource and amount 0, never one without the other.
 * <p>
 * As with {@link ItemSlots}, the methods here are the machine's own access, each its own committed step: not while a
 * NeoForge transfer transaction is open on the thread (an {@link IllegalStateException}). Automation and menus go
 * through handlers that take part in transactions. Server thread only.
 * <p>
 * The change listener runs once per change, never for a simulation or a rolled-back transaction: at once for the
 * methods here, when the root transaction commits for changes made through a handler.
 * <p>
 * A saved resource that no longer loads, wholly (its mod was removed, or a component of it was), is kept as it was
 * saved and saved back unchanged, so it returns with its mod: the tank then is {@link #unknown()}, reads as empty,
 * takes nothing in and refuses {@link #set} and {@link #clear} until the owner calls {@link #discardUnknown()}. This
 * holds while the block exists: unknown contents cannot go into the block's item, so they vanish when it is broken. A
 * save holding more than the capacity (the machine's tank shrank) loads whole: the tank takes nothing until drained
 * below its capacity, and gauges show it as full.
 *
 * @param <K>
 *            the key type of the tank's kind
 */
@ApiStatus.NonExtendable
public interface ResourceTank<K> {
    /**
     * An empty tank of {@code kind} holding up to {@code capacity} (at least 1), taking any resource of the kind, that
     * reports changes to {@code onChange}.
     */
    static <K> ResourceTank<K> create(ResourceKind<K> kind, long capacity, Runnable onChange) {
        return create(kind, capacity, key -> true, onChange);
    }

    /** Like {@link #create(ResourceKind, long, Runnable)}, taking only what {@code filter} accepts. */
    static <K> ResourceTank<K> create(ResourceKind<K> kind, long capacity, Predicate<? super K> filter,
            Runnable onChange) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(onChange, "onChange");
        if (capacity < 1) {
            throw new IllegalArgumentException("Tank capacity must be at least 1, got " + capacity);
        }
        return InventoryFactory.get().tank(kind, capacity, filter, onChange);
    }

    /** The tank's kind. */
    ResourceKind<K> kind();

    /**
     * The resource in the tank, empty when the tank is. The same {@code Optional} instance until the resource changes,
     * so a menu getter can return it every tick without allocating.
     */
    Optional<K> resource();

    /** The amount held, 0 to {@link #capacity()}, or more after loading a save from a larger tank. */
    long amount();

    /** The most the tank holds. */
    long capacity();

    /** Whether the tank's filter takes {@code key} (room and current contents aside). */
    boolean accepts(K key);

    /**
     * Puts up to {@code amount} of {@code key} in if the filter takes it and the tank is empty or holds {@code key};
     * returns how much went in.
     */
    long insert(K key, long amount, boolean simulate);

    /** Takes up to {@code amount} of {@code key} out if the tank holds it; returns how much came out. */
    long extract(K key, long amount, boolean simulate);

    /**
     * Whether the tank holds saved contents that no longer load (their mod was removed): kept and saved back as they
     * were, never moved, and shown as unknown.
     */
    boolean unknown();

    /** Throws away {@link #unknown()} contents for good, leaving the tank empty; does nothing otherwise. */
    void discardUnknown();

    /**
     * Replaces the contents with {@code amount} of {@code key} (0 empties the tank), checking neither filter nor
     * capacity beyond {@code amount >= 0}. Throws {@link IllegalStateException} while the tank is {@link #unknown()}.
     */
    void set(K key, long amount);

    /** Empties the tank; throws {@link IllegalStateException} while it is {@link #unknown()}. */
    void clear();

    /** Saves the contents into {@code output}, a child the owner names (e.g. {@code output.child("tank")}). */
    void save(ValueOutput output);

    /** Loads saved contents from {@code input}; does not call the change listener. */
    void load(ValueInput input);
}
