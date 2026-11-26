package dev.silencebound.maxtech.storage.logic.controller;

/**
 * Lang keys for a text with a count: a key {@code k} is the plural form ("asked for %s actions") and {@code k_one} the
 * singular ("asked for %s action"). Minecraft's lang files have no plural rules, so a language whose plural differs
 * from English writes the forms it needs into both keys. Pure.
 */
public final class Plural {
    /** The suffix of a key's singular form. */
    public static final String ONE = "_one";

    private Plural() {
    }

    /** The key to use for {@code count}: {@code key + "_one"} for exactly one, else {@code key}. */
    public static String key(String key, long count) {
        return count == 1 ? key + ONE : key;
    }
}
