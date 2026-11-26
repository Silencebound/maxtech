package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import java.util.Optional;
import java.util.UUID;

/**
 * A provider that makes one output without inputs by switching a machine on (M7C item 11, the generator provider): a
 * cobblestone generator, an infinite water source. Internal, not part of the public api: the planner treats such an
 * output as an infinite source without inputs, and the executor orders amounts from it, per job, instead of pushing
 * pattern inputs. Its {@link dev.silencebound.maxtech.storage.api.crafting.PatternProvider#patterns() patterns} stay
 * empty. Server thread only.
 */
public interface GeneratorSource {
    /** What it makes, or empty while it is not set up. */
    Optional<StackKey> output();

    /**
     * Its priority among the patterns making the same output (owner decision 2026-10-10): the planner tries higher ones
     * first, and on a tie the generator before the patterns.
     */
    int priority();

    /** Whether it can take an order now: set up, still there, allowed to reach its machine and not stopped. */
    boolean canGenerate();

    /** Whether it still works on any order. */
    boolean busy();

    /** What it still owes job {@code job}; 0 when it holds no order for it. */
    long owed(UUID job);

    /**
     * Orders {@code amount} more of its output for job {@code job}: it switches its machine on and collects that much
     * into the network, reporting each arrival to the crafting deliveries. Returns what it took on: 0 or
     * {@code amount}.
     */
    long order(UUID job, long amount);

    /**
     * Withdraws job {@code job}'s order (the job ended or was cancelled); with no order left the signal goes off at
     * once and nothing more is pulled. Nothing happens without an order for it.
     */
    void cancel(UUID job);

    /**
     * Whether the source's device is gone (broken or unloaded); the dispatch then drops it if its own unregister was
     * missed. False by default.
     */
    default boolean gone() {
        return false;
    }

    /** Measured ticks per 1,000 units (items or mB) while its machine runs, or 0 while it has measured nothing. */
    long ticksPerThousand();
}
