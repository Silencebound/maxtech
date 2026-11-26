package dev.silencebound.maxtech.storage.crafting.pattern;

/**
 * A {@link dev.silencebound.maxtech.storage.api.crafting.PatternProvider} whose patterns carry priorities (M7C item 2):
 * among the patterns making the same item the planner tries the highest priority first. Providers without it count as
 * priority 0. Server thread.
 */
public interface PatternPriorities {
    /** The priority of the provider's {@code patterns().get(index)}. */
    int priority(int index);
}
