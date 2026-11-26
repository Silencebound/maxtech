package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;

/**
 * How autocrafting behaves ({@code crafting.autocraftingMode}, M7 C6, C29). Both modes run the same planner and
 * executor; classic only turns the extended features off around them.
 */
public enum CraftingMode {
    /** Partial results, resume and full job reports (the default). */
    EXTENDED,
    /**
     * AE2's behaviour: a job starts only when everything can be made, never ends partial, and reports only its status.
     * The CPU cluster stays as it is.
     */
    CLASSIC;

    /** Whether partial results, resume and the full job report are on. */
    public boolean extended() {
        return this == EXTENDED;
    }

    /** The configured mode; server thread. */
    public static CraftingMode current() {
        return StorageServerConfig.CRAFTING_MODE.get();
    }
}
