package dev.silencebound.maxtech.lib.api.resource;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

/**
 * How amounts of a {@link ResourceKind} are counted and shown: a lang key that takes the amount as its one argument,
 * and how many units make one bucket. Immutable; any thread.
 *
 * @param langKey
 *            lang key of the shown amount, with one {@code %s} for the number ({@code "%s mB"})
 * @param perBucket
 *            units in one bucket, for gauges and bucket clicks (1000 for mB); 1 for kinds without buckets
 */
@ApiStatus.Experimental
public record AmountUnit(String langKey, long perBucket) {
    /** Items, counted one by one. */
    public static final AmountUnit ITEMS = new AmountUnit("maxtech_lib.unit.items", 1);
    /** Millibuckets, 1000 to the bucket: fluids, and later chemicals. */
    public static final AmountUnit MILLIBUCKETS = new AmountUnit("maxtech_lib.unit.millibuckets", 1000);
    /** MaxTech Units of energy (MU). */
    public static final AmountUnit MU = new AmountUnit("maxtech_lib.unit.mu", 1);

    /** Throws {@link IllegalArgumentException} for an empty lang key or a {@code perBucket} below 1. */
    public AmountUnit {
        if (langKey.isEmpty()) {
            throw new IllegalArgumentException("Empty lang key");
        }
        if (perBucket < 1) {
            throw new IllegalArgumentException("perBucket must be at least 1: " + perBucket);
        }
    }

    /**
     * {@code amount} with this unit, the number grouped by thousands with a comma whatever the client's language
     * ({@code 1,250 mB}), so it can be built on the server and sent.
     */
    public Component format(long amount) {
        return Component.translatable(langKey, String.format(Locale.ROOT, "%,d", amount));
    }
}
