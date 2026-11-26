package dev.silencebound.maxtech.lib.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Interning, release and the variant index, counts kept in step with registry totals, and the palette codec. Keys are
 * strings whose base is the part before a colon ("stone:damaged" is a variant of "stone"), so no game is needed; the
 * same code runs on {@code StackKey}s in game ({@code resource_keys_*} GameTests).
 */
class KeyRegistryTest {
    private static final Map<String, String> BASES = new HashMap<>();

    /** One canonical base string per base, since the variant index compares bases by identity. */
    private static Object baseOf(String key) {
        String base = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
        return BASES.computeIfAbsent(base, b -> b);
    }

    private static KeyRegistry<String> registry() {
        return new KeyRegistry<>(KeyRegistryTest::baseOf);
    }

    @Test
    void internGivesStableDenseIds() {
        KeyRegistry<String> registry = registry();
        int stone = registry.intern("stone");
        int dirt = registry.intern("dirt");
        assertEquals(0, stone);
        assertEquals(1, dirt);
        assertEquals(stone, registry.intern("stone"), "interning again returns the same id");
        assertEquals(dirt, registry.id("dirt"));
        assertEquals(-1, registry.id("sand"), "lookup does not intern");
        assertEquals("stone", registry.key(stone));
        assertEquals(2, registry.size());
    }

    @Test
    void zeroTotalsAreReleasedOnlyWhenAsked() {
        KeyRegistry<String> registry = registry();
        IntArrayList released = new IntArrayList();
        registry.onRelease(released::add);
        int stone = registry.intern("stone");
        int dirt = registry.intern("dirt");
        registry.add(stone, 10);
        registry.add(dirt, 5);
        registry.add(dirt, -5);
        assertEquals(dirt, registry.id("dirt"), "a key at 0 stays until releaseUnused");
        registry.add(dirt, 3);
        registry.add(dirt, -3);
        registry.add(dirt, 1);
        assertEquals(0, registry.releaseUnused(), "dirt came back before the release");
        registry.add(dirt, -1);
        assertEquals(1, registry.releaseUnused());
        assertEquals(List.of(dirt), released);
        assertEquals(-1, registry.id("dirt"));
        assertNull(registry.key(dirt));
        assertEquals(10, registry.total(stone));
        assertEquals(dirt, registry.intern("sand"), "a released id is reused");
        assertEquals(0, registry.total(dirt), "a reused id starts at 0");
    }

    @Test
    void internedButNeverCountedIsReleased() {
        KeyRegistry<String> registry = registry();
        registry.intern("stone");
        assertEquals(1, registry.releaseUnused());
        assertEquals(0, registry.size());
    }

    @Test
    void negativeTotalsAndUnknownIdsThrow() {
        KeyRegistry<String> registry = registry();
        int stone = registry.intern("stone");
        registry.add(stone, 2);
        assertThrows(IllegalArgumentException.class, () -> registry.add(stone, -3));
        assertEquals(2, registry.total(stone), "a refused change changes nothing");
        assertThrows(IllegalArgumentException.class, () -> registry.add(7, 1));
    }

    @Test
    void internOfAKnownKeyLeavesFreeIdsAlone() {
        KeyRegistry<String> registry = registry();
        int stone = registry.intern("stone");
        int dirt = registry.intern("dirt");
        registry.add(stone, 1);
        assertEquals(1, registry.releaseUnused());
        assertEquals(stone, registry.intern("stone"), "a known key keeps its id while a free id waits");
        assertEquals(dirt, registry.intern("sand"), "the next new key takes the freed id");
        assertEquals(2, registry.idLimit());
    }

    @Test
    void bulkReleaseKeepsTheRestFindable() {
        KeyRegistry<String> registry = registry();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 10_000; i++) {
                registry.add(registry.intern("stone:" + i), 1);
            }
            for (int i = 0; i < 10_000; i++) {
                if (i % 1000 != 0) {
                    registry.add(registry.id("stone:" + i), -1);
                }
            }
            int full = registry.tableSlots();
            assertEquals(9_990, registry.releaseUnused());
            assertEquals(10, registry.size());
            assertTrue(registry.tableSlots() <= full / 64,
                    "a bulk release shrinks the table: " + full + " -> " + registry.tableSlots());
            for (int i = 0; i < 10_000; i++) {
                int id = registry.id("stone:" + i);
                if (i % 1000 == 0) {
                    assertEquals("stone:" + i, registry.key(id), "kept key " + i);
                    registry.add(id, -1);
                } else {
                    assertEquals(-1, id, "released key " + i);
                }
            }
            assertEquals(10, registry.releaseUnused());
            assertEquals(0, registry.size());
            assertTrue(registry.variants(baseOf("stone")).isEmpty());
            assertEquals(10_000, registry.idLimit(), "released ids are reused, round " + round);
        }
    }

    @Test
    void variantsFollowInternAndRelease() {
        KeyRegistry<String> registry = registry();
        int plain = registry.intern("pick");
        int damaged = registry.intern("pick:damaged");
        int named = registry.intern("pick:named");
        int other = registry.intern("axe");
        assertEquals(new IntOpenHashSet(new int[]{plain, damaged, named}),
                new IntOpenHashSet(registry.variants(baseOf("pick"))));
        assertEquals(new IntOpenHashSet(new int[]{other}), new IntOpenHashSet(registry.variants(baseOf("axe"))));
        assertTrue(registry.variants(baseOf("shovel")).isEmpty());
        registry.add(plain, 1);
        registry.releaseUnused();
        assertEquals(new IntOpenHashSet(new int[]{plain}), new IntOpenHashSet(registry.variants(baseOf("pick"))));
        assertTrue(registry.variants(baseOf("axe")).isEmpty(), "a base with no keys left is dropped");
    }

    @Test
    void countsKeepRegistryTotalsAndDirtyFlag() {
        KeyRegistry<String> registry = registry();
        KeyCounts<String> a = new KeyCounts<>(registry);
        KeyCounts<String> b = new KeyCounts<>(registry);
        int stone = registry.intern("stone");
        a.add(stone, 40);
        b.add(stone, 2);
        assertEquals(42, registry.total(stone));
        assertTrue(a.isDirty());
        a.markSaved();
        assertFalse(a.isDirty());
        assertThrows(IllegalArgumentException.class, () -> a.add(stone, -41));
        assertFalse(a.isDirty(), "a refused change does not dirty the container");
        assertEquals(42, registry.total(stone), "nor touch the registry");
        a.add(stone, -40);
        assertEquals(0, a.kinds(), "a count of 0 is not kept");
        assertTrue(a.isDirty());
        b.clear();
        assertEquals(0, registry.total(stone));
        assertEquals(1, registry.releaseUnused());
    }

    @Test
    void paletteRoundTripsThroughCodec() {
        KeyRegistry<String> registry = registry();
        KeyCounts<String> cell = new KeyCounts<>(registry);
        cell.add(registry.intern("stone"), 1_000_000_000_000L);
        cell.add(registry.intern("pick:damaged"), 1);
        cell.add(registry.intern("dirt"), 64);
        cell.markSaved();

        Codec<KeyPalette<String>> codec = KeyPalette.codec(Codec.STRING);
        JsonElement saved = codec.encodeStart(JsonOps.INSTANCE, cell.toPalette()).getOrThrow();
        KeyPalette<String> decoded = codec.parse(JsonOps.INSTANCE, saved).getOrThrow();

        KeyRegistry<String> loadedRegistry = registry();
        KeyCounts<String> loaded = new KeyCounts<>(loadedRegistry);
        loaded.load(decoded);
        assertFalse(loaded.isDirty(), "a freshly loaded container is clean");
        assertEquals(3, loaded.kinds());
        assertEquals(1_000_000_000_064L + 1, loaded.total());
        assertEquals(1_000_000_000_000L, loaded.count(loadedRegistry.id("stone")));
        assertEquals(1, loadedRegistry.total(loadedRegistry.id("pick:damaged")));
        assertEquals(64, loadedRegistry.total(loadedRegistry.id("dirt")));
        assertEquals(1, loadedRegistry.variants(baseOf("pick")).size());
    }

    @Test
    void paletteRepeatsAddUpAndBadDataFails() {
        Codec<KeyPalette<String>> codec = KeyPalette.codec(Codec.STRING);
        KeyPalette<String> repeated = new KeyPalette<>(List.of("stone", "dirt"), new int[]{0, 1, 0},
                new long[]{5, 6, 7});
        KeyRegistry<String> registry = registry();
        KeyCounts<String> counts = new KeyCounts<>(registry);
        counts.load(
                codec.parse(JsonOps.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, repeated).getOrThrow()).getOrThrow());
        assertEquals(12, counts.count(registry.id("stone")));
        assertEquals(6, counts.count(registry.id("dirt")));
        assertArrayEquals(new int[]{0, 1, 0}, repeated.indices());

        assertTrue(encodeAndParse(codec, new KeyPalette<>(List.of("stone"), new int[]{1}, new long[]{1})).isEmpty(),
                "index outside the palette");
        assertTrue(encodeAndParse(codec, new KeyPalette<>(List.of("stone"), new int[]{0}, new long[]{-1})).isEmpty(),
                "negative count");
        assertTrue(encodeAndParse(codec, new KeyPalette<>(List.of("stone"), new int[]{0, 0}, new long[]{1})).isEmpty(),
                "arrays of different lengths");
    }

    private static java.util.Optional<KeyPalette<String>> encodeAndParse(Codec<KeyPalette<String>> codec,
            KeyPalette<String> palette) {
        return codec.encodeStart(JsonOps.INSTANCE, palette).flatMap(json -> codec.parse(JsonOps.INSTANCE, json))
                .result();
    }
}
