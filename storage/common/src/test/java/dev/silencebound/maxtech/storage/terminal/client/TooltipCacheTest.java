package dev.silencebound.maxtech.storage.terminal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TooltipCacheTest {
    private boolean playerPresent;
    private final List<String> built = new ArrayList<>();

    private @Nullable String build(String key) {
        built.add(key);
        return playerPresent ? key + "\nlore" : null;
    }

    private static String name(String key) {
        return key;
    }

    @Test
    void fallbackWithoutAPlayerIsNotKept() {
        TooltipCache<String> cache = new TooltipCache<>(100);
        assertEquals("iron", cache.get("iron", false, this::build, TooltipCacheTest::name), "the name stands in");
        assertFalse(cache.cached("iron", false), "the stand-in is not kept");
        playerPresent = true;
        assertEquals("iron\nlore", cache.get("iron", false, this::build, TooltipCacheTest::name),
                "built properly once a player exists");
        assertTrue(cache.cached("iron", false));
        cache.get("iron", false, this::build, TooltipCacheTest::name);
        assertEquals(List.of("iron", "iron"), built, "built twice: once refused, once kept");
    }

    @Test
    void flagSwitchClearAndCapDropTheTexts() {
        playerPresent = true;
        TooltipCache<String> cache = new TooltipCache<>(2);
        cache.get("a", false, this::build, TooltipCacheTest::name);
        assertTrue(cache.cached("a", false));
        assertFalse(cache.cached("a", true), "advanced tooltips switched");
        int generation = cache.generation();
        cache.get("a", true, this::build, TooltipCacheTest::name);
        cache.clear();
        assertFalse(cache.cached("a", true));
        assertTrue(cache.generation() > generation);
        cache.get("a", true, this::build, TooltipCacheTest::name);
        cache.get("b", true, this::build, TooltipCacheTest::name);
        cache.get("c", true, this::build, TooltipCacheTest::name);
        assertFalse(cache.cached("a", true), "over the cap the cache starts again");
        assertTrue(cache.cached("c", true));
    }
}
