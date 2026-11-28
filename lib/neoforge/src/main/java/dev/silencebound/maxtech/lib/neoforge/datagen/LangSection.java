package dev.silencebound.maxtech.lib.neoforge.datagen;

import java.util.function.BiConsumer;

/**
 * One feature's English lines for its mod's language provider. Each feature keeps its lines in its own section class
 * and adds one line to its mod's append-only section list, so parallel features never edit the same lines; the provider
 * sorts the keys and fails on a key added twice, so the generated file doesn't depend on the list's order.
 */
@FunctionalInterface
public interface LangSection {
    void addTo(BiConsumer<String, String> lang);
}
