package dev.silencebound.maxtech.storage.sensor.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class ReaderPathTest {
    private static ReaderPath path(String text) {
        return ReaderPath.parse(text).getOrThrow();
    }

    private static String error(String text) {
        return ReaderPath.parse(text).error().orElseThrow().message();
    }

    /**
     * {"Energy": {"stored": 1200}, "Tanks": [{"Amount": 500}, {"Amount": 250}], "odd.name": {"v": 7}, "Ints": [4, 5]}
     */
    private static CompoundTag data() {
        CompoundTag root = new CompoundTag();
        CompoundTag energy = new CompoundTag();
        energy.putInt("stored", 1200);
        root.put("Energy", energy);
        ListTag tanks = new ListTag();
        CompoundTag first = new CompoundTag();
        first.putInt("Amount", 500);
        CompoundTag second = new CompoundTag();
        second.putInt("Amount", 250);
        tanks.add(first);
        tanks.add(second);
        root.put("Tanks", tanks);
        CompoundTag odd = new CompoundTag();
        odd.putInt("v", 7);
        root.put("odd.name", odd);
        root.put("Ints", new IntArrayTag(new int[]{4, 5}));
        root.putString("Mode", "fast");
        return root;
    }

    @Test
    void namesPositionsAndQuotedNamesFindTheirValues() {
        CompoundTag data = data();
        assertEquals(IntTag.valueOf(1200), path("Energy.stored").find(data));
        assertEquals(IntTag.valueOf(250), path("Tanks[1].Amount").find(data));
        assertEquals(IntTag.valueOf(7), path("[\"odd.name\"].v").find(data));
        assertEquals(IntTag.valueOf(5), path("Ints[1]").find(data));
        assertEquals(StringTag.valueOf("fast"), path("Mode").find(data));
        Tag list = path("Tanks").find(data);
        assertTrue(list instanceof ListTag tags && tags.size() == 2, "the list itself");
    }

    @Test
    void missingStepsGiveNullNeverAnException() {
        CompoundTag data = data();
        assertNull(path("Energy.capacity").find(data));
        assertNull(path("Tanks[2].Amount").find(data));
        assertNull(path("Energy[0]").find(data), "a position asked of a compound");
        assertNull(path("Mode.inner").find(data), "a name asked of a string");
        assertNull(path("Ints[9]").find(data));
        assertNull(path("Nothing.at.all").find(new CompoundTag()));
    }

    @Test
    void stepsAreParsedOnce() {
        ReaderPath path = path("a.b[3][\"c.d\"]");
        assertEquals(4, path.steps());
        assertEquals("a", path.name(0));
        assertEquals("b", path.name(1));
        assertEquals(3, path.position(2));
        assertEquals("c.d", path.name(3));
        assertEquals(-1, path.position(3));
    }

    @Test
    void badPathsSayWhatAndWhere() {
        assertEquals("empty path", error(""));
        assertTrue(error("a..b").contains("a name is missing after '.' at character 3"), error("a..b"));
        assertTrue(error(".a").contains("a name is missing before '.'"), error(".a"));
        assertTrue(error("a.").contains("ends with '.'"), error("a."));
        assertTrue(error("a[1").contains("'[' without ']'"), error("a[1"));
        assertTrue(error("a[x]").contains("'x' is not a list position"), error("a[x]"));
        assertTrue(error("a[-1]").contains("not a list position"), error("a[-1]"));
        assertTrue(error("a[1]b").contains("'.' or '[' expected at character 5"), error("a[1]b"));
        assertTrue(error("a]").contains("unexpected ']'"), error("a]"));
        assertTrue(error("a[\"\"]").contains("empty name in quotes"), error("a[\"\"]"));
    }

    @Test
    void codecKeepsTheText() {
        ReaderPath path = path("Tanks[0].Amount");
        assertEquals("Tanks[0].Amount", path.text());
        assertEquals(path, ReaderPath.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE,
                        ReaderPath.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, path).getOrThrow())
                .getOrThrow());
    }
}
