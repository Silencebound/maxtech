package dev.silencebound.maxtech.lib.api.config;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Declares config values. The returned suppliers read the live value and are cheap enough to call from game logic, but
 * hot paths should still copy values into fields on config reload rather than read them per tick.
 *
 * <p>
 * Every value and section gets the translation key {@code <modid>.configuration.<dotted path>} for the loader's config
 * screen, and a value's comment is the English tooltip datagen writes to {@code <key>.tooltip}, so every value needs a
 * comment that names its unit.
 */
public interface ConfigBuilder {
    /** Attaches a comment to the next value or section. */
    ConfigBuilder comment(String... lines);

    /**
     * Marks the next value as needing a world reload: the game keeps the value it read until the world closes, and the
     * config screen offers to leave the world after a change.
     */
    ConfigBuilder worldRestart();

    /**
     * Marks the next value as needing a game restart: the game keeps the value it first read, and the config screen
     * offers to quit after a change. Not allowed in server configs.
     */
    ConfigBuilder gameRestart();

    /** Opens section {@code section}; the values declared next go into it until {@link #pop()}. */
    ConfigBuilder push(String section);

    /** Closes the section the last {@link #push} opened. */
    ConfigBuilder pop();

    /** A boolean value. */
    BooleanSupplier define(String path, boolean defaultValue);

    /** An int value from {@code min} to {@code max}; the loader puts the default back for one outside. */
    IntSupplier defineInRange(String path, int defaultValue, int min, int max);

    /** A long value from {@code min} to {@code max}; the loader puts the default back for one outside. */
    LongSupplier defineInRange(String path, long defaultValue, long min, long max);

    /** A double value from {@code min} to {@code max}; the loader puts the default back for one outside. */
    DoubleSupplier defineInRange(String path, double defaultValue, double min, double max);

    /** A string value. */
    Supplier<String> define(String path, String defaultValue);

    /**
     * A string value that must pass {@code valid}; the loader logs a value that does not and puts the default back, so
     * a typo in a config file never goes unnoticed.
     */
    Supplier<String> define(String path, String defaultValue, Predicate<String> valid);

    /** An enum value, written by constant name; the loader puts the default back for an unknown name. */
    <E extends Enum<E>> Supplier<E> defineEnum(String path, E defaultValue);
}
