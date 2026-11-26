package dev.silencebound.maxtech.storage.crafting.stock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

/**
 * One stock keeping rule (PRD 7.2, Stock keeping): keep {@code keep} of {@code key} in the network. Once the stored
 * amount falls below {@code restart}, the keeper requests crafts of at most {@code batch} at a time until {@code keep}
 * is reached again, so a rule does not start a job for every item taken. Immutable; saved with the keeper and kept in
 * its item.
 *
 * @param key
 *            what to keep; empty for an unused rule
 * @param keep
 *            the amount to fill up to
 * @param restart
 *            crafting starts again once the stored amount is below this; at most {@code keep}
 * @param batch
 *            the most one job asks for; 0 for no limit
 * @param priority
 *            higher rules submit first, and the job queue orders background jobs by it
 * @param cpuTier
 *            the only CPU tier the rule's jobs may run on, 1 to 3, or 0 for any
 * @param redstone
 *            when the rule may request
 * @param switchedOff
 *            the rule requests nothing until switched on again (from the keeper's screen or by a logic rule, M7L2 chunk
 *            15); it still tracks whether it is filling
 */
public record StockRule(Optional<StackKey> key, long keep, long restart, long batch, int priority, int cpuTier,
        Redstone redstone, boolean switchedOff) {
    /** Amounts stay well inside a long, so a step button never overflows. */
    public static final long MAX_AMOUNT = Long.MAX_VALUE / 16;
    public static final int MAX_PRIORITY = 99;
    public static final StockRule EMPTY = new StockRule(Optional.empty(), 0, 0, 0, 0, 0, Redstone.ALWAYS, false);

    public static final Codec<StockRule> CODEC = RecordCodecBuilder.create(instance -> instance
            // A key of a removed mod reads as none, so the rule keeps its slot and numbers.
            .group(StackKey.CODEC.lenientOptionalFieldOf("key").forGetter(StockRule::key),
                    Codec.LONG.optionalFieldOf("keep", 0L).forGetter(StockRule::keep),
                    Codec.LONG.optionalFieldOf("restart", 0L).forGetter(StockRule::restart),
                    Codec.LONG.optionalFieldOf("batch", 0L).forGetter(StockRule::batch),
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(StockRule::priority),
                    Codec.INT.optionalFieldOf("cpu_tier", 0).forGetter(StockRule::cpuTier),
                    StringRepresentable.fromEnum(Redstone::values).optionalFieldOf("redstone", Redstone.ALWAYS)
                            .forGetter(StockRule::redstone),
                    Codec.BOOL.optionalFieldOf("switched_off", false).forGetter(StockRule::switchedOff))
            .apply(instance, StockRule::new));

    public StockRule {
        keep = Math.clamp(keep, 0, MAX_AMOUNT);
        restart = Math.clamp(restart, 0, keep);
        batch = Math.clamp(batch, 0, MAX_AMOUNT);
        priority = Math.clamp(priority, -MAX_PRIORITY, MAX_PRIORITY);
        cpuTier = Math.clamp(cpuTier, 0, 3);
    }

    /** Whether the rule names a key and something to keep. */
    public boolean active() {
        return key.isPresent() && keep > 0;
    }

    public StockRule withKey(Optional<StackKey> key) {
        return new StockRule(key, keep, restart, batch, priority, cpuTier, redstone, switchedOff);
    }

    /** A new keep amount; a restart threshold that sat at the old keep amount follows it up. */
    public StockRule withKeep(long keep) {
        return new StockRule(key, keep, restart == this.keep ? keep : restart, batch, priority, cpuTier, redstone,
                switchedOff);
    }

    public StockRule withRestart(long restart) {
        return new StockRule(key, keep, restart, batch, priority, cpuTier, redstone, switchedOff);
    }

    public StockRule withBatch(long batch) {
        return new StockRule(key, keep, restart, batch, priority, cpuTier, redstone, switchedOff);
    }

    public StockRule withPriority(int priority) {
        return new StockRule(key, keep, restart, batch, priority, cpuTier, redstone, switchedOff);
    }

    public StockRule withCpuTier(int cpuTier) {
        return new StockRule(key, keep, restart, batch, priority, cpuTier, redstone, switchedOff);
    }

    public StockRule withRedstone(Redstone redstone) {
        return new StockRule(key, keep, restart, batch, priority, cpuTier, redstone, switchedOff);
    }

    public StockRule withSwitchedOff(boolean switchedOff) {
        return new StockRule(key, keep, restart, batch, priority, cpuTier, redstone, switchedOff);
    }

    /**
     * Whether the rule starts filling at {@code stored}: below the restart threshold, or below the keep amount when the
     * threshold is 0 (a rule that only says how much to keep tops up at once).
     */
    public boolean shouldStart(long stored) {
        return stored < (restart > 0 ? restart : keep);
    }

    /** How much one job asks for while filling at {@code stored}: what is missing, at most one batch; 0 when full. */
    public long request(long stored) {
        long missing = keep - Math.max(0, stored);
        if (missing <= 0) {
            return 0;
        }
        return batch > 0 ? Math.min(batch, missing) : missing;
    }

    /**
     * Whether the rule may request with the keeper's redstone input {@code powered}: switched on, and redstone allows.
     */
    public boolean enabled(boolean powered) {
        if (switchedOff) {
            return false;
        }
        return switch (redstone) {
            case ALWAYS -> true;
            case HIGH -> powered;
            case LOW -> !powered;
        };
    }

    /** When a rule may request: always, only with a redstone signal, or only without one. */
    public enum Redstone implements StringRepresentable {
        ALWAYS(0), HIGH(1), LOW(2);

        private final int id;

        Redstone(int id) {
            this.id = id;
        }

        /** The id the GUI syncs. */
        public int id() {
            return id;
        }

        /** The mode with {@code id}; {@link #ALWAYS} for an unknown one. */
        public static Redstone byId(int id) {
            return switch (id) {
                case 1 -> HIGH;
                case 2 -> LOW;
                default -> ALWAYS;
            };
        }

        public Redstone next() {
            return switch (this) {
                case ALWAYS -> HIGH;
                case HIGH -> LOW;
                case LOW -> ALWAYS;
            };
        }

        public String translationKey() {
            return StockTexts.key("redstone." + getSerializedName());
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
