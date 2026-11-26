package dev.silencebound.maxtech.storage.terminal.client;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Tooltip texts per key for {@link TerminalTooltips}, without game types so it can be tested. A text the builder cannot
 * make yet (no player) is answered with a fallback and not kept, so it is built properly later. Dropped when the
 * advanced-tooltips flag changes, on {@link #clear}, and when it would hold more than {@code max} keys. Client thread.
 *
 * @param <K>
 *            the key kind (a stack key in the game)
 */
final class TooltipCache<K> {
    private final Object2ObjectOpenHashMap<K, String> texts = new Object2ObjectOpenHashMap<>();
    private final int max;
    private boolean advanced;
    private int generation;

    TooltipCache(int max) {
        this.max = max;
    }

    /** Drops every text and raises {@link #generation}. */
    void clear() {
        texts.clear();
        generation++;
    }

    int generation() {
        return generation;
    }

    /** Whether {@code key}'s text is kept for the current {@code advancedNow} flag. */
    boolean cached(K key, boolean advancedNow) {
        checkFlag(advancedNow);
        return texts.containsKey(key);
    }

    /**
     * {@code key}'s text: the kept one, or {@code builder}'s, which is kept unless it returned null; then
     * {@code fallback}'s is returned and not kept.
     */
    String get(K key, boolean advancedNow, Function<K, @Nullable String> builder, Function<K, String> fallback) {
        checkFlag(advancedNow);
        String text = texts.get(key);
        if (text != null) {
            return text;
        }
        text = builder.apply(key);
        if (text == null) {
            return fallback.apply(key);
        }
        if (texts.size() >= max) {
            texts.clear();
        }
        texts.put(key, text);
        return text;
    }

    private void checkFlag(boolean advancedNow) {
        if (advancedNow != advanced) {
            advanced = advancedNow;
            clear();
        }
    }
}
