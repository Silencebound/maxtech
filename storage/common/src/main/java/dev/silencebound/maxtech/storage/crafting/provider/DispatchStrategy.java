package dev.silencebound.maxtech.storage.crafting.provider;

/**
 * How a crafting step is shared out when several pattern providers have its pattern (M7 machine-aware dispatch, PRD 7.2
 * extension 4). Every strategy splits a batch across the providers that accept it; they differ in what they balance.
 * Set by {@code crafting.dispatchStrategy}.
 */
public enum DispatchStrategy {
    /** Earliest estimated finish: crafts in flight times the recipe's duration at each provider. */
    FASTEST,
    /** Even shares, starting with the provider that was sent work longest ago. */
    ROUND_ROBIN,
    /** Fewest crafts in flight, whatever they take. */
    LOWEST_LOAD
}
