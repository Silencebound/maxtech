package dev.silencebound.maxtech.lib.api.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.resource.TestKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFilter.Mode;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Filters over test kinds; item and fluid tags need registries and are left to the port GameTests. */
class PortFilterTest {
    private static final Identifier SHOUTS = Identifier.fromNamespaceAndPath("test", "shouts");

    static {
        // Text kind tag test:shouts holds the upper-case strings.
        PortFilter.registerTags(TestKinds.TEXT,
                id -> id.equals(SHOUTS) ? key -> key.equals(key.toUpperCase(Locale.ROOT)) : key -> false);
    }

    @Test
    void allowListMovesOnlyListedKeysAndTagMembers() {
        PortFilter<String> filter = PortFilter.of(TestKinds.TEXT, Mode.ALLOW, List.of("apple"), List.of(SHOUTS));
        assertTrue(filter.test("apple"));
        assertTrue(filter.test("PEAR"));
        assertFalse(filter.test("pear"));
        assertFalse(PortFilter.of(TestKinds.TEXT, Mode.ALLOW, List.of(), List.of()).test("apple"));
    }

    @Test
    void denyListMovesEverythingElse() {
        PortFilter<String> filter = PortFilter.of(TestKinds.TEXT, Mode.DENY, List.of("apple"), List.of(SHOUTS));
        assertFalse(filter.test("apple"));
        assertFalse(filter.test("PEAR"));
        assertTrue(filter.test("pear"));
        assertTrue(filter.withMode(Mode.ALLOW).test("apple"));
        assertTrue(PortFilter.of(TestKinds.TEXT, Mode.DENY, List.of(), List.of()).test("apple"));
    }

    @Test
    void tagsOfAKindWithoutTagSupportMatchNothing() {
        PortFilter<Integer> filter = PortFilter.of(TestKinds.NUMBER, Mode.ALLOW, List.of(1), List.of(SHOUTS));
        assertTrue(filter.test(1));
        assertFalse(filter.test(2));
        assertThrows(IllegalArgumentException.class, () -> PortFilter.registerTags(TestKinds.TEXT, id -> key -> true));
    }

    @Test
    void savedKeysThatDoNotLoadAreKeptAndSavedBack() {
        CompoundTag saved = new CompoundTag();
        saved.putString("mode", "allow");
        ListTag keys = new ListTag();
        keys.add(IntTag.valueOf(7));
        keys.add(StringTag.valueOf("not a number"));
        saved.put("keys", keys);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf("test:shouts"));
        saved.put("tags", tags);

        PortFilter<Integer> filter = PortFilter.codec(TestKinds.NUMBER).parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(List.of(7), filter.keys());
        assertEquals(List.<Tag>of(StringTag.valueOf("not a number")), filter.unknownKeys());
        assertEquals(List.of(SHOUTS), filter.tags());
        assertEquals(3, filter.size());
        assertTrue(filter.test(7));
        assertEquals(saved, PortFilter.codec(TestKinds.NUMBER).encodeStart(NbtOps.INSTANCE, filter).getOrThrow());
    }
}
