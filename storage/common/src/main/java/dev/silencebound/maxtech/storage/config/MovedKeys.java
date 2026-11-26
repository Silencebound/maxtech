package dev.silencebound.maxtech.storage.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Server config values that moved to a new key (M7R item 8: {@code controller.modules.*} and {@code services.ledger.*}
 * regrouped under {@code controller.cards.*}, {@code controller.recorders.*} and {@code devices.*}). For one version
 * the old key stays in the file and is still read: while it holds a value other than {@link #UNSET}, that value wins
 * over the new key's, and loading the config logs a warning that names the new key. Both presets set every old key to
 * {@link #UNSET}, so an old key a server owner never edited is reset by the preset and the new key applies; one they
 * edited keeps their value. The old keys go in the version after.
 *
 * <p>
 * The value in effect is cached when the config loads or reloads ({@link #refresh()}), so a read on a hot path (the
 * ledger per move, a bridge per crossing) is one volatile read; before the first load it is worked out on each read.
 */
public final class MovedKeys {
    /** What an old key holds while it is not used: below every moved value's range. */
    public static final int UNSET = -1;

    /** One moved value: its old and new dotted keys and the old key's raw value. */
    public record Moved(String oldKey, String newKey, LongSupplier oldValue) {
    }

    /** {@link Cached#value} before the first {@link #refresh()}. */
    private static final long UNCACHED = Long.MIN_VALUE;

    /** The value in effect for one moved key, as of the last config load. */
    private static final class Cached {
        final LongSupplier old;
        final LongSupplier current;
        volatile long value = UNCACHED;

        Cached(LongSupplier old, LongSupplier current) {
            this.old = old;
            this.current = current;
        }

        long get() {
            long cached = value;
            return cached != UNCACHED ? cached : pick(old.getAsLong(), current.getAsLong());
        }
    }

    private static final List<Moved> MOVED = new ArrayList<>();
    private static final List<Cached> CACHED = new ArrayList<>();

    private MovedKeys() {
    }

    /**
     * The value in effect for a moved int value: {@code old}'s while it is set, else {@code current}'s. Registers the
     * pair for {@link #warnings()}. Call while the config is declared.
     */
    public static IntSupplier moved(String oldKey, IntSupplier old, String newKey, IntSupplier current) {
        Cached cached = add(oldKey, old::getAsInt, newKey, current::getAsInt);
        return () -> (int) cached.get();
    }

    /** {@link #moved(String, IntSupplier, String, IntSupplier)} for a long value. */
    public static LongSupplier moved(String oldKey, LongSupplier old, String newKey, LongSupplier current) {
        return add(oldKey, old, newKey, current)::get;
    }

    private static Cached add(String oldKey, LongSupplier old, String newKey, LongSupplier current) {
        MOVED.add(new Moved(oldKey, newKey, old));
        Cached cached = new Cached(old, current);
        CACHED.add(cached);
        return cached;
    }

    /**
     * Works out every moved value again from the config; called on each load and reload of the config file (the
     * server's, or a client's synced copy), after the preset applied. Reads the config.
     */
    public static void refresh() {
        for (int i = 0; i < CACHED.size(); i++) {
            Cached cached = CACHED.get(i);
            cached.value = pick(cached.old.getAsLong(), cached.current.getAsLong());
        }
    }

    /** {@code old} while it is set (not negative), else {@code current}. */
    public static int pick(int old, int current) {
        return old >= 0 ? old : current;
    }

    /** {@code old} while it is set (not negative), else {@code current}. */
    public static long pick(long old, long current) {
        return old >= 0 ? old : current;
    }

    /** Every moved value, in declaration order. */
    public static List<Moved> all() {
        return List.copyOf(MOVED);
    }

    /** One warning per old key that is still set, naming its new key; empty when none is. Reads the config. */
    public static List<String> warnings() {
        return warnings(MOVED);
    }

    /** {@link #warnings()} over {@code moved}. */
    static List<String> warnings(List<Moved> moved) {
        List<String> out = new ArrayList<>();
        for (Moved entry : moved) {
            long value = entry.oldValue().getAsLong();
            if (value >= 0) {
                out.add("Server config '" + entry.oldKey() + "' is deprecated and goes in the next version; its value "
                        + value + " is used for '" + entry.newKey() + "'. Set it on '" + entry.newKey()
                        + "' instead and set '" + entry.oldKey() + "' to " + UNSET + ".");
            }
        }
        return out;
    }
}
